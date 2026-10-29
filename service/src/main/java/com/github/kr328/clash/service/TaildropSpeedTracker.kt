package com.github.kr328.clash.service

import com.github.kr328.clash.core.model.TaildropStatus
import com.github.kr328.clash.core.model.TaildropTransfer
import java.util.ArrayDeque

internal class TaildropSpeedTracker {
    private data class Sample(val time: Long, val bytes: Long)
    private class Window {
        val samples = ArrayDeque<Sample>()
        var speed = -1L
    }
    private var session = ""
    private val windows = mutableMapOf<String, Window>()

    fun update(status: TaildropStatus, now: Long): TaildropStatus {
        if (session != status.session || !status.running) windows.clear()
        session = status.session
        val active = mutableSetOf<String>()
        fun measure(item: TaildropTransfer): TaildropTransfer {
            if (!status.running || item.finished || item.state == "queued") return item.copy(speed = -1)
            val key = item.direction + ":" + item.id
            active += key
            val window = windows.getOrPut(key) { Window() }
            val samples = window.samples
            if (samples.isNotEmpty() && (now < samples.last.time || item.done < samples.last.bytes)) {
                samples.clear()
                window.speed = -1
            }
            if (samples.isEmpty() || now - samples.last.time >= 500) {
                samples.addLast(Sample(now, item.done))
                while (samples.size > 2 && now - samples.first.time > 3000) samples.removeFirst()
                val elapsed = now - samples.first.time
                if (elapsed >= 500) window.speed = ((item.done - samples.first.bytes).toDouble() * 1000 / elapsed).toLong().coerceAtLeast(0)
            }
            return item.copy(speed = window.speed)
        }
        val result = status.copy(incoming = status.incoming.map(::measure), transfers = status.transfers.map(::measure))
        windows.keys.retainAll(active)
        return result
    }
}
