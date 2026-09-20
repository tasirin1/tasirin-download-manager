package com.tasirin.httpdownloadmanager.ui

import android.content.res.ColorStateList
import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tasirin.httpdownloadmanager.R
import com.tasirin.httpdownloadmanager.data.DownloadItem
import com.tasirin.httpdownloadmanager.data.DownloadState
import com.tasirin.httpdownloadmanager.util.Formats
import com.tasirin.httpdownloadmanager.util.MimeTypes
import com.tasirin.httpdownloadmanager.util.StoragePrefs
import com.tasirin.httpdownloadmanager.databinding.ItemDownloadBinding
import com.tasirin.httpdownloadmanager.databinding.ItemSectionHeaderBinding
import java.io.File
import java.util.Locale

/** Baris daftar: header grup status atau item download. */
sealed class DownloadRow {
    data class Header(
        val title: String,
        val count: Int,
        val collapsed: Boolean,
        val key: String
    ) : DownloadRow()
    data class Item(val item: DownloadItem) : DownloadRow()
}

class DownloadAdapter(private val listener: Listener) :
    ListAdapter<DownloadRow, RecyclerView.ViewHolder>(DIFF) {

    enum class Action { PAUSE, RESUME, CANCEL, DELETE, OPEN, OPEN_FOLDER, MONITOR }

    interface Listener {
        fun onAction(item: DownloadItem, action: Action)
        fun onTap(item: DownloadItem)
        fun onLongPress(item: DownloadItem)
        fun onToggleSection(key: String)
    }

    class ItemHolder(val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root)
    class HeaderHolder(val binding: ItemSectionHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        var bindToggle: (() -> Unit)? = null
    }

    private fun progressColor(state: DownloadState): Int = when (state) {
        DownloadState.PENDING, DownloadState.DOWNLOADING -> R.color.primary
        DownloadState.COMPLETED -> R.color.status_on
        DownloadState.FAILED -> R.color.status_off
        DownloadState.PAUSED, DownloadState.CANCELLED -> R.color.text_secondary
    }

    override fun getItemViewType(position: Int): Int =
        if (getItem(position) is DownloadRow.Header) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemSectionHeaderBinding.inflate(inflater, parent, false))
        } else {
            val binding = ItemDownloadBinding.inflate(inflater, parent, false)
            binding.progressBar.max = 100
            ItemHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is DownloadRow.Header -> bindHeader(holder as HeaderHolder, row)
            is DownloadRow.Item -> bindItem(holder as ItemHolder, row.item)
        }
    }

    private fun bindHeader(holder: HeaderHolder, row: DownloadRow.Header) {
        val b = holder.binding
        b.textSectionTitle.text = row.title
        b.textSectionCount.text = String.format(Locale.US, "%d", row.count)
        val chevron = if (row.collapsed) R.drawable.ic_chevron else R.drawable.ic_chevron_up
        b.sectionChevron.setImageResource(chevron)
        b.sectionChevron.visibility = if (row.count > 0) View.VISIBLE else View.INVISIBLE
        b.sectionHeaderRoot.setOnClickListener { holder.bindToggle?.invoke() }
        holder.bindToggle = {
            listener.onToggleSection(row.key)
        }
    }

    // Cache hasil resolve warna: bind berjalan tiap tick progres (1 detik),
    // getColor berulang hanya membuang CPU.
    private val colorCache = HashMap<Int, Int>()
    private fun cachedColor(ctx: android.content.Context, res: Int): Int =
        colorCache.getOrPut(res) { ContextCompat.getColor(ctx, res) }

    private fun bindItem(holder: ItemHolder, item: DownloadItem) {
        val b = holder.binding
        val ctx = b.root.context

        b.textName.text = item.fileName
        // Satu lookup mime per bind (sebelumnya 2x via fileIconRes + circle).
        val mime = MimeTypes.forFile(item.fileName)
        b.fileIcon.setImageResource(fileIconRes(mime))
        b.fileIcon.imageTintList = ColorStateList.valueOf(
            cachedColor(ctx, R.color.text_primary)
        )
        b.fileIconCircle.backgroundTintList = ColorStateList.valueOf(
            cachedColor(ctx, fileIconCircleColor(mime))
        )
        b.statusBadge.text = badgeText(item, ctx)
        b.statusBadge.backgroundTintList = ColorStateList.valueOf(
            cachedColor(ctx, badgeBgColor(item.state))
        )
        b.statusBadge.setTextColor(cachedColor(ctx, badgeTextColor(item.state)))
        val prevProgress = b.progressBar.progress
        smoothProgress(b.progressBar, prevProgress, item.progressPercent)
        b.progressBar.progressTintList = ColorStateList.valueOf(
            cachedColor(ctx, progressColor(item.state))
        )

        b.textProgress.text = if (item.state == DownloadState.COMPLETED) {
            // Selesai: 100% + ukuran riil. Jangan tampilkan "100% x / y" saat
            // totalBytes server lebih besar dari byte yang diterima (CDN
            // Instagram kerap melaporkan Content-Length berlebih).
            val size = if (item.totalBytes > 0) item.totalBytes else item.bytesDownloaded
            String.format(Locale.US, "100%%  %s", Formats.bytes(size))
        } else if (item.totalBytes > 0) {
            // HLS: totalBytes bisa berupa estimasi (BANDWIDTH x durasi) —
            // tandai dengan "~" supaya jelas bukan angka pasti.
            val totalTxt = if (item.totalBytesEstimated) {
                "~" + Formats.bytes(item.totalBytes)
            } else {
                Formats.bytes(item.totalBytes)
            }
            String.format(
                Locale.US, "%d%%  %s / %s",
                item.progressPercent,
                Formats.bytes(item.bytesDownloaded),
                totalTxt
            )
        } else if (item.progressPercentOverride >= 0) {
            // HLS: total asli tidak diketahui — persen + byte riil tanpa denominator palsu.
            String.format(
                Locale.US, "%d%%  %s",
                item.progressPercent,
                Formats.bytes(item.bytesDownloaded)
            )
        } else {
            Formats.bytes(item.bytesDownloaded)
        }

        val showSpeed = item.state == DownloadState.DOWNLOADING && item.speedBps > 0
        b.textSpeed.visibility = if (showSpeed) View.VISIBLE else View.GONE
        if (showSpeed) {
            b.textSpeed.text = ctx.getString(
                R.string.speed_and_eta,
                Formats.speed(item.speedBps),
                Formats.eta(item.etaSeconds)
            )
        }

        val error = item.error?.takeIf { it.isNotBlank() }
        b.textError.visibility = if (error != null) View.VISIBLE else View.GONE
        if (error != null) b.textError.text = error

        b.textChecksumOk.visibility =
            if (item.state == DownloadState.COMPLETED && item.checksumVerified) {
                View.VISIBLE
            } else {
                View.GONE
            }
        if (item.state == DownloadState.COMPLETED) {
            val location = when {
                !item.filePath.isNullOrEmpty() -> {
                    File(item.filePath).parent ?: item.filePath
                }
                !item.contentUri.isNullOrEmpty() ->
                    ctx.getString(R.string.location_media_store)
                else -> ctx.getString(R.string.location_internal)
            }
            b.textLocation.text = ctx.getString(R.string.location_label, location)
            b.textLocation.visibility = View.VISIBLE
        } else {
            b.textLocation.visibility = View.GONE
        }

        // Aksi cepat kontekstual: hemat satu ketuk + ramah D-pad/TV.
        when (item.state) {
            DownloadState.DOWNLOADING, DownloadState.PENDING -> {
                b.btnQuickPrimary.setText(R.string.pause)
                b.btnQuickPrimary.setOnClickListener { listener.onAction(item, Action.PAUSE) }
                b.btnQuickSecondary.setText(R.string.cancel)
                b.btnQuickSecondary.setOnClickListener { listener.onAction(item, Action.CANCEL) }
            }
            DownloadState.PAUSED -> {
                b.btnQuickPrimary.setText(R.string.resume)
                b.btnQuickPrimary.setOnClickListener { listener.onAction(item, Action.RESUME) }
                b.btnQuickSecondary.setText(R.string.cancel)
                b.btnQuickSecondary.setOnClickListener { listener.onAction(item, Action.CANCEL) }
            }
            DownloadState.FAILED -> {
                b.btnQuickPrimary.setText(R.string.retry)
                b.btnQuickPrimary.setOnClickListener { listener.onAction(item, Action.RESUME) }
                b.btnQuickSecondary.setText(R.string.delete)
                b.btnQuickSecondary.setOnClickListener { listener.onAction(item, Action.DELETE) }
            }
            DownloadState.CANCELLED -> {
                // resume() kini menerima CANCELLED (mulai ulang dari nol).
                b.btnQuickPrimary.setText(R.string.retry)
                b.btnQuickPrimary.setOnClickListener { listener.onAction(item, Action.RESUME) }
                b.btnQuickSecondary.setText(R.string.delete)
                b.btnQuickSecondary.setOnClickListener { listener.onAction(item, Action.DELETE) }
            }
            DownloadState.COMPLETED -> {
                b.btnQuickPrimary.setText(R.string.open)
                b.btnQuickPrimary.setOnClickListener { listener.onAction(item, Action.OPEN) }
                b.btnQuickSecondary.setText(R.string.open_folder)
                b.btnQuickSecondary.setOnClickListener { listener.onAction(item, Action.OPEN_FOLDER) }
            }
        }
        b.quickActions.visibility = View.VISIBLE

        b.root.setOnClickListener { listener.onTap(item) }
        b.root.setOnLongClickListener {
            listener.onLongPress(item)
            true
        }
    }

    private fun badgeText(item: DownloadItem, context: android.content.Context): String {
        return when (item.state) {
            DownloadState.PENDING -> context.getString(R.string.status_pending)
            DownloadState.DOWNLOADING -> context.getString(R.string.status_downloading)
            DownloadState.PAUSED -> context.getString(R.string.status_paused)
            DownloadState.COMPLETED -> context.getString(R.string.status_completed)
            DownloadState.CANCELLED -> context.getString(R.string.status_cancelled)
            DownloadState.FAILED -> context.getString(R.string.status_failed)
        }.let { base ->
            if (item.state == DownloadState.COMPLETED && item.monitor) {
                "$base · ${context.getString(R.string.status_monitor)}"
            } else {
                base
            }
        }
    }

    /** 7. Softer badge background for failed/cancelled: use red-bg tint. */
    private fun badgeBgColor(state: DownloadState): Int = when (state) {
        DownloadState.FAILED, DownloadState.CANCELLED -> R.color.status_off_soft
        else -> progressColor(state)
    }

    private fun badgeTextColor(state: DownloadState): Int = when (state) {
        DownloadState.FAILED, DownloadState.CANCELLED -> R.color.status_off
        else -> R.color.white
    }

    /** Animasi hanya untuk lompatan berarti; tick kecil 1-3% di-set langsung
     *  agar tidak membuat ObjectAnimator tiap detik per item aktif. */
    private fun smoothProgress(bar: android.widget.ProgressBar, from: Int, to: Int) {
        (bar.getTag(R.id.progress_animator) as? ObjectAnimator)?.cancel()
        if (from >= 0 && to > from && to - from > 3 && to - from <= 25) {
            val anim = ObjectAnimator.ofInt(bar, "progress", from, to)
                .setDuration(350)
            bar.setTag(R.id.progress_animator, anim)
            anim.start()
        } else {
            bar.progress = to
            bar.setTag(R.id.progress_animator, null)
        }
    }

    /** Warna lingkaran ikon per tipe file (lembut, kontras dengan ikon gelap). */
    private fun fileIconCircleColor(mime: String): Int {
        return when {
            mime.startsWith("video") -> R.color.icon_circle_video
            mime.startsWith("audio") -> R.color.icon_circle_audio
            mime.startsWith("image") -> R.color.icon_circle_image
            mime == "application/pdf" -> R.color.icon_circle_pdf
            mime == "application/vnd.android.package-archive" -> R.color.icon_circle_apk
            mime == "application/zip" ||
                mime == "application/x-rar-compressed" ||
                mime == "application/x-7z-compressed" -> R.color.icon_circle_archive
            else -> R.color.icon_circle_generic
        }
    }

    /** Pilih ikon tipe file berdasarkan mime (satu lookup per bind). */
    private fun fileIconRes(mime: String): Int {
        return when {
            mime.startsWith("video") -> R.drawable.ic_file_video
            mime.startsWith("audio") -> R.drawable.ic_file_audio
            mime.startsWith("image") -> R.drawable.ic_file_image
            mime == "application/pdf" -> R.drawable.ic_file_pdf
            mime == "application/zip" ||
                mime == "application/x-rar-compressed" ||
                mime == "application/x-7z-compressed" -> R.drawable.ic_file_archive
            else -> R.drawable.ic_file_generic
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1

        /** Bangun daftar ber-section: Active → Paused → Completed → Failed.
         *  Section yang collapse (StoragePrefs.collapsed_sections) memakai
         *  header dan menyembunyikan item-nya. */
        fun buildSections(context: android.content.Context, items: List<DownloadItem>): List<DownloadRow> {
            val rows = mutableListOf<DownloadRow>()
            fun addGroup(labelRes: Int, group: List<DownloadItem>) {
                if (group.isEmpty()) return
                val key = context.resources.getResourceEntryName(labelRes)
                val collapsed = StoragePrefs.isSectionCollapsed(context, key)
                rows.add(DownloadRow.Header(context.getString(labelRes), group.size, collapsed, key))
                if (!collapsed) group.forEach { rows.add(DownloadRow.Item(it)) }
            }
            // Satu pass pengelompokan (bukan 4x filter): dipanggil pada tiap
            // emisi StateFlow (progress/download aktif), hemat alokasi.
            val active = ArrayList<DownloadItem>(items.size)
            val paused = ArrayList<DownloadItem>()
            val completed = ArrayList<DownloadItem>()
            val failed = ArrayList<DownloadItem>()
            for (item in items) {
                when (item.state) {
                    DownloadState.DOWNLOADING, DownloadState.PENDING -> active.add(item)
                    DownloadState.PAUSED -> paused.add(item)
                    DownloadState.COMPLETED -> completed.add(item)
                    DownloadState.FAILED, DownloadState.CANCELLED -> failed.add(item)
                }
            }
            addGroup(R.string.section_active, active)
            addGroup(R.string.section_paused, paused)
            addGroup(R.string.section_completed, completed)
            addGroup(R.string.section_failed, failed)
            return rows
        }

        private val DIFF = object : DiffUtil.ItemCallback<DownloadRow>() {
            override fun areItemsTheSame(oldItem: DownloadRow, newItem: DownloadRow): Boolean {
                return when {
                    oldItem is DownloadRow.Header && newItem is DownloadRow.Header ->
                        oldItem.key == newItem.key
                    oldItem is DownloadRow.Item && newItem is DownloadRow.Item ->
                        oldItem.item.id == newItem.item.id
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItem: DownloadRow, newItem: DownloadRow): Boolean =
                oldItem == newItem
        }
    }
}
