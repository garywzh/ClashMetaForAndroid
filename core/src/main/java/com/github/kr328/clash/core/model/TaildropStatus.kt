package com.github.kr328.clash.core.model

import kotlinx.serialization.Serializable

@Serializable
data class TaildropStatus(
    val session: String = "",
    val running: Boolean = false,
    val targets: List<TaildropTarget> = emptyList(),
    val files: List<TaildropFile> = emptyList(),
    val incoming: List<TaildropTransfer> = emptyList(),
    val transfers: List<TaildropTransfer> = emptyList(),
    val error: String = "",
)

@Serializable
data class TaildropTarget(val id: String, val name: String, val online: Boolean)

@Serializable
data class TaildropFile(val name: String, val size: Long)

@Serializable
data class TaildropTransfer(
    val id: String,
    val name: String,
    val direction: String,
    val size: Long,
    val done: Long,
    val state: String,
    val error: String = "",
) {
    val finished: Boolean get() = state in setOf("done", "failed", "cancelled")
}
