package com.github.kr328.clash.design

import android.content.Context
import android.text.format.Formatter
import android.view.View
import androidx.appcompat.widget.PopupMenu
import com.github.kr328.clash.core.model.TaildropFile
import com.github.kr328.clash.core.model.TaildropOffer
import com.github.kr328.clash.core.model.TaildropStatus
import com.github.kr328.clash.core.model.TaildropTransfer
import com.github.kr328.clash.design.databinding.DesignTailnetBinding
import com.github.kr328.clash.design.databinding.ViewTaildropLocationBinding
import com.github.kr328.clash.design.databinding.AdapterTaildropTransferBinding
import com.github.kr328.clash.design.databinding.AdapterTaildropOfferBinding
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.util.*
import com.github.kr328.clash.service.R as ServiceR

class TaildropDesign(context: Context) : Design<TaildropDesign.Request>(context) {
    sealed interface Request {
        data object Send : Request
        data object Refresh : Request
        data object Dismiss : Request
        data object Configure : Request
        data class TransferActions(val transfer: TaildropTransfer) : Request
        data class FileActions(val file: TaildropFile) : Request
        data class Decide(val offer: TaildropOffer, val accept: Boolean) : Request
    }

    private val binding = DesignTailnetBinding.inflate(context.layoutInflater, context.root, false)
    override val root: View get() = binding.root
    private lateinit var statusRow: ClickablePreference
    private lateinit var noticeRow: ClickablePreference
    private val location = ViewTaildropLocationBinding.inflate(context.layoutInflater, binding.content, false)
    private var hasFinished = false
    private val transfers = preferenceScreen(context) {}
    private val inbox = preferenceScreen(context) {}
    private val offers = preferenceScreen(context) {}
    private var pendingOffers = emptyList<TaildropOffer>()
    private var transferKeys = emptyList<String>()
    private var transferRows = emptyList<AdapterTaildropTransferBinding>()
    private var inboxFiles = emptyList<TaildropFile>()
    private var inboxInitialized = false

    init {
        binding.surface = surface
        binding.activityBarLayout.applyFrom(context)
        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)
        binding.primaryAction.apply {
            visibility = View.VISIBLE
            setText(R.string.taildrop_send_action)
            contentDescription = context.getString(R.string.taildrop_send)
            setOnClickListener { requests.trySend(Request.Send) }
        }
        binding.moreAction.visibility = View.VISIBLE
        binding.moreAction.setOnClickListener {
            PopupMenu(context, binding.moreAction).apply {
                menu.add(0, R.string.taildrop_refresh, 0, R.string.taildrop_refresh)
                if (hasFinished) menu.add(0, R.string.taildrop_clear_finished, 1, R.string.taildrop_clear_finished)
                setOnMenuItemClickListener {
                    requests.trySend(if (it.itemId == R.string.taildrop_refresh) Request.Refresh else Request.Dismiss)
                    true
                }
                show()
            }
        }
        val screen = preferenceScreen(context) {
            statusRow = clickable(R.string.tailscale) { clicked { requests.trySend(Request.Configure) } }
            noticeRow = clickable(R.string.taildrop_no_devices) { view.isClickable = false; view.isFocusable = false }
            root.addView(offers.root)
            root.addView(transfers.root)
            root.addView(inbox.root)
            root.addView(location.root)
        }
        binding.content.addView(screen.root)
    }

    fun update(status: TaildropStatus, selected: Int = 0) {
        if (status.offers != pendingOffers) {
            pendingOffers = status.offers
            offers.root.removeAllViews()
            status.offers.forEach { offer ->
                val row = AdapterTaildropOfferBinding.inflate(context.layoutInflater, offers.root, false)
                row.titleView.text = offer.name
                row.summaryView.text = listOf(offer.sender, if (offer.size >= 0) size(offer.size)
                    else context.getString(R.string.taildrop_unknown_size)).joinToString(" · ")
                row.acceptView.setOnClickListener { requests.trySend(Request.Decide(offer, true)) }
                row.rejectView.setOnClickListener { requests.trySend(Request.Decide(offer, false)) }
                offers.root.addView(row.root)
            }
        }
        statusRow.view.visibility = if (!status.running || status.error.isNotBlank()) View.VISIBLE else View.GONE
        statusRow.summary = if (!status.running) context.getString(R.string.taildrop_start_vpn) else status.error
        binding.primaryAction.isEnabled = status.running && status.targets.any { it.online }
        noticeRow.view.visibility = if (selected > 0 || (status.running && status.targets.none { it.online })) View.VISIBLE else View.GONE
        noticeRow.title = when {
            selected > 0 -> context.getString(R.string.taildrop_selected, selected)
            else -> context.getString(R.string.taildrop_no_devices)
        }
        // Automatic Downloads jobs become saved files, not a second completion row.
        val visibleTransfers = status.transfers.filter { it.direction != "download" || !it.finished }
        val items = (status.incoming + visibleTransfers.asReversed()).sortedBy { it.finished }
        val keys = items.map { it.direction + ":" + it.id }
        if (keys != transferKeys) {
            transferKeys = keys
            transfers.root.removeAllViews()
            transferRows = items.map { item ->
                AdapterTaildropTransferBinding.inflate(context.layoutInflater, transfers.root, false).also { row ->
                    row.titleView.text = item.name
                    row.root.setOnClickListener { requests.trySend(Request.TransferActions(item)) }
                    transfers.root.addView(row.root)
                }
            }
        }
        items.zip(transferRows).forEach { (item, row) ->
            val state = when (item.state) {
                "queued" -> R.string.taildrop_queued
                "sending" -> R.string.taildrop_sending
                "receiving" -> R.string.taildrop_receiving
                "saving" -> if (item.direction == "download") R.string.taildrop_saving_download else R.string.taildrop_saving
                "done" -> when (item.direction) {
                    "send" -> R.string.taildrop_sent
                    "download" -> ServiceR.string.taildrop_download_saved
                    else -> R.string.taildrop_saved
                }
                "cancelled" -> R.string.taildrop_cancelled
                else -> R.string.taildrop_failed
            }
            val progress = if (item.size >= 0) "${size(item.done)} / ${size(item.size)}" else size(item.done)
            val speed = if (!item.finished && item.state != "queued") {
                if (item.speed >= 0) "${size(item.speed)}/s" else "…"
            } else ""
            val percent = if (!item.finished && item.size > 0) ((item.done.toDouble() / item.size) * 100).toInt().coerceIn(0, 100) else null
            val detail = listOf(progress, speed, percent?.let { "$it%" }.orEmpty(), item.error).filter { it.isNotBlank() }.joinToString(" · ")
            val stateText = context.getString(state)
            row.summaryView.text = "$stateText\n$detail"
            row.progressView.visibility = if (item.finished || item.state == "queued") View.GONE else View.VISIBLE
            row.progressView.isIndeterminate = item.size < 0
            row.progressView.progress = percent ?: 0
            row.root.isClickable = !item.finished
            row.root.isFocusable = !item.finished
        }
        hasFinished = visibleTransfers.any { it.finished }
        location.root.visibility = if (status.files.any { it.state == "saved" }) View.VISIBLE else View.GONE
        if (!inboxInitialized || status.files != inboxFiles) {
            inboxInitialized = true
            inboxFiles = status.files
            inbox.root.removeAllViews()
            status.files.forEach { file ->
                inbox.clickable(R.string.taildrop_inbox) {
                    title = file.name
                    summary = listOf(size(file.size), when (file.state) {
                        "saved" -> context.getString(R.string.taildrop_file_saved)
                        "failed" -> file.error
                        else -> context.getString(R.string.taildrop_waiting_download)
                    }).filter { it.isNotBlank() }.joinToString(" · ")
                    clicked { requests.trySend(Request.FileActions(file)) }
                }
            }
            if (status.files.isEmpty()) inbox.clickable(R.string.taildrop_empty) { view.isClickable = false; view.isFocusable = false }
        }
        inbox.root.visibility = if (status.files.isNotEmpty() || (items.isEmpty() && status.offers.isEmpty())) View.VISIBLE else View.GONE
    }

    private fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0))
}
