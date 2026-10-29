package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.appcompat.widget.PopupMenu
import com.github.kr328.clash.core.model.TailscaleStatus
import com.github.kr328.clash.design.databinding.AdapterTailnetFeatureBinding
import com.github.kr328.clash.design.databinding.DesignTailnetBinding
import com.github.kr328.clash.design.databinding.ViewTailnetConnectionBinding
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.util.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class TailscaleDesign(context: Context, initial: TailscaleStatus) : Design<TailscaleDesign.Request>(context) {
    sealed interface Request {
        data class Save(val enabled: Boolean) : Request
        data object Login : Request
        data object Logout : Request
        data object ConfirmLogout : Request
        data object CopyAddress : Request
        data object Refresh : Request
        data object Taildrop : Request
        data object PortForwarding : Request
        data object SubnetRoutes : Request
    }

    private val binding = DesignTailnetBinding.inflate(context.layoutInflater, context.root, false)
    override val root: View get() = binding.root
    private var enabled = initial.enabled
    private var current = initial
    private lateinit var enabledSwitch: SwitchPreference
    private val connection = ViewTailnetConnectionBinding.inflate(context.layoutInflater, binding.content, false)
    private lateinit var routes: AdapterTailnetFeatureBinding
    private lateinit var forwards: AdapterTailnetFeatureBinding

    init {
        binding.surface = surface
        binding.activityBarLayout.applyFrom(context)
        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)
        binding.moreAction.visibility = View.VISIBLE
        binding.moreAction.setOnClickListener {
            PopupMenu(context, binding.moreAction).apply {
                menu.add(0, R.string.tailscale_usage, 0, R.string.tailscale_usage)
                if (current.enabled && (current.ips.isNotEmpty() || current.state == "NeedsMachineAuth")) {
                    menu.add(0, R.string.tailscale_logout, 1, R.string.tailscale_logout)
                }
                setOnMenuItemClickListener {
                    if (it.itemId == R.string.tailscale_logout) requests.trySend(Request.Logout)
                    else MaterialAlertDialogBuilder(context).setTitle(R.string.tailscale_usage)
                        .setMessage(R.string.tailscale_scope_summary).setPositiveButton(android.R.string.ok, null).show()
                    true
                }
                show()
            }
        }
        connection.copyView.setOnClickListener { requests.trySend(Request.CopyAddress) }
        connection.loginView.setOnClickListener { requests.trySend(Request.Login) }
        val screen = preferenceScreen(context) {
            category(R.string.tailscale_connection)
            enabledSwitch = switch(value = ::enabled, title = R.string.tailscale_enable) {
                listener = OnChangedListener { requests.trySend(Request.Save(enabled)) }
            }
            root.addView(connection.root)
            category(R.string.tailscale_features)
            fun feature(title: Int, request: Request): AdapterTailnetFeatureBinding =
                AdapterTailnetFeatureBinding.inflate(context.layoutInflater, root, false).also {
                    it.titleView.setText(title)
                    it.root.setOnClickListener { requests.trySend(request) }
                    root.addView(it.root)
                }
            feature(R.string.tailscale_file_transfer, Request.Taildrop)
            routes = feature(R.string.tailscale_routes, Request.SubnetRoutes)
            forwards = feature(R.string.tailscale_port_forwarding, Request.PortForwarding)
        }
        binding.content.addView(screen.root)
        update(initial)
    }

    fun update(status: TailscaleStatus) {
        current = status
        enabled = status.enabled
        enabledSwitch.checked = enabled
        connection.stateView.setText(status.connectionState())
        val problems = (listOf(status.error) + status.health).filter { it.isNotBlank() }.distinct().joinToString("\n")
        connection.problemView.text = problems
        connection.problemView.visibility = if (problems.isEmpty()) View.GONE else View.VISIBLE
        connection.addressView.text = status.ips.joinToString("\n")
        connection.addressRow.visibility = if (status.ips.isEmpty()) View.GONE else View.VISIBLE
        connection.loginView.visibility = if (enabled && status.state in listOf("NeedsLogin", "NoState")) View.VISIBLE else View.GONE
        routes.summaryView.visibility = View.VISIBLE
        routes.summaryView.text = when {
            !status.acceptRoutes -> context.getString(R.string.tailscale_disabled)
            status.subnetError.isNotBlank() -> context.getString(R.string.tailscale_routes_error)
            !status.enabled || status.state != "Running" -> context.getString(status.connectionState())
            else -> context.getString(R.string.tailscale_routes_count, status.subnetRoutes.size)
        }
        forwards.summaryView.visibility = View.VISIBLE
        forwards.summaryView.text = if (status.forwards.isEmpty()) context.getString(R.string.port_forward_empty)
            else context.getString(R.string.port_forward_enabled_count, status.forwards.count { it.enabled })
    }
}
