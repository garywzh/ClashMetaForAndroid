package com.github.kr328.clash.design.util

import com.github.kr328.clash.core.model.TailscaleStatus
import com.github.kr328.clash.design.R

internal fun TailscaleStatus.connectionState(): Int = when {
    !enabled -> R.string.tailscale_disabled
    state == "Running" -> R.string.tailscale_connected
    state == "NeedsLogin" || state == "NoState" -> R.string.tailscale_needs_login
    state == "NeedsMachineAuth" -> R.string.tailscale_needs_approval
    state == "Stopped" -> R.string.tailscale_start_clash
    else -> R.string.tailscale_connecting
}
