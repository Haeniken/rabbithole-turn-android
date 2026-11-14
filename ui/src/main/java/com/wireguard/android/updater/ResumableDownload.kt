package com.wireguard.android.updater

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Keeps a bounded partial APK keyed by the expected release hash, not a temporary redirect URL. */
internal class ResumableDownload(
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val log: (String) -> Unit = {},
) {
    companion object { const val MAX_BYTES = 100L * 1024 * 1024 }

    private fun verified(file: File, hash: ByteArray): Boolean = file.isFile && file.length() in 1..MAX_BYTES &&
        file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(32768)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().contentEquals(hash)
        }

    fun fetch(url: URL, file: File, hash: ByteArray, progress: (Long, Long) -> Unit): File {
        require(url.protocol == "https") { "Update URL must use HTTPS" }
        if (verified(file, hash)) {
            log("Verified cached APK: ${file.length()} bytes")
            progress(file.length(), file.length())
            return file
        }
        if (file.length() >= MAX_BYTES) file.delete()
        file.parentFile?.mkdirs()
        val offset = file.length()
        val connection = connect(url)
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "Rabbit-Hole-Updater")
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
        try {
            connection.connect()
            if (connection.url.protocol != "https") throw SecurityException("Update redirect must use HTTPS")
            val status = connection.responseCode
            log("APK response: HTTP $status, resume offset=$offset")
            if (status == 416) {
                file.delete()
                throw IOException("Partial APK no longer matches the release; retry from zero")
            }
            var downloaded: Long
            val total: Long
            val append: Boolean
            when (status) {
                200 -> {
                    append = false
                    downloaded = 0
                    total = connection.contentLengthLong.takeIf { it > 0 } ?: 0
                    if (offset > 0) log("Server ignored Range; restarting APK download")
                }
                206 -> {
                    val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
                        .matchEntire(connection.getHeaderField("Content-Range").orEmpty())
                        ?: throw IOException("Invalid APK Content-Range")
                    val start = range.groupValues[1].toLongOrNull() ?: throw IOException("Invalid range start")
                    val end = range.groupValues[2].toLongOrNull() ?: throw IOException("Invalid range end")
                    total = range.groupValues[3].toLongOrNull() ?: throw IOException("Invalid range size")
                    if (start != offset || end < start || end != total - 1 ||
                        (connection.contentLengthLong >= 0 && connection.contentLengthLong != end - start + 1))
                        throw IOException("APK range does not match partial file")
                    append = true
                    downloaded = offset
                }
                else -> throw IOException("APK download HTTP $status")
            }
            if (total > MAX_BYTES) throw IOException("APK exceeds size limit")
            progress(downloaded, total)
            FileOutputStream(file, append).use { dest ->
                connection.inputStream.use { src ->
                    val buffer = ByteArray(32768)
                    var lastProgress = 0L
                    var lastLog = 0L
                    while (true) {
                        val count = src.read(buffer)
                        if (count < 0) break
                        if (downloaded + count > MAX_BYTES || (total > 0 && downloaded + count > total))
                            throw IOException("APK exceeds expected size")
                        dest.write(buffer, 0, count)
                        downloaded += count
                        val now = System.nanoTime()
                        if (now - lastLog >= 5_000_000_000) {
                            log("APK progress: bytes=$downloaded, total=$total")
                            lastLog = now
                        }
                        if (now - lastProgress >= 200_000_000) {
                            progress(downloaded, total)
                            lastProgress = now
                        }
                    }
                    dest.fd.sync()
                }
            }
            progress(downloaded, total)
            if (total > 0 && downloaded != total) throw IOException("APK download ended early ($downloaded/$total)")
            if (!verified(file, hash)) {
                file.delete()
                throw SecurityException("Update has invalid SHA-256")
            }
            log("APK SHA-256 verified: $downloaded bytes")
            return file
        } finally {
            connection.disconnect()
        }
    }
}
