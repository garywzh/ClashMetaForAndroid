package com.github.kr328.clash.core.model

import kotlinx.serialization.Serializable

@Serializable
data class TailscaleStatus(
    val enabled: Boolean = false,
    val mcpEnabled: Boolean = false,
    val state: String = "Stopped",
    val authUrl: String = "",
    val ips: List<String> = emptyList(),
    val health: List<String> = emptyList(),
    val mcpListening: Boolean = false,
    val error: String = "",
)

@Serializable
data class TailscaleSettings(val enabled: Boolean, val mcpEnabled: Boolean)
