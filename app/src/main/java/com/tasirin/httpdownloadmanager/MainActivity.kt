package com.tasirin.httpdownloadmanager

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipboardManager
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
import android.view.animation.AnimationUtils
import androidx.core.view.isVisible
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.content.FileProvider
import androidx.core.content.res.toDrawable
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tasirin.httpdownloadmanager.data.DownloadItem
import com.tasirin.httpdownloadmanager.util.SocialMediaExtractor
import com.tasirin.httpdownloadmanager.data.DownloadState
import com.tasirin.httpdownloadmanager.remote.HttpControlServer
import com.tasirin.httpdownloadmanager.databinding.ActivityMainBinding
import com.tasirin.httpdownloadmanager.ui.DownloadAdapter
import com.tasirin.httpdownloadmanager.ui.DownloadRow
import com.tasirin.httpdownloadmanager.util.MimeTypes
import com.tasirin.httpdownloadmanager.util.Permissions
import com.tasirin.httpdownloadmanager.util.StoragePrefs
import com.tasirin.httpdownloadmanager.util.Updater
import com.tasirin.httpdownloadmanager.util.applyEdgeToEdge
import com.tasirin.httpdownloadmanager.util.setupSpinner
import com.tasirin.httpdownloadmanager.util.versionCodeCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity

class MainActivity : AppCompatActivity(), DownloadAdapter.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: DownloadAdapter
    private lateinit var listLayoutManager: LinearLayoutManager
    private var pendingMoveId: String? = null
    private var summaryActive = 0
    private var summaryPaused = 0
    private var summaryFailed = 0
    private var lastItems: List<DownloadItem> = emptyList()
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
        setSupportActionBar(binding.toolbar)
        val overflowColor = ContextCompat.getColor(this, R.color.white)
        binding.toolbar.overflowIcon = R.drawable.ic_more.toDrawable().mutate()
            ?.apply { setTint(overflowColor) }

        adapter = DownloadAdapter(this)
        listLayoutManager = LinearLayoutManager(this)
        binding.recycler.layoutManager = listLayoutManager
        binding.recycler.adapter = adapter
        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateStickyHeader()
            }
        })

        binding.fabAdd.setOnClickListener { showAddDialog() }
        binding.emptyAddButton.setOnClickListener { showAddDialog() }
        binding.emptyPasteButton.setOnClickListener { pasteFromClipboard() }
        binding.emptyRemoteButton.setOnClickListener { openRemote() }

        // Geser item: kanan = pause/resume, kiri = hapus (dengan konfirmasi).
        val swipeCallback = object :
            ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                // Header section tidak boleh digeser.
                if (viewHolder is DownloadAdapter.HeaderHolder) return 0
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return
                val row = adapter.currentList.getOrNull(position) ?: return
                // Pulihkan tampilan item setelah animasi swipe.
                adapter.notifyItemChanged(position)
                if (row !is DownloadRow.Item) return
                val item = row.item
                when (direction) {
                    ItemTouchHelper.RIGHT -> when (item.state) {
                        DownloadState.DOWNLOADING, DownloadState.PENDING ->
                            App.engine.pause(item.id)
                        DownloadState.PAUSED -> App.engine.resume(item.id)
                        else -> Unit
                    }
                    ItemTouchHelper.LEFT -> confirmSwipeDelete(item)
                }
            }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.recycler)

        lifecycleScope.launch {
            App.engine.items.collect { items ->
                lastItems = items
                runCatching {
                    adapter.submitList(DownloadAdapter.buildSections(this@MainActivity, items))
                    updateStickyHeader()
                    val showEmpty = items.isEmpty()
                    binding.emptyView.visibility =
                        if (showEmpty) View.VISIBLE else View.GONE
                    if (showEmpty && binding.emptyView.animation == null) {
                        runCatching {
                            val pulse = AnimationUtils.loadAnimation(
                                this@MainActivity, R.anim.pulse
                            )
                            binding.emptyView.getChildAt(0).startAnimation(pulse)
                        }
                    } else if (!showEmpty) {
                        binding.emptyView.getChildAt(0).clearAnimation()
                    }
                    updateToolbar(items)
                }
            }
        }

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

    override fun onResume() {
        super.onResume()
        // Setelah kembali dari halaman izin sistem: auto-aktifkan "Full access to
        // main storage" bila izin "All files access" baru saja diberikan.
        Permissions.syncFullAccessAfterGrant(this)
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

    /** Tawarkan aktivasi "All files access" sekali saja saat pertama kali dibuka. */


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
        if (!prefillUrl.isNullOrBlank()) urlInput.setText(prefillUrl)
        val nameInput = view.findViewById<EditText>(R.id.input_name)
        val usernameInput = view.findViewById<EditText>(R.id.input_username)
        val passwordInput = view.findViewById<EditText>(R.id.input_password)
        val headersInput = view.findViewById<EditText>(R.id.input_headers)
        val checksumInput = view.findViewById<EditText>(R.id.input_checksum)
        val mirrorInput = view.findViewById<EditText>(R.id.input_mirrors)
        val btnPasteUrl = view.findViewById<Button>(R.id.btn_paste_url)
        val platformBadge = view.findViewById<TextView>(R.id.text_social_platform)
        val socialQualitySection = view.findViewById<View>(R.id.social_quality_section)
        val socialQualitySpinner = view.findViewById<Spinner>(R.id.spinner_social_quality)
        val socialCarouselSection = view.findViewById<View>(R.id.social_carousel_section)
        val socialCarouselSpinner = view.findViewById<Spinner>(R.id.spinner_social_carousel)
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

        // Tombol Paste: isi URL dari clipboard tanpa perlu keluar dialog.
        btnPasteUrl.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            } else {
                urlInput.setText(text)
                urlInput.setSelection(urlInput.text.length)
            }
        }

        /* Advanced options toggle (accordion dengan chevron berputar) */
        val advancedToggle = view.findViewById<View>(R.id.advanced_toggle)
        val advancedChevron = view.findViewById<ImageView>(R.id.advanced_chevron)
        val advancedSection = view.findViewById<View>(R.id.advanced_section)
        advancedToggle.setOnClickListener {
            val expanded = !advancedSection.isVisible
            advancedSection.isVisible = expanded
            // Chevron berputar mengikuti status buka/tutup.
            advancedChevron.animate().rotation(if (expanded) 180f else 0f).setDuration(200).start()
        }

        // Deteksi link media sosial → tampilkan pemilihan resolusi.
        // YouTube memakai daftar resolusi tetap (1080/720/480/360/240);
        // platform lain memakai kualitas hasil ekstraksi (HD/SD/Photo, dst).
        var socialOptions: List<SocialMediaExtractor.Result> = emptyList()
        var socialVideoOptions: List<SocialMediaExtractor.Result> = emptyList()
        var socialPhotoOptions: List<SocialMediaExtractor.Result> = emptyList()
        var socialYoutubeHeights: IntArray = intArrayOf()
        var socialJob: Job? = null
        fun platformLabelFrom(url: String): String {
            val host = runCatching { url.toUri().host.orEmpty() }.getOrDefault("").lowercase()
            return when {
                host.contains("youtube.com") || host.contains("youtu.be") ->
                    getString(R.string.platform_youtube)
                host.contains("tiktok.com") || host.contains("douyin.com") ->
                    getString(R.string.platform_tiktok)
                host.contains("instagram.com") || host.contains("instagr.am") ->
                    getString(R.string.platform_instagram)
                host.contains("twitter.com") || host.contains("x.com") ->
                    getString(R.string.platform_x)
                else -> getString(R.string.platform_social)
            }
        }
        fun setPlatformBadge(url: String?) {
            if (url.isNullOrBlank()) {
                platformBadge.isVisible = false
                return
            }
            platformBadge.isVisible = true
            platformBadge.text = getString(R.string.platform_detected, platformLabelFrom(url))
        }
        fun probeSocialQuality() {
            socialJob?.cancel()
            val allUrls = urlInput.text?.toString().orEmpty()
            val target = allUrls
                .split(URL_SPLIT)
                .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
                ?.trim().orEmpty()
            val isSocial = target.isNotEmpty() && SocialMediaExtractor.isSocialMediaUrl(target)
            if (!isSocial) {
                socialJob = null
                socialOptions = emptyList()
                socialVideoOptions = emptyList()
                socialPhotoOptions = emptyList()
                socialYoutubeHeights = intArrayOf()
                socialQualitySection.isVisible = false
                socialCarouselSection.isVisible = false
                platformBadge.isVisible = false
                return
            }
            setPlatformBadge(target)
            val isYoutube = target.contains("youtube.com/") || target.contains("youtu.be/")
            if (isYoutube) {
                socialOptions = emptyList()
                socialVideoOptions = emptyList()
                socialPhotoOptions = emptyList()
                socialYoutubeHeights = intArrayOf(1080, 720, 480, 360, 240)
                val labels = listOf(getString(R.string.social_quality_default)) +
                    socialYoutubeHeights.map { "${it}p" }
                setupSpinner(this@MainActivity, socialQualitySpinner, labels)
                socialQualitySection.isVisible = true
                socialCarouselSection.isVisible = false
                return
            }
            socialYoutubeHeights = intArrayOf()
            socialJob = lifecycleScope.launch {
                val options = withContext(Dispatchers.IO) {
                    runCatching { SocialMediaExtractor.extractAll(target) }.getOrElse { emptyList() }
                }
                socialOptions = options
                // Pisahkan opsi video dan foto
                socialVideoOptions = options.filter { it.mimeType.startsWith("video") }
                socialPhotoOptions = options.filter { it.mimeType.startsWith("image") }
                val hasVideo = socialVideoOptions.isNotEmpty()
                val hasPhotos = socialPhotoOptions.isNotEmpty()
                socialQualitySection.isVisible = false
                socialCarouselSection.isVisible = false
                if (!hasVideo && !hasPhotos) {
                    platformBadge.isVisible = false
                    return@launch
                }
                // Section kualitas video
                if (hasVideo) {
                    val labels = listOf(getString(R.string.social_quality_default)) +
                        socialVideoOptions.map { opt ->
                            if (opt.quality.isBlank()) {
                                opt.mimeType.takeIf { it.isNotBlank() } ?: "Video"
                            } else {
                                opt.quality
                            }
                        }
                    setupSpinner(this@MainActivity, socialQualitySpinner, labels)
                    socialQualitySection.isVisible = true
                }
                // Section pemilihan foto carousel
                if (hasPhotos) {
                    val photoLabels = if (socialPhotoOptions.size > 1) {
                        listOf(String.format(getString(R.string.social_carousel_all), socialPhotoOptions.size)) +
                            socialPhotoOptions.mapIndexed { i, opt ->
                                val label = opt.quality.takeIf { it.isNotBlank() }
                                    ?: opt.mimeType.takeIf { it.isNotBlank() }
                                    ?: "Photo"
                                "${i + 1}. $label"
                            }
                    } else {
                        listOf(getString(R.string.social_quality_default)) +
                            socialPhotoOptions.map { opt ->
                                opt.quality.takeIf { it.isNotBlank() }
                                    ?: opt.mimeType.takeIf { it.isNotBlank() }
                                    ?: "Photo"
                            }
                    }
                    setupSpinner(this@MainActivity, socialCarouselSpinner, photoLabels)
                    socialCarouselSection.isVisible = true
                }
            }
        }
        val socialWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                probeSocialQuality()
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        // Deteksi kualitas hanya bergantung pada URL; watcher di field lain
        // (username/password/headers) tidak perlu karena tiap ketikan di sana
        // akan membatalkan & memulai ulang ekstraksi jaringan tanpa mengubah URL.
        urlInput.addTextChangedListener(socialWatcher)
        probeSocialQuality()

        fun parseMirrors(): List<String> =
            mirrorInput.text?.toString()?.trim().orEmpty()
                .split(URL_SPLIT)
                .filter { it.startsWith("http://") || it.startsWith("https://") }

        fun addYoutubeWithHeight(
            urls: List<String>,
            height: Int,
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
                    mirrors = if (index == 0) mirrors else emptyList(),
                    preferredHeight = height
                )
            }
        }

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

        // Dialog bottom-sheet: tanpa title bar AlertDialog, tombol ada di layout.
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.BOTTOM)
        }
        fun submitDownload() {
            val urls = urlInput.text?.toString()?.trim().orEmpty()
                .split(URL_SPLIT)
                .filter { it.startsWith("http://") || it.startsWith("https://") }
            if (urls.isEmpty()) {
                Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
                return
            }
            // Media sosial: pakai opsi yang dipilih dari spinner.
            val socialSel = socialQualitySpinner.selectedItemPosition
            val selectedVideoOption = if (
                socialQualitySection.isVisible &&
                socialVideoOptions.isNotEmpty() &&
                socialSel in 1..socialVideoOptions.size
            ) socialVideoOptions[socialSel - 1] else null
            val selectedYtHeight = if (
                socialQualitySection.isVisible &&
                socialYoutubeHeights.isNotEmpty() &&
                socialSel in 1..socialYoutubeHeights.size
            ) socialYoutubeHeights[socialSel - 1] else 0
            val carouselSel = socialCarouselSpinner.selectedItemPosition
            val carouselAll = socialCarouselSection.isVisible &&
                socialPhotoOptions.size > 1 && carouselSel == 0
            val selectedPhotoOption = if (
                socialCarouselSection.isVisible &&
                socialPhotoOptions.isNotEmpty() &&
                carouselSel in 1..socialPhotoOptions.size
            ) socialPhotoOptions[carouselSel - 1] else null
            val selectedOption = if (carouselAll) null else (selectedPhotoOption ?: selectedVideoOption)
            val name = nameInput.text?.toString()?.trim().orEmpty()
            val username = usernameInput.text?.toString()?.trim().orEmpty()
            val password = passwordInput.text?.toString()?.trim().orEmpty()
            val headers = headersInput.text?.toString()?.trim().orEmpty()
            val checksum = checksumInput.text?.toString()?.trim().orEmpty()
            val perSpeed = speedKbps[spinnerSpeedPer.selectedItemPosition]
            val priority = priorityValues[spinnerPriority.selectedItemPosition]
            if (selectedYtHeight > 0) {
                addYoutubeWithHeight(
                    urls = urls,
                    height = selectedYtHeight,
                    name = name,
                    username = username,
                    password = password,
                    headers = headers,
                    perSpeed = perSpeed,
                    priority = priority,
                    checksum = checksum,
                    mirrors = parseMirrors()
                )
            } else if (carouselAll && socialPhotoOptions.isNotEmpty()) {
                // Download semua foto carousel sekaligus
                socialPhotoOptions.forEachIndexed { idx, opt ->
                    val mergedHeaders = if (opt.cookies.isNotEmpty()) {
                        val existing = headers.trim()
                        if (existing.isNotEmpty()) "${existing}\nCookie: ${opt.cookies}"
                        else "Cookie: ${opt.cookies}"
                    } else headers
                    App.engine.addDownload(
                        url = opt.directUrl,
                        fileName = opt.fileName,
                        username = username,
                        password = password,
                        headers = mergedHeaders,
                        speedLimitKbps = perSpeed,
                        priority = priority,
                        checksum = if (idx == 0) checksum else "",
                        mirrors = if (idx == 0) parseMirrors() else emptyList()
                    )
                }
            } else if (selectedOption != null) {
                val mergedHeaders = if (selectedOption.cookies.isNotEmpty()) {
                    val existing = headers.trim()
                    if (existing.isNotEmpty()) "${existing}\nCookie: ${selectedOption.cookies}"
                    else "Cookie: ${selectedOption.cookies}"
                } else headers
                App.engine.addDownload(
                    url = selectedOption.directUrl,
                    fileName = selectedOption.fileName ?: name,
                    username = username,
                    password = password,
                    headers = mergedHeaders,
                    speedLimitKbps = perSpeed,
                    priority = priority,
                    checksum = checksum,
                    mirrors = parseMirrors()
                )
            } else if (urls.size == 1 && urls[0].contains("m3u8", ignoreCase = true)) {
                lifecycleScope.launch {
                    val variants = withContext(Dispatchers.IO) {
                        runCatching { App.engine.probeHlsVariants(urls[0]) }.getOrNull()
                    }
                    if (variants.isNullOrEmpty()) {
                        addAll(urls, name, username, password, headers, perSpeed, priority, checksum, parseMirrors())
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
            dialog.dismiss()
        }
        // Tombol Cancel
        view.findViewById<Button>(R.id.btn_cancel).setOnClickListener {
            dialog.dismiss()
        }
        // Tombol Download
        view.findViewById<Button>(R.id.btn_download).setOnClickListener {
            submitDownload()
        }
        dialog.show()
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

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_pause_all)?.isVisible = summaryActive > 0
        menu.findItem(R.id.action_resume_all)?.isVisible = summaryPaused > 0 || summaryFailed > 0
        menu.findItem(R.id.action_retry_failed)?.isVisible = summaryFailed > 0
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_pause_all -> {
                App.engine.pauseAll()
                Toast.makeText(this, R.string.pause_all, Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_resume_all -> {
                App.engine.resumeAll()
                Toast.makeText(this, R.string.resume_all, Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_retry_failed -> {
                App.engine.retryFailed()
                true
            }

            R.id.action_clear_completed -> {
                lifecycleScope.launch(Dispatchers.IO) { App.engine.clearCompleted() }
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
        val build = info?.versionCodeCompat()?.toInt() ?: 0
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
                packageManager.getPackageInfo(packageName, 0).versionCodeCompat().toInt()
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
        if (item.state != DownloadState.DOWNLOADING) {
            options.add(getString(R.string.action_move_up) to {
                App.engine.moveUp(item.id)
                Toast.makeText(this, R.string.moved_up, Toast.LENGTH_SHORT).show()
            })
            options.add(getString(R.string.action_move_down) to {
                App.engine.moveDown(item.id)
                Toast.makeText(this, R.string.moved_down, Toast.LENGTH_SHORT).show()
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

    override fun onToggleSection(key: String) {
        val collapsed = StoragePrefs.isSectionCollapsed(this, key)
        StoragePrefs.setSectionCollapsed(this, key, !collapsed)
        adapter.submitList(DownloadAdapter.buildSections(this, lastItems))
        updateStickyHeader()
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

    /** Ringkasan ringkas: "3 active · 5 done · 1 failed", dihitung dalam SATU
     *  iterasi daftar (bukan beberapa pass terpisah). */
    private fun updateToolbar(items: List<DownloadItem>) {
        var active = 0
        var paused = 0
        var failed = 0
        var done = 0
        for (item in items) {
            when (item.state) {
                DownloadState.DOWNLOADING, DownloadState.PENDING -> active++
                DownloadState.PAUSED -> paused++
                DownloadState.FAILED -> failed++
                DownloadState.COMPLETED -> done++
                else -> {}
            }
        }
        summaryActive = active
        summaryPaused = paused
        summaryFailed = failed
        updateSummaryChips(active, paused, done, failed)
    }

    /** 2. Summary jadi 3 chip kecil berwarna (Active/Paused/Done/Failed).
     *  Chip hanya tampil bila jumlah > 0 supaya ringkas. */
    private fun updateSummaryChips(active: Int, paused: Int, done: Int, failed: Int) {
        binding.chipActive.visibility =
            if (active > 0) View.VISIBLE else View.GONE
        if (active > 0) binding.chipActive.text =
            getString(R.string.summary_count_active, active)
        binding.chipDone.visibility =
            if (done > 0) View.VISIBLE else View.GONE
        if (done > 0) binding.chipDone.text =
            getString(R.string.summary_count_done, done)
        binding.chipFailed.visibility =
            if (failed > 0 || paused > 0) View.VISIBLE else View.GONE
        // Sembunyikan seluruh summary card bila tidak ada chip aktif
        val anyVisible = active > 0 || done > 0 || failed > 0 || paused > 0
        binding.summaryCard.visibility = if (anyVisible) View.VISIBLE else View.GONE
        // Gabung failed + paused jadi satu chip status tersendiri biar ringkas;
        // jumlahnya menunjukkan item yang butuh perhatian.
        if (failed > 0 || paused > 0) {
            val total = failed + paused
            val label = if (failed > 0) {
                getString(R.string.summary_failed_label)
            } else {
                getString(R.string.summary_paused_label)
            }
            binding.chipFailed.text = getString(R.string.summary_count_failed, total, label)
        }
    }

    /** Header section sticky: tampilkan judul section yang sedang di-scroll. */
    private fun updateStickyHeader() {
        val first = listLayoutManager.findFirstVisibleItemPosition()
        val scrolled = binding.recycler.canScrollVertically(-1)
        var title: String? = null
        if (first != RecyclerView.NO_POSITION) {
            for (i in 0..first) {
                val row = adapter.currentList.getOrNull(i) ?: break
                if (row is DownloadRow.Header) title = row.title
            }
        }
        binding.textSectionSticky.isVisible = scrolled && title != null
        if (title != null) binding.textSectionSticky.text = title
    }

    /** Tempel URL dari clipboard ke dialog tambah download (untuk empty state). */
    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        showAddDialog(text)
    }

    /** Konfirmasi hapus item dari hasil swipe ke kiri. */
    private fun confirmSwipeDelete(item: DownloadItem) {
        AlertDialog.Builder(this)
            .setTitle(item.fileName)
            .setMessage(R.string.confirm_delete)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) { App.engine.remove(item.id) }
            }
            .show()
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
        private val URL_SPLIT = Regex("[\\s,]+")
        private const val EXTRA_ADD_DOWNLOAD = "com.tasirin.httpdownloadmanager.ADD_DOWNLOAD"
        private val SPEED_KBPS = intArrayOf(0, 128, 256, 512, 1024, 2048, 5120)
        private val PRIORITY_VALUES = intArrayOf(-1, 0, 1)
    }
}
