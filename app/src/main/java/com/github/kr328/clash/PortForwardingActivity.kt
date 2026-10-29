package com.github.kr328.clash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import com.github.kr328.clash.core.model.TailscaleForwardRule
import com.github.kr328.clash.core.model.TailscaleStatus
import com.github.kr328.clash.design.PortForwardingDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class PortForwardingActivity : BaseActivity<PortForwardingDesign>() {
    private val json = Json { ignoreUnknownKeys = true }
    private var editorDraft: Bundle? = null
    private var refreshQueued = false

    override fun onCreate(savedInstanceState: Bundle?) {
        editorDraft = savedInstanceState?.getBundle("editor")
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle("editor", design?.saveEditor())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        design?.dismissDialogs()
        super.onDestroy()
    }

    private suspend fun command(command: String, content: String = "") = json.decodeFromString(
        TailscaleStatus.serializer(), withClash { tailscaleCommand(command, content) }
    )

    override suspend fun main() {
        var status = command("status")
        val design = PortForwardingDesign(this)
        setContentDesign(design)
        design.update(status)
        design.restoreEditor(editorDraft)
        editorDraft = null
        launch {
            while (isActive) {
                delay(1500)
                if (activityStarted && !refreshQueued) {
                    refreshQueued = design.requests.trySend(PortForwardingDesign.Request.Refresh).isSuccess
                }
            }
        }
        while (isActive) {
            select<Unit> {
                events.onReceive { }
                design.requests.onReceive { request ->
                    if (request == PortForwardingDesign.Request.Refresh) refreshQueued = false
                    try {
                        var rules: List<TailscaleForwardRule>? = null
                        when (request) {
                            is PortForwardingDesign.Request.Put -> {
                                val existing = status.forwards.any { it.id == request.rule.id }
                                rules = if (existing) status.forwards.map { if (it.id == request.rule.id) request.rule else it }
                                    else status.forwards + request.rule
                            }
                            is PortForwardingDesign.Request.Delete -> rules = status.forwards.filterNot { it.id == request.id }
                            is PortForwardingDesign.Request.Copy -> {
                                val ip = status.ips.firstOrNull { !it.contains(':') } ?: status.ips.firstOrNull()
                                if (ip != null) {
                                    val host = if (ip.contains(':')) "[$ip]" else ip
                                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                                        ClipData.newPlainText(request.rule.name, "$host:${request.rule.tailnetPort}")
                                    )
                                }
                            }
                            PortForwardingDesign.Request.Configure -> {
                                startActivity(Intent(this@PortForwardingActivity, TailscaleActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                            }
                            PortForwardingDesign.Request.Refresh -> status = command("status")
                        }
                        if (rules != null) {
                            status = command("forwards", json.encodeToString(ListSerializer(TailscaleForwardRule.serializer()), rules))
                            if (status.error.isNotBlank()) design.showToast(status.error, ToastDuration.Long)
                        }
                        design.update(status)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        design.showToast(e.message ?: "Tailscale", ToastDuration.Long)
                    }
                }
            }
        }
    }
}
