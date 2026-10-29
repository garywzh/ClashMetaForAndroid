package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.core.model.TailscaleStatus
import com.github.kr328.clash.design.databinding.DesignTailnetBinding
import com.github.kr328.clash.design.databinding.ViewTailnetNoteBinding
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.util.*

class SubnetRoutesDesign(context: Context, initial: TailscaleStatus) : Design<SubnetRoutesDesign.Request>(context) {
    sealed interface Request {
        data class Save(val acceptRoutes: Boolean) : Request
        data object Refresh : Request
        data object Configure : Request
    }

    private val binding = DesignTailnetBinding.inflate(context.layoutInflater, context.root, false)
    override val root: View get() = binding.root
    private var acceptRoutes = initial.acceptRoutes
    private lateinit var routesSwitch: SwitchPreference
    private lateinit var statusRow: ClickablePreference
    private val routes = preferenceScreen(context) {}
    private val routeSection = preferenceScreen(context) { category(R.string.tailscale_subnet_routes) }
    private var previous: TailscaleStatus? = null

    init {
        binding.surface = surface
        binding.activityBarLayout.applyFrom(context)
        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)
        val screen = preferenceScreen(context) {
            routesSwitch = switch(value = ::acceptRoutes, title = R.string.tailscale_accept_routes) {
                listener = OnChangedListener { requests.trySend(Request.Save(acceptRoutes)) }
            }
            statusRow = clickable(R.string.tailscale) { clicked { requests.trySend(Request.Configure) } }
            routeSection.root.addView(routes.root)
            root.addView(routeSection.root)
            root.addView(ViewTailnetNoteBinding.inflate(context.layoutInflater, root, false).apply {
                noteView.setText(R.string.tailscale_accept_routes_summary)
            }.root)
        }
        binding.content.addView(screen.root)
        update(initial)
    }

    fun update(status: TailscaleStatus) {
        acceptRoutes = status.acceptRoutes
        routesSwitch.checked = acceptRoutes
        statusRow.view.visibility = if (acceptRoutes && (!status.enabled || status.state != "Running")) View.VISIBLE else View.GONE
        statusRow.summary = context.getString(status.connectionState())
        routeSection.root.visibility = if (acceptRoutes && (!status.enabled || status.state != "Running") && status.subnetError.isBlank()) View.GONE else View.VISIBLE
        if (previous?.let {
                it.acceptRoutes == status.acceptRoutes && it.enabled == status.enabled && it.state == status.state &&
                    it.subnetRoutes == status.subnetRoutes && it.subnetError == status.subnetError
            } == true) return
        previous = status
        routes.root.removeAllViews()
        val message = when {
            !acceptRoutes -> context.getString(R.string.tailscale_routes_off)
            status.subnetError.isNotBlank() -> status.subnetError
            !status.enabled || status.state != "Running" -> context.getString(status.connectionState())
            status.subnetRoutes.isEmpty() -> context.getString(R.string.tailscale_no_subnet_routes)
            else -> null
        }
        for (text in message?.let { listOf(it) } ?: status.subnetRoutes) {
            routes.clickable(R.string.tailscale_subnet_routes) {
                title = text
                view.isClickable = false
                view.isFocusable = false
            }
        }
    }
}
