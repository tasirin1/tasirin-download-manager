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
import com.tasirin.httpdownloadmanager.databinding.ActivityLogBinding
import com.tasirin.httpdownloadmanager.util.applyEdgeToEdge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Halaman khusus log server realtime: layar penuh, auto-scroll default mati. */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding

    private companion object {
        val EXPORT_TIME = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val EXPORT_STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
    private var logAutoScroll = false
    private var logSearch = ""
    private var lastLogKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdge(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

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
        val pollLog = object : Runnable {
            override fun run() {
                if (isDestroyed || isFinishing) return
                refreshLog()
                binding.log.postDelayed(this, 1000)
            }
        }
        binding.log.postDelayed(pollLog, 1000)
    }

    private fun exportLogTxt() {
        val log = App.httpServer.snapshotLog()
        val header = buildString {
            appendLine("=== Tasirin Download Manager - Log Server (realtime) ===")
            appendLine("Time: ${EXPORT_TIME.format(Date())}")
            appendLine(
                "App version: " + runCatching {
                    val info = packageManager.getPackageInfo(packageName, 0)
                    info.versionName + " (build " + info.versionCode + ")"
                }.getOrDefault("?")
            )
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            append(if (log.isBlank()) "(No server activity yet)\n" else log)
            val crashText = runCatching {
                val f = File(filesDir, App.CRASH_LOG_FILE)
                if (f.exists()) f.readText().trim() else ""
            }.getOrDefault("")
            if (crashText.isNotEmpty()) {
                appendLine()
                appendLine("=== Crash log (previous launches) ===")
                appendLine(crashText)
            }
            appendLine()
        }
        val stamp = EXPORT_STAMP.format(Date())
        val ok = runCatching {
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
                if (dir == null) return@runCatching false
                if (!dir.isDirectory && !dir.mkdirs()) return@runCatching false
                File(dir, "httpdm-serverlog-$stamp.txt").writeText(header)
                true
            }
        }.getOrDefault(false)
        Toast.makeText(
            this,
            if (ok) R.string.log_exported else R.string.log_export_failed,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun refreshLog() {
        val text = App.httpServer.snapshotLog()
            .ifEmpty { getString(R.string.remote_log_empty) }
        val lines = text.lines().count { it.isNotBlank() }
        binding.logCount.text = resources.getQuantityString(
            R.plurals.remote_log_lines, lines, lines
        )
        // Kunci render = isi log + kata kunci: teks sama tapi kata kunci
        // berubah tetap harus di-highlight ulang.
        val key = text + "\u0000" + logSearch
        if (key == lastLogKey) return
        lastLogKey = key
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
            val upper = text.substring(lineStart, end).uppercase()
            if (upper.contains("ERROR") || upper.contains("FAILED")) {
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
