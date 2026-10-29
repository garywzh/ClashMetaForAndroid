package com.github.kr328.clash

import android.content.Intent
import com.github.kr328.clash.core.model.TailscaleSettings
import com.github.kr328.clash.core.model.TailscaleStatus
import com.github.kr328.clash.design.SubnetRoutesDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.Json

class SubnetRoutesActivity : BaseActivity<SubnetRoutesDesign>() {
    private val json = Json { ignoreUnknownKeys = true }
    private var refreshQueued = false

    private suspend fun command(name: String, content: String = ""): TailscaleStatus =
        json.decodeFromString(TailscaleStatus.serializer(), withClash { tailscaleCommand(name, content) })

    override suspend fun main() {
        var status = command("status")
        val design = SubnetRoutesDesign(this, status)
        setContentDesign(design)
        launch {
            while (isActive) {
                delay(1500)
                if (activityStarted && !refreshQueued) {
                    refreshQueued = design.requests.trySend(SubnetRoutesDesign.Request.Refresh).isSuccess
                }
            }
        }
        while (isActive) {
            select<Unit> {
                events.onReceive { }
                design.requests.onReceive { request ->
                    if (request == SubnetRoutesDesign.Request.Refresh) refreshQueued = false
                    try {
                        when (request) {
                            is SubnetRoutesDesign.Request.Save -> {
                                // Preserve the other setting from the latest backend snapshot.
                                status = command("status")
                                status = command("settings", json.encodeToString(TailscaleSettings.serializer(),
                                    TailscaleSettings(status.enabled, request.acceptRoutes)))
                            }
                            SubnetRoutesDesign.Request.Refresh -> status = command("status")
                            SubnetRoutesDesign.Request.Configure -> startActivity(Intent(this@SubnetRoutesActivity, TailscaleActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        }
                        design.update(status)
                        if (request is SubnetRoutesDesign.Request.Save && status.error.isNotBlank()) {
                            design.showToast(status.error, ToastDuration.Long)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        design.update(status)
                        design.showToast(e.message ?: "Tailscale", ToastDuration.Long)
                    }
                }
            }
        }
    }
}
