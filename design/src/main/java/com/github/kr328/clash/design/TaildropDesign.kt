package com.github.kr328.clash.design

import android.content.Context
import android.text.format.Formatter
import android.view.View
import com.github.kr328.clash.core.model.TaildropFile
import com.github.kr328.clash.core.model.TaildropStatus
import com.github.kr328.clash.core.model.TaildropTransfer
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.util.*

class TaildropDesign(context: Context) : Design<TaildropDesign.Request>(context) {
    sealed interface Request {
        data object Send : Request
        data object Refresh : Request
        data object Dismiss : Request
        data object Configure : Request
        data class Cancel(val transfer: TaildropTransfer) : Request
        data class Open(val file: TaildropFile) : Request
    }

    private val binding = DesignSettingsCommonBinding.inflate(context.layoutInflater, context.root, false)
    override val root: View get() = binding.root
    private lateinit var statusRow: ClickablePreference
    private lateinit var sendRow: ClickablePreference
    private lateinit var dismissRow: ClickablePreference
    private val transfers = preferenceScreen(context) {}
    private val inbox = preferenceScreen(context) {}
    private var transferKeys = emptyList<String>()
    private var transferRows = emptyList<ClickablePreference>()
    private var inboxFiles = emptyList<TaildropFile>()
    private var inboxInitialized = false

    init {
        binding.surface = surface
        binding.activityBarLayout.applyFrom(context)
        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)
        val screen = preferenceScreen(context) {
            statusRow = clickable(R.string.tailscale) { clicked { requests.trySend(Request.Configure) } }
            sendRow = clickable(R.string.taildrop_send) { clicked { requests.trySend(Request.Send) } }
            clickable(R.string.taildrop_refresh) { clicked { requests.trySend(Request.Refresh) } }
            category(R.string.taildrop_transfers)
            root.addView(transfers.root)
            dismissRow = clickable(R.string.taildrop_clear_finished) { clicked { requests.trySend(Request.Dismiss) } }
            category(R.string.taildrop_inbox)
            root.addView(inbox.root)
        }
        binding.content.addView(screen.root)
    }

    fun update(status: TaildropStatus, selected: Int = 0) {
        statusRow.view.visibility = if (!status.running || status.error.isNotBlank()) View.VISIBLE else View.GONE
        statusRow.summary = if (!status.running) context.getString(R.string.taildrop_start_vpn) else status.error
        sendRow.enabled = status.running && status.targets.any { it.online }
        sendRow.summary = when {
            selected > 0 -> context.getString(R.string.taildrop_selected, selected)
            status.running && status.targets.none { it.online } -> context.getString(R.string.taildrop_no_devices)
            else -> null
        }
        val items = status.incoming + status.transfers.asReversed()
        val keys = items.map { it.direction + ":" + it.id }
        if (keys != transferKeys) {
            transferKeys = keys
            transfers.root.removeAllViews()
            transferRows = items.map { item ->
                transfers.clickable(R.string.taildrop_transfers) {
                    title = item.name
                    clicked { requests.trySend(Request.Cancel(item)) }
                }
            }
        }
        if (items.isEmpty() && transfers.root.childCount == 0) {
            transfers.clickable(R.string.taildrop_no_transfers) { view.isClickable = false; view.isFocusable = false }
        }
        items.zip(transferRows).forEach { (item, row) ->
            val state = when (item.state) {
                "queued" -> R.string.taildrop_queued
                "sending" -> R.string.taildrop_sending
                "receiving" -> R.string.taildrop_receiving
                "saving" -> R.string.taildrop_saving
                "done" -> if (item.direction == "send") R.string.taildrop_sent else R.string.taildrop_saved
                "cancelled" -> R.string.taildrop_cancelled
                else -> R.string.taildrop_failed
            }
            val progress = if (item.size >= 0) "${size(item.done)} / ${size(item.size)}" else size(item.done)
            row.summary = listOf(context.getString(state), progress, item.error).filter { it.isNotBlank() }.joinToString(" · ")
            if (!item.finished) row.summary = "${row.summary} · ${context.getString(android.R.string.cancel)}"
            row.view.isClickable = !item.finished
            row.view.isFocusable = !item.finished
        }
        dismissRow.view.visibility = if (status.transfers.any { it.finished }) View.VISIBLE else View.GONE
        if (!inboxInitialized || status.files != inboxFiles) {
            inboxInitialized = true
            inboxFiles = status.files
            inbox.root.removeAllViews()
            status.files.forEach { file ->
                inbox.clickable(R.string.taildrop_inbox) {
                    title = file.name
                    summary = size(file.size)
                    clicked { requests.trySend(Request.Open(file)) }
                }
            }
            if (status.files.isEmpty()) inbox.clickable(R.string.taildrop_empty) { view.isClickable = false; view.isFocusable = false }
        }
    }

    private fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0))
}
