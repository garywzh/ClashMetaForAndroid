package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.core.Clash
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

class TailscaleRouteModule(service: Service, initial: List<String>) : Module<List<String>>(service) {
    private var routes = initial

    override suspend fun run() {
        val changes = receiveBroadcast {
            addAction(Intents.ACTION_OVERRIDE_CHANGED)
        }
        while (currentCoroutineContext().isActive) {
            // Read only the native snapshot, without querying the control plane.
            val status = Clash.queryTailscaleRouting()
            val next = if (status.enabled && status.acceptRoutes) status.subnetRoutes else emptyList()
            if (next != routes) {
                routes = next
                enqueueEvent(next)
            }
            if (status.enabled && status.acceptRoutes) {
                withTimeoutOrNull(1000) { changes.receive() }
            } else {
                changes.receive()
            }
        }
    }
}
