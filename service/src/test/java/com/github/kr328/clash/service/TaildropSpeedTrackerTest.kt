package com.github.kr328.clash.service

import com.github.kr328.clash.core.model.TaildropStatus
import com.github.kr328.clash.core.model.TaildropTransfer
import org.junit.Assert.*
import org.junit.Test

class TaildropSpeedTrackerTest {
    private val tracker = TaildropSpeedTracker()
    private fun status(done: Long, session: String = "session") = TaildropStatus(
        session = session, running = true,
        incoming = listOf(TaildropTransfer("file", "test.bin", "receive", 10000, done, "receiving")),
    )
    private fun speed(done: Long, time: Long) = tracker.update(status(done), time).incoming.single().speed

    @Test fun firstSampleDoesNotInventASpeedOrCountResumedBytes() {
        assertEquals(-1L, speed(5000, 1000))
        assertEquals(1000L, speed(6000, 2000))
    }

    @Test fun nearbyReadersReuseTheSharedSample() {
        speed(0, 0)
        assertEquals(1000L, speed(1000, 1000))
        assertEquals(1000L, speed(1300, 1100))
    }

    @Test fun stalledTransferDecaysToZero() {
        speed(0, 0)
        speed(1000, 1000)
        speed(1000, 2000)
        speed(1000, 3000)
        assertEquals(0L, speed(1000, 4000))
    }

    @Test fun elapsedTimeRatherThanPollingIntervalDeterminesSpeed() {
        speed(0, 0)
        assertEquals(1000L, speed(2500, 2500))
    }

    @Test fun retryOrClockResetDoesNotProduceNegativeSpeed() {
        speed(4000, 1000)
        assertEquals(-1L, speed(10, 2000))
        assertEquals(1000L, speed(1010, 3000))
        assertEquals(-1L, speed(2010, 500))
    }

    @Test fun sessionsDoNotShareMeasurements() {
        speed(0, 0)
        speed(1000, 1000)
        val result = tracker.update(status(5000, "new-session"), 2000)
        assertEquals(-1L, result.incoming.single().speed)
    }

    @Test fun completedAndQueuedTransfersHaveNoSpeed() {
        val initial = status(0)
        tracker.update(initial, 0)
        for (state in listOf("done", "failed", "cancelled", "queued")) {
            val result = tracker.update(initial.copy(incoming = initial.incoming.map { it.copy(state = state) }), 1000)
            assertEquals(-1L, result.incoming.single().speed)
        }
    }
}
