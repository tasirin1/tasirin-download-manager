@file:SuppressLint("UseKtx")

package com.tasirin.httpdownloadmanager

import android.annotation.SuppressLint
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.Spinner
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.tasirin.httpdownloadmanager.data.DownloadState
import com.tasirin.httpdownloadmanager.databinding.ActivitySettingsBinding
import com.tasirin.httpdownloadmanager.download.DownloadService
import com.tasirin.httpdownloadmanager.remote.HttpControlServer
import com.tasirin.httpdownloadmanager.util.Formats
import com.tasirin.httpdownloadmanager.util.FileNames
import com.tasirin.httpdownloadmanager.util.StoragePrefs
import com.tasirin.httpdownloadmanager.util.Permissions
import com.tasirin.httpdownloadmanager.util.Updater
import com.tasirin.httpdownloadmanager.util.applyEdgeToEdge
import com.tasirin.httpdownloadmanager.util.setupSpinner
import com.tasirin.httpdownloadmanager.util.versionCodeCompat
import com.tasirin.httpdownloadmanager.util.packageInfoCompat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tasirin.httpdownloadmanager.util.whiteNavigationIcon
import com.tasirin.httpdownloadmanager.util.SpeedOptions

/** Halaman pengaturan: server remote, keamanan, log, unduhan, dan penyimpanan. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private var activeStorageInput: EditText? = null
    private var storagePathEdited = false
    private var updatingStorageInput = false

    // Cache IP LAN agar enumerasi NetworkInterface tak jalan di main thread
    // tiap renderServer/onResume (10-100ms+ jank di HP lama, parah di Android 6
    // dengan banyak interface). TTL 10 detik, refresh async dengan guard gen.
    private var cachedLanIps: List<String> = emptyList()
    private var cachedLanIpsAt: Long = 0
    private var lanIpGen = 0

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
        binding.toolbar.whiteNavigationIcon()

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
        // Gallery folder rows (same pattern as extra folders)
        val galleryBox = findViewById<LinearLayout>(R.id.gallery_folders_container)
        StoragePrefs.getGalleryFolders(this).forEach { addGalleryFolderRow(galleryBox, it) }
        findViewById<Button>(R.id.btn_add_gallery_folder).setOnClickListener {
            addGalleryFolderRow(galleryBox, "")
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
        if (Permissions.syncFullAccessAfterGrant(this)) {
            runCatching { App.httpServer.invalidateFsRootsCache() }
        }
        renderServer()
        renderChecks()
    }

    /** Dialog penjelasan sebelum membuka halaman izin "All files access" sistem:
     *  justifikasi harus kontekstual (saat pengguna meminta fitur file manager),
     *  bukan prompt otomatis saat aplikasi dibuka (pola yang dicurigai Play Protect). */
    private fun offerSystemAllFilesAccess() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_fs_full_access)
            .setMessage(R.string.storage_hint_all_files)
            .setPositiveButton(R.string.ok) { _, _ ->
                if (!Permissions.requestAllFilesAccess(this)) {
                    Toast.makeText(this, R.string.settings_open_system_failed, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Dialog penjelasan sebelum membuka halaman izin "Install unknown
     *  apps" sistem: kontekstual dari Pengaturan (tombolnya hanya tampil
     *  bila izin belum diberikan), bukan prompt otomatis — pola yang sama
     *  dengan offerSystemAllFilesAccess. */
    private fun offerInstallPermission() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_install_perm_btn)
            .setMessage(R.string.settings_install_perm_hint)
            .setPositiveButton(R.string.ok) { _, _ ->
                if (!Permissions.requestInstallPermission(this)) {
                    Toast.makeText(this, R.string.settings_open_system_failed, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Seksi kartu yang bisa dilipat (state disimpan di StoragePrefs). */
    private class SectionSpec(
        val sectionId: Int,
        val headerId: Int,
        val chevronId: Int,
        val key: String
    )

    private val sections = listOf(
        SectionSpec(R.id.section_server, R.id.header_server, R.id.chevron_server, "server"),
        SectionSpec(R.id.section_download, R.id.header_download, R.id.chevron_download, "download"),
        SectionSpec(R.id.section_storage, R.id.header_storage, R.id.chevron_storage, "storage"),
        SectionSpec(R.id.section_other, R.id.header_other, R.id.chevron_other, "other")
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
            val header = findViewById<View>(spec.headerId)
            val chevron = findViewById<ImageView>(spec.chevronId)
            val collapsed = StoragePrefs.isSectionCollapsed(this, spec.key)
            setSectionExpanded(section, header, chevron, !collapsed)
            header.setOnClickListener {
                val nowExpanded = isSectionExpanded(section, header)
                setSectionExpanded(section, header, chevron, !nowExpanded)
                StoragePrefs.setSectionCollapsed(this, spec.key, nowExpanded)
            }
        }
    }

    private fun isSectionExpanded(section: ViewGroup, header: View): Boolean {
        for (i in 0 until section.childCount) {
            val child = section.getChildAt(i)
            if (child !== header && child.isVisible) return true
        }
        return false
    }

    private fun setSectionExpanded(
        section: ViewGroup, header: View, chevron: ImageView, expanded: Boolean
    ) {
        for (i in 0 until section.childCount) {
            val child = section.getChildAt(i)
            if (child !== header) {
                child.isVisible = expanded
            }
        }
        chevron.setImageResource(
            if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron
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
        val header = findViewById<View>(spec.headerId)
        val chevron = findViewById<ImageView>(spec.chevronId)
        setSectionExpanded(section, header, chevron, true)
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

    // Refresh IP LAN di IO tanpa menahan main thread; guard gen memastikan
    // hasil basi (mis. server sudah stop / activity recreate) tak menimpa UI.
    private fun refreshLanIpsAsync(port: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - cachedLanIpsAt < 10_000 && cachedLanIps.isNotEmpty()) return
        val gen = ++lanIpGen
        lifecycleScope.launch {
            val ips = withContext(Dispatchers.IO) {
                HttpControlServer.ipv4Addresses()
            }
            if (gen != lanIpGen || isFinishing || isDestroyed) return@launch
            if (!App.httpServer.isAlive || App.httpServer.listeningPort != port) return@launch
            cachedLanIps = ips
            cachedLanIpsAt = android.os.SystemClock.elapsedRealtime()
            binding.urls.text = ips
                .map { "http://$it:$port/" }
                .joinToString("\n").ifEmpty {
                    getString(R.string.remote_no_url)
                }
        }
    }

    private fun cachedRemoteUrl(): String? =
        if (App.httpServer.isAlive) {
            cachedLanIps.firstOrNull()
                ?.let { "http://$it:${App.httpServer.listeningPort}/" }
        } else null

    private fun openRemoteNow() {
        val fast = cachedRemoteUrl()
        if (fast != null) {
            openRemoteUrl(fast)
            return
        }
        if (!App.httpServer.isAlive) {
            Toast.makeText(this, R.string.server_not_running_hint, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val ip = withContext(Dispatchers.IO) {
                HttpControlServer.ipv4Addresses().firstOrNull()
            }
            if (ip != null) {
                cachedLanIps = listOf(ip)
                cachedLanIpsAt = android.os.SystemClock.elapsedRealtime()
                openRemoteUrl("http://$ip:${App.httpServer.listeningPort}/")
            } else {
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.server_not_running_hint,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun openRemoteUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }.onFailure {
            Toast.makeText(this, R.string.open_remote_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyRemoteUrl() {
        val fast = cachedRemoteUrl()
        if (fast != null) {
            copyRemoteText(fast)
            return
        }
        if (!App.httpServer.isAlive) {
            Toast.makeText(this, R.string.server_not_running_hint, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val ip = withContext(Dispatchers.IO) {
                HttpControlServer.ipv4Addresses().firstOrNull()
            }
            if (ip != null) {
                cachedLanIps = listOf(ip)
                cachedLanIpsAt = android.os.SystemClock.elapsedRealtime()
                copyRemoteText("http://$ip:${App.httpServer.listeningPort}/")
            } else {
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.server_not_running_hint,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun copyRemoteText(url: String) {
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


    private fun addGalleryFolderRow(box: LinearLayout, path: String) {
        val row = layoutInflater.inflate(R.layout.row_extra_folder, box, false)
        val input = row.findViewById<EditText>(R.id.extra_folder_path)
        input.hint = getString(R.string.gallery_folder_hint)
        input.setText(path)
        input.setSelection(input.text.length)
        row.findViewById<Button>(R.id.btn_remove_folder).setOnClickListener {
            box.removeView(row)
        }
        box.addView(row)
    }

    /** Baca nilai mentah baris-baris folder dari kontainer UI (WAJIB di
     *  main thread — menyentuh hierarki view). Dipakai bersama folder
     *  ekstra dan folder galeri; keduanya memakai id baris yang sama di
     *  kontainer berbeda. */
    private fun folderInputValues(containerId: Int): List<String> {
        val box = findViewById<LinearLayout>(containerId)
        val raws = mutableListOf<String>()
        for (i in 0 until box.childCount) {
            val input = box.getChildAt(i).findViewById<EditText>(R.id.extra_folder_path)
            val path = input.text?.toString()?.trim().orEmpty()
            if (path.isNotEmpty()) raws.add(path)
        }
        return raws
    }

    /** Normalisasi daftar path folder: canonicalPath + cek direktori
     *  (dibuat bila [createMissing]). Murni I/O berkas — aman dipanggil
     *  dari Dispatchers.IO (issue #400). null bila ADA satu path tak
     *  sah, meniru perilaku lama: kategori itu tak disimpan sama sekali.
     *  canonicalPath bisa melempar IOException untuk path aneh, jadi
     *  kegagalannya berarti tak sah, bukan crash. */
    private fun normalizeFolderPaths(raws: List<String>, createMissing: Boolean): List<String>? {
        val paths = mutableListOf<String>()
        for (path in raws) {
            val normalized = runCatching { java.io.File(path).canonicalPath }.getOrNull()
            if (normalized.isNullOrEmpty()) return null
            val dir = java.io.File(normalized)
            if (!dir.isDirectory && !(createMissing && dir.mkdirs())) return null
            paths.add(normalized)
        }
        return paths
    }

    private fun cleanupJunkFiles(): Pair<Int, Long> {
        var files = 0
        var bytes = 0L
        fun deleteIf(f: File) {
            if (!f.isFile) return
            // Byte hanya dihitung BILA hapus berhasil: angka yang dilaporkan
            // ke pengguna adalah ruang yang benar-benar dibebaskan.
            val len = f.length()
            if (f.delete()) {
                files++
                bytes += len
            }
        }
        // File di disk memakai FileNames.safe(): bandingkan nama safe agar
        // .part AKTIF yang namanya disanitasi (|?:*, >200 char, reserved)
        // tidak dianggap sampah lalu dihapus saat download berjalan.
        val itemNames = App.engine.items.value.map { FileNames.safe(it.fileName) }.toSet()
        runCatching {
            File(filesDir, "downloads").listFiles()?.forEach { f ->
                // Satu find() saja: replace + containsMatchIn memindai nama 2x.
                val m = PART_PATTERN.find(f.name) ?: return@forEach
                if (f.name.substring(0, m.range.first) !in itemNames) {
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
        binding.storageBtn.visibility = when {
            Build.VERSION.SDK_INT >= 30 -> if (Permissions.needsAllFilesAccess(this))
                View.VISIBLE else View.GONE
            // minSdk 23: cabang else di sini = API 23-29 (izin runtime).
            else ->
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                        PackageManager.PERMISSION_GRANTED
                ) View.VISIBLE else View.GONE
        }
        binding.storageBtn.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 30) {
                offerSystemAllFilesAccess()
            } else {
                requestPermissionsIfNeeded()
            }
        }
        // Tombol izin install hanya tampil bila izinnya belum diberikan
        // (pola storageBtn di atas): sesudah user memberi izin di halaman
        // sistem, renderServer dari onResume menyembunyikannya lagi.
        binding.installPermBtn.visibility =
            if (Permissions.needsInstallPermission(this)) View.VISIBLE else View.GONE
        binding.installPermBtn.setOnClickListener { offerInstallPermission() }
        binding.serverSwitch.text = getString(
            if (server.isAlive) R.string.server_stop else R.string.server_start
        )
        binding.btnOpenRemote.isEnabled = server.isAlive
        binding.btnCopyUrl.isEnabled = server.isAlive
        // Tombol hapus PIN hanya tampil bila memang ada PIN tersimpan.
        binding.btnRemovePin.visibility =
            if (StoragePrefs.storedPinHash(this) != null) View.VISIBLE else View.GONE

        // 6. Server status badge (hijau ON / merah OFF)
        val badge = binding.serverBadge
        if (server.isAlive) {
            badge.isVisible = true
            badge.setText(R.string.settings_badge_on)
            badge.setTextColor(ContextCompat.getColor(this, R.color.badge_on_text))
            badge.setBackgroundResource(R.drawable.bg_badge_on)

            binding.serverStatus.setText(R.string.remote_running)
            val port = server.listeningPort
            binding.urls.text = cachedLanIps
                .map { "http://$it:$port/" }
                .joinToString("\n").ifEmpty {
                    getString(R.string.remote_no_url)
                }
            refreshLanIpsAsync(port)
        } else {
            badge.isVisible = true
            badge.setText(R.string.settings_badge_off)
            badge.setTextColor(ContextCompat.getColor(this, R.color.badge_off_text))
            badge.setBackgroundResource(R.drawable.bg_badge_off)

            binding.serverStatus.setText(R.string.remote_stopped)
            binding.urls.text = getString(R.string.remote_no_url)
            lanIpGen++
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
                // Operasi + seluruh konsekuensi pref-nya satu rangkaian
                // yang tak boleh putus di tengah (issue #901): operasi
                // berjalan NonCancellable di IO, lalu penulisan pref di
                // bawah berjalan sinkron di Main TANPA hop withContext
                // terpisah (pola #840) — hop itu adalah titik suspensi
                // yang membatalkan konsekuensi tepat sesudah server
                // sudah start/stop, meninggalkan pref lama yang
                // menghidupkan lagi server pada start dingin berikutnya.
                val operation = if (started) {
                    withContext(NonCancellable + Dispatchers.IO) { App.stopHttpServer() }
                } else {
                    withContext(NonCancellable + Dispatchers.IO) { App.startHttpServer() }
                }
                run {
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
        // Status harus kentara tanpa mengandalkan warna saja: ON = tombol terisi
        // + label "ON", OFF = tombol tonal + label "OFF", plus ikon ujung.
        val state = getString(if (on) R.string.settings_badge_on else R.string.settings_badge_off)
        btn.text = getString(R.string.settings_toggle_state, label, state)
        btn.contentDescription = getString(R.string.settings_toggle_state_desc, label, state)
        if (on) {
            btn.setBackgroundResource(R.drawable.bg_btn_filled)
            btn.setTextColor(ContextCompat.getColor(this, R.color.white))
            ContextCompat.getDrawable(this, R.drawable.ic_check)?.let { icon ->
                icon.mutate().setTint(ContextCompat.getColor(this, R.color.white))
                btn.setCompoundDrawablesRelative(null, null, icon, null)
                btn.compoundDrawablePadding = 12
            }
        } else {
            btn.setBackgroundResource(R.drawable.bg_btn_tv)
            btn.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            ContextCompat.getDrawable(this, R.drawable.ic_close)?.let { icon ->
                icon.mutate().setTint(ContextCompat.getColor(this, R.color.text_secondary))
                btn.setCompoundDrawablesRelative(null, null, icon, null)
                btn.compoundDrawablePadding = 12
            }
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
            binding.checkServerReadOnly,
            StoragePrefs.isServerReadOnly(this),
            getString(R.string.settings_server_read_only)
        )
        renderToggle(
            binding.checkFsFullAccess,
            StoragePrefs.isFsFullAccessEnabled(this),
            getString(R.string.settings_fs_full_access)
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
        // Selaraskan toggle baterai dengan status exemption SISTEM
        // (issue #523): pref ditulis optimistis sebelum dialog sistem
        // yang tak memberi callback hasil, jadi tanpa rekonsiliasi ini
        // toggle berbohong permanen sesudah pengguna menolak/menekan
        // Back atau mencabut exemption dari Pengaturan sistem.
        // renderChecks berjalan dari onResume, jadi status terpulihkan
        // tiap kali pengguna kembali ke layar ini.
        val pmBattery = getSystemService(Context.POWER_SERVICE) as PowerManager
        StoragePrefs.setBatteryExemptEnabled(
            this, pmBattery.isIgnoringBatteryOptimizations(packageName)
        )
        renderToggle(
            binding.checkBattery,
            StoragePrefs.isBatteryExemptEnabled(this),
            getString(R.string.settings_battery)
        )
        renderToggle(
            binding.checkAutoOpen,
            StoragePrefs.isAutoOpenComplete(this),
            getString(R.string.settings_auto_open)
        )
        renderToggle(
            binding.checkSmallFirst,
            StoragePrefs.isSmallFirstEnabled(this),
            getString(R.string.settings_small_first)
        )
        renderToggle(
            binding.checkAutoSort,
            StoragePrefs.isAutoSortEnabled(this),
            getString(R.string.settings_auto_sort)
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
                    // NonCancellable (issue #832, kelas #823): pref sudah
                    // terlanjur berubah di atas; stop yang terbatalkan di
                    // titik suspensi meninggalkan server menyala melawan
                    // pref + PIN wajib tanpa PIN.
                    withContext(NonCancellable + Dispatchers.IO) { App.stopHttpServer() }
                    // Langsung di sini, tanpa withContext terpisah
                    // (issue #840): lifecycleScope sudah berjalan di
                    // Main, dan hop Main ekstra adalah titik suspensi
                    // yang bisa membatalkan stopServiceIfIdle tepat
                    // sesudah stop — kedua situs saudara memanggilnya
                    // sinkron sesudah stop mereka.
                    stopServiceIfIdle()
                    renderServer()
                }
            }
            renderChecks()
        }
        binding.checkFsFullAccess.setOnClickListener {
            if (!StoragePrefs.isFsFullAccessEnabled(this)) {
                // Menyalakan butuh izin sistem di Android 11+: jelaskan dulu,
                // lalu minta izin; pref aktif otomatis saat kembali dari
                // halaman izin bila pengguna menyetujui (lihat onResume).
                if (Permissions.needsAllFilesAccess(this)) {
                    offerSystemAllFilesAccess()
                } else {
                    StoragePrefs.setFsFullAccessEnabled(this, true)
                    runCatching { App.httpServer.invalidateFsRootsCache() }
                }
            } else {
                StoragePrefs.setFsFullAccessEnabled(this, false)
                runCatching { App.httpServer.invalidateFsRootsCache() }
            }
            renderChecks()
        }
        binding.checkServerReadOnly.setOnClickListener {
            StoragePrefs.setServerReadOnly(this, !StoragePrefs.isServerReadOnly(this))
            // Status readOnly di-cache server: invalidasi agar remote web
            // langsung menampilkan status baru (tanpa ini basi sampai restart).
            App.httpServer.invalidateStatusCache()
            renderChecks()
        }
        // PIN disimpan sebagai hash — field dikosongkan, hint menjelaskan
        // aturannya: kosong saat Simpan = PIN lama DIPERTAHANKAN (issue
        // #383: sebelumnya kosong berarti hapus, sehingga menyimpan
        // perubahan apa pun diam-diam mencabut PIN). Penghapusan PIN
        // adalah aksi eksplisit lewat tombol Remove PIN di bawah.
        binding.inputPin.setText("")
        binding.btnRemovePin.setOnClickListener {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    StoragePrefs.setServerPin(this@SettingsActivity, null)
                }
                App.logEvent("PIN REMOVED")
                // Sama seperti blok penegakan di saveSettings: PIN wajib
                // yang hilang mematikan server agar tak berjalan terbuka.
                if (StoragePrefs.isPinEnforced(this@SettingsActivity) &&
                    StoragePrefs.getServerPin(this@SettingsActivity).isNullOrEmpty()
                ) {
                    StoragePrefs.setServerBackgroundEnabled(this@SettingsActivity, false)
                    StoragePrefs.setServerAutoStartEnabled(this@SettingsActivity, false)
                    // NonCancellable (issue #832, kelas #823): PIN sudah
                    // terhapus dan pref sudah mati di atas; stop yang
                    // terbatalkan membuat server berjalan terbuka.
                    withContext(NonCancellable + Dispatchers.IO) { App.stopHttpServer() }
                    stopServiceIfIdle()
                }
                renderServer()
            }
        }
        binding.inputPort.setText(
            StoragePrefs.serverPort(this).toString()
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
            // Exemption sistem sedang AKTIF (issue #531): aplikasi
            // tidak bisa mencabutnya secara programatik, dan sesudah
            // sinkronisasi #523 ketukan biasa menjadi no-op diam-diam
            // (pref ditulis false lalu renderChecks mengembalikannya
            // ke status sistem). Arahkan pengguna ke daftar optimasi
            // baterai sistem — satu-satunya tempat pencabutan bisa
            // terjadi — dan biarkan onResume menyelaraskan toggle.
            val pmBatteryTap = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pmBatteryTap.isIgnoringBatteryOptimizations(packageName)) {
                runCatching {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }.onFailure {
                    Toast.makeText(this, R.string.battery_request_failed, Toast.LENGTH_LONG).show()
                }
                return@setOnClickListener
            }
            val next = !StoragePrefs.isBatteryExemptEnabled(this)
            StoragePrefs.setBatteryExemptEnabled(this, next)
            if (next) requestBatteryExemption()
            renderChecks()
        }
        binding.checkAutoOpen.setOnClickListener {
            StoragePrefs.setAutoOpenComplete(this, !StoragePrefs.isAutoOpenComplete(this))
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
                runCatching { App.engine.refreshMaxConcurrent() }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val currentSpeed = StoragePrefs.speedLimitKbps(this)
        val speedOptions = resources.getStringArray(R.array.speed_limit_options).toMutableList()
        val speedKbps = SpeedOptions.SPEED_KBPS
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
                // Limiter global di-cache di engine: tanpa refresh, nilai baru
                // tak berlaku sampai restart (kecuali dimatikan ke 0).
                runCatching { App.engine.refreshSpeedLimit() }
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
                runCatching { App.engine.refreshTimeouts() }
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
                runCatching { App.engine.refreshTimeouts() }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // User-Agent: preset spinner + optional custom input
        val uaPresets = listOf(
            "Default (Chrome Mobile)" to "",
            "Chrome Desktop" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Firefox Desktop" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0",
            "Safari (iPhone)" to "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1",
            "Chrome (Android)" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            "curl" to "curl/8.5.0",
            "Wget" to "Wget/1.21.4",
            "Custom..." to "CUSTOM"
        )
        val spinnerUA = findViewById<Spinner>(R.id.spinner_user_agent)
        val customUAInput = findViewById<EditText>(R.id.edit_user_agent)
        val uaLabels = uaPresets.map { it.first }
        setupSpinner(this, spinnerUA, uaLabels)
        val savedUA = StoragePrefs.getUserAgent(this)
        // UA tersimpan yang tak cocok dengan preset mana pun adalah UA
        // KUSTOM (disimpan mentah oleh TextWatcher): petakan ke indeks
        // Custom, bukan dipaksa 0 (issue #506). Pemaksaan lama membuat
        // spinner menampilkan Default dan menyembunyikan kolom kustom,
        // padahal UA kustom itulah yang aktif di engine — dan callback
        // awal listener di posisi 0 berisiko menimpa UA tersimpan
        // menjadi kosong.
        val savedIdx = uaPresets.indexOfFirst { it.second == savedUA }
            .let { if (it >= 0) it else uaPresets.size - 1 }
        spinnerUA.setSelection(savedIdx)
        customUAInput.setText(savedUA)
        customUAInput.visibility = if (savedIdx == uaPresets.size - 1) View.VISIBLE else View.GONE
        spinnerUA.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val value = uaPresets[position].second
                if (value == "CUSTOM") {
                    customUAInput.visibility = View.VISIBLE
                    customUAInput.requestFocus()
                } else {
                    customUAInput.visibility = View.GONE
                    StoragePrefs.setUserAgent(this@SettingsActivity, value)
                    runCatching { App.engine.refreshUserAgent() }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        customUAInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (spinnerUA.selectedItemPosition == uaPresets.size - 1) {
                    StoragePrefs.setUserAgent(this@SettingsActivity, s?.toString().orEmpty())
                    runCatching { App.engine.refreshUserAgent() }
                }
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

    // applyExtraFolders/applyGalleryFolders/applyStoragePath yang lama
    // dihapus (issue #400): ketiganya membaca view lalu LANGSUNG
    // menjalankan I/O path (canonicalPath/mkdirs) di main thread.
    // Penggantinya folderInputValues (baca mentah di main) +
    // normalizeFolderPaths (I/O di Dispatchers.IO), dirangkai di
    // saveSettings.

    /** Cegah save ganda: tap cepat sebelum tombol nonaktif tidak boleh
     *  menjalankan dua saveSettings paralel (balapan tulis prefs/server). */
    @Volatile private var savingSettings = false

    // Penjaga in-flight Check Update (issue #524): tanpa ini dua
    // ketuk cepat meluncurkan dua cek jaringan dan dialog update
    // bisa bertumpuk dua lapis.
    @Volatile private var checkingUpdate = false

    private fun wireSave() {
        binding.btnSave.setOnClickListener {
            if (savingSettings) return@setOnClickListener
            savingSettings = true
            binding.btnSave.isEnabled = false
            lifecycleScope.launch {
                try {
                    // Simpan adalah satu rangkaian konsekuensi yang
                    // sudah dimulai pengguna (issue #902, kelas #823):
                    // tanpa NonCancellable, Back di tengah proses
                    // membatalkan segmen folder->PIN->port di titik
                    // suspensi berikutnya sehingga sebagian tersimpan
                    // diam-diam dan sebagian hilang tanpa sinyal.
                    withContext(NonCancellable) {
                        saveSettings(binding.inputPort.text?.toString()?.trim()?.toIntOrNull())
                    }
                } finally {
                    savingSettings = false
                    binding.btnSave.isEnabled = true
                }
            }
        }
    }

    /** Isi save dipisah agar suspend (hash PIN di IO) tanpa menahan main thread. */
    private suspend fun saveSettings(requestedPort: Int?) {
            // Validasi port per-bagian (issue #547): dulu port tak
            // sah menggugurkan SELURUH Simpan di awal secara
            // diam-diam — perubahan PIN dan folder pada layar yang
            // sama hilang hanya dengan toast port. Kini bagian lain
            // tetap diterapkan; hanya penerapan port yang dilewati,
            // dan toast port tampil di akhir menggantikan toast
            // tersimpan agar jelas bagian mana yang belum tersimpan.
            val portInvalid = requestedPort == null || requestedPort !in 1024..65535
            // Folder: nilai mentah dibaca di main thread, normalisasi
            // path (canonicalPath/mkdirs) + simpan berjalan di
            // Dispatchers.IO (issue #400) — sebelumnya ketiganya sinkron
            // di main sehingga Simpan bisa jank pada storage lambat
            // dengan banyak baris folder. Toast ketidak-sahan per
            // kategori tampil kembali di main, meniru toast lama.
            applyStoragePathRaw(
                findViewById<EditText>(R.id.input_storage_path).text?.toString()?.trim().orEmpty()
            )
            val extraRaws = folderInputValues(R.id.extra_folders_container)
            val galleryRaws = folderInputValues(R.id.gallery_folders_container)
            var extraInvalid = false
            var galleryInvalid = false
            withContext(Dispatchers.IO) {
                val extra = normalizeFolderPaths(extraRaws, createMissing = true)
                if (extra == null) extraInvalid = true
                else StoragePrefs.setExtraFolders(this@SettingsActivity, extra)
                val gallery = normalizeFolderPaths(galleryRaws, createMissing = false)
                if (gallery == null) galleryInvalid = true
                else StoragePrefs.setGalleryFolders(this@SettingsActivity, gallery)
            }
            if (extraInvalid || galleryInvalid) {
                Toast.makeText(this, R.string.storage_text_folder_invalid, Toast.LENGTH_LONG).show()
            }
            App.httpServer.invalidateFsRootsCache()
            App.httpServer.invalidateStatusCache()
            val newPin = binding.inputPin.text?.toString()?.trim().orEmpty()
            // PBKDF2 150k iterasi berat di HP lama: hash/verify di IO agar save tak jank.
            // (Tombol nonaktif + re-enable dijamin wireSave via try/finally.)
            // Field kosong = PERTAHANKAN PIN lama (issue #383): field ini
            // selalu kosong saat layar dibuka, jadi meneruskan kekosongan
            // ke setServerPin menghapus PIN pada setiap Simpan yang tak
            // terkait. Penghapusan hanya lewat tombol Remove PIN eksplisit.
            val pinEvent = if (newPin.isEmpty()) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    val oldPinHash = StoragePrefs.storedPinHash(this@SettingsActivity)
                    // Cek MURNI verifyStoredPin, BUKAN pinMatches (issue
                    // #414): pinMatches memigrasi + me-rotate secret sesi
                    // untuk hash warisan walau PIN-nya sama, sehingga
                    // menyimpan ulang PIN yang sama diam-diam menendang
                    // semua sesi remote — melawan janji #375 yang
                    // setServerPin sendiri jaga dengan cek murni.
                    val event =
                        if (oldPinHash == null || !StoragePrefs.verifyStoredPin(oldPinHash, newPin)) "PIN SET" else null
                    StoragePrefs.setServerPin(this@SettingsActivity, newPin)
                    event
                }
            }
            if (pinEvent != null) App.logEvent(pinEvent)
            // PIN tidak boleh tertinggal terlihat di field setelah disimpan.
            binding.inputPin.setText("")
            if (StoragePrefs.isPinEnforced(this) &&
                StoragePrefs.getServerPin(this).isNullOrEmpty()
            ) {
                StoragePrefs.setServerBackgroundEnabled(this, false)
                StoragePrefs.setServerAutoStartEnabled(this, false)
                // Inline, bukan coroutine lepas (issue #801): launch
                // fire-and-forget bisa batal sebelum jalan bila activity
                // dihancurkan tepat sesudah Simpan — server tetap hidup
                // padahal PIN diwajibkan tanpa PIN dan pref sudah
                // terlanjur dimatikan. Pola handler hapus PIN di berkas
                // ini: tunggu stop di dalam saveSettings sendiri.
                // renderServer() dipanggil pemanggil sesudah blok ini.
                // NonCancellable (issue #823): pref sudah terlanjur
                // ditulis false di atas — pembatalan lifecycleScope
                // yang mendarat persis di titik suspensi withContext
                // tak boleh lagi membatalkan stop-nya, atau server
                // tetap hidup tak konsisten dengan pref.
                withContext(NonCancellable + Dispatchers.IO) { App.stopHttpServer() }
                stopServiceIfIdle()
            }
            val newPort = requestedPort
            if (!portInvalid && newPort != null) {
                val oldPort = StoragePrefs.serverPort(this)
                StoragePrefs.setServerPort(this, newPort)
                if (newPort != oldPort) {
                    App.logEvent("PORT CHANGED: $oldPort -> $newPort")
                    // Inline seperti cabang PIN di atas (issue #801):
                    // restart tak boleh hilang karena coroutine lepas
                    // keburu batal — pref port sudah berubah.
                    // NonCancellable untuk alasan yang sama (#823).
                    withContext(NonCancellable + Dispatchers.IO) {
                        App.restartHttpServer(applicationContext)
                    }
                }
            }
            renderServer()
            if (portInvalid) {
                Toast.makeText(this, R.string.settings_port_invalid, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            }
    }

    private fun checkForUpdate() {
        if (checkingUpdate) return
        checkingUpdate = true
        binding.btnCheckUpdate.isEnabled = false
        binding.updateStatus.text = getString(R.string.update_checking)
        lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { Updater.checkLatest(this@SettingsActivity) }
                if (info == null) {
                    binding.updateStatus.text = getString(R.string.update_failed)
                    return@launch
                }
                val current = runCatching {
                    packageManager.packageInfoCompat(packageName).versionCodeCompat().toInt()
                }.getOrDefault(0)
                if (info.versionCode <= current) {
                    binding.updateStatus.text = getString(R.string.update_latest)
                    // Sudah terbaru: jangan unduh ulang APK yang sama (buang kuota).
                    return@launch
                }
                binding.updateStatus.text = getString(
                    R.string.update_available, info.versionName, info.versionCode
                )
                // Penjaga seumur activity (pola refreshLanIpsAsync, #300):
                // cek update menunggu jaringan — pengguna bisa menekan Back
                // di tengah jalan, dan show() pada activity yang sedang
                // finishing melempar BadTokenException.
                if (isFinishing || isDestroyed) return@launch
                // Aplikasi tidak pernah mengunduh APK sendiri (pola dropper yang
                // dicurigai Play Protect): pengguna mengambil versi baru lewat
                // browser dari halaman rilis resmi.
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(R.string.update_title)
                    .setMessage(
                        getString(
                            R.string.update_available, info.versionName, info.versionCode
                        ) + "\n\n" + getString(R.string.update_browser_note)
                    )
                    .setNegativeButton(R.string.update_later, null)
                    .setPositiveButton(R.string.update_open_release) { _, _ ->
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, info.pageUrl.toUri()))
                        }
                    }
                    .show()
            } finally {
                checkingUpdate = false
                binding.btnCheckUpdate.isEnabled = true
            }
        }
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

    /** Terapkan path penyimpanan teks dari nilai mentah yang sudah
     *  dibaca di main thread: normalisasi + simpan berjalan di
     *  Dispatchers.IO (issue #400); Toast dan refresh UI kembali di
     *  main. Semantik sama seperti applyStoragePath lama: hanya bila
     *  pengguna mengedit dan path tak kosong; path kanonikal yang
     *  disimpan agar `..`/trailing slash tak lolos mentah. */
    private suspend fun applyStoragePathRaw(raw: String) {
        if (!storagePathEdited) return
        if (raw.isEmpty()) return
        val normalized = withContext(Dispatchers.IO) {
            normalizeFolderPaths(listOf(raw), createMissing = true)?.firstOrNull()
        }
        if (normalized == null) {
            Toast.makeText(this, R.string.storage_text_folder_invalid, Toast.LENGTH_LONG).show()
            return
        }
        StoragePrefs.setTextFolder(this, normalized)
        StoragePrefs.saveFolder(this, null)
        refreshActiveStorageUi()
        Toast.makeText(
            this,
            getString(R.string.storage_text_folder_saved, normalized),
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

    companion object {
        private val PART_PATTERN = Regex("\\.part(\\.\\d+)?$")
    }
}
