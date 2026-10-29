package com.github.kr328.clash.service.clash.module

import com.github.kr328.clash.core.model.TaildropFile
import com.github.kr328.clash.core.model.TaildropStatus
import com.github.kr328.clash.core.model.TaildropTarget
import com.github.kr328.clash.core.model.TaildropTransfer
import com.github.kr328.clash.service.clash.module.TaildropNotificationState.Action.*
import org.junit.Assert.assertEquals
import org.junit.Test

class TaildropNotificationStateTest {
    private val file = TaildropFile("received.txt", 100)
    private val other = TaildropFile("other.txt", 200)
    private val transfer = TaildropTransfer("send-1", "sent.txt", "send", 100, 20, "sending")
    private val received = TaildropStatus(session = "session-1", running = true, files = listOf(file))
    private val state = TaildropNotificationState()

    @Test fun unchangedInboxDoesNotRepostAfterDismissal() {
        assertEquals(SHOW, state.update(received, false).inbox)
        repeat(5) { assertEquals(KEEP, state.update(received, false).inbox) }
    }

    @Test fun peerAndErrorChangesDoNotRepostNotifications() {
        val status = received.copy(transfers = listOf(transfer.copy(state = "done", done = 100)))
        state.update(status, false)
        val update = state.update(status.copy(
            targets = listOf(TaildropTarget("peer", "phone", true)), error = "temporary error"
        ), false)
        assertEquals(KEEP, update.inbox)
        assertEquals(KEEP, update.transfers)
    }

    @Test fun transferProgressDoesNotRepostInbox() {
        val status = received.copy(transfers = listOf(transfer))
        state.update(status, false)
        val update = state.update(status.copy(transfers = listOf(transfer.copy(done = 40))), false)
        assertEquals(KEEP, update.inbox)
        assertEquals(SHOW, update.transfers)
    }

    @Test fun incomingProgressDoesNotRepostInbox() {
        state.update(received, false)
        val update = state.update(received.copy(incoming = listOf(transfer.copy(direction = "receive"))), false)
        assertEquals(KEEP, update.inbox)
        assertEquals(SHOW, update.transfers)
    }

    @Test fun newFileNotifiesEvenWhenCountIsUnchanged() {
        state.update(received, false)
        assertEquals(SHOW, state.update(received.copy(files = listOf(other)), false).inbox)
    }

    @Test fun reorderingFilesAndTransfersDoesNotRepost() {
        val status = received.copy(files = listOf(file, other), transfers = listOf(
            transfer, transfer.copy(id = "send-2")
        ))
        state.update(status, false)
        val update = state.update(status.copy(
            files = status.files.reversed(), transfers = status.transfers.reversed()
        ), false)
        assertEquals(KEEP, update.inbox)
        assertEquals(KEEP, update.transfers)
    }

    @Test fun deletionUpdatesVisibleInboxButDoesNotResurfaceDismissedInbox() {
        val status = received.copy(files = listOf(file, other))
        state.update(status, false)
        assertEquals(KEEP, state.update(received, false).inbox)
        state.update(status, false)
        assertEquals(SHOW, state.update(received, true).inbox)
        assertEquals(CANCEL, state.update(received.copy(files = emptyList()), true).inbox)
    }

    @Test fun inboxChangesDoNotRepostFinishedTransfers() {
        val status = received.copy(transfers = listOf(transfer.copy(state = "done", done = 100)))
        state.update(status, false)
        val update = state.update(status.copy(files = listOf(file, other)), false)
        assertEquals(SHOW, update.inbox)
        assertEquals(KEEP, update.transfers)
    }

    @Test fun reconnectDoesNotRepostExistingInboxOrFinishedTransfers() {
        val status = received.copy(transfers = listOf(transfer.copy(state = "done", done = 100)))
        state.update(status, false)
        val stopped = state.update(TaildropStatus(), true)
        assertEquals(CANCEL, stopped.inbox)
        assertEquals(CANCEL, stopped.transfers)
        assertEquals(TaildropNotificationState.Update(), state.update(TaildropStatus(), false))
        val restored = state.update(status, false)
        assertEquals(KEEP, restored.inbox)
        assertEquals(KEEP, restored.transfers)
    }

    @Test fun reconnectRestoresActiveTransferNotification() {
        val status = received.copy(transfers = listOf(transfer))
        state.update(status, false)
        state.update(TaildropStatus(), true)
        val restored = state.update(status, false)
        assertEquals(KEEP, restored.inbox)
        assertEquals(SHOW, restored.transfers)
    }

    @Test fun arrivalsDuringReconnectNotify() {
        state.update(received, false)
        state.update(TaildropStatus(), true)
        assertEquals(SHOW, state.update(received.copy(files = listOf(file, other)), false).inbox)
    }

    @Test fun newSessionDoesNotInheritPreviousInbox() {
        state.update(received, false)
        assertEquals(SHOW, state.update(received.copy(session = "session-2"), false).inbox)
        assertEquals(CANCEL, state.update(received.copy(session = "session-3", files = emptyList()), true).inbox)
    }
}
