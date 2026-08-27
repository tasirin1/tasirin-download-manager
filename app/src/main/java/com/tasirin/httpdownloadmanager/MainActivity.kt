package com.tasirin.httpdownloadmanager

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.app.DownloadManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Menu
import android.view.Window
import android.view.WindowManager
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tasirin.httpdownloadmanager.data.DownloadItem
import com.tasirin.httpdownloadmanager.data.DownloadState
import com.tasirin.httpdownloadmanager.remote.HttpControlServer
import com.tasirin.httpdownloadmanager.databinding.ActivityMainBinding
import com.tasirin.httpdownloadmanager.ui.DownloadAdapter
import com.tasirin.httpdownloadmanager.util.MimeTypes
import com.tasirin.httpdownloadmanager.util.Permissions
import com.tasirin.httpdownloadmanager.util.Formats
import com.tasirin.httpdownloadmanager.util.StoragePrefs
import com.tasirin.httpdownloadmanager.util.Updater
import com.tasirin.httpdownloadmanager.util.SocialMediaExtractor
import com.tasirin.httpdownloadmanager.util.applyEdgeToEdge
import com.tasirin.httpdownloadmanager.util.setupSpinner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity(), DownloadAdapter.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: DownloadAdapter
    private var pendingMoveId: String? = null
    private lateinit var statTotal: TextView
    private lateinit var statActive: TextView
    private lateinit var statDone: TextView
    private lateinit var statFailed: TextView
    private lateinit var statActiveLabel: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* hasil izin tidak wajib untuk fungsi inti */ }

    private val movePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val id = pendingMoveId
        pendingMoveId = null
        if (uri != null && id != null) {
            runCatching {
                takePersistablePermission(uri)
                App.engine.move(id, uri)
            }.onFailure {
                Toast.makeText(this, R.string.storage_picker_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash klasik: tema splash dari manifest untuk window awal, lalu
        // pindah ke tema terang sebelum konten digambar (lihat themes.xml).
        setTheme(R.style.Theme_HttpDownloadManager)
        super.onCreate(savedInstanceState)
        try {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdge(binding.root)
        // Cache TextView statistik: updateToolbar dipanggil ~2x/detik saat
        // download aktif, findViewById tiap tick tidak perlu.
        statTotal = binding.statTotalValue
        statActive = binding.statActiveValue
        statDone = binding.statDoneValue
        statFailed = binding.statFailedValue
        statActiveLabel = binding.statActiveLabel

        setSupportActionBar(binding.toolbar)

        adapter = DownloadAdapter(this)
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.fabAdd.setOnClickListener { showAddDialog() }
        binding.emptyAddButton.setOnClickListener { showAddDialog() }
        binding.emptyRemoteButton.setOnClickListener { openRemote() }
        binding.btnOpenRemote.setOnClickListener { openRemote() }
        binding.serverCard.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnPauseAll.setOnClickListener {
            App.engine.pauseAll()
            Toast.makeText(this, R.string.pause_all, Toast.LENGTH_SHORT).show()
        }
        binding.btnResumeAll.setOnClickListener {
            App.engine.resumeAll()
            Toast.makeText(this, R.string.resume_all, Toast.LENGTH_SHORT).show()
        }
        binding.btnRetryFailed.setOnClickListener {
            App.engine.retryFailed()
            Toast.makeText(this, R.string.retry_failed, Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            App.engine.items.collect { items ->
                runCatching {
                    val filtered = applyFilter(items)
                    adapter.submitList(filtered)
                    binding.emptyView.visibility =
                        if (filtered.isEmpty()) View.VISIBLE else View.GONE
                    updateToolbar(items)
                }
            }
        }

        setupFilterViews()

        requestPermissionsIfNeeded()
        runCatching {
            if (StoragePrefs.isBackgroundEnabled(this)) {
                App.engine.resumeInterrupted()
            }
        }
        if (StoragePrefs.isServerBackgroundEnabled(this) &&
            StoragePrefs.isServerStartAllowed(this) && !App.httpServer.isAlive
        ) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { App.httpServer.startServer() }
            }
        }
        if (StoragePrefs.isBatteryExemptEnabled(this)) {
            requestBatteryExemption()
        }
        handleIncomingIntent(intent)
        } catch (t: Throwable) {
            showFatalError(t)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showFatalError(t: Throwable) {
        App.appendCrash(this, "onCreate", t)
        val stack = Log.getStackTraceString(t)
        runCatching {
            val tv = TextView(this)
            tv.setTextIsSelectable(true)
            tv.text = getString(R.string.fatal_error_message) + "\n\n" + stack
            tv.setPadding(24, 24, 24, 24)
            setContentView(tv)
        }
        runCatching {
            AlertDialog.Builder(this)
                .setTitle(R.string.fatal_error_title)
                .setMessage(stack)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateServerStatus()
    }

    private fun updateServerStatus() {
        val tv = findViewById<TextView>(R.id.server_status) ?: return
        val detail = findViewById<TextView>(R.id.server_detail) ?: return
        val btn = findViewById<View>(R.id.btn_open_remote) ?: return
        val alive = App.httpServer.isAlive
        tv.text = getString(
            if (alive) R.string.server_status_running else R.string.server_status_stopped
        )
        tv.setTextColor(ContextCompat.getColor(this, if (alive) R.color.status_on else R.color.status_off))
        if (alive) {
            detail.text = remoteUrl() ?: getString(R.string.remote_no_url)
            btn.isEnabled = true
        } else {
            detail.text = getString(R.string.server_status_detail_off)
            btn.isEnabled = false
        }
    }

    /** Buka halaman remote web di browser. */
    private fun openRemote() {
        if (!App.httpServer.isAlive) {
            Toast.makeText(this, R.string.server_not_running_hint, Toast.LENGTH_SHORT).show()
            return
        }
        val url = remoteUrl()
        if (url == null) {
            Toast.makeText(this, R.string.remote_no_url, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }.onFailure {
            Toast.makeText(this, R.string.open_remote_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Alamat remote pertama (http://ip:port/), null bila tidak ada IP. */
    private fun remoteUrl(): String? {
        if (!App.httpServer.isAlive) return null
        return HttpControlServer.ipv4Addresses().firstOrNull()
            ?.let { "http://$it:${App.httpServer.listeningPort}/" }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun requestPermissionsIfNeeded() {
        val needed = Permissions.missingRuntime(this)
        if (needed.isNotEmpty()) permissionLauncher.launch(needed)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_ADD_DOWNLOAD, false) == true) {
            showAddDialog()
            return
        }
        if (intent == null) return
        val raw = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.data?.toString()
            else -> null
        } ?: return
        val url = raw.trim().split(URL_SPLIT).firstOrNull {
            it.startsWith("http://") || it.startsWith("https://")
        } ?: return
        showAddDialog(url)
    }

    @SuppressLint("InflateParams") // Inflate dialog dengan root null adalah pola standar.
    private fun showAddDialog(prefillUrl: String? = null) {
        val view = layoutInflater.inflate(R.layout.dialog_add_download, null)
        val urlInput = view.findViewById<EditText>(R.id.input_url)
        val nameInput = view.findViewById<EditText>(R.id.input_name)
        val usernameInput = view.findViewById<EditText>(R.id.input_username)
        val passwordInput = view.findViewById<EditText>(R.id.input_password)
        val headersInput = view.findViewById<EditText>(R.id.input_headers)
        val checksumInput = view.findViewById<EditText>(R.id.input_checksum)
        val mirrorInput = view.findViewById<EditText>(R.id.input_mirrors)
        val speedKbps = SPEED_KBPS
        val spinnerSpeedPer = view.findViewById<Spinner>(R.id.spinner_speed_limit_per)
        setupSpinner(
            this,
            spinnerSpeedPer,
            resources.getStringArray(R.array.speed_limit_per_options).toList()
        )
        val priorityValues = PRIORITY_VALUES
        val spinnerPriority = view.findViewById<Spinner>(R.id.spinner_priority)
        setupSpinner(
            this,
            spinnerPriority,
            resources.getStringArray(R.array.priority_options).toList()
        )
        spinnerPriority.setSelection(1)

        /* Advanced options toggle */
        val advancedToggle = view.findViewById<TextView>(R.id.advanced_toggle)
        val advancedSection = view.findViewById<View>(R.id.advanced_section)
        advancedToggle.setOnClickListener {
            if (advancedSection.visibility == View.VISIBLE) {
                advancedSection.visibility = View.GONE
                advancedToggle.text = getString(R.string.advanced_options)
            } else {
                advancedSection.visibility = View.VISIBLE
                advancedToggle.text = getString(R.string.advanced_options_expanded)
            }
        }

        val storageText = view.findViewById<TextView>(R.id.text_storage_remaining)
        storageText.text = getString(R.string.storage_remaining, Formats.bytes(App.engine.freeSpaceBytes()))

        val fileInfoText = view.findViewById<TextView>(R.id.text_file_info)
        var probeJob: Job? = null
        fun probeFileInfo() {
            probeJob?.cancel()
            val allUrls = urlInput.text?.toString().orEmpty()
            val probeTarget = allUrls
                .split(URL_SPLIT)
                .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
                ?.trim().orEmpty()
            if (probeTarget.isEmpty()) {
                fileInfoText.visibility = View.GONE
                return
            }
            probeJob = lifecycleScope.launch {
                delay(600)
                fileInfoText.text = getString(R.string.file_info_checking)
                fileInfoText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.primary))
                fileInfoText.visibility = View.VISIBLE
                val probe = withContext(Dispatchers.IO) {
                    runCatching {
                        App.engine.probeUrl(
                            probeTarget,
                            usernameInput.text?.toString()?.trim().orEmpty(),
                            passwordInput.text?.toString().orEmpty(),
                            headersInput.text?.toString()?.trim().orEmpty()
                        )
                    }.getOrNull()
                }
                if (probe == null) {
                    fileInfoText.text = getString(R.string.file_info_unknown)
                    return@launch
                }
                val guessedName = probeTarget.substringAfterLast('/').substringBefore('?')
                val name = probe.fileName?.takeIf { it.isNotBlank() } ?: guessedName
                val size = if (probe.sizeBytes > 0) {
                    Formats.bytes(probe.sizeBytes)
                } else {
                    getString(R.string.file_info_size_unknown)
                }
                val type = probe.contentType?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.file_info_type_unknown)
                fileInfoText.text = getString(R.string.file_info_format, name, size, type)
                if (probe.sizeBytes > 0 && probe.sizeBytes > App.engine.freeSpaceBytes()) {
                    fileInfoText.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_off))
                    fileInfoText.append("\n" + getString(
                        R.string.file_info_large_warning,
                        Formats.bytes(probe.sizeBytes)
                    ))
                }
            }
        }
        val fileInfoWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                probeFileInfo()
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        urlInput.addTextChangedListener(fileInfoWatcher)
        usernameInput.addTextChangedListener(fileInfoWatcher)
        passwordInput.addTextChangedListener(fileInfoWatcher)
        headersInput.addTextChangedListener(fileInfoWatcher)
        probeFileInfo()

        fun parseMirrors(): List<String> =
            mirrorInput.text?.toString()?.trim().orEmpty()
                .split(URL_SPLIT)
                .filter { it.startsWith("http://") || it.startsWith("https://") }

        fun addAll(
            urls: List<String>,
            name: String,
            username: String,
            password: String,
            headers: String,
            perSpeed: Int,
            priority: Int,
            checksum: String,
            mirrors: List<String>
        ) {
            urls.forEachIndexed { index, url ->
                App.engine.addDownload(
                    url = url,
                    fileName = if (index == 0) name else null,
                    username = username,
                    password = password,
                    headers = headers,
                    speedLimitKbps = perSpeed,
                    priority = priority,
                    checksum = if (index == 0) checksum else "",
                    mirrors = if (index == 0) mirrors else emptyList()
                )
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.add_download)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.download) { _, _ ->
                val urls = urlInput.text?.toString()?.trim().orEmpty()
                    .split(URL_SPLIT)
                    .filter { it.startsWith("http://") || it.startsWith("https://") }
                if (urls.isEmpty()) {
                    Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val name = nameInput.text?.toString()?.trim().orEmpty()
                val username = usernameInput.text?.toString()?.trim().orEmpty()
                val password = passwordInput.text?.toString()?.trim().orEmpty()
                val headers = headersInput.text?.toString()?.trim().orEmpty()
                val checksum = checksumInput.text?.toString()?.trim().orEmpty()
                val perSpeed = speedKbps[spinnerSpeedPer.selectedItemPosition]
                val priority = priorityValues[spinnerPriority.selectedItemPosition]
                val singleUrl = urls.firstOrNull().orEmpty()
                if (SocialMediaExtractor.isSocialMediaUrl(singleUrl) && urls.size == 1) {
                    // Social media: ekstrak semua opsi resolusi dulu
                    lifecycleScope.launch {
                        val options = withContext(Dispatchers.IO) {
                            runCatching { SocialMediaExtractor.extractAll(singleUrl) }.getOrNull()
                        }
                        if (options.isNullOrEmpty()) {
                            val mirrors = parseMirrors()
                            addAll(urls, name, username, password, headers, perSpeed, priority, checksum, mirrors)
                        } else if (options.size == 1) {
                            val chosen = options[0]
                            App.engine.addDownload(
                                url = chosen.directUrl,
                                fileName = chosen.fileName ?: name.ifEmpty { null },
                                username = username,
                                password = password,
                                headers = headers,
                                speedLimitKbps = perSpeed,
                                priority = priority,
                                checksum = checksum,
                                mirrors = parseMirrors()
                            )
                        } else {
                            showSocialQualityPicker(
                                options = options,
                                name = name,
                                username = username,
                                password = password,
                                headers = headers,
                                perSpeed = perSpeed,
                                priority = priority,
                                checksum = checksum,
                                mirrors = parseMirrors()
                            )
                        }
                    }
                } else if (urls.size == 1 && urls[0].contains("m3u8", ignoreCase = true)) {
                    lifecycleScope.launch {
                        val variants = withContext(Dispatchers.IO) {
                            runCatching { App.engine.probeHlsVariants(urls[0]) }.getOrNull()
                        }
                        if (variants.isNullOrEmpty()) {
                            val mirrors = parseMirrors()
                            addAll(urls, name, username, password, headers, perSpeed, priority, checksum, mirrors)
                        } else {
                            showHlsPicker(
                                variants = variants,
                                originalUrl = urls[0],
                                name = name,
                                username = username,
                                password = password,
                                headers = headers,
                                perSpeed = perSpeed,
                                priority = priority,
                                checksum = checksum,
                                mirrors = parseMirrors()
                            )
                        }
                    }
                } else {
                    addAll(urls, name, username, password, headers, perSpeed, priority, checksum, parseMirrors())
                }
            }
            .show()
    }

    private fun showSocialQualityPicker(
        options: List<SocialMediaExtractor.Result>,
        name: String,
        username: String,
        password: String,
        headers: String,
        perSpeed: Int,
        priority: Int,
        checksum: String,
        mirrors: List<String> = emptyList()
    ) {
        val labels = options.map { opt ->
            val size = if (opt.quality.isNotBlank()) " [${opt.quality}]" else ""
            "${opt.fileName?.substringBeforeLast('.') ?: "media"}$size"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.social_quality_title)
            .setItems(labels.toTypedArray()) { _, which ->
                val chosen = options[which]
                App.engine.addDownload(
                    url = chosen.directUrl,
                    fileName = chosen.fileName ?: name.ifEmpty { null },
                    username = username,
                    password = password,
                    headers = headers,
                    speedLimitKbps = perSpeed,
                    priority = priority,
                    checksum = checksum,
                    mirrors = mirrors
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showHlsPicker(
        variants: List<com.tasirin.httpdownloadmanager.download.HlsVariant>,
        originalUrl: String,
        name: String,
        username: String,
        password: String,
        headers: String,
        perSpeed: Int,
        priority: Int,
        checksum: String,
        mirrors: List<String> = emptyList()
    ) {
        val labels = variants.map { it.name } + getString(R.string.hls_direct)
        AlertDialog.Builder(this)
            .setTitle(R.string.hls_quality_title)
            .setItems(labels.toTypedArray()) { _, which ->
                val target = if (which < variants.size) variants[which].url else originalUrl
                val chosenName = if (which < variants.size) {
                    variants[which].name.replace(' ', '_') + ".m3u8"
                } else {
                    name
                }
                App.engine.addDownload(
                    url = target,
                    fileName = chosenName,
                    username = username,
                    password = password,
                    headers = headers,
                    speedLimitKbps = perSpeed,
                    priority = priority,
                    checksum = checksum,
                    mirrors = mirrors
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    @SuppressLint("InflateParams")
    private fun showAboutDialog() {
        val info = runCatching {
            packageManager.getPackageInfo(packageName, 0)
        }.getOrNull()
        val version = info?.versionName ?: "1.0"
        val build = info?.versionCode ?: 0
        val view = layoutInflater.inflate(R.layout.dialog_about, null)
        view.findViewById<TextView>(R.id.about_version).text =
            getString(R.string.about_version, version)
        // Baris info terstruktur: ikon + teks (sumber: arrays about_icons/about_rows).
        val rows = view.findViewById<LinearLayout>(R.id.about_rows)
        val icons = resources.getStringArray(R.array.about_icons)
        val texts = resources.getStringArray(R.array.about_rows)
        for (i in icons.indices) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = android.view.Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (i > 0) lp.topMargin = dp(6)
            val icon = TextView(this)
            icon.text = icons[i]
            icon.textSize = 16f
            icon.setPadding(0, 0, dp(10), 0)

            val txt = TextView(this)
            txt.text = texts[i]
            txt.textSize = 13.5f
            txt.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            row.addView(icon)
            row.addView(txt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            rows.addView(row, lp)
        }
        val targetSdk = runCatching {
            packageManager.getApplicationInfo(packageName, 0).targetSdkVersion
        }.getOrDefault(36)
        view.findViewById<TextView>(R.id.about_footer).text = getString(
            R.string.about_tech,
            21, // minSdk dijaga 21 (aturan AGENTS.md: jangan naikkan)
            targetSdk,
            build
        )
        view.findViewById<Button>(R.id.btn_about_github).setOnClickListener {
            runCatching {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        "https://github.com/tasirin1/tasirin-download-manager".toUri()
                    )
                )
            }
        }
        view.findViewById<Button>(R.id.btn_about_update).setOnClickListener {
            checkUpdateFromAbout()
        }
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        // Lebar dialog ramah layar: maks 560dp, tapi jangan sampai penuh layar
        // (HP density tinggi) — sisakan margin 48dp tiap sisi.
        val density = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels
        val width = minOf((560 * density).toInt(), screenW - (48 * density).toInt())
        dialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.show()
        view.findViewById<Button>(R.id.btn_about_ok).setOnClickListener { dialog.dismiss() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Cek versi terbaru dari GitHub (tanpa unduh/pasang — itu ada di Pengaturan). */
    private fun checkUpdateFromAbout() {
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { Updater.checkLatest(this@MainActivity) }
            val current = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionCode
            }.getOrDefault(0)
            val msg = when {
                info == null -> getString(R.string.update_failed)
                info.versionCode > current ->
                    getString(R.string.update_available, info.versionName, info.versionCode)
                else -> getString(R.string.update_latest)
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.update_check)
                .setMessage(msg)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    override fun onAction(item: DownloadItem, action: DownloadAdapter.Action) {
        when (action) {
            DownloadAdapter.Action.PAUSE -> App.engine.pause(item.id)
            DownloadAdapter.Action.RESUME -> App.engine.resume(item.id)
            DownloadAdapter.Action.CANCEL -> lifecycleScope.launch(Dispatchers.IO) {
                App.engine.cancel(item.id)
            }
            DownloadAdapter.Action.DELETE -> lifecycleScope.launch(Dispatchers.IO) {
                App.engine.remove(item.id)
            }
            DownloadAdapter.Action.OPEN -> openDownload(item)
            DownloadAdapter.Action.OPEN_FOLDER -> openFolder(item)
            DownloadAdapter.Action.MONITOR ->
                App.engine.setMonitor(item.id, !item.monitor)
        }
    }

    override fun onTap(item: DownloadItem) {
        when (item.state) {
            DownloadState.DOWNLOADING, DownloadState.PENDING -> App.engine.pause(item.id)
            DownloadState.PAUSED, DownloadState.FAILED -> App.engine.resume(item.id)
            DownloadState.COMPLETED -> openDownload(item)
            else -> Unit
        }
    }

    override fun onLongPress(item: DownloadItem) {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (item.state == DownloadState.DOWNLOADING || item.state == DownloadState.PENDING) {
            options.add(getString(R.string.pause) to { App.engine.pause(item.id) })
        }
        if (item.state == DownloadState.PAUSED || item.state == DownloadState.FAILED) {
            options.add(getString(R.string.resume) to { App.engine.resume(item.id) })
        }
        if (item.state == DownloadState.COMPLETED) {
            options.add(getString(R.string.open) to { openDownload(item) })
            options.add(getString(R.string.open_folder) to { openFolder(item) })
            options.add(
                getString(if (item.monitor) R.string.action_monitor_off else R.string.action_monitor_on) to
                    { App.engine.setMonitor(item.id, !item.monitor) }
            )
            options.add(getString(R.string.action_rename) to { showRenameDialog(item) })
            options.add(getString(R.string.action_move) to {
                pendingMoveId = item.id
                launchDocumentTree(movePicker)
            })
        }
        if (item.state == DownloadState.PENDING ||
            item.state == DownloadState.PAUSED ||
            item.state == DownloadState.FAILED
        ) {
            options.add(
                getString(R.string.action_limit_priority) to {
                    showLimitPriorityDialog(item)
                }
            )
        }
        if (item.state == DownloadState.DOWNLOADING ||
            item.state == DownloadState.PENDING ||
            item.state == DownloadState.PAUSED
        ) {
            options.add(getString(R.string.cancel) to {
                lifecycleScope.launch(Dispatchers.IO) { App.engine.cancel(item.id) }
            })
        }
        if (item.state == DownloadState.COMPLETED ||
            item.state == DownloadState.FAILED ||
            item.state == DownloadState.CANCELLED
        ) {
            options.add(getString(R.string.delete) to {
                lifecycleScope.launch(Dispatchers.IO) { App.engine.remove(item.id) }
            })
        }
        val labels = options.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(item.fileName)
            .setItems(labels) { _, which -> options[which].second.invoke() }
            .show()
    }

    private fun showRenameDialog(item: DownloadItem) {
        val input = EditText(this)
        input.isFocusable = true
        input.isFocusableInTouchMode = true
        input.setText(item.fileName)
        input.setSelection(input.text.length)
        AlertDialog.Builder(this)
            .setTitle(R.string.action_rename)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = input.text?.toString()?.trim().orEmpty()
                if (newName.isNotEmpty() && newName != item.fileName) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        App.engine.rename(item.id, newName)
                    }
                }
            }
            .show()
    }

    private fun takePersistablePermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }


    private fun launchDocumentTree(launcher: ActivityResultLauncher<Uri?>) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
        if (intent.resolveActivity(packageManager) == null) {
            Toast.makeText(this, R.string.storage_picker_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        launcher.launch(downloadsInitialUri())
    }

    private fun downloadsInitialUri(): Uri? {
        if (Build.VERSION.SDK_INT < 26) return null
        return runCatching {
            DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:Download"
            )
        }.getOrNull()
    }


    @SuppressLint("BatteryLife") // Penjelasan izin baterai jelas bagi pengguna TV box/HP.
    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < 23) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    "package:$packageName".toUri()
                )
            )
        }.onFailure {
            Toast.makeText(this, R.string.battery_request_failed, Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("InflateParams")
    private fun showLimitPriorityDialog(item: DownloadItem) {
        val view = layoutInflater.inflate(R.layout.dialog_limit_priority, null)
        val itemSpeed = item.speedLimitKbps
        val speedPerOptions = resources.getStringArray(R.array.speed_limit_per_options).toMutableList()
        val speedKbps = SPEED_KBPS
        if (itemSpeed !in speedKbps) {
            speedPerOptions.add(getString(R.string.settings_speed_custom, itemSpeed))
        }
        val spinnerSpeed = view.findViewById<Spinner>(R.id.spinner_speed_limit_per)
        setupSpinner(this, spinnerSpeed, speedPerOptions)
        val speedIndex = speedKbps.indexOf(itemSpeed)
        spinnerSpeed.setSelection(if (speedIndex >= 0) speedIndex else speedPerOptions.size - 1)

        val priorityValues = PRIORITY_VALUES
        val spinnerPriority = view.findViewById<Spinner>(R.id.spinner_priority)
        setupSpinner(
            this,
            spinnerPriority,
            resources.getStringArray(R.array.priority_options).toList()
        )
        spinnerPriority.setSelection(
            priorityValues.indexOf(item.priority).coerceAtLeast(0)
        )

        AlertDialog.Builder(this)
            .setTitle(item.fileName)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val selSpeed = spinnerSpeed.selectedItemPosition
                App.engine.setLimitAndPriority(
                    item.id,
                    if (selSpeed < speedKbps.size) speedKbps[selSpeed] else itemSpeed,
                    priorityValues[spinnerPriority.selectedItemPosition]
                )
            }
            .show()
    }

    private enum class DownloadFilter { ALL, ACTIVE, COMPLETED, FAILED }

    private var currentFilter = DownloadFilter.ALL
    private var sortMode = 0

    private fun setupFilterViews() {
        // Diinisialisasi di sini, bukan di properti, karena getSharedPreferences
        // belum tersedia saat field Activity dibuat (force close di Android).
        sortMode = StoragePrefs.sortMode(this)
        findViewById<TextView>(R.id.sort_button)?.setOnClickListener { showSortDialog() }
        updateSortButton()

        val map = listOf(
            R.id.filter_all to DownloadFilter.ALL,
            R.id.filter_active to DownloadFilter.ACTIVE,
            R.id.filter_completed to DownloadFilter.COMPLETED,
            R.id.filter_failed to DownloadFilter.FAILED
        )
        map.forEach { (id, filter) ->
            findViewById<TextView>(id)?.setOnClickListener {
                currentFilter = filter
                updateFilterColors()
                refreshList()
            }
        }
        updateFilterColors()
    }

    private fun updateFilterColors() {
        val map = listOf(
            R.id.filter_all to DownloadFilter.ALL,
            R.id.filter_active to DownloadFilter.ACTIVE,
            R.id.filter_completed to DownloadFilter.COMPLETED,
            R.id.filter_failed to DownloadFilter.FAILED
        )
        map.forEach { (id, filter) ->
            val tv = findViewById<TextView>(id) ?: return@forEach
            val selected = filter == currentFilter
            tv.isSelected = selected
            tv.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.white else R.color.text_secondary
                )
            )
            tv.typeface = if (selected) android.graphics.Typeface.DEFAULT_BOLD else null
        }
    }

    /** Statistik + visibilitas tombol batch dihitung dalam SATU iterasi daftar
     *  (sebelumnya 4× iterasi per emisi: 3× any{} + 1× statistik). */
    private fun updateToolbar(items: List<DownloadItem>) {
        var active = 0
        var done = 0
        var failed = 0
        var paused = 0
        var speed = 0L
        for (item in items) {
            when (item.state) {
                DownloadState.DOWNLOADING -> {
                    active++
                    speed += item.speedBps
                }
                DownloadState.PENDING -> active++
                DownloadState.COMPLETED -> done++
                DownloadState.FAILED -> failed++
                DownloadState.PAUSED -> paused++
                else -> {}
            }
        }
        statTotal.text = getString(R.string.stat_number, items.size)
        statActive.text = getString(R.string.stat_number, active)
        statDone.text = getString(R.string.stat_number, done)
        statFailed.text = getString(R.string.stat_number, failed)
        statActiveLabel.text = getString(
            if (speed > 0) R.string.stat_active_speed else R.string.stat_active,
            Formats.speed(speed)
        )
        binding.btnPauseAll.visibility = if (active > 0) View.VISIBLE else View.GONE
        binding.btnResumeAll.visibility = if (paused > 0 || failed > 0) View.VISIBLE else View.GONE
        binding.btnRetryFailed.visibility = if (failed > 0) View.VISIBLE else View.GONE
    }

    private fun refreshList() {
        runCatching {
            val items = App.engine.items.value
            val filtered = applyFilter(items)
            adapter.submitList(filtered)
            binding.emptyView.visibility =
                if (filtered.isEmpty()) View.VISIBLE else View.GONE
            updateToolbar(items)
        }
    }

    private fun applyFilter(items: List<DownloadItem>): List<DownloadItem> {
        val filtered = when (currentFilter) {
            DownloadFilter.ALL -> items
            DownloadFilter.ACTIVE -> items.filter {
                it.state == DownloadState.DOWNLOADING || it.state == DownloadState.PENDING
            }
            DownloadFilter.COMPLETED -> items.filter { it.state == DownloadState.COMPLETED }
            DownloadFilter.FAILED -> items.filter {
                it.state == DownloadState.FAILED || it.state == DownloadState.CANCELLED
            }
        }
        return when (sortMode) {
            // Daftar engine sudah terurut addedAt desc, jadi tanpa sort ulang.
            0 -> filtered
            1 -> filtered.asReversed()
            2 -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.fileName })
            3 -> filtered.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.fileName })
            4 -> filtered.sortedByDescending { it.totalBytes }
            5 -> filtered.sortedBy { it.totalBytes }
            else -> filtered.sortedWith(
                compareBy<DownloadItem> { STATE_RANK[it.state] ?: 0 }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.fileName }
            )
        }
    }

    private fun showSortDialog() {
        val options = resources.getStringArray(R.array.sort_options)
        AlertDialog.Builder(this)
            .setTitle(R.string.sort_by)
            .setSingleChoiceItems(options, sortMode) { _, which ->
                sortMode = which
                StoragePrefs.setSortMode(this, which)
                updateSortButton()
                refreshList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateSortButton() {
        val tv = findViewById<TextView>(R.id.sort_button) ?: return
        val options = resources.getStringArray(R.array.sort_options)
        val label = options.getOrElse(sortMode) { options[0] }
                tv.text = getString(R.string.label_value, getString(R.string.sort_by), label)
    }

    /** Ekspor log error (crash + error server) ke file .txt di folder Download. */
    private fun openDownload(item: DownloadItem) {
        if (item.state != DownloadState.COMPLETED) return
        val mime = MimeTypes.forFile(item.fileName)
        val intent = when {
            !item.contentUri.isNullOrEmpty() -> {
                Intent(Intent.ACTION_VIEW).setDataAndType(item.contentUri.toUri(), mime)
            }
            !item.filePath.isNullOrEmpty() -> {
                val uri = FileProvider.getUriForFile(
                    this, "$packageName.fileprovider", File(item.filePath)
                )
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            else -> null
        }
        if (intent != null) {
            try {
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openFolder(item: DownloadItem) {
        // Beberapa file manager tidak mengikuti activity resolution standar,
        // jadi intent dicoba langsung sebelum jatuh ke app Downloads.
        folderIntents(item).forEach { intent ->
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
        val downloads = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { startActivity(downloads) }.isSuccess) return
        Toast.makeText(this, R.string.open_folder_unavailable, Toast.LENGTH_LONG)
            .show()
    }

    /** Kembalikan daftar intent fallback untuk membuka folder download.
     *  Urutan: DocumentsContract → File URI → Downloads app bawaan. */
    private fun folderIntents(item: DownloadItem): List<Intent> {
        val list = mutableListOf<Intent>()
        if (!item.filePath.isNullOrEmpty()) {
            val parent = File(item.filePath).parentFile
            if (parent != null && parent.isDirectory) {
                val rel = parent.absolutePath.removePrefix("/storage/emulated/0/")
                if (rel != parent.absolutePath) {
                    list.add(
                        Intent(Intent.ACTION_VIEW).setDataAndType(
                            DocumentsContract.buildDocumentUri(
                                "com.android.externalstorage.documents", "primary:$rel"
                            ),
                            "vnd.android.document/directory"
                        )
                    )
                }
                // Fallback File URI (Android 5-6 TV box sering tidak punya DocumentsUI).
                if (Build.VERSION.SDK_INT < 24) {
                    list.add(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(Uri.fromFile(parent), "resource/folder")
                    )
                }
            }
        }
        if (!item.contentUri.isNullOrEmpty() && Build.VERSION.SDK_INT >= 29) {
            val uri = item.contentUri.toUri()
            val rel = runCatching {
                contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                    null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH))
                    } else null
                }
            }.getOrNull()?.trim('/')
            val targetRel = rel?.takeIf { it.isNotBlank() } ?: "Download"
            list.add(
                Intent(Intent.ACTION_VIEW).setDataAndType(
                    DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents", "primary:$targetRel"
                    ),
                    "vnd.android.document/directory"
                )
            )
        }
        return list
    }

    companion object {
        private val STATE_RANK = mapOf(
            DownloadState.PENDING to 0,
            DownloadState.DOWNLOADING to 1,
            DownloadState.PAUSED to 2,
            DownloadState.COMPLETED to 3,
            DownloadState.FAILED to 4,
            DownloadState.CANCELLED to 5
        )
        private val URL_SPLIT = Regex("[\\s,]+")
        private const val EXTRA_ADD_DOWNLOAD = "com.tasirin.httpdownloadmanager.ADD_DOWNLOAD"
        private val SPEED_KBPS = intArrayOf(0, 128, 256, 512, 1024, 2048, 5120)
        private val PRIORITY_VALUES = intArrayOf(-1, 0, 1)
    }
}
