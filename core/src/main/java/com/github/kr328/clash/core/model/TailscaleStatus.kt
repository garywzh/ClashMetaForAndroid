package com.github.kr328.clash.core.model

import kotlinx.serialization.Serializable

@Serializable
data class TailscaleStatus(
    val enabled: Boolean = false,
    val forwards: List<TailscaleForwardRule> = emptyList(),
    val acceptRoutes: Boolean = false,
    val state: String = "Stopped",
    val authUrl: String = "",
    val ips: List<String> = emptyList(),
    val health: List<String> = emptyList(),
    val subnetRoutes: List<String> = emptyList(),
    val subnetError: String = "",
    val forwardStatus: List<TailscaleForwardStatus> = emptyList(),
    val error: String = "",
)

@Serializable
data class TailscaleSettings(
    val enabled: Boolean,
    val acceptRoutes: Boolean,
)

@Serializable
data class TailscaleForwardRule(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val localPort: Int,
    val tailnetPort: Int,
)

@Serializable
data class TailscaleForwardStatus(val id: String, val listening: Boolean = false, val error: String = "")
