package com.tasirin.httpdownloadmanager

import android.annotation.SuppressLint
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import com.tasirin.httpdownloadmanager.data.DownloadState
import com.tasirin.httpdownloadmanager.databinding.ActivitySettingsBinding
import com.tasirin.httpdownloadmanager.download.DownloadService
import com.tasirin.httpdownloadmanager.remote.HttpControlServer
import com.tasirin.httpdownloadmanager.util.Formats
import com.tasirin.httpdownloadmanager.util.FileSaver
import com.tasirin.httpdownloadmanager.util.StoragePrefs
import com.tasirin.httpdownloadmanager.util.Permissions
import com.tasirin.httpdownloadmanager.util.UpdateInfo
import com.tasirin.httpdownloadmanager.util.Updater
import com.tasirin.httpdownloadmanager.util.QrEncoder
import com.tasirin.httpdownloadmanager.util.applyEdgeToEdge
import com.tasirin.httpdownloadmanager.util.setupSpinner
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Halaman pengaturan: server remote, keamanan, log, unduhan, dan penyimpanan. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private var activeStorageInput: EditText? = null
    private var storagePathEdited = false
    private var updatingStorageInput = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* hasil izin tidak wajib untuk fungsi inti */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdge(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        renderServer()
        wireServerSwitch()
        wireServerChecks()
        binding.btnLog.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        binding.btnCleanup.setOnClickListener {
            binding.btnCleanup.isEnabled = false
            binding.cleanupResult.setText(R.string.cleanup_running)
            lifecycleScope.launch {
                val (files, bytes) = withContext(Dispatchers.IO) { cleanupJunkFiles() }
                binding.cleanupResult.text = if (files > 0) {
                    resources.getQuantityString(
                        R.plurals.cleanup_done, files, files, Formats.bytes(bytes)
                    )
                } else {
                    getString(R.string.cleanup_empty)
                }
                binding.btnCleanup.isEnabled = true
            }
        }
        wireDownloadSettings()
        wireStorageSection()
        wireSave()
        binding.btnCheckUpdate.setOnClickListener { checkForUpdate() }
        setupCollapsibleSections()
        setupQuickNav()
        binding.btnOpenRemote.setOnClickListener { openRemoteNow() }
        binding.btnCopyUrl.setOnClickListener { copyRemoteUrl() }
    }

    override fun onResume() {
        super.onResume()
        renderServer()
    }

    /** Seksi kartu yang bisa dilipat (state disimpan di StoragePrefs). */
    private class SectionSpec(
        val sectionId: Int,
        val headerId: Int,
        val key: String
    )

    private val sections = listOf(
        SectionSpec(R.id.section_server, R.id.header_server, "server"),
        SectionSpec(R.id.section_download, R.id.header_download, "download"),
        SectionSpec(R.id.section_storage, R.id.header_storage, "storage"),
        SectionSpec(R.id.section_other, R.id.header_other, "other")
    )

    private val navMap = mapOf(
        R.id.nav_server to sections[0],
        R.id.nav_download to sections[1],
        R.id.nav_storage to sections[2],
        R.id.nav_other to sections[3]
    )

    private fun setupCollapsibleSections() {
        sections.forEach { spec ->
            val section = findViewById<ViewGroup>(spec.sectionId)
            val header = findViewById<TextView>(spec.headerId)
            val collapsed = StoragePrefs.isSectionCollapsed(this, spec.key)
            setSectionExpanded(section, header, !collapsed)
            header.setOnClickListener {
                val nowExpanded = isSectionExpanded(section, header)
                setSectionExpanded(section, header, !nowExpanded)
                StoragePrefs.setSectionCollapsed(this, spec.key, nowExpanded)
            }
        }
    }

    private fun isSectionExpanded(section: ViewGroup, header: TextView): Boolean {
        for (i in 0 until section.childCount) {
            val child = section.getChildAt(i)
            if (child !== header && child.isVisible) return true
        }
        return false
    }

    private fun setSectionExpanded(section: ViewGroup, header: TextView, expanded: Boolean) {
        for (i in 0 until section.childCount) {
            val child = section.getChildAt(i)
            if (child !== header) {
                child.isVisible = expanded
            }
        }
        TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(
            header, 0, 0,
            if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron,
            0
        )
    }

    private fun setupQuickNav() {
        navMap.forEach { (chipId, spec) ->
            findViewById<View>(chipId).setOnClickListener {
                expandSection(spec)
                scrollToSection(spec)
            }
        }
    }

    private fun expandSection(spec: SectionSpec) {
        val section = findViewById<ViewGroup>(spec.sectionId)
        val header = findViewById<TextView>(spec.headerId)
        setSectionExpanded(section, header, true)
        StoragePrefs.setSectionCollapsed(this, spec.key, false)
    }

    private fun scrollToSection(spec: SectionSpec) {
        val target = findViewById<View>(spec.sectionId) ?: return
        var offset = 0
        var p: View? = target
        while (p != null && p.id != R.id.settings_content) {
            offset += p.top
            p = p.parent as? View
        }
        binding.scrollSettings.post {
            binding.scrollSettings.smoothScrollTo(0, (offset - 8).coerceAtLeast(0))
        }
    }

    private fun remoteUrl(): String? =
        if (App.httpServer.isAlive) {
            HttpControlServer.ipv4Addresses().firstOrNull()
                ?.let { "http://$it:${App.httpServer.listeningPort}/" }
        } else null

    private fun openRemoteNow() {
        val url = remoteUrl()
        if (url == null) {
            Toast.makeText(this, R.string.server_not_running_hint, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }.onFailure {
            Toast.makeText(this, R.string.open_remote_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyRemoteUrl() {
        val url = remoteUrl()
        if (url == null) {
            Toast.makeText(this, R.string.server_not_running_hint, Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("remote", url))
        Toast.makeText(this, R.string.address_copied, Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /** Hapus file .part menggantung + cache thumbnail. Tidak menyentuh file
     *  milik download yang masih berjalan/antre/jeda. */
    private fun cleanupJunkFiles(): Pair<Int, Long> {
        var files = 0
        var bytes = 0L
        fun deleteIf(f: File) {
            if (!f.isFile) return
            bytes += f.length()
            if (f.delete()) files++
        }
        val itemNames = App.engine.items.value.map { it.fileName }.toSet()
        runCatching {
            File(filesDir, "downloads").listFiles()?.forEach { f ->
                val base = f.name.replace(PART_PATTERN, "")
                if (PART_PATTERN.containsMatchIn(f.name) && base !in itemNames) {
                    deleteIf(f)
                }
            }
        }
        runCatching {
            File(cacheDir, "thumbs").listFiles()?.forEach { deleteIf(it) }
        }
        return files to bytes
    }

    private fun renderServer() {
        val server = App.httpServer
        val needsStorage = when {
            Build.VERSION.SDK_INT >= 30 -> !Environment.isExternalStorageManager()
            Build.VERSION.SDK_INT >= 23 ->
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                    PackageManager.PERMISSION_GRANTED
            else -> false
        }
        binding.storageBtn.visibility = if (needsStorage) View.VISIBLE else View.GONE
        binding.storageBtn.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 30) {
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            "package:$packageName".toUri()
                        )
                    )
                }.onFailure {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
            } else {
                requestPermissionsIfNeeded()
            }
        }
        binding.serverSwitch.text = getString(
            if (server.isAlive) R.string.server_stop else R.string.server_start
        )
        binding.btnOpenRemote.isEnabled = server.isAlive
        binding.btnCopyUrl.isEnabled = server.isAlive
        if (server.isAlive) {
            binding.serverStatus.setText(R.string.remote_running)
            val urls = HttpControlServer.ipv4Addresses()
                .map { "http://$it:${server.listeningPort}/" }
            binding.urls.text = urls.joinToString("\n").ifEmpty {
                getString(R.string.remote_no_url)
            }
            urls.firstOrNull()?.let { address ->
                generateQrCode(address, 640)?.let { binding.qr.setImageBitmap(it) }
            }
            binding.qr.visibility = View.VISIBLE
        } else {
            binding.serverStatus.setText(R.string.remote_stopped)
            binding.urls.text = getString(R.string.remote_no_url)
            binding.qr.visibility = View.GONE
        }
    }

    private fun wireServerSwitch() {
        binding.serverSwitch.setOnClickListener {
            if (binding.serverSwitch.isEnabled.not()) return@setOnClickListener
            val started = App.httpServer.isAlive
            if (!started &&
                StoragePrefs.isPinEnforced(this) &&
                StoragePrefs.getServerPin(this).isNullOrEmpty()
            ) {
                Toast.makeText(this, R.string.remote_pin_required, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            binding.serverSwitch.isEnabled = false
            lifecycleScope.launch {
                val operation = if (started) {
                    withContext(Dispatchers.IO) { runCatching { App.httpServer.stopServer() }.isSuccess }
                } else {
                    withContext(Dispatchers.IO) { runCatching { App.httpServer.startServer() }.isSuccess }
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (!operation) {
                        if (started) {
                            Toast.makeText(
                                this@SettingsActivity,
                                getString(R.string.remote_start_failed, "stop failed"),
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            StoragePrefs.setServerBackgroundEnabled(this@SettingsActivity, false)
                            Toast.makeText(
                                this@SettingsActivity,
                                getString(
                                    R.string.remote_start_failed,
                                    App.httpServer.lastError ?: "start failed"
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else if (!started) {
                        StoragePrefs.setServerBackgroundEnabled(this@SettingsActivity, true)
                        Toast.makeText(
                            this@SettingsActivity,
                            getString(R.string.remote_started, App.httpServer.listeningPort),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        StoragePrefs.setServerBackgroundEnabled(this@SettingsActivity, false)
                        StoragePrefs.setServerAutoStartEnabled(this@SettingsActivity, false)
                        stopServiceIfIdle()
                        Toast.makeText(
                            this@SettingsActivity,
                            R.string.remote_stopped,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    binding.serverSwitch.isEnabled = true
                    renderServer()
                }
            }
        }
    }

    private fun renderToggle(btn: Button, on: Boolean, label: String) {
        btn.text = label
        val color = ContextCompat.getColor(
            this, if (on) R.color.status_on else R.color.text_secondary
        )
        btn.setTextColor(color)
        val icon = ContextCompat.getDrawable(
            this, if (on) R.drawable.ic_check else R.drawable.ic_close
        )
        if (icon != null) {
            icon.mutate().setTint(color)
            btn.setCompoundDrawablesRelative(null, null, icon, null)
            btn.compoundDrawablePadding = 12
        }
    }

    private fun renderChecks() {
        renderToggle(
            binding.checkServerAutostart,
            StoragePrefs.isServerAutoStartEnabled(this),
            getString(R.string.settings_server_autostart)
        )
        renderToggle(
            binding.checkPinEnforced,
            StoragePrefs.isPinEnforced(this),
            getString(R.string.settings_pin_enforced)
        )
        renderToggle(
            binding.checkFsFullAccess,
            StoragePrefs.isFsFullAccessEnabled(this),
            getString(R.string.settings_fs_full_access)
        )
        renderToggle(
            binding.checkServerReadOnly,
            StoragePrefs.isServerReadOnly(this),
            getString(R.string.settings_server_read_only)
        )
        renderToggle(
            binding.checkBackground,
            StoragePrefs.isBackgroundEnabled(this),
            getString(R.string.settings_background)
        )
        renderToggle(
            binding.checkAutostart,
            StoragePrefs.isAutoStartEnabled(this),
            getString(R.string.settings_autostart)
        )
        renderToggle(
            binding.checkBattery,
            StoragePrefs.isBatteryExemptEnabled(this),
            getString(R.string.settings_battery)
        )
        renderToggle(
            binding.checkAutoSort,
            StoragePrefs.isAutoSortEnabled(this),
            getString(R.string.settings_auto_sort)
        )
        renderToggle(
            binding.checkSmallFirst,
            StoragePrefs.isSmallFirstEnabled(this),
            getString(R.string.settings_small_first)
        )
        renderToggle(
            binding.checkDeletePartial,
            StoragePrefs.isDeletePartialOnCancel(this),
            getString(R.string.settings_delete_partial_on_cancel)
        )
    }

    private fun wireServerChecks() {
        binding.checkServerAutostart.setOnClickListener {
            StoragePrefs.setServerAutoStartEnabled(this, !StoragePrefs.isServerAutoStartEnabled(this))
            renderChecks()
        }
        binding.checkPinEnforced.setOnClickListener {
            val next = !StoragePrefs.isPinEnforced(this)
            StoragePrefs.setPinEnforced(this, next)
            if (next && StoragePrefs.getServerPin(this).isNullOrEmpty()) {
                StoragePrefs.setServerBackgroundEnabled(this, false)
                StoragePrefs.setServerAutoStartEnabled(this, false)
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { runCatching { App.httpServer.stopServer() } }
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        stopServiceIfIdle()
                        renderServer()
                    }
                }
            }
            renderChecks()
        }
        binding.checkFsFullAccess.setOnClickListener {
            StoragePrefs.setFsFullAccessEnabled(this, !StoragePrefs.isFsFullAccessEnabled(this))
            renderChecks()
        }
        binding.checkServerReadOnly.setOnClickListener {
            StoragePrefs.setServerReadOnly(this, !StoragePrefs.isServerReadOnly(this))
            renderChecks()
        }
        // PIN disimpan sebagai hash — field dikosongkan, hint menjelaskan
        // aturannya (kosongkan = nonaktif).
        binding.inputPin.setText("")
        binding.inputPort.setText(
            String.format(java.util.Locale.US, "%d", StoragePrefs.serverPort(this))
        )
        renderChecks()
    }

    private fun wireDownloadSettings() {
        binding.checkBackground.setOnClickListener {
            StoragePrefs.setBackgroundEnabled(this, !StoragePrefs.isBackgroundEnabled(this))
            renderChecks()
        }
        binding.checkAutostart.setOnClickListener {
            StoragePrefs.setAutoStartEnabled(this, !StoragePrefs.isAutoStartEnabled(this))
            renderChecks()
        }
        binding.checkBattery.setOnClickListener {
            val next = !StoragePrefs.isBatteryExemptEnabled(this)
            StoragePrefs.setBatteryExemptEnabled(this, next)
            if (next) requestBatteryExemption()
            renderChecks()
        }
        binding.checkAutoSort.setOnClickListener {
            StoragePrefs.setAutoSortEnabled(this, !StoragePrefs.isAutoSortEnabled(this))
            renderChecks()
        }
        binding.checkSmallFirst.setOnClickListener {
            StoragePrefs.setSmallFirstEnabled(this, !StoragePrefs.isSmallFirstEnabled(this))
            renderChecks()
        }
        binding.checkDeletePartial.setOnClickListener {
            StoragePrefs.setDeletePartialOnCancel(this, !StoragePrefs.isDeletePartialOnCancel(this))
            renderChecks()
        }

        val concurrentOptions = resources.getStringArray(R.array.concurrent_options)
        setupSpinner(this, binding.spinnerConcurrent, concurrentOptions.toList())
        binding.spinnerConcurrent.setSelection(
            (StoragePrefs.maxConcurrent(this) - 1).coerceIn(0, concurrentOptions.size - 1)
        )
        binding.spinnerConcurrent.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                StoragePrefs.setMaxConcurrent(this@SettingsActivity, position + 1)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val currentSpeed = StoragePrefs.speedLimitKbps(this)
        val speedOptions = resources.getStringArray(R.array.speed_limit_options).toMutableList()
        val speedKbps = SPEED_KBPS
        if (currentSpeed !in speedKbps) {
            speedOptions.add(getString(R.string.settings_speed_custom, currentSpeed))
        }
        setupSpinner(this, binding.spinnerSpeed, speedOptions)
        binding.spinnerSpeed.setSelection(
            if (currentSpeed in speedKbps) speedKbps.indexOf(currentSpeed) else speedOptions.size - 1
        )
        binding.spinnerSpeed.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                StoragePrefs.setSpeedLimitKbps(
                    this@SettingsActivity,
                    if (position < speedKbps.size) speedKbps[position] else currentSpeed
                )
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val retryOptions = resources.getStringArray(R.array.retry_options)
        setupSpinner(this, binding.spinnerRetry, retryOptions.toList())
        binding.spinnerRetry.setSelection(
            StoragePrefs.maxRetries(this).coerceIn(0, retryOptions.size - 1)
        )
        binding.spinnerRetry.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                StoragePrefs.setMaxRetries(this@SettingsActivity, position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val segmentOptions = resources.getStringArray(R.array.segment_options)
        val segmentValues = intArrayOf(1, 2, 4, 6, 8)
        setupSpinner(this, binding.spinnerSegments, segmentOptions.toList())
        binding.spinnerSegments.setSelection(
            segmentValues.indexOf(StoragePrefs.segmentCount(this)).coerceAtLeast(0)
        )
        binding.spinnerSegments.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                StoragePrefs.setSegmentCount(this@SettingsActivity, segmentValues[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val connectOptions = resources.getStringArray(R.array.connect_timeout_options)
        val connectValues = intArrayOf(5, 10, 15, 30, 60)
        setupSpinner(this, binding.spinnerConnectTimeout, connectOptions.toList())
        binding.spinnerConnectTimeout.setSelection(
            connectValues.indexOf(StoragePrefs.getConnectTimeoutSec(this)).coerceAtLeast(0)
        )
        binding.spinnerConnectTimeout.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                StoragePrefs.setConnectTimeoutSec(this@SettingsActivity, connectValues[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val readOptions = resources.getStringArray(R.array.read_timeout_options)
        val readValues = intArrayOf(10, 15, 30, 60, 120)
        setupSpinner(this, binding.spinnerReadTimeout, readOptions.toList())
        binding.spinnerReadTimeout.setSelection(
            readValues.indexOf(StoragePrefs.getReadTimeoutSec(this)).coerceAtLeast(0)
        )
        binding.spinnerReadTimeout.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                StoragePrefs.setReadTimeoutSec(this@SettingsActivity, readValues[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Custom User-Agent
        val userAgentInput = findViewById<EditText>(R.id.edit_user_agent)
        userAgentInput.setText(StoragePrefs.getUserAgent(this))
        userAgentInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                StoragePrefs.setUserAgent(this@SettingsActivity, s?.toString().orEmpty())
            }
        })
    }

    private fun wireStorageSection() {
        // View binding tidak mengekspos view dari <include>, jadi pakai
        // findViewById (pola yang sama seperti MainActivity).
        val pathInput = findViewById<EditText>(R.id.input_storage_path)
        activeStorageInput = pathInput
        storagePathEdited = false
        pathInput.setText(StoragePrefs.getTextFolder(this) ?: defaultDownloadsPath())
        pathInput.setSelection(pathInput.text.length)
        pathInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!updatingStorageInput) storagePathEdited = true
            }
        })
        val box = findViewById<LinearLayout>(R.id.extra_folders_container)
        StoragePrefs.getExtraFolders(this).forEach { addExtraFolderRow(box, it) }
        findViewById<Button>(R.id.btn_add_folder).setOnClickListener {
            addExtraFolderRow(box, "")
        }
    }

    private fun addExtraFolderRow(box: LinearLayout, path: String) {
        val row = layoutInflater.inflate(R.layout.row_extra_folder, box, false)
        val input = row.findViewById<EditText>(R.id.extra_folder_path)
        input.setText(path)
        input.setSelection(input.text.length)
        row.findViewById<Button>(R.id.btn_remove_folder).setOnClickListener {
            box.removeView(row)
        }
        box.addView(row)
    }

    private fun applyExtraFolders(view: View) {
        val box = view.findViewById<LinearLayout>(R.id.extra_folders_container)
        val paths = mutableListOf<String>()
        for (i in 0 until box.childCount) {
            val input = box.getChildAt(i).findViewById<EditText>(R.id.extra_folder_path)
            val path = input.text?.toString()?.trim().orEmpty()
            if (path.isEmpty()) continue
            val dir = java.io.File(path)
            if (!dir.isDirectory && !dir.mkdirs()) {
                Toast.makeText(this, R.string.storage_text_folder_invalid, Toast.LENGTH_LONG).show()
                return
            }
            paths.add(path)
        }
        StoragePrefs.setExtraFolders(this, paths)
    }

    private fun wireSave() {
        binding.btnSave.setOnClickListener {
            val requestedPort = binding.inputPort.text?.toString()?.trim()?.toIntOrNull()
            if (requestedPort == null || requestedPort !in 1024..65535) {
                Toast.makeText(this, R.string.settings_port_invalid, Toast.LENGTH_LONG).show()
                renderServer()
                return@setOnClickListener
            }
            applyStoragePath(findViewById<EditText>(R.id.input_storage_path))
            applyExtraFolders(binding.root)
            App.httpServer.invalidateFsRootsCache()
            App.httpServer.invalidateStatusCache()
            val newPin = binding.inputPin.text?.toString()?.trim().orEmpty()
            val oldPinHash = StoragePrefs.storedPinHash(this)
            if (newPin.isEmpty()) {
                if (oldPinHash != null) App.logEvent("PIN REMOVED")
            } else if (oldPinHash == null || !StoragePrefs.pinMatches(this, newPin)) {
                App.logEvent("PIN SET")
            }
            StoragePrefs.setServerPin(this, newPin)
            if (StoragePrefs.isPinEnforced(this) &&
                StoragePrefs.getServerPin(this).isNullOrEmpty()
            ) {
                StoragePrefs.setServerBackgroundEnabled(this, false)
                StoragePrefs.setServerAutoStartEnabled(this, false)
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { runCatching { App.httpServer.stopServer() } }
                    stopServiceIfIdle()
                    renderServer()
                }
            }
            val newPort = requestedPort
            val oldPort = StoragePrefs.serverPort(this)
            StoragePrefs.setServerPort(this, newPort)
            if (newPort != oldPort) {
                App.logEvent("PORT CHANGED: $oldPort -> $newPort")
                lifecycleScope.launch(Dispatchers.IO) {
                    App.restartHttpServer(applicationContext)
                }
            }
            renderServer()
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkForUpdate() {
        binding.updateStatus.text = getString(R.string.update_checking)
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { Updater.checkLatest(this@SettingsActivity) }
            if (info == null) {
                binding.updateStatus.text = getString(R.string.update_failed)
                return@launch
            }
            val current = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionCode
            }.getOrDefault(0)
            if (info.versionCode <= current) {
                binding.updateStatus.text = getString(R.string.update_latest)
            } else {
                binding.updateStatus.text = getString(
                    R.string.update_available, info.versionName, info.versionCode
                )
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(R.string.update_title)
                    .setMessage(
                        getString(
                            R.string.update_message,
                            info.versionName,
                            info.versionCode,
                            Formats.bytes(info.apkSize)
                        )
                    )
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.update_download) { _, _ ->
                        downloadUpdate(info)
                    }
                    .show()
            }
        }
    }

    @SuppressLint("InflateParams") // Inflate dialog progres dengan root null adalah pola standar.
    private fun downloadUpdate(info: UpdateInfo) {
        val view = layoutInflater.inflate(R.layout.dialog_update_progress, null)
        val bar = view.findViewById<ProgressBar>(R.id.update_progress_bar)
        val txt = view.findViewById<TextView>(R.id.update_progress_text)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.update_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .show()
        var lastProgressUi = 0L
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                Updater.download(this@SettingsActivity, info) { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastProgressUi < 100) return@download
                    lastProgressUi = now
                    runOnUiThread {
                        if (total > 0) {
                            bar.progress = (done * 100 / total).toInt()
                            txt.text = getString(
                                R.string.update_progress_detail,
                                Formats.bytes(done),
                                Formats.bytes(total)
                            )
                        } else {
                            txt.text = getString(R.string.update_progress_unknown, Formats.bytes(done))
                        }
                    }
                }
            }
            val status = withContext(Dispatchers.IO) { saveDownloadedUpdate(file, info) }
            runCatching { if (dialog.isShowing) dialog.dismiss() }
            binding.updateStatus.text = status
        }
    }

    /** Simpan APK hasil unduhan ke folder Downloads publik (tanpa pasang
     *  otomatis — aplikasi tidak lagi meminta REQUEST_INSTALL_PACKAGES). */
    private fun saveDownloadedUpdate(file: File?, info: UpdateInfo): String {
        if (file == null) return getString(R.string.update_download_failed)
        if (!Updater.isSignatureValid(this, file)) {
            file.delete()
            return getString(R.string.update_signature_failed)
        }
        val displayName = "tasirin-download-manager-${info.versionName}-${info.versionCode}.apk"
        val saved = FileSaver(this).saveStream(displayName, "download", "") { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        file.delete()
        val location = saved?.filePath ?: saved?.contentUri ?: saved?.fileName ?: displayName
        return getString(R.string.update_downloaded_path, location)
    }

    private fun refreshActiveStorageUi() {
        val input = activeStorageInput
        storagePathEdited = false
        if (input != null) {
            updatingStorageInput = true
            input.setText(StoragePrefs.getTextFolder(this) ?: defaultDownloadsPath())
            input.setSelection(input.text.length)
            updatingStorageInput = false
        }
    }

    private fun applyStoragePath(pathInput: EditText) {
        if (!storagePathEdited) return
        val path = pathInput.text?.toString()?.trim().orEmpty()
        if (path.isEmpty()) return
        val dir = java.io.File(path)
        if (!dir.isDirectory && !dir.mkdirs()) {
            Toast.makeText(this, R.string.storage_text_folder_invalid, Toast.LENGTH_LONG).show()
            return
        }
        StoragePrefs.setTextFolder(this, path)
        StoragePrefs.saveFolder(this, null, null)
        refreshActiveStorageUi()
        Toast.makeText(
            this,
            getString(R.string.storage_text_folder_saved, path),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun requestPermissionsIfNeeded() {
        val needed = Permissions.missingRuntime(this)
        if (needed.isNotEmpty()) permissionLauncher.launch(needed)
    }

    private fun defaultDownloadsPath(): String {
        if (Build.VERSION.SDK_INT >= 29) return "/storage/emulated/0/Download"
        return runCatching {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                .absolutePath
        }.getOrDefault("/storage/emulated/0/Download")
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

    /** Hentikan DownloadService bila tidak ada download aktif (server juga mati). */
    private fun stopServiceIfIdle() {
        val anyActive = App.engine.items.value.any {
            it.state == DownloadState.DOWNLOADING || it.state == DownloadState.PENDING
        }
        if (!anyActive) {
            runCatching { stopService(Intent(this, DownloadService::class.java)) }
        }
    }

    private fun generateQrCode(content: String, size: Int): Bitmap? {
        return runCatching {
            val matrix = QrEncoder.encode(content) ?: return null
            val quiet = 1 // quiet zone 1 modul biar mudah discan
            val dim = matrix.size + quiet * 2
            val scale = (size / dim).coerceAtLeast(1)
            val offset = (size - matrix.size * scale) / 2
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val mx = (x - offset) / scale
                    val my = (y - offset) / scale
                    val dark = mx in 0 until matrix.size && my in 0 until matrix.size &&
                        matrix.get(mx, my)
                    pixels[y * size + x] = if (dark) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }

    companion object {
        private val PART_PATTERN = Regex("\\.part(\\.\\d+)?$")
        private val SPEED_KBPS = intArrayOf(0, 128, 256, 512, 1024, 2048, 5120)
    }
}
