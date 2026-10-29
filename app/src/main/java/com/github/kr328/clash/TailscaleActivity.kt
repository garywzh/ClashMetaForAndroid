package com.github.kr328.clash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import com.github.kr328.clash.core.model.TailscaleSettings
import com.github.kr328.clash.core.model.TailscaleStatus
import com.github.kr328.clash.design.TailscaleDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.util.withClash
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.Json

class TailscaleActivity : BaseActivity<TailscaleDesign>() {
    private val json = Json { ignoreUnknownKeys = true }
    private var pendingLogin = false
    private var refreshQueued = false

    private suspend fun command(command: String, content: String = ""): TailscaleStatus =
        json.decodeFromString(TailscaleStatus.serializer(), withClash { tailscaleCommand(command, content) })

    override suspend fun main() {
        var status = command("status")
        val design = TailscaleDesign(this, status)
        setContentDesign(design)
        launch {
            while (isActive) {
                delay(1500)
                if (activityStarted && !refreshQueued) refreshQueued = design.requests.trySend(TailscaleDesign.Request.Refresh).isSuccess
            }
        }
        while (isActive) {
            select<Unit> {
                events.onReceive { }
                design.requests.onReceive { request ->
                    if (request == TailscaleDesign.Request.Refresh) refreshQueued = false
                    try {
                        when (request) {
                            is TailscaleDesign.Request.Save -> {
                                status = command("status")
                                status = command("settings", json.encodeToString(TailscaleSettings.serializer(),
                                    TailscaleSettings(request.enabled, status.acceptRoutes)))
                                if (!status.enabled) pendingLogin = false
                            }
                            TailscaleDesign.Request.Login -> {
                                pendingLogin = true
                                status = command("login")
                            }
                            TailscaleDesign.Request.Logout -> {
                                MaterialAlertDialogBuilder(this@TailscaleActivity)
                                    .setMessage(com.github.kr328.clash.design.R.string.tailscale_logout_confirm)
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .setPositiveButton(android.R.string.ok) { _, _ ->
                                        design.requests.trySend(TailscaleDesign.Request.ConfirmLogout)
                                    }.show()
                            }
                            TailscaleDesign.Request.ConfirmLogout -> {
                                pendingLogin = false
                                status = command("logout")
                            }
                            TailscaleDesign.Request.CopyAddress -> {
                                val ip = status.ips.firstOrNull { !it.contains(':') } ?: status.ips.firstOrNull()
                                if (ip != null) {
                                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                                        .setPrimaryClip(ClipData.newPlainText("Tailscale", ip))
                                }
                            }
                            TailscaleDesign.Request.Refresh -> status = command("status")
                            TailscaleDesign.Request.Taildrop -> startActivity(Intent(this@TailscaleActivity, TaildropActivity::class.java))
                            TailscaleDesign.Request.PortForwarding -> startActivity(Intent(this@TailscaleActivity, PortForwardingActivity::class.java))
                            TailscaleDesign.Request.SubnetRoutes -> startActivity(Intent(this@TailscaleActivity, SubnetRoutesActivity::class.java))
                        }
                        design.update(status)
                        if (status.error.isNotEmpty()) pendingLogin = false
                        if (pendingLogin && status.authUrl.isNotEmpty()) {
                            val uri = Uri.parse(status.authUrl)
                            require(uri.scheme == "https" && uri.host == "login.tailscale.com") { "Invalid Tailscale login URL" }
                            pendingLogin = false
                            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
                        }
                        if (status.state == "Running") pendingLogin = false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        pendingLogin = false
                        design.update(status)
                        design.showToast(e.message ?: "Tailscale", ToastDuration.Long)
                    }
                }
            }
        }
    }
}
