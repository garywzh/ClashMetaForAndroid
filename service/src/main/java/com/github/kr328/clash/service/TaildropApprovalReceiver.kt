package com.github.kr328.clash.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.github.kr328.clash.core.Clash
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class TaildropApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val session = intent.getStringExtra("session") ?: return
        val accept = when (intent.action) {
            ACCEPT -> true
            REJECT -> false
            else -> return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Session and random request ID are revalidated by native code.
                val result = Clash.tailscaleCommand(if (accept) "taildrop-accept" else "taildrop-reject", buildJsonObject {
                    put("id", id); put("session", session)
                }.toString())
                if (Json.parseToJsonElement(result).jsonObject["error"]?.jsonPrimitive?.content.orEmpty().isEmpty()) {
                    TaildropApprovalNotifications.cancel(context, id)
                }
            } catch (e: Exception) {
                Log.w("Taildrop", "Could not answer receive request", e)
            } finally { pending.finish() }
        }
    }

    companion object {
        const val ACCEPT = "taildrop.accept"
        const val REJECT = "taildrop.reject"
    }
}
