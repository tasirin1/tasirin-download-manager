package com.tasirin.httpdownloadmanager

import android.content.Intent
import android.os.Bundle
import android.os.StatFs
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tasirin.httpdownloadmanager.databinding.ActivityFileManagerBinding
import com.tasirin.httpdownloadmanager.util.Formats
import com.tasirin.httpdownloadmanager.util.StoragePrefs
import com.tasirin.httpdownloadmanager.util.applyEdgeToEdge
import com.tasirin.httpdownloadmanager.util.whiteNavigationIcon
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileManagerBinding
    private var currentDir: File = File("/storage/emulated/0/Download")

    // Mode pindah
    private var moveSource: File? = null
    private var isMoveMode = false

    private val adapter = FileAdapter(
        onClick = { entry -> onItemClicked(entry) },
        onLongClick = { entry, view -> showItemMenu(entry, view) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdge(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.whiteNavigationIcon()
        onBackPressedDispatcher.addCallback(this, backCallback)

        binding.fileList.layoutManager = LinearLayoutManager(this)
        binding.fileList.adapter = adapter

        val startDir = File(StoragePrefs.getTextFolder(this)
            ?: currentDir.absolutePath)
        if (startDir.isDirectory) currentDir = startDir
        loadDir(currentDir)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Tombol Cancel saat mode pindah
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        if (isMoveMode) {
            menu.clear()
            menu.add(0, R.id.action_cancel_move, 0, R.string.cancel)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                if (isMoveMode) {
                    exitMoveMode()
                    return true
                }
                val parent = currentDir.parentFile
                if (parent != null && parent.canRead()) {
                    loadDir(parent)
                    return true
                }
                finish()
                return true
            }
            R.id.action_cancel_move -> {
                exitMoveMode()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private val backCallback = object : androidx.activity.OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (isMoveMode) {
                exitMoveMode()
                return
            }
            val parent = currentDir.parentFile
            if (parent != null && parent.canRead()) {
                loadDir(parent)
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    // ── Mode Pindah ────────────────────────────────────────────────────

    private fun enterMoveMode(source: File) {
        isMoveMode = true
        moveSource = source
        supportActionBar?.let {
            it.title = getString(R.string.file_manager_moving, source.name)
            it.setDisplayHomeAsUpEnabled(true)
        }
        invalidateOptionsMenu()
        Toast.makeText(this, getString(R.string.file_manager_move_navigate), Toast.LENGTH_SHORT).show()
    }

    private fun exitMoveMode() {
        isMoveMode = false
        moveSource = null
        supportActionBar?.title = getString(R.string.action_file_manager)
        invalidateOptionsMenu()
    }

    private fun executeMove() {
        val src = moveSource ?: return
        val dest = File(currentDir, src.name)
        if (dest.exists()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.file_manager_move_conflict)
                .setMessage(getString(R.string.file_manager_move_conflict_detail, dest.name))
                .setPositiveButton(R.string.overwrite) { _, _ ->
                    performMove(src, dest, overwrite = true)
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    // User batal — tetap di mode pindah
                }
                .show()
        } else {
            performMove(src, dest, overwrite = false)
        }
    }

    private fun performMove(src: File, dest: File, overwrite: Boolean) {
        if (overwrite && dest.exists()) dest.delete()
        val ok = src.renameTo(dest)
        if (ok) {
            Toast.makeText(this, getString(R.string.file_manager_moved, src.name), Toast.LENGTH_SHORT).show()
            exitMoveMode()
            loadDir(currentDir)
        } else {
            // Fallback: copy + delete (cross-filesystem)
            runCatching {
                src.inputStream().use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                src.delete()
                exitMoveMode()
                loadDir(currentDir)
                Toast.makeText(this, getString(R.string.file_manager_moved, src.name), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this, R.string.file_manager_move_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Navigasi ───────────────────────────────────────────────────────

    private fun loadDir(dir: File) {
        currentDir = dir
        binding.breadcrumb.text = dir.absolutePath

        val children = dir.listFiles()
        val files = children
            ?.filter { it.name.startsWith(".").not() }
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            ?: emptyList()

        val fileCount = files.count { it.isFile }
        val folderCount = files.count { it.isDirectory }
        val parts = mutableListOf<String>()
        if (folderCount > 0) parts.add("$folderCount folders")
        if (fileCount > 0) parts.add("$fileCount files")
        binding.folderCount.text = parts.joinToString(" · ")

        val free = getFreeBytes()
        binding.storageFree.text = getString(R.string.file_manager_freed, Formats.bytes(free))

        val entries = files.map { f ->
            FileEntry(
                file = f,
                name = f.name,
                isDir = f.isDirectory,
                size = if (f.isFile) f.length() else null,
                modified = f.lastModified(),
                children = if (f.isDirectory) f.listFiles()?.size else null
            )
        }

        adapter.submitList(entries)
        binding.fileList.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        binding.emptyState.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE

        // Tampilkan tombol paste jika di mode pindah
        binding.pasteButton.visibility = if (isMoveMode) View.VISIBLE else View.GONE
        binding.pasteButton.setOnClickListener { executeMove() }
    }

    // ── Klik item ──────────────────────────────────────────────────────

    private fun onItemClicked(entry: FileEntry) {
        if (entry.isDir) {
            loadDir(entry.file)
            return
        }
        openFile(entry.file)
    }

    private fun openFile(file: File) {
        try {
            val mime = guessMime(file)
            val uri = if (file.absolutePath.startsWith(filesDir.absolutePath)) {
                FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            } else {
                file.toUri()
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(intent, file.name))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.file_manager_error, Toast.LENGTH_SHORT).show()
        }
    }

    // ── Context menu (long press) ──────────────────────────────────────

    private fun showItemMenu(entry: FileEntry, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_file_manager_item, popup.menu)
        // Sembunyikan "Open" untuk folder
        popup.menu.findItem(R.id.fm_open)?.isVisible = !entry.isDir
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.fm_open -> { openFile(entry.file); true }
                R.id.fm_move -> { enterMoveMode(entry.file); true }
                R.id.fm_rename -> { renameFile(entry); true }
                R.id.fm_delete -> { confirmDelete(entry); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun renameFile(entry: FileEntry) {
        val input = EditText(this).apply {
            setText(entry.name)
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.file_manager_rename)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != entry.name) {
                    val dest = File(entry.file.parentFile, newName)
                    if (entry.file.renameTo(dest)) {
                        loadDir(currentDir)
                    } else {
                        Toast.makeText(this, R.string.file_manager_error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(entry: FileEntry) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.file_manager_confirm_delete, entry.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                if (entry.file.deleteRecursively()) {
                    loadDir(currentDir)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun getFreeBytes(): Long {
        return runCatching {
            val stat = StatFs(currentDir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        }.getOrDefault(0L)
    }

    private fun guessMime(file: File): String {
        val ext = file.extension.lowercase()
        return when {
            ext in setOf("mp4", "mkv", "webm", "avi", "ts", "m4v") -> "video/*"
            ext in setOf("mp3", "m4a", "aac", "ogg", "wav", "flac") -> "audio/*"
            ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp") -> "image/*"
            ext == "pdf" -> "application/pdf"
            ext == "apk" -> "application/vnd.android.package-archive"
            ext in setOf("zip", "rar", "7z", "tar", "gz") -> "application/zip"
            ext in setOf("txt", "log", "json", "xml", "html", "csv") -> "text/plain"
            ext in setOf("doc", "docx") -> "application/msword"
            ext in setOf("xls", "xlsx") -> "application/vnd.ms-excel"
            else -> "*/*"
        }
    }

    private data class FileEntry(
        val file: File,
        val name: String,
        val isDir: Boolean,
        val size: Long? = null,
        val modified: Long = 0,
        val children: Int? = null
    )

    private class FileAdapter(
        private val onClick: (FileEntry) -> Unit,
        private val onLongClick: (FileEntry, View) -> Unit
    ) : RecyclerView.Adapter<FileAdapter.VH>() {

        private var items: List<FileEntry> = emptyList()

        fun submitList(newItems: List<FileEntry>) {
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = items.size
                override fun getNewListSize() = newItems.size
                override fun areItemsTheSame(a: Int, b: Int) = items[a].file.path == newItems[b].file.path
                override fun areContentsTheSame(a: Int, b: Int) = items[a] == newItems[b]
            })
            items = newItems
            diff.dispatchUpdatesTo(this)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_file_manager, parent, false)
            return VH(view)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = items[position]
            val ctx = holder.itemView.context
            holder.icon.text = if (entry.isDir) "\uD83D\uDCC1" else iconForFile(entry.name)
            holder.name.text = entry.name
            holder.meta.text = when {
                entry.isDir -> {
                    val n = entry.children ?: 0
                    ctx.resources.getQuantityString(R.plurals.file_manager_items_count, n, n)
                }
                entry.size != null -> Formats.bytes(entry.size)
                else -> ""
            }
            val dateStr = if (entry.modified > 0) {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(entry.modified))
            } else ""
            if (dateStr.isNotEmpty() && !entry.isDir) {
                holder.meta.text = "${holder.meta.text} · $dateStr"
            }
            holder.itemView.setOnClickListener { onClick(entry) }
            holder.itemView.setOnLongClickListener { v ->
                onLongClick(entry, v)
                true
            }
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: android.widget.TextView = view.findViewById(R.id.file_icon)
            val name: android.widget.TextView = view.findViewById(R.id.file_name)
            val meta: android.widget.TextView = view.findViewById(R.id.file_meta)
        }

        private fun iconForFile(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "mp4", "mkv", "webm", "avi", "ts", "m4v" -> "\uD83C\uDFA5"
                "mp3", "m4a", "aac", "ogg", "wav", "flac" -> "\uD83C\uDFB5"
                "jpg", "jpeg", "png", "gif", "webp", "bmp" -> "\uD83D\uDDBC"
                "pdf" -> "\uD83D\uDCC4"
                "apk" -> "\uD83D\uDCE6"
                "zip", "rar", "7z", "tar", "gz" -> "\uD83D\uDCE6"
                "txt", "log" -> "\uD83D\uDCC3"
                else -> "\uD83D\uDCC4"
            }
        }
    }
}
