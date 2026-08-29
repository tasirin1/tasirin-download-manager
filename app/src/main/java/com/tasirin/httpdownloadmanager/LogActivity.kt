package com.tasirin.httpdownloadmanager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tasirin.httpdownloadmanager.databinding.ActivityLogBinding
import com.tasirin.httpdownloadmanager.util.applyEdgeToEdge
import com.tasirin.httpdownloadmanager.util.versionCodeCompat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.tasirin.httpdownloadmanager.util.whiteNavigationIcon

/** Halaman khusus log server realtime: layar penuh, auto-scroll default mati. */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding

    private companion object {
        val EXPORT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val EXPORT_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
    private var logAutoScroll = false
    private var logSearch = ""
    private var lastLogVersion = -1L
    private var lastLogSearch: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdge(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.whiteNavigationIcon()

        binding.logAutoscroll.isChecked = logAutoScroll
        binding.logAutoscroll.setOnCheckedChangeListener { _, checked ->
            logAutoScroll = checked
        }
        binding.logCopy.setOnClickListener {
            val text = App.httpServer.snapshotLog()
                .ifEmpty { getString(R.string.remote_log_empty) }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("server log", text))
            Toast.makeText(this, R.string.remote_log_copied, Toast.LENGTH_SHORT).show()
        }
        binding.logClear.setOnClickListener {
            App.httpServer.clearLog()
            refreshLog()
        }
        binding.logExport.setOnClickListener { exportLogTxt() }
        binding.logSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                logSearch = s?.toString().orEmpty()
                refreshLog()
            }
        })

        refreshLog()
    }

    private val pollLog = object : Runnable {
        override fun run() {
            if (isDestroyed || isFinishing) return
            refreshLog()
            binding.log.postDelayed(this, 1000)
        }
    }

    override fun onStart() {
        super.onStart()
        binding.log.postDelayed(pollLog, 1000)
    }

    override fun onStop() {
        // Hentikan polling saat layar log tidak terlihat (hemat CPU/baterai).
        binding.log.removeCallbacks(pollLog)
        super.onStop()
    }

    private fun exportLogTxt() {
        binding.logExport.isEnabled = false
        lifecycleScope.launch {
            val exported = withContext(Dispatchers.IO) {
                val log = App.httpServer.snapshotLog()
                val header = buildString {
                    appendLine("=== Tasirin Download Manager - Log Server (realtime) ===")
                    appendLine("Time: ${EXPORT_TIME.format(LocalDateTime.now())}")
                    appendLine(
                        "App version: " + runCatching {
                            val info = packageManager.getPackageInfo(packageName, 0)
                            info.versionName + " (build " + info.versionCodeCompat() + ")"
                        }.getOrDefault("?")
                    )
                    appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine()
                    append(if (log.isBlank()) "(No server activity yet)\n" else log)
                    val crashText = runCatching {
                        // Baca dari folder data eksternal (sinkron dengan CrashLog).
                        val dir = getExternalFilesDir(null) ?: filesDir
                        val f = File(dir, App.CRASH_LOG_FILE)
                        if (f.exists()) f.readText().trim() else ""
                    }.getOrDefault("")
                    if (crashText.isNotEmpty()) {
                        appendLine()
                        appendLine("=== Crash log (previous launches) ===")
                        appendLine(crashText)
                    }
                    appendLine()
                }
                val stamp = EXPORT_STAMP.format(LocalDateTime.now())
                runCatching {
                    if (Build.VERSION.SDK_INT >= 29) {
                        val resolver = contentResolver
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, "httpdm-serverlog-$stamp.txt")
                            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                            put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
                            put(MediaStore.Downloads.IS_PENDING, 1)
                        }
                        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: return@runCatching false
                        runCatching {
                            resolver.openOutputStream(uri)?.use { it.write(header.toByteArray()) }
                        }.onFailure { resolver.delete(uri, null, null) }
                        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                        resolver.update(uri, done, null, null) > 0
                    } else {
                        val dir = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS
                        )
                        if (!dir.isDirectory && !dir.mkdirs()) return@runCatching false
                        File(dir, "httpdm-serverlog-$stamp.txt").writeText(header)
                        true
                    }
                }.getOrDefault(false)
            }
            val messageRes = if (exported) {
                R.string.log_exported
            } else {
                R.string.log_export_failed
            }
            Toast.makeText(this@LogActivity, messageRes, Toast.LENGTH_LONG).show()
            binding.logExport.isEnabled = true
        }
    }

    private fun refreshLog() {
        val version = App.httpServer.logVersion()
        if (version == lastLogVersion && logSearch == lastLogSearch) return
        val text = App.httpServer.snapshotLog()
            .ifEmpty { getString(R.string.remote_log_empty) }
        lastLogVersion = version
        lastLogSearch = logSearch
        // Hitung baris non-kosong tanpa mengalokasikan daftar String (sebelumnya
        // text.lines() membangun array baru setiap tick 1 dtk).
        var lines = 0
        var from = 0
        while (from <= text.length) {
            val nl = text.indexOf('\n', from)
            val end = if (nl < 0) text.length else nl
            if (end > from) lines++
            if (nl < 0) break
            from = nl + 1
        }
        binding.logCount.text = resources.getQuantityString(
            R.plurals.remote_log_lines, lines, lines
        )
        val prevScroll = binding.logScroll.scrollY
        binding.log.text = highlightLog(text)
        binding.logScroll.post {
            if (logAutoScroll) {
                binding.logScroll.fullScroll(View.FOCUS_DOWN)
            } else {
                val max = binding.logScroll.getChildAt(0)?.height
                    ?.minus(binding.logScroll.height) ?: 0
                binding.logScroll.scrollTo(0, prevScroll.coerceIn(0, max.coerceAtLeast(0)))
            }
        }
    }

    /** Sorot baris GAGAL/ERROR merah dan kata kunci pencarian kuning. */
    private fun highlightLog(text: String): CharSequence {
        val q = logSearch.trim()
        if (q.isEmpty() && !text.contains("ERROR") &&
            !text.contains("FAILED")
        ) {
            return text
        }
        val sb = SpannableStringBuilder(text)
        if (q.isNotEmpty()) {
            // Cari tanpa mengalokasikan salinan string lowercase per baris log.
            var from = 0
            while (true) {
                val idx = text.indexOf(q, from, ignoreCase = true)
                if (idx < 0) break
                sb.setSpan(
                    BackgroundColorSpan(0xFFFFE082.toInt()),
                    idx, idx + q.length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                from = idx + q.length
            }
        }
        var lineStart = 0
        while (lineStart < sb.length) {
            val lineEnd = text.indexOf('\n', lineStart)
            val end = if (lineEnd < 0) sb.length else lineEnd
            // contains ignoreCase: menggantikan substring().uppercase() —
            // tanpa alokasi String baru per baris (regionMatches internal).
            val lineStr = text.substring(lineStart, end)
            if (lineStr.contains("ERROR", ignoreCase = true) ||
                lineStr.contains("FAILED", ignoreCase = true)
            ) {
                sb.setSpan(
                    ForegroundColorSpan(Color.RED),
                    lineStart, end, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (lineEnd < 0) break
            lineStart = lineEnd + 1
        }
        return sb
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
