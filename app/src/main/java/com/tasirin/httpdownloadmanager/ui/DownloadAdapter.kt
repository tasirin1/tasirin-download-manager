package com.tasirin.httpdownloadmanager.ui

import android.content.res.ColorStateList
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
import com.tasirin.httpdownloadmanager.databinding.ItemDownloadBinding
import java.io.File
import java.util.Locale

class DownloadAdapter(private val listener: Listener) :
    ListAdapter<DownloadItem, DownloadAdapter.ViewHolder>(DIFF) {

    enum class Action { PAUSE, RESUME, CANCEL, DELETE, OPEN, OPEN_FOLDER, MONITOR }

    interface Listener {
        fun onAction(item: DownloadItem, action: Action)
        fun onTap(item: DownloadItem)
        fun onLongPress(item: DownloadItem)
    }

    class ViewHolder(val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root)

    private fun progressColor(state: DownloadState): Int = when (state) {
        DownloadState.PENDING, DownloadState.DOWNLOADING -> R.color.primary
        DownloadState.COMPLETED -> R.color.status_on
        DownloadState.FAILED -> R.color.status_off
        DownloadState.PAUSED, DownloadState.CANCELLED -> R.color.text_secondary
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.progressBar.max = 100
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val b = holder.binding

        b.textName.text = item.fileName
        b.textStatus.text = statusText(item, b.root.context)
        b.progressBar.progress = item.progressPercent
        b.progressBar.progressTintList = ColorStateList.valueOf(
            ContextCompat.getColor(b.root.context, progressColor(item.state))
        )

        b.textProgress.text = if (item.totalBytes > 0) {
            String.format(
                Locale.US, "%d%% \u2022 %s / %s",
                item.progressPercent,
                Formats.bytes(item.bytesDownloaded),
                Formats.bytes(item.totalBytes)
            )
        } else if (item.progressPercentOverride >= 0) {
            // HLS: total asli tidak diketahui — tampilkan persen + byte riil
            // tanpa denominator palsu (mis. "56% • 4.0 MB").
            String.format(
                Locale.US, "%d%% \u2022 %s",
                item.progressPercent,
                Formats.bytes(item.bytesDownloaded)
            )
        } else {
            Formats.bytes(item.bytesDownloaded)
        }

        val showSpeed = item.state == DownloadState.DOWNLOADING && item.speedBps > 0
        b.textSpeed.visibility = if (showSpeed) View.VISIBLE else View.GONE
        if (showSpeed) {
            b.textSpeed.text = b.root.context.getString(
                R.string.speed_and_eta,
                Formats.speed(item.speedBps),
                Formats.eta(item.etaSeconds)
            )
        }

        b.textChecksumOk.visibility =
            if (item.state == DownloadState.COMPLETED && item.checksumVerified) {
                View.VISIBLE
            } else {
                View.GONE
            }
        if (item.state == DownloadState.COMPLETED) {
            val context = b.root.context
            val location = when {
                !item.filePath.isNullOrEmpty() -> {
                    File(item.filePath).parent ?: item.filePath
                }
                !item.contentUri.isNullOrEmpty() ->
                    context.getString(R.string.location_media_store)
                else -> context.getString(R.string.location_internal)
            }
            b.textLocation.text = context.getString(R.string.location_label, location)
            b.textLocation.visibility = View.VISIBLE
        } else {
            b.textLocation.visibility = View.GONE
        }

        b.root.setOnClickListener { listener.onTap(item) }
        b.root.setOnLongClickListener {
            listener.onLongPress(item)
            true
        }
    }

    private fun statusText(item: DownloadItem, context: android.content.Context): String {
        return when (item.state) {
            DownloadState.PENDING ->
                item.error ?: context.getString(R.string.status_pending)
            DownloadState.DOWNLOADING -> context.getString(R.string.status_downloading)
            DownloadState.PAUSED -> context.getString(R.string.status_paused)
            DownloadState.COMPLETED -> context.getString(R.string.status_completed)
            DownloadState.CANCELLED -> context.getString(R.string.status_cancelled)
            DownloadState.FAILED -> item.error ?: context.getString(R.string.status_failed)
        }.let { base ->
            if (item.state == DownloadState.COMPLETED && item.monitor) {
                "$base · ${context.getString(R.string.status_monitor)}"
            } else {
                base
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DownloadItem>() {
            override fun areItemsTheSame(oldItem: DownloadItem, newItem: DownloadItem) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: DownloadItem, newItem: DownloadItem) =
                oldItem == newItem
        }
    }
}
