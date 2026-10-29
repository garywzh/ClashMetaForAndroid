package com.github.kr328.clash.service.clash.module

import com.github.kr328.clash.core.model.TaildropStatus

internal class TaildropNotificationState {
    enum class Action { KEEP, SHOW, CANCEL }

    data class Update(val inbox: Action = Action.KEEP, val transfers: Action = Action.KEEP)

    private var lastRunning: TaildropStatus? = null
    private var running = false

    fun update(status: TaildropStatus, inboxVisible: Boolean): Update {
        if (!status.running) {
            val wasRunning = running
            running = false
            // Retain the last successful snapshot: reconnecting is not a new delivery.
            return if (wasRunning) Update(Action.CANCEL, Action.CANCEL) else Update()
        }

        val previous = lastRunning?.takeIf { it.session == status.session }
        val files = status.files.toSet()
        val oldFiles = previous?.files?.toSet().orEmpty()
        val inbox = when {
            files.isEmpty() && previous == null -> Action.CANCEL
            files == oldFiles -> Action.KEEP
            files.isEmpty() -> Action.CANCEL
            (files - oldFiles).isNotEmpty() || inboxVisible -> Action.SHOW
            else -> Action.KEEP
        }
        val transfersChanged = previous == null ||
            status.transfers.sortedBy { it.id } != previous.transfers.sortedBy { it.id } ||
            status.incoming.sortedBy { it.id } != previous.incoming.sortedBy { it.id }
        val hasActive = (status.transfers + status.incoming).any { !it.finished }
        val transfers = when {
            !transfersChanged && (running || !hasActive) -> Action.KEEP
            hasActive || status.transfers.any { it.finished } -> Action.SHOW
            else -> Action.CANCEL
        }
        lastRunning = status
        running = true
        return Update(inbox, transfers)
    }
}
