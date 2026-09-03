package com.tasirin.httpdownloadmanager

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.content.ComponentCallbacks2
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.tasirin.httpdownloadmanager.data.DownloadItem
import com.tasirin.httpdownloadmanager.data.DownloadState
import com.tasirin.httpdownloadmanager.databinding.ActivityGalleryBinding
import com.tasirin.httpdownloadmanager.databinding.ItemGalleryBinding
import com.tasirin.httpdownloadmanager.util.MediaLibrary
import com.tasirin.httpdownloadmanager.util.Formats
import com.tasirin.httpdownloadmanager.util.scaleDown
import com.tasirin.httpdownloadmanager.util.MimeTypes
import com.tasirin.httpdownloadmanager.util.applyEdgeToEdge
import com.tasirin.httpdownloadmanager.util.StoragePrefs
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.tasirin.httpdownloadmanager.util.whiteNavigationIcon

class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private var fullList: List<MediaLibrary.MediaEntry> = emptyList()
    private var loadedCount = GALLERY_PAGE
    private var loadingMore = false
    private var partialProgress: Map<String, Int> = emptyMap()
    private val adapter = GalleryAdapter(
        loader = { e -> loadThumb(this, e, THUMB_SIZE) },
        onClick = { e -> openEntry(e) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdge(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.whiteNavigationIcon()

        binding.recycler.layoutManager = GridLayoutManager(this, SPAN_COUNT)
        binding.recycler.adapter = adapter
        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 6) {
                    loadMore()
                }
            }
        })

        lifecycleScope.launch {
            binding.progress.visibility = View.VISIBLE
            partialProgress = activeDownloadProgress()
            fullList = withContext(Dispatchers.IO) {
                MediaLibrary.scan(this@GalleryActivity, partialProgress, loadedCount, StoragePrefs.getGalleryFolders(this@GalleryActivity)).items
            }
            binding.progress.visibility = View.GONE
            loadMore() // halaman awal kurang dari satu layar -> isi otomatis
        }

        // Update progres tanpa scan ulang MediaStore. Scan hanya saat daftar file
        // aktif berubah (mulai/selesai), bukan tiap tick persentase.
        lifecycleScope.launch {
            App.engine.items.collect { items ->
                val newPartial = activeDownloadProgress(items)
                val filesChanged = newPartial.keys != partialProgress.keys
                partialProgress = newPartial
                if (filesChanged) {
                    fullList = withContext(Dispatchers.IO) {
                        MediaLibrary.scan(this@GalleryActivity, partialProgress, loadedCount, StoragePrefs.getGalleryFolders(this@GalleryActivity)).items
                    }
                } else {
                    fullList = mergedProgress(fullList)
                }
                updateList()
            }
        }
    }

    private fun activeDownloadProgress(
        items: List<DownloadItem> = App.engine.items.value
    ): Map<String, Int> = items
        .filter { it.state != DownloadState.COMPLETED }
        .associate { it.fileName to it.progressPercent }

    private fun mergedProgress(
        items: List<MediaLibrary.MediaEntry>
    ): List<MediaLibrary.MediaEntry> = items.map { entry ->
        if (!entry.isPartial) entry
        else entry.copy(progressPercent = partialProgress[entry.name] ?: -1)
    }

    private fun updateList() {
        adapter.submit(fullList)
        binding.emptyView.visibility = if (fullList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun canLoadMore(): Boolean =
        fullList.size >= loadedCount && loadedCount < MediaLibrary.GALLERY_MAX_ENTRIES

    /** Naikkan batas scan bertahap (halaman per halaman) dan perbarui daftar
     *  lewat DiffUtil — galeri besar tidak pernah di-hold penuh di memori. */
    private fun loadMore() {
        if (loadingMore || !canLoadMore()) return
        loadingMore = true
        lifecycleScope.launch {
            try {
                while (canLoadMore() && fullList.size < GALLERY_MIN_FILL) {
                    val next = minOf(loadedCount + GALLERY_PAGE, MediaLibrary.GALLERY_MAX_ENTRIES)
                    val had = fullList.size
                    fullList = withContext(Dispatchers.IO) {
                        MediaLibrary.scan(this@GalleryActivity, partialProgress, next).items
                    }
                    loadedCount = next
                    if (fullList.size <= had) break
                }
            } finally {
                loadingMore = false
            }
            updateList()
        }
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
            val url = "http://127.0.0.1:${App.httpServer.listeningPort}${App.httpServer.createPartialStreamUrl(item.id)}"
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

    /** Sistem minta memori (Android 5+): buang cache thumbnail agar tidak
     *  ikut membebani device RAM kecil. Thumbnail akan di-decode ulang dari
     *  disk cache (thumbs/) saat galeri digulir lagi. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            clearThumbCache()
        }
    }

    override fun onDestroy() {
        adapter.release()
        clearThumbCache()
        super.onDestroy()
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
        private const val GALLERY_PAGE = 300
        private const val GALLERY_MIN_FILL = 24

        @Volatile
        private var thumbCache: LruCache<String, Bitmap>? = null
        private val cacheLock = Any()

        /** Buang seluruh cache thumbnail di memori (dipanggil saat tekanan
         *  memori; disk cache tetap dipakai, jadi galeri tidak perlu re-scan). */
        fun clearThumbCache() {
            synchronized(cacheLock) {
                thumbCache?.evictAll()
            }
        }

        private fun cacheFor(context: Context): LruCache<String, Bitmap> {
            thumbCache?.let { return it }
            synchronized(cacheLock) {
                thumbCache?.let { return it }
                val memoryClass = runCatching {
                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    am.memoryClass
                }.getOrDefault(128)
                // Cap absolut: memoryClass/8 bagus, tapi jangan lebih dari
                // 24 MB agar device RAM kecil (Android 5+) tetap lega.
                val maxKb = minOf((memoryClass / 8) * 1024, MAX_THUMB_CACHE_KB)
                val cache = object : LruCache<String, Bitmap>(maxKb) {
                    override fun sizeOf(key: String, value: Bitmap): Int =
                        runCatching { value.byteCount / 1024 }.getOrDefault(256)
                }
                thumbCache = cache
                return cache
            }
        }

        suspend fun loadThumb(context: Context, e: MediaLibrary.MediaEntry, req: Int): Bitmap? =
            withContext(Dispatchers.IO) {
                val cache = cacheFor(context)
                cache.get(e.token)?.let { return@withContext it }

                // Satu generator/cache disk dipakai remote web & galeri native;
                // ini menghindari dua file thumbnail berbeda untuk video sama.
                val thumb = runCatching {
                    App.httpServer.galleryThumbFile(e.token)
                }.getOrNull() ?: return@withContext null

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(thumb.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= req &&
                    bounds.outHeight / (sample * 2) >= req
                ) {
                    sample *= 2
                }
                val decoded = BitmapFactory.decodeFile(
                    thumb.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                ) ?: return@withContext null
                val bitmap = scaleDown(decoded, req)
                if (bitmap !== decoded) decoded.recycle()
                cache.put(e.token, bitmap)
                bitmap
            }
    }
}

private class GalleryAdapter(
    private val loader: suspend (MediaLibrary.MediaEntry) -> Bitmap?,
    private val onClick: (MediaLibrary.MediaEntry) -> Unit,
) : RecyclerView.Adapter<GalleryAdapter.Holder>() {

    private val items = mutableListOf<MediaLibrary.MediaEntry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun release() { scope.cancel() }

    fun submit(list: List<MediaLibrary.MediaEntry>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = items.size
            override fun getNewListSize(): Int = list.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                items[oldItemPosition].token == list[newItemPosition].token
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                items[oldItemPosition] == list[newItemPosition]
        }, false)
        items.clear()
        items.addAll(list)
        diff.dispatchUpdatesTo(this)
    }

    // Formatter di-cache per-locale: onBindViewHolder bisa dipanggil ratusan
    // kali saat scroll, membuat SimpleDateFormat per bind itu alokasi sia-sia.
    // (SimpleDateFormat tidak thread-safe, tapi bind selalu di main thread.)
    private var dateFormat: java.text.SimpleDateFormat? = null

    private fun formatDate(ms: Long): String {
        if (ms <= 0) return ""
        val fmt = dateFormat ?: java.text.SimpleDateFormat(
            "dd MMM yyyy", java.util.Locale.getDefault()
        ).also { dateFormat = it }
        return fmt.format(java.util.Date(ms))
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
        // 3. Tampilkan play overlay saat tidak ada thumbnail supaya cell tidak kosong
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
        holder.job?.cancel()
        holder.job = scope.launch {
            val bmp = loader(e)
            if (bmp != null && holder.bindingAdapterPosition == pos) {
                b.imageThumb.setImageBitmap(bmp)
                // Thumbnail ada: sembunyikan play overlay (gambar sudah cukup)
                if (e.isVideo) b.playOverlay.visibility = View.GONE
            } else if (e.isVideo) {
                // 3. Tidak ada thumbnail: play overlay tetap tampil sebagai fallback
                b.playOverlay.visibility = View.VISIBLE
            }
        }
    }

}
