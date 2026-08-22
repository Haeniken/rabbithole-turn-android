/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.updater

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.wireguard.android.Application
import com.wireguard.android.BuildConfig
import com.wireguard.android.activity.MainActivity
import com.wireguard.android.util.applicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.InvalidParameterException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds

object Updater {
    private const val TAG = "WireGuard/Updater"
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/Haeniken/rabbithole-turn-android/releases/latest"
    private const val RELEASES_PAGE_URL = "https://github.com/Haeniken/rabbithole-turn-android/releases/latest"
    private const val APK_NAME_PREFIX = "rabbit-hole-"
    private const val APK_NAME_SUFFIX = ".apk"
    private val CURRENT_VERSION by lazy { Version(BuildConfig.VERSION_NAME) }

    private val updaterScope = CoroutineScope(Job() + Dispatchers.IO)
    private val checkMutex = Mutex()
    private val monitorStarted = AtomicBoolean(false)
    @Volatile private var availableUpdate: Update? = null

    private fun installer(context: Context): String = try {
        val packageName = context.packageName
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(packageName).installingPackageName ?: ""
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(packageName) ?: ""
        }
    } catch (_: Throwable) {
        ""
    }

    fun installerIsGooglePlay(context: Context): Boolean = installer(context) == "com.android.vending"

    sealed class Progress {
        object Complete : Progress()
        class Available(val version: String) : Progress() {
            fun update() {
                updaterScope.launch {
                    downloadAndUpdateWrapErrors()
                }
            }
        }

        object Rechecking : Progress()
        class Downloading(val bytesDownloaded: ULong, val bytesTotal: ULong) : Progress()
        object Installing : Progress()
        class NeedsUserIntervention(val intent: Intent, private val id: Int) : Progress() {

            private suspend fun installerActive(): Boolean {
                if (mutableState.firstOrNull() != this@NeedsUserIntervention)
                    return true
                try {
                    if (Application.get().packageManager.packageInstaller.getSessionInfo(id)?.isActive == true)
                        return true
                } catch (_: SecurityException) {
                    return true
                }
                return false
            }

            fun markAsDone() {
                applicationScope.launch {
                    if (installerActive())
                        return@launch
                    delay(7.seconds)
                    if (installerActive())
                        return@launch
                    emitProgress(Failure(Exception("Ignored by user")))
                }
            }
        }

        class Failure(val error: Throwable) : Progress() {
            fun retry() {
                updaterScope.launch {
                    downloadAndUpdateWrapErrors()
                }
            }
        }

        class Corrupt : Progress() {
            val downloadUrl: String
                get() = RELEASES_PAGE_URL
        }
    }

    sealed class CheckResult {
        object UpToDate : CheckResult()
        class UpdateAvailable(val version: String) : CheckResult()
        class Failed(val error: Throwable) : CheckResult()
    }

    private val mutableState = MutableStateFlow<Progress>(Progress.Complete)
    val state = mutableState.asStateFlow()

    private suspend fun emitProgress(progress: Progress, force: Boolean = false) {
        if (force || mutableState.firstOrNull()?.javaClass != progress.javaClass)
            mutableState.emit(progress)
    }

    private class Sha256Digest(hex: String) {
        val bytes: ByteArray

        init {
            if (hex.length != 64)
                throw InvalidParameterException("SHA256 hashes must be 32 bytes long")
            bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    private class Version(version: String) : Comparable<Version> {
        val parts: ULongArray

        init {
            val normalized = version.trim().removePrefix("v").substringBefore('-').substringBefore('+')
            val strParts = normalized.split(".")
            if (strParts.isEmpty())
                throw InvalidParameterException("Version has no parts")
            parts = ULongArray(strParts.size)
            for (i in parts.indices) {
                parts[i] = strParts[i].toULong()
            }
        }

        override fun toString(): String {
            return parts.joinToString(".")
        }

        override fun compareTo(other: Version): Int {
            for (i in 0 until max(parts.size, other.parts.size)) {
                val lhsPart = if (i < parts.size) parts[i] else 0UL
                val rhsPart = if (i < other.parts.size) other.parts[i] else 0UL
                if (lhsPart > rhsPart)
                    return 1
                else if (lhsPart < rhsPart)
                    return -1
            }
            return 0
        }
    }

    private class Update(
        val fileName: String,
        val downloadUrl: String,
        val version: Version,
        val hash: Sha256Digest,
    )

    private fun fetchText(url: String, maxBytes: Int, accept: String? = null): String {
        val parsedUrl = URL(url)
        if (parsedUrl.protocol != "https") throw SecurityException("Update URL must use HTTPS")
        val connection = parsedUrl.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", Application.USER_AGENT)
        accept?.let { connection.setRequestProperty("Accept", it) }
        if (parsedUrl.host == "api.github.com")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        return try {
            connection.connect()
            if (connection.url.protocol != "https")
                throw SecurityException("Update metadata redirect must use HTTPS")
            if (connection.responseCode != HttpURLConnection.HTTP_OK)
                throw IOException("GitHub returned HTTP ${connection.responseCode}: ${connection.responseMessage}")
            val output = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
            val buffer = ByteArray(8 * 1024)
            var total = 0
            connection.inputStream.use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    total += count
                    if (total > maxBytes) throw IOException("Update metadata is too large")
                    output.write(buffer, 0, count)
                }
            }
            output.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    private fun parseChecksum(checksum: String, expectedFileName: String): Sha256Digest {
        val components = checksum.trim().lineSequence().firstOrNull { it.isNotBlank() }
            ?.trim()?.split(Regex("\\s+"), limit = 2)
            ?: throw IOException("Release checksum is empty")
        if (components.size != 2 || components[1].removePrefix("*") != expectedFileName)
            throw SecurityException("Release checksum references an unexpected file")
        return Sha256Digest(components[0].lowercase())
    }

    private fun checkForUpdates(): Update {
        val release = JSONObject(fetchText(LATEST_RELEASE_API, 1024 * 1024, "application/vnd.github+json"))
        if (release.optBoolean("draft") || release.optBoolean("prerelease"))
            throw IOException("Latest GitHub release is not stable")
        val version = Version(release.getString("tag_name"))
        val expectedFileName = "$APK_NAME_PREFIX$version$APK_NAME_SUFFIX"
        val expectedChecksumName = "$expectedFileName.sha256"
        var apkUrl: String? = null
        var checksumUrl: String? = null
        val assets = release.getJSONArray("assets")
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            when (asset.getString("name")) {
                expectedFileName -> apkUrl = asset.getString("browser_download_url")
                expectedChecksumName -> checksumUrl = asset.getString("browser_download_url")
            }
        }
        val downloadUrl = apkUrl ?: throw IOException("Release does not contain $expectedFileName")
        val checksumDownloadUrl = checksumUrl ?: throw IOException("Release does not contain $expectedChecksumName")
        val checksum = parseChecksum(fetchText(checksumDownloadUrl, 4 * 1024, "text/plain"), expectedFileName)
        return Update(expectedFileName, downloadUrl, version, checksum)
    }

    suspend fun checkNow(): CheckResult = withContext(Dispatchers.IO) {
        checkMutex.withLock { checkNowLocked(announce = true) }
    }

    private suspend fun checkNowLocked(announce: Boolean): CheckResult {
        if (announce) emitProgress(Progress.Rechecking, true)
        return try {
            val update = checkForUpdates()
            if (update.version > CURRENT_VERSION) {
                availableUpdate = update
                Log.i(TAG, "Update available: ${update.version}")
                emitProgress(Progress.Available(update.version.toString()), true)
                CheckResult.UpdateAvailable(update.version.toString())
            } else {
                availableUpdate = null
                if (announce) emitProgress(Progress.Complete, true)
                CheckResult.UpToDate
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to check GitHub Releases", error)
            if (announce) emitProgress(Progress.Complete, true)
            CheckResult.Failed(error)
        }
    }

    private suspend fun downloadAndUpdate() = withContext(Dispatchers.IO) {
        val context = Application.get().applicationContext
        emitProgress(Progress.Rechecking)
        val update = availableUpdate ?: checkForUpdates()
        if (update.version <= CURRENT_VERSION) {
            emitProgress(Progress.Complete)
            return@withContext
        }

        emitProgress(Progress.Downloading(0UL, 0UL), true)
        val connection = URL(update.downloadUrl).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("User-Agent", Application.USER_AGENT)
        try {
            connection.connect()
            if (connection.url.protocol != "https")
                throw SecurityException("Update redirect must use HTTPS")
            if (connection.responseCode != HttpURLConnection.HTTP_OK)
                throw IOException("Update could not be fetched: ${connection.responseCode}")

            var downloadedByteLen = 0UL
            val totalByteLen = connection.contentLengthLong.takeIf { it > 0 }?.toULong() ?: 0UL
            val fileBytes = ByteArray(1024 * 32 /* 32 KiB */)
            val digest = MessageDigest.getInstance("SHA-256")
            emitProgress(Progress.Downloading(downloadedByteLen, totalByteLen), true)

            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            params.setAppPackageName(context.packageName) /* Enforces updates; disallows new apps. */
            val session = installer.openSession(installer.createSession(params))
            var committed = false
            try {
                session.openWrite(update.fileName, 0, -1).use { dest ->
                    connection.inputStream.use { src ->
                        while (true) {
                            val readLen = src.read(fileBytes)
                            if (readLen <= 0)
                                break

                            digest.update(fileBytes, 0, readLen)
                            dest.write(fileBytes, 0, readLen)

                            downloadedByteLen += readLen.toULong()
                            emitProgress(Progress.Downloading(downloadedByteLen, totalByteLen), true)

                            if (downloadedByteLen >= 1024UL * 1024UL * 100UL /* 100 MiB */)
                                throw IOException("File too large")
                        }
                        session.fsync(dest)
                    }
                }

                emitProgress(Progress.Installing)
                if (!digest.digest().contentEquals(update.hash.bytes))
                    throw SecurityException("Update has invalid hash")

                val receiver = InstallReceiver()
                val pendingIntent = withContext(Dispatchers.Main) {
                    ContextCompat.registerReceiver(context, receiver, IntentFilter(receiver.sessionId), ContextCompat.RECEIVER_NOT_EXPORTED)
                    PendingIntent.getBroadcast(
                        context,
                        0,
                        Intent(receiver.sessionId).setPackage(context.packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )
                }
                try {
                    session.commit(pendingIntent.intentSender)
                    committed = true
                } catch (error: Throwable) {
                    context.unregisterReceiver(receiver)
                    throw error
                }
            } finally {
                if (!committed)
                    session.abandon()
                session.close()
            }
        } finally {
            connection.disconnect()
        }
    }

    private var updating = false
    fun installAvailableUpdate() {
        updaterScope.launch { downloadAndUpdateWrapErrors() }
    }

    private suspend fun downloadAndUpdateWrapErrors() {
        if (updating)
            return
        updating = true
        try {
            downloadAndUpdate()
        } catch (e: Throwable) {
            Log.e(TAG, "Update failure", e)
            emitProgress(Progress.Failure(e))
        }
        updating = false
    }

    private class InstallReceiver : BroadcastReceiver() {
        val sessionId = UUID.randomUUID().toString()

        override fun onReceive(context: Context, intent: Intent) {
            if (sessionId != intent.action)
                return

            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_INVALID)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, 0)
                    val userIntervention = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)!!
                    applicationScope.launch {
                        emitProgress(Progress.NeedsUserIntervention(userIntervention, id))
                    }
                }

                PackageInstaller.STATUS_SUCCESS -> {
                    applicationScope.launch {
                        emitProgress(Progress.Complete)
                    }
                    context.applicationContext.unregisterReceiver(this)
                }

                else -> {
                    val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, 0)
                    try {
                        context.applicationContext.packageManager.packageInstaller.abandonSession(id)
                    } catch (_: SecurityException) {
                    }
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Installation error $status"
                    applicationScope.launch {
                        val e = Exception(message)
                        Log.e(TAG, "Update failure", e)
                        emitProgress(Progress.Failure(e))
                    }
                    context.applicationContext.unregisterReceiver(this)
                }
            }
        }
    }

    fun monitorForUpdates() {
        if (!monitorStarted.compareAndSet(false, true))
            return
        if (BuildConfig.DEBUG)
            return

        val context = Application.get()

        if (installerIsGooglePlay(context))
            return

        if (BuildConfig.BUILD_TYPE == "googleplay") {
            if (installer(context).isNotEmpty()) {
                applicationScope.launch {
                    emitProgress(Progress.Corrupt())
                }
            }
            return
        }

        if (if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            } else {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            }.requestedPermissions?.contains(Manifest.permission.REQUEST_INSTALL_PACKAGES) != true
        ) {
            if (installer(context).isNotEmpty()) {
                updaterScope.launch { emitProgress(Progress.Corrupt()) }
            }
            return
        }

        updaterScope.launch {
            delay(1.seconds)
            checkMutex.withLock { checkNowLocked(announce = false) }
        }
    }

    class AppUpdatedReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED)
                return

            if (installer(context) != context.packageName)
                return

            /* TODO: does not work because of restrictions placed on broadcast receivers. */
            val start = Intent(context, MainActivity::class.java)
            start.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(start)
        }
    }
}
