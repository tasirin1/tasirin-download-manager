package com.tasirin.httpdownloadmanager

import android.content.ContentUris
import android.content.Intent
import android.os.Bundle
import android.os.StatFs
import android.provider.MediaStore
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
    private var sortMode = SORT_NAME

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
        menuInflater.inflate(R.menu.menu_file_manager, menu)
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
                if (isMoveMode) { exitMoveMode(); return true }
                val parent = currentDir.parentFile
                if (parent != null && parent.canRead()) { loadDir(parent); return true }
                finish(); return true
            }
            R.id.action_cancel_move -> { exitMoveMode(); return true }
            R.id.fm_new_folder -> { createNewFolder(); return true }
            R.id.fm_sort -> { showSortMenu(); return true }
        }
        return super.onOptionsItemSelected(item)
    }

    private val backCallback = object : androidx.activity.OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (isMoveMode) { exitMoveMode(); return }
            val parent = currentDir.parentFile
            if (parent != null && parent.canRead()) loadDir(parent)
            else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
        }
    }

    // ── New Folder ─────────────────────────────────────────────────────

    private fun createNewFolder() {
        val input = EditText(this).apply { hint = getString(R.string.file_manager_new_folder_hint) }
        AlertDialog.Builder(this)
            .setTitle(R.string.file_manager_new_folder)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val dir = File(currentDir, name)
                    if (dir.mkdir()) {
                        Toast.makeText(this, R.string.file_manager_folder_created, Toast.LENGTH_SHORT).show()
                        loadDir(currentDir)
                    } else {
                        Toast.makeText(this, R.string.file_manager_error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ── Sort ───────────────────────────────────────────────────────────

    private fun showSortMenu() {
        val popup = PopupMenu(this, binding.toolbar)
        popup.menu.add(0, SORT_NAME, 0, R.string.file_manager_sort_name)
        popup.menu.add(0, SORT_DATE, 0, R.string.file_manager_sort_date)
        popup.menu.add(0, SORT_SIZE, 0, R.string.file_manager_sort_size)
        popup.setOnMenuItemClickListener { mi ->
            sortMode = mi.itemId
            loadDir(currentDir)
            true
        }
        popup.show()
    }

    // ── Mode Pindah ────────────────────────────────────────────────────

    private fun enterMoveMode(source: File) {
        isMoveMode = true
        moveSource = source
        supportActionBar?.title = getString(R.string.file_manager_moving, source.name)
        invalidateOptionsMenu()
        Toast.makeText(this, R.string.file_manager_move_navigate, Toast.LENGTH_SHORT).show()
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
                .setPositiveButton(R.string.overwrite) { _, _ -> performMove(src, dest, true) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            performMove(src, dest, false)
        }
    }

    private fun performMove(src: File, dest: File, overwrite: Boolean) {
        if (overwrite && dest.exists()) dest.delete()
        if (src.renameTo(dest)) {
            Toast.makeText(this, getString(R.string.file_manager_moved, src.name), Toast.LENGTH_SHORT).show()
            exitMoveMode(); loadDir(currentDir)
        } else {
            runCatching {
                src.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                src.delete()
                exitMoveMode(); loadDir(currentDir)
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
            ?.filter { !it.name.startsWith(".") }
            ?.sortedWith(when (sortMode) {
                SORT_DATE -> compareBy<File> { !it.isDirectory }.thenByDescending { it.lastModified() }
                SORT_SIZE -> compareBy<File> { !it.isDirectory }.thenByDescending { if (it.isFile) it.length() else 0L }
                else -> compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }
            }) ?: emptyList()
        val fileCount = files.count { it.isFile }
        val folderCount = files.count { it.isDirectory }
        val parts = mutableListOf<String>()
        if (folderCount > 0) parts.add("$folderCount folders")
        if (fileCount > 0) parts.add("$fileCount files")
        binding.folderCount.text = parts.joinToString(" · ")
        binding.storageFree.text = getString(R.string.file_manager_freed, Formats.bytes(getFreeBytes()))
        adapter.submitList(files.map { f ->
            FileEntry(f, f.name, f.isDirectory, if (f.isFile) f.length() else null, f.lastModified(), if (f.isDirectory) f.listFiles()?.size else null)
        })
        binding.fileList.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        binding.emptyState.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        binding.pasteButton.visibility = if (isMoveMode) View.VISIBLE else View.GONE
        binding.pasteButton.setOnClickListener { executeMove() }
    }

    // ── Klik & Buka File ───────────────────────────────────────────────

    private fun onItemClicked(entry: FileEntry) {
        if (entry.isDir) loadDir(entry.file) else openFile(entry.file)
    }

    private fun openFile(file: File) {
        try {
            val mime = guessMime(file)
            val uri = resolveContentUri(file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(intent, file.name))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.file_manager_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(file: File) {
        try {
            val uri = resolveContentUri(file)
            val mime = guessMime(file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, file.name))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.file_manager_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun resolveContentUri(file: File): android.net.Uri {
        val mediaUri = queryMediaStore(file)
        if (mediaUri != null) return mediaUri
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }

    private fun queryMediaStore(file: File): android.net.Uri? {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DATA} = ?"
        return contentResolver.query(collection, projection, selection, arrayOf(file.absolutePath), null)?.use { c ->
            if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))) else null
        }
    }

    // ── Context menu ───────────────────────────────────────────────────

    private fun showItemMenu(entry: FileEntry, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_file_manager_item, popup.menu)
        popup.menu.findItem(R.id.fm_open)?.isVisible = !entry.isDir
        popup.menu.findItem(R.id.fm_share)?.isVisible = !entry.isDir
        popup.menu.findItem(R.id.fm_move)?.isVisible = !entry.isDir
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.fm_open -> { openFile(entry.file); true }
                R.id.fm_move -> { enterMoveMode(entry.file); true }
                R.id.fm_rename -> { renameFile(entry); true }
                R.id.fm_share -> { shareFile(entry.file); true }
                R.id.fm_delete -> { confirmDelete(entry); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun renameFile(entry: FileEntry) {
        val input = EditText(this).apply { setText(entry.name); selectAll() }
        AlertDialog.Builder(this)
            .setTitle(R.string.file_manager_rename).setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && name != entry.name) {
                    if (entry.file.renameTo(File(entry.file.parentFile, name))) loadDir(currentDir)
                    else Toast.makeText(this, R.string.file_manager_error, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun confirmDelete(entry: FileEntry) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.file_manager_confirm_delete, entry.name))
            .setPositiveButton(R.string.delete) { _, _ -> if (entry.file.deleteRecursively()) loadDir(currentDir) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun getFreeBytes(): Long = runCatching {
        val s = StatFs(currentDir.absolutePath); s.availableBlocksLong * s.blockSizeLong
    }.getOrDefault(0L)

    private fun guessMime(file: File): String = when (file.extension.lowercase()) {
        "mp4", "mkv", "webm", "avi", "ts", "m4v" -> "video/*"
        "mp3", "m4a", "aac", "ogg", "wav", "flac" -> "audio/*"
        "jpg", "jpeg", "png", "gif", "webp", "bmp" -> "image/*"
        "pdf" -> "application/pdf"
        "apk" -> "application/vnd.android.package-archive"
        "zip", "rar", "7z", "tar", "gz" -> "application/zip"
        "txt", "log", "json", "xml", "html", "csv" -> "text/plain"
        "doc", "docx" -> "application/msword"
        "xls", "xlsx" -> "application/vnd.ms-excel"
        else -> "*/*"
    }

    private data class FileEntry(val file: File, val name: String, val isDir: Boolean,
        val size: Long? = null, val modified: Long = 0, val children: Int? = null)

    private class FileAdapter(
        private val onClick: (FileEntry) -> Unit,
        private val onLongClick: (FileEntry, View) -> Unit
    ) : RecyclerView.Adapter<FileAdapter.VH>() {
        private var items: List<FileEntry> = emptyList()
        fun submitList(n: List<FileEntry>) {
            val d = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = items.size
                override fun getNewListSize() = n.size
                override fun areItemsTheSame(a: Int, b: Int) = items[a].file.path == n[b].file.path
                override fun areContentsTheSame(a: Int, b: Int) = items[a] == n[b]
            })
            items = n
            d.dispatchUpdatesTo(this)
        }
        override fun onCreateViewHolder(p: ViewGroup, v: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_file_manager, p, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val e = items[pos]; val ctx = h.itemView.context
            h.icon.text = if (e.isDir) "\uD83D\uDCC1" else iconFor(e.name)
            h.name.text = e.name
            h.meta.text = when {
                e.isDir -> ctx.resources.getQuantityString(R.plurals.file_manager_items_count, e.children ?: 0, e.children ?: 0)
                e.size != null -> Formats.bytes(e.size)
                else -> ""
            }
            if (e.modified > 0 && !e.isDir) {
                val d = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(e.modified))
                h.meta.text = "${h.meta.text} · $d"
            }
            h.itemView.setOnClickListener { onClick(e) }
            h.itemView.setOnLongClickListener { v -> onLongClick(e, v); true }
        }
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: android.widget.TextView = v.findViewById(R.id.file_icon)
            val name: android.widget.TextView = v.findViewById(R.id.file_name)
            val meta: android.widget.TextView = v.findViewById(R.id.file_meta)
        }
        private fun iconFor(n: String) = when (n.substringAfterLast('.', "").lowercase()) {
            "mp4", "mkv", "webm", "avi", "ts", "m4v" -> "\uD83C\uDFA5"
            "mp3", "m4a", "aac", "ogg", "wav", "flac" -> "\uD83C\uDFB5"
            "jpg", "jpeg", "png", "gif", "webp", "bmp" -> "\uD83D\uDDBC"
            "pdf" -> "\uD83D\uDCC4"; "apk" -> "\uD83D\uDCE6"
            "zip", "rar", "7z", "tar", "gz" -> "\uD83D\uDCE6"
            "txt", "log" -> "\uD83D\uDCC3"; else -> "\uD83D\uDCC4"
        }
    }

    companion object {
        private const val SORT_NAME = 0
        private const val SORT_DATE = 1
        private const val SORT_SIZE = 2
    }
}
