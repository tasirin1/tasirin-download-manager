package com.tasirin.httpdownloadmanager

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tasirin.httpdownloadmanager.data.DownloadState
import com.tasirin.httpdownloadmanager.databinding.ActivityGalleryBinding
import com.tasirin.httpdownloadmanager.databinding.ItemGalleryBinding
import com.tasirin.httpdownloadmanager.util.Hex
import com.tasirin.httpdownloadmanager.util.MediaLibrary
import com.tasirin.httpdownloadmanager.util.Formats
import com.tasirin.httpdownloadmanager.util.MimeTypes
import com.tasirin.httpdownloadmanager.util.applyEdgeToEdge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private var fullList: List<MediaLibrary.MediaEntry> = emptyList()
    private var filter = GalleryFilter.ALL
    private val adapter = GalleryAdapter(
        loader = { e -> loadThumb(this, e, THUMB_SIZE) },
        onClick = { e -> openEntry(e) },
        onLongClick = { e -> confirmDelete(e) }
    )

    private enum class GalleryFilter { ALL, IMAGE, VIDEO }

    override fun onCreate(savedInstanceState: Bundle?) {
        runCatching { installSplashScreen() }
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdge(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.recycler.layoutManager = GridLayoutManager(this, SPAN_COUNT)
        binding.recycler.adapter = adapter

        setupFilters()

        lifecycleScope.launch {
            binding.progress.visibility = View.VISIBLE
            fullList = withContext(Dispatchers.IO) {
                val partials = App.engine.items.value
                    .filter { it.state != DownloadState.COMPLETED }
                    .associate { it.fileName to it.progressPercent }
                MediaLibrary.scan(this@GalleryActivity, partials)
            }
            binding.progress.visibility = View.GONE
            applyFilterUi()
        }
    }

    private fun setupFilters() {
        val map = listOf(
            R.id.gfilter_all to GalleryFilter.ALL,
            R.id.gfilter_image to GalleryFilter.IMAGE,
            R.id.gfilter_video to GalleryFilter.VIDEO
        )
        map.forEach { (id, f) ->
            findViewById<TextView>(id)?.setOnClickListener {
                filter = f
                updateFilterColors()
                applyFilterUi()
            }
        }
        updateFilterColors()
    }

    private fun updateFilterColors() {
        val map = listOf(
            R.id.gfilter_all to GalleryFilter.ALL,
            R.id.gfilter_image to GalleryFilter.IMAGE,
            R.id.gfilter_video to GalleryFilter.VIDEO
        )
        map.forEach { (id, f) ->
            val tv = findViewById<TextView>(id) ?: return@forEach
            val selected = f == filter
            tv.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    this,
                    if (selected) R.color.primary else R.color.text_secondary
                )
            )
            tv.typeface = if (selected) android.graphics.Typeface.DEFAULT_BOLD else null
        }
    }

    private fun applyFilterUi() {
        val filtered = when (filter) {
            GalleryFilter.ALL -> fullList
            GalleryFilter.IMAGE -> fullList.filter { !it.isVideo }
            GalleryFilter.VIDEO -> fullList.filter { it.isVideo }
        }
        adapter.submit(filtered)
        binding.emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmDelete(e: MediaLibrary.MediaEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.gallery_delete_title)
            .setMessage(getString(R.string.gallery_delete_message, e.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                runCatching {
                    val raw = MediaLibrary.decodeToken(e.token)
                    if (raw != null) App.engine.deleteMedia(raw)
                }.onFailure {
                    Toast.makeText(this, R.string.gallery_delete_error, Toast.LENGTH_SHORT).show()
                }
                fullList = fullList.filterNot { it.token == e.token }
                applyFilterUi()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openEntry(e: MediaLibrary.MediaEntry) {
        val mime = MimeTypes.forFile(e.name)
        val intent = when {
            e.isPartial -> partialPlayIntent(e, mime)
            !e.contentUri.isNullOrEmpty() ->
                Intent(Intent.ACTION_VIEW).setDataAndType(e.contentUri.toUri(), mime)
            !e.filePath.isNullOrEmpty() -> {
                val uri = FileProvider.getUriForFile(
                    this, "$packageName.fileprovider", File(e.filePath)
                )
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            else -> null
        }
        if (intent != null) {
            runCatching { startActivity(intent) }.onFailure {
                Toast.makeText(this, R.string.gallery_open_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** File belum selesai: putar progresif via server lokal (mendukung Range/seek)
     *  agar bisa diputar dan bertambah terus; fallback FileProvider offline. */
    private fun partialPlayIntent(e: MediaLibrary.MediaEntry, mime: String): Intent? {
        val item = App.engine.items.value.find { it.fileName == e.name }
        if (item != null && App.httpServer.isAlive) {
            val url = "http://127.0.0.1:${App.httpServer.listeningPort}/stream_part/${item.id}"
            return Intent(Intent.ACTION_VIEW).setDataAndType(url.toUri(), mime)
        }
        if (!e.filePath.isNullOrEmpty()) {
            val uri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", File(e.filePath)
            )
            return Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return null
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        private const val SPAN_COUNT = 3
        private const val THUMB_SIZE = 256
        private const val MAX_THUMB_CACHE_KB = 24 * 1024

        @Volatile
        private var thumbCache: LruCache<String, Bitmap>? = null
        private val cacheLock = Any()

        private fun cacheFor(context: Context): LruCache<String, Bitmap> {
            var cache = thumbCache
            if (cache == null) {
                synchronized(cacheLock) {
                    cache = thumbCache
                    if (cache == null) {
                        val memoryClass = runCatching {
                            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                            am.memoryClass
                        }.getOrDefault(128)
                        // Cap absolut: memoryClass/8 bagus, tapi jangan lebih dari
                        // 24 MB agar device RAM kecil (Android 5+) tetap lega.
                        val maxKb = minOf((memoryClass / 8) * 1024, MAX_THUMB_CACHE_KB)
                        cache = object : LruCache<String, Bitmap>(maxKb) {
                            override fun sizeOf(key: String, value: Bitmap): Int =
                                runCatching { value.byteCount / 1024 }.getOrDefault(256)
                        }
                        thumbCache = cache
                    }
                }
            }
            return cache!!
        }

        suspend fun loadThumb(context: Context, e: MediaLibrary.MediaEntry, req: Int): Bitmap? =
            withContext(Dispatchers.IO) {
                val cache = cacheFor(context)
                cache.get(e.token)?.let { return@withContext it }
                val key = thumbKey(e.token)
                diskThumb(context, key)?.let {
                    cache.put(e.token, it)
                    return@withContext it
                }
                val bmp = generateThumb(context, e, req)
                if (bmp != null) {
                    cache.put(e.token, bmp)
                    saveDiskThumb(context, key, bmp)
                }
                bmp
            }

        /** Pakai thumbnail bawaan MediaStore (cepat) dulu, lalu fallback decode. */
        private fun generateThumb(
            context: Context,
            e: MediaLibrary.MediaEntry,
            req: Int
        ): Bitmap? {
            val uri = e.contentUri?.let { runCatching { it.toUri() }.getOrNull() }
            val id = uri?.lastPathSegment?.toLongOrNull()
            if (uri != null && id != null) {
                val native = if (Build.VERSION.SDK_INT >= 29) {
                    runCatching {
                        context.contentResolver.loadThumbnail(uri, Size(req, req), null)
                    }.getOrNull()
                } else if (e.isVideo) {
                    runCatching {
                        MediaStore.Video.Thumbnails.getThumbnail(
                            context.contentResolver, id,
                            MediaStore.Video.Thumbnails.MINI_KIND, null
                        )
                    }.getOrNull()
                } else {
                    runCatching {
                        MediaStore.Images.Thumbnails.getThumbnail(
                            context.contentResolver, id,
                            MediaStore.Images.Thumbnails.MINI_KIND, null
                        )
                    }.getOrNull()
                }
                if (native != null) return scaleDown(native, req)
            }
            if (!e.filePath.isNullOrEmpty()) {
                return if (e.isVideo) {
                    runCatching {
                        ThumbnailUtils.createVideoThumbnail(
                            e.filePath, MediaStore.Images.Thumbnails.MINI_KIND
                        )
                    }.getOrNull()?.let { scaleDown(it, req) }
                } else {
                    decodeFile(context, e.filePath, req)
                }
            }
            if (!e.contentUri.isNullOrEmpty()) {
                return decodeUri(context, e.contentUri, req)
            }
            return null
        }

        private fun scaleDown(src: Bitmap, max: Int): Bitmap {
            if (src.width <= max && src.height <= max) return src
            val scale = max.toDouble() / maxOf(src.width, src.height)
            val w = (src.width * scale).toInt().coerceAtLeast(1)
            val h = (src.height * scale).toInt().coerceAtLeast(1)
            val out = Bitmap.createScaledBitmap(src, w, h, true)
            if (out !== src) src.recycle()
            return out
        }

        private fun thumbDir(context: Context): File =
            File(context.cacheDir, "thumbs").apply { runCatching { mkdirs() } }

        private fun thumbKey(token: String): String = runCatching {
            Hex.encode(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
        }.getOrDefault(token.hashCode().toString()) + ".jpg"

        private fun diskThumb(context: Context, key: String): Bitmap? = runCatching {
            val f = File(thumbDir(context), key)
            if (f.isFile && f.length() > 0) BitmapFactory.decodeFile(f.absolutePath) else null
        }.getOrNull()

        private fun saveDiskThumb(context: Context, key: String, bmp: Bitmap) {
            runCatching {
                val f = File(thumbDir(context), key)
                FileOutputStream(f).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 78, out)
                }
            }
        }

        private fun decodeFile(context: Context, path: String, req: Int): Bitmap? =
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = computeSample(bounds, req)
                }
                BitmapFactory.decodeFile(path, opts)
            }.getOrNull()

        private fun decodeUri(context: Context, uri: String, req: Int): Bitmap? =
            runCatching {
                val resolver = context.contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri.toUri())?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = computeSample(bounds, req)
                }
                resolver.openInputStream(uri.toUri())?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            }.getOrNull()

        private fun computeSample(bounds: BitmapFactory.Options, req: Int): Int {
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return 1
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= req &&
                bounds.outHeight / (sample * 2) >= req
            ) {
                sample *= 2
            }
            return sample
        }
    }
}

private class GalleryAdapter(
    private val loader: suspend (MediaLibrary.MediaEntry) -> Bitmap?,
    private val onClick: (MediaLibrary.MediaEntry) -> Unit,
    private val onLongClick: (MediaLibrary.MediaEntry) -> Unit = {}
) : RecyclerView.Adapter<GalleryAdapter.Holder>() {

    private val items = mutableListOf<MediaLibrary.MediaEntry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @SuppressLint("NotifyDataSetChanged") // Daftar galeri diganti utuh per scan; diff halus menyusul.
    fun submit(list: List<MediaLibrary.MediaEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    private fun formatDate(ms: Long): String {
        if (ms <= 0) return ""
        return DATE_FORMAT.format(java.util.Date(ms))
    }

    // Adapter berjalan di main thread; formatter tunggal aman & hemat alokasi.
    private companion object {
        val DATE_FORMAT = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault())
    }

    class Holder(val binding: ItemGalleryBinding) : RecyclerView.ViewHolder(binding.root) {
        var job: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemGalleryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val e = items[position]
        val b = holder.binding
        b.textName.text = e.name
        b.textExt.text = e.name.substringAfterLast('.', "").uppercase()
        val ctx = holder.itemView.context
        b.playOverlay.visibility = if (e.isVideo) View.VISIBLE else View.GONE
        b.textInfo.text = if (e.isPartial) {
            val pct = if (e.progressPercent in 0..100) " · ${e.progressPercent}%" else ""
            ctx.getString(R.string.gallery_partial_badge) + pct + " · " + Formats.bytes(e.size)
        } else if (e.isVideo && e.durationMs > 0) {
            Formats.duration(e.durationMs) + " · " + Formats.bytes(e.size)
        } else {
            Formats.bytes(e.size) + " · " + formatDate(e.modified)
        }
        b.imageThumb.setImageDrawable(null)
        val pos = position
        holder.itemView.setOnClickListener { onClick(e) }
        holder.itemView.setOnLongClickListener {
            onLongClick(e)
            true
        }
        holder.job?.cancel()
        holder.job = scope.launch {
            val bmp = loader(e)
            if (bmp != null && holder.bindingAdapterPosition == pos) {
                b.imageThumb.setImageBitmap(bmp)
            }
        }
    }

}
