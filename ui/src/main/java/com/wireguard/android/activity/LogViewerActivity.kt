/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.activity

import android.content.ClipDescription.compareMimeTypes
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.collection.CircularArray
import androidx.core.app.ShareCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textview.MaterialTextView
import com.wireguard.android.BuildConfig
import com.wireguard.android.R
import com.wireguard.android.databinding.LogViewerActivityBinding
import com.wireguard.android.util.DownloadsFileSaver
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.util.resolveAttribute
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.DateFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher
import java.util.regex.Pattern

class LogViewerActivity : AppCompatActivity() {
    private lateinit var binding: LogViewerActivityBinding
    private lateinit var logAdapter: LogEntryAdapter
    private var logLines = CircularArray<LogLine>()
    private var rawLogLines = CircularArray<String>()
    private var recyclerView: RecyclerView? = null
    private var saveButton: MenuItem? = null
    private var selectedFilter = LogFilter.ALL
    private val year by lazy {
        val yearFormatter: DateFormat = SimpleDateFormat("yyyy", Locale.US)
        yearFormatter.format(Date())
    }

    private val entryTimeFormatter: DateFormat by lazy { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    private val defaultColor by lazy { resolveAttribute(com.google.android.material.R.attr.colorOnSurface) }

    private val debugColor by lazy { ResourcesCompat.getColor(resources, R.color.debug_tag_color, theme) }

    private val errorColor by lazy { ResourcesCompat.getColor(resources, R.color.error_tag_color, theme) }

    private val infoColor by lazy { ResourcesCompat.getColor(resources, R.color.info_tag_color, theme) }

    private val warningColor by lazy { ResourcesCompat.getColor(resources, R.color.warning_tag_color, theme) }

    private var lastUri: Uri? = null

    private fun revokeLastUri() {
        lastUri?.let {
            LOGS.remove(it.pathSegments.lastOrNull())
            revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            lastUri = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LogViewerActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        logAdapter = LogEntryAdapter()
        binding.recyclerView.apply {
            recyclerView = this
            layoutManager = LinearLayoutManager(context)
            adapter = logAdapter
        }
        binding.logFilters.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectedFilter = when (checkedId) {
                R.id.filter_tunnel -> LogFilter.TUNNEL
                R.id.filter_turn -> LogFilter.TURN
                R.id.filter_subscription -> LogFilter.SUBSCRIPTION
                R.id.filter_routing -> LogFilter.ROUTING
                R.id.filter_app -> LogFilter.APP
                R.id.filter_errors -> LogFilter.ERRORS
                else -> LogFilter.ALL
            }
            logAdapter.rebuild()
            recyclerView?.scrollToPosition(0)
        }
        logAdapter.rebuild()

        lifecycleScope.launch(Dispatchers.IO) { streamingLog() }

        val revokeLastActivityResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            revokeLastUri()
        }

        binding.shareFab.setOnClickListener {
            lifecycleScope.launch {
                revokeLastUri()
                val key = KeyPair().privateKey.toHex()
                LOGS[key] = rawLogBytes()
                lastUri = Uri.parse("content://${BuildConfig.APPLICATION_ID}.exported-log/$key")
                val shareIntent = ShareCompat.IntentBuilder(this@LogViewerActivity)
                    .setType("text/plain")
                    .setSubject(getString(R.string.log_export_subject))
                    .setStream(lastUri)
                    .setChooserTitle(R.string.log_export_title)
                    .createChooserIntent()
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                grantUriPermission("android", lastUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                revokeLastActivityResultLauncher.launch(shareIntent)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.log_viewer, menu)
        saveButton = menu.findItem(R.id.save_log)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }

            R.id.save_log -> {
                saveButton?.isEnabled = false
                lifecycleScope.launch { saveLog() }
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private val downloadsFileSaver = DownloadsFileSaver(this)

    private suspend fun rawLogBytes(): ByteArray {
        val builder = StringBuilder()
        withContext(Dispatchers.IO) {
            for (i in 0 until rawLogLines.size()) {
                builder.append(rawLogLines[i])
                builder.append('\n')
            }
        }
        return builder.toString().toByteArray(Charsets.UTF_8)
    }

    private suspend fun saveLog() {
        var exception: Throwable? = null
        var outputFile: DownloadsFileSaver.DownloadsFile? = null
        withContext(Dispatchers.IO) {
            try {
                outputFile = downloadsFileSaver.save("rabbit-hole-log.txt", "text/plain", true)
                outputFile?.outputStream?.write(rawLogBytes())
            } catch (e: Throwable) {
                outputFile?.delete()
                exception = e
            }
        }
        saveButton?.isEnabled = true
        if (outputFile == null)
            return
        Snackbar.make(
            findViewById(android.R.id.content),
            if (exception == null) getString(R.string.log_export_success, outputFile.fileName)
            else getString(R.string.log_export_error, ErrorMessages[exception]),
            if (exception == null) Snackbar.LENGTH_SHORT else Snackbar.LENGTH_LONG
        )
            .setAnchorView(binding.shareFab)
            .show()
    }

    private suspend fun streamingLog() = withContext(Dispatchers.IO) {
        val builder = ProcessBuilder().command(
            "logcat",
            "-b",
            "all",
            "--pid=${android.os.Process.myPid()}",
            "-v",
            "threadtime",
            "*:V",
        )
        builder.environment()["LC_ALL"] = "C"
        var process: Process? = null
        try {
            process = try {
                builder.start()
            } catch (e: IOException) {
                Log.e(TAG, Log.getStackTraceString(e))
                withContext(Dispatchers.Main.immediate) {
                    binding.logStatus.setText(R.string.log_status_unavailable)
                    binding.logStatus.setTextColor(ResourcesCompat.getColor(resources, R.color.error_tag_color, theme))
                }
                return@withContext
            }
            val stdout = BufferedReader(InputStreamReader(process!!.inputStream, StandardCharsets.UTF_8))

            var timeLastNotify = System.nanoTime()
            val bufferedLogLines = arrayListOf<LogLine>()
            var timeout = 1000000000L / 2 // The timeout is initially small so that the view gets populated immediately.
            val MAX_LINES = (1 shl 16) - 1
            val MAX_BUFFERED_LINES = (1 shl 14) - 1
            var previousLineWasRelevant = false

            while (true) {
                val line = stdout.readLine() ?: break
                val logLine = parseLine(line)
                if (logLine != null) {
                    previousLineWasRelevant = isRelevant(logLine)
                    if (previousLineWasRelevant) {
                        if (rawLogLines.size() >= MAX_LINES) rawLogLines.popFirst()
                        rawLogLines.addLast(line)
                        bufferedLogLines.add(logLine)
                    }
                } else if (previousLineWasRelevant) {
                    if (rawLogLines.size() >= MAX_LINES) rawLogLines.popFirst()
                    rawLogLines.addLast(line)
                    if (bufferedLogLines.isNotEmpty()) {
                        bufferedLogLines.last().msg += "\n$line"
                    } else if (!logLines.isEmpty()) {
                        logLines[logLines.size() - 1].msg += "\n$line"
                    }
                }
                if (bufferedLogLines.isEmpty()) continue
                val timeNow = System.nanoTime()
                if (bufferedLogLines.size < MAX_BUFFERED_LINES && (timeNow - timeLastNotify) < timeout && stdout.ready())
                    continue
                timeout = 1000000000L * 5 / 2 // Increase the timeout after the initial view has something in it.
                timeLastNotify = timeNow

                withContext(Dispatchers.Main.immediate) {
                    val isScrolledToTopAlready = recyclerView?.canScrollVertically(-1) == false
                    val fullLen = logLines.size() + bufferedLogLines.size
                    if (fullLen >= MAX_LINES) {
                        val numToRemove = fullLen - MAX_LINES + 1
                        logLines.removeFromStart(numToRemove)
                    }
                    for (bufferedLine in bufferedLogLines) {
                        logLines.addLast(bufferedLine)
                    }
                    bufferedLogLines.clear()
                    logAdapter.rebuild()

                    if (isScrolledToTopAlready && logAdapter.itemCount > 0) {
                        recyclerView?.scrollToPosition(0)
                    }
                }
            }
        } finally {
            process?.destroy()
        }
    }

    private fun parseTime(timeStr: String): Date? {
        val formatter: DateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return try {
            formatter.parse("$year-$timeStr")
        } catch (e: ParseException) {
            null
        }
    }

    private fun parseLine(line: String): LogLine? {
        val m: Matcher = THREADTIME_LINE.matcher(line)
        return if (m.matches()) {
            LogLine(m.group(2)!!.toInt(), m.group(3)!!.toInt(), parseTime(m.group(1)!!), m.group(4)!!, m.group(5)!!, m.group(6)!!)
        } else {
            null
        }
    }

    private data class LogLine(val pid: Int, val tid: Int, val time: Date?, val level: String, val tag: String, var msg: String)

    private enum class LogGroup(val labelRes: Int) {
        TUNNEL(R.string.log_group_tunnel),
        TURN(R.string.log_group_turn),
        SUBSCRIPTION(R.string.log_group_subscription),
        ROUTING(R.string.log_group_routing),
        APP(R.string.log_group_app),
    }

    private enum class LogFilter {
        ALL,
        TUNNEL,
        TURN,
        SUBSCRIPTION,
        ROUTING,
        APP,
        ERRORS,
    }

    private fun groupFor(line: LogLine): LogGroup {
        val searchable = "${line.tag} ${line.msg}".lowercase(Locale.ROOT)
        return when {
            searchable.containsAny("subscription", "subscrib", "profile update") -> LogGroup.SUBSCRIPTION
            searchable.containsAny("routinglist", "routing list", "routeexcluder", "excluded route", "georouting", "geoip", "geosite", "ru-direct") -> LogGroup.ROUTING
            searchable.containsAny("turn", "captcha", "coturn", "webview") -> LogGroup.TURN
            searchable.containsAny("wireguard", "gobackend", "wgquick", "tunnel", "vpnservice", "vpn service") -> LogGroup.TUNNEL
            else -> LogGroup.APP
        }
    }

    private fun String.containsAny(vararg needles: String) = needles.any(::contains)

    private fun effectiveLevel(line: LogLine): String {
        if (line.level != "I") return line.level
        val message = line.msg.lowercase(Locale.ROOT)
        if (
            (message.contains("[dns] server") && message.contains(" failed:")) ||
            message.contains("getcallpreview request failed:") ||
            message.contains("auto captcha failed") ||
            message.contains("watchdog recycling stream") ||
            message.contains("use of closed network connection") ||
            message.contains("read/write on closed pipe")
        ) return "W"
        return if (message.containsAny(" error:", " failed:", " failed ", "exception", "timed out", "timeout")) "E" else line.level
    }

    private fun matchesFilter(line: LogLine): Boolean = when (selectedFilter) {
        LogFilter.ALL -> true
        LogFilter.TUNNEL -> groupFor(line) == LogGroup.TUNNEL
        LogFilter.TURN -> groupFor(line) == LogGroup.TURN
        LogFilter.SUBSCRIPTION -> groupFor(line) == LogGroup.SUBSCRIPTION
        LogFilter.ROUTING -> groupFor(line) == LogGroup.ROUTING
        LogFilter.APP -> groupFor(line) == LogGroup.APP
        LogFilter.ERRORS -> effectiveLevel(line) in ERROR_LEVELS
    }

    private fun isRelevant(line: LogLine): Boolean =
        line.tag.startsWith("WireGuard/") ||
            line.tag.startsWith("RabbitHole/") ||
            line.tag == "QrCodeFromFileScanner"

    companion object {
        /**
         * Match a single line of `logcat -v threadtime`, such as:
         *
         * <pre>05-26 11:02:36.886 5689 5689 D AndroidRuntime: CheckJNI is OFF.</pre>
         */
        private val THREADTIME_LINE: Pattern =
            Pattern.compile("^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}.\\d{3})(?:\\s+[0-9A-Za-z]+)?\\s+(\\d+)\\s+(\\d+)\\s+([A-Z])\\s+(.+?)\\s*: (.*)$")
        private val LOGS: MutableMap<String, ByteArray> = ConcurrentHashMap()
        private val ERROR_LEVELS = setOf("A", "E", "F")
        private const val TAG = "WireGuard/LogViewerActivity"
    }

    private inner class LogEntryAdapter : RecyclerView.Adapter<LogEntryAdapter.ViewHolder>() {
        private var visiblePositions = IntArray(0)

        private inner class ViewHolder(val layout: View, var isSingleLine: Boolean = true) : RecyclerView.ViewHolder(layout)

        fun rebuild() {
            val positions = ArrayList<Int>(logLines.size())
            for (position in logLines.size() - 1 downTo 0) {
                if (matchesFilter(logLines[position])) positions.add(position)
            }
            visiblePositions = positions.toIntArray()
            notifyDataSetChanged()
            binding.logCount.text = getString(R.string.log_entries_count, visiblePositions.size)
        }

        private fun levelToColor(level: String): Int {
            return when (level) {
                "V", "D" -> debugColor
                "A", "E", "F" -> errorColor
                "I" -> infoColor
                "W" -> warningColor
                else -> defaultColor
            }
        }

        private fun levelLabel(level: String) = when (level) {
            "V" -> "VERBOSE"
            "D" -> "DEBUG"
            "I" -> "INFO"
            "W" -> "WARN"
            "A", "E", "F" -> "ERROR"
            else -> level
        }

        override fun getItemCount() = visiblePositions.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.log_viewer_entry, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val line = logLines[visiblePositions[position]]
            val group = groupFor(line)
            val level = effectiveLevel(line)
            val previousGroup = if (position > 0) groupFor(logLines[visiblePositions[position - 1]]) else null
            holder.layout.apply {
                findViewById<TextView>(R.id.log_group).apply {
                    visibility = if (previousGroup == group) View.GONE else View.VISIBLE
                    setText(group.labelRes)
                }
                findViewById<MaterialTextView>(R.id.log_date).text = line.time?.let(entryTimeFormatter::format) ?: "—"
                findViewById<MaterialTextView>(R.id.log_level).apply {
                    text = levelLabel(level)
                    setTextColor(levelToColor(level))
                }
                findViewById<MaterialTextView>(R.id.log_tag).text = line.tag
                findViewById<MaterialTextView>(R.id.log_msg).apply {
                    holder.isSingleLine = true
                    setSingleLine(true)
                    text = line.msg
                    setOnClickListener {
                        holder.isSingleLine = !holder.isSingleLine
                        setSingleLine(holder.isSingleLine)
                    }
                }
            }
        }
    }

    class ExportedLogContentProvider : ContentProvider() {
        private fun logForUri(uri: Uri): ByteArray? = LOGS[uri.pathSegments.lastOrNull()]

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
            logForUri(uri)?.let {
                val m = MatrixCursor(arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), 1)
                m.addRow(arrayOf<Any>("rabbit-hole-log.txt", it.size.toLong()))
                m
            }

        override fun onCreate(): Boolean = true

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun getType(uri: Uri): String? = logForUri(uri)?.let { "text/plain" }

        override fun getStreamTypes(uri: Uri, mimeTypeFilter: String): Array<String>? =
            getType(uri)?.let { if (compareMimeTypes(it, mimeTypeFilter)) arrayOf(it) else null }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            if (mode != "r") return null
            val log = logForUri(uri) ?: return null
            return openPipeHelper(uri, "text/plain", null, log) { output, _, _, _, l ->
                try {
                    FileOutputStream(output.fileDescriptor).write(l!!)
                } catch (_: Throwable) {
                }
            }
        }
    }
}
