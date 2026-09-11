package com.tasirin.httpdownloadmanager

import android.content.Intent
import android.os.Bundle
import android.os.StatFs
import android.view.LayoutInflater
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
    private val adapter = FileAdapter(
        onClick = { entry -> onItemClicked(entry) },
        onMenu = { entry, anchor -> showItemMenu(entry, anchor) }
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

        // Mulai dari folder download default
        val startDir = File(StoragePrefs.getTextFolder(this)
            ?: currentDir.absolutePath)
        if (startDir.isDirectory) currentDir = startDir
        loadDir(currentDir)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            val parent = currentDir.parentFile
            if (parent != null && parent.canRead()) {
                loadDir(parent)
                return true
            }
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private val backCallback = object : androidx.activity.OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val parent = currentDir.parentFile
            if (parent != null && parent.canRead()) {
                loadDir(parent)
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private fun loadDir(dir: File) {
        currentDir = dir
        binding.breadcrumb.text = dir.absolutePath

        val children = dir.listFiles()
        val files = children
            ?.filter { it.name.startsWith(".").not() } // Sembunyikan file tersembunyi
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
    }

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
            }
            startActivity(Intent.createChooser(intent, file.name))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.file_manager_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showItemMenu(entry: FileEntry, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_file_manager_item, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.fm_open -> { openFile(entry.file); true }
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
            ext in setOf("pdf") -> "application/pdf"
            ext in setOf("apk") -> "application/vnd.android.package-archive"
            ext in setOf("zip", "rar", "7z", "tar", "gz") -> "application/zip"
            ext in setOf("txt", "log", "json", "xml", "html", "csv") -> "text/*"
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
        private val onMenu: (FileEntry, View) -> Unit
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
                    getString(R.string.file_manager_files_count, n)
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
            holder.menuBtn.setOnClickListener { onMenu(entry, it) }
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: android.widget.TextView = view.findViewById(R.id.file_icon)
            val name: android.widget.TextView = view.findViewById(R.id.file_name)
            val meta: android.widget.TextView = view.findViewById(R.id.file_meta)
            val menuBtn: android.widget.ImageButton = view.findViewById(R.id.file_menu)
        }

        private fun iconForFile(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "mp4", "mkv", "webm", "avi", "ts", "m4v" -> "\uD83C\uDFA5"   // 🎥
                "mp3", "m4a", "aac", "ogg", "wav", "flac" -> "\uD83C\uDFB5"  // 🎵
                "jpg", "jpeg", "png", "gif", "webp", "bmp" -> "\uD83D\uDDBC" // 🖼
                "pdf" -> "\uD83D\uDCC4"                                       // 📄
                "apk" -> "\uD83D\uDCE6"                                       // 📦
                "zip", "rar", "7z", "tar", "gz" -> "\uD83D\uDCE6"            // 📦
                "txt", "log" -> "\uD83D\uDCC3"                                // 📃
                else -> "\uD83D\uDCC4"                                        // 📄
            }
        }
    }
}
