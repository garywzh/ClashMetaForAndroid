package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.core.model.TailscaleSettings
import com.github.kr328.clash.core.model.TailscaleStatus
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

class TailscaleDesign(context: Context, initial: TailscaleStatus) : Design<TailscaleDesign.Request>(context) {
    sealed interface Request {
        data class Save(val settings: TailscaleSettings) : Request
        data object Login : Request
        data object Logout : Request
        data object ConfirmLogout : Request
        data object CopyAddress : Request
        data object Refresh : Request
    }

    private val binding = DesignSettingsCommonBinding.inflate(context.layoutInflater, context.root, false)
    override val root: View get() = binding.root

    private var enabled = initial.enabled
    private var mcpEnabled = initial.mcpEnabled
    private var acceptRoutes = initial.acceptRoutes
    private lateinit var enabledSwitch: SwitchPreference
    private lateinit var routesSwitch: SwitchPreference
    private lateinit var routesRow: ClickablePreference
    private lateinit var statusRow: ClickablePreference
    private lateinit var addressRow: ClickablePreference
    private lateinit var loginRow: ClickablePreference
    private lateinit var logoutRow: ClickablePreference
    private lateinit var forwardSwitch: SwitchPreference

    init {
        binding.surface = surface
        binding.activityBarLayout.applyFrom(context)
        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)
        val screen = preferenceScreen(context) {
            enabledSwitch = switch(value = ::enabled, title = R.string.tailscale_enable) {
                listener = OnChangedListener { save() }
            }
            statusRow = clickable(title = R.string.tailscale_status) {
                view.isClickable = false
                view.isFocusable = false
            }
            addressRow = clickable(title = R.string.tailscale_address) {
                clicked { requests.trySend(Request.CopyAddress) }
            }
            loginRow = clickable(title = R.string.tailscale_login) {
                clicked { requests.trySend(Request.Login) }
            }
            logoutRow = clickable(title = R.string.tailscale_logout) {
                clicked { requests.trySend(Request.Logout) }
            }
            routesSwitch = switch(
                value = ::acceptRoutes,
                title = R.string.tailscale_accept_routes,
                summary = R.string.tailscale_accept_routes_summary,
            ) {
                listener = OnChangedListener { save() }
            }
            routesRow = clickable(title = R.string.tailscale_subnet_routes) {
                view.isClickable = false
                view.isFocusable = false
            }
            clickable(title = R.string.tailscale_scope, summary = R.string.tailscale_scope_summary) {
                view.isClickable = false
                view.isFocusable = false
            }
            category(R.string.tailscale_mcp)
            forwardSwitch = switch(
                value = ::mcpEnabled,
                title = R.string.tailscale_mcp_forward,
                summary = R.string.tailscale_mcp_summary,
            ) {
                listener = OnChangedListener { save() }
            }
        }
        binding.content.addView(screen.root)
        update(initial)
    }

    private fun save() {
        forwardSwitch.enabled = enabled
        routesSwitch.enabled = enabled
        requests.trySend(Request.Save(TailscaleSettings(enabled, mcpEnabled, acceptRoutes)))
    }

    fun update(status: TailscaleStatus) {
        enabled = status.enabled
        mcpEnabled = status.mcpEnabled
        acceptRoutes = status.acceptRoutes
        enabledSwitch.checked = enabled
        forwardSwitch.checked = mcpEnabled
        routesSwitch.checked = acceptRoutes
        val state = when {
            !status.enabled -> R.string.tailscale_disabled
            status.state == "Running" -> R.string.tailscale_connected
            status.state == "NeedsLogin" -> R.string.tailscale_needs_login
            status.state == "NeedsMachineAuth" -> R.string.tailscale_needs_approval
            status.state == "Stopped" -> R.string.tailscale_start_clash
            else -> R.string.tailscale_connecting
        }
        statusRow.summary = listOf(context.getString(state), status.error, status.health.joinToString("\n"))
            .filter { it.isNotBlank() }.joinToString("\n")
        addressRow.summary = status.ips.joinToString("\n") { ip ->
            if (status.mcpListening) {
                val host = if (ip.contains(':')) "[$ip]" else ip
                "http://$host:8080/mcp"
            } else ip
        }.ifEmpty { null }
        addressRow.enabled = status.ips.isNotEmpty()
        loginRow.enabled = status.enabled && status.state != "Stopped" && status.state != "Running"
        logoutRow.enabled = status.enabled && status.state != "Stopped" && status.state != "NoState"
        forwardSwitch.enabled = enabled
        routesSwitch.enabled = enabled
        routesRow.summary = when {
            !enabled || !acceptRoutes -> context.getString(R.string.tailscale_disabled)
            status.subnetError.isNotBlank() -> status.subnetError
            status.state != "Running" -> context.getString(state)
            status.subnetRoutes.isEmpty() -> context.getString(R.string.tailscale_no_subnet_routes)
            else -> status.subnetRoutes.joinToString("\n")
        }
    }
}
