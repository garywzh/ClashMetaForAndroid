package com.github.kr328.clash.service.clash.module

import android.app.PendingIntent
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.TaildropStatus
import com.github.kr328.clash.service.R
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

class TaildropModule(service: Service) : Module<Unit>(service) {
    private val manager = NotificationManagerCompat.from(service)
    private val json = Json { ignoreUnknownKeys = true }

    private fun builder() = NotificationCompat.Builder(service, CHANNEL)
        .setSmallIcon(R.drawable.ic_logo_service)
        .setContentTitle("Taildrop")
        .setOnlyAlertOnce(true)
        .setAutoCancel(true)
        .setContentIntent(PendingIntent.getActivity(
            service, R.id.nf_taildrop_transfers,
            Intent().setClassName(service.packageName, "com.github.kr328.clash.TaildropActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        ))

    override suspend fun run() = withContext(Dispatchers.IO) {
        manager.createNotificationChannel(NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(service.getString(R.string.taildrop_notification_channel)).build())
        val notifications = TaildropNotificationState()
        val wakeLock = service.getSystemService<PowerManager>()?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CMFA:Taildrop")
            ?.apply { setReferenceCounted(false) }
        var lastWake = 0L
        try {
            while (isActive) {
                val status = json.decodeFromString(TaildropStatus.serializer(), Clash.tailscaleCommand("taildrop-status"))
                val active = (status.transfers + status.incoming).filter { !it.finished }
                if (status.running && active.isNotEmpty()) {
                    if (wakeLock?.isHeld == false || SystemClock.elapsedRealtime() - lastWake > 30_000) {
                        wakeLock?.acquire(60_000)
                        lastWake = SystemClock.elapsedRealtime()
                    }
                } else if (wakeLock?.isHeld == true) wakeLock.release()
                val inboxVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    service.getSystemService<NotificationManager>()?.activeNotifications
                        ?.any { it.id == R.id.nf_taildrop_inbox } == true
                val update = notifications.update(status, inboxVisible)
                when (update.transfers) {
                    TaildropNotificationState.Action.CANCEL -> manager.cancel(R.id.nf_taildrop_transfers)
                    TaildropNotificationState.Action.KEEP -> Unit
                    TaildropNotificationState.Action.SHOW -> {
                        val failed = status.transfers.count { it.state == "failed" }
                        val finished = status.transfers.count { it.finished }
                        val resource = when {
                            active.isNotEmpty() -> R.string.taildrop_notification_active
                            failed > 0 -> R.string.taildrop_notification_failed
                            else -> R.string.taildrop_notification_finished
                        }
                        val count = if (active.isNotEmpty()) active.size else if (failed > 0) failed else finished
                        val builder = builder().setContentText(service.getString(resource, count)).setOngoing(active.isNotEmpty())
                        if (active.isNotEmpty()) {
                            val total = active.sumOf { it.size.coerceAtLeast(0) }
                            val done = active.sumOf { it.done }
                            builder.setProgress(100, if (total > 0) ((done.toDouble() / total) * 100).toInt().coerceIn(0, 100) else 0, active.any { it.size < 0 })
                        }
                        notify(R.id.nf_taildrop_transfers, builder)
                    }
                }
                when (update.inbox) {
                    TaildropNotificationState.Action.SHOW -> {
                        notify(R.id.nf_taildrop_inbox, builder().setContentText(service.getString(R.string.taildrop_notification_files, status.files.size)))
                    }
                    TaildropNotificationState.Action.CANCEL -> manager.cancel(R.id.nf_taildrop_inbox)
                    TaildropNotificationState.Action.KEEP -> Unit
                }
                delay(1500)
            }
        } finally {
            if (wakeLock?.isHeld == true) wakeLock.release()
            manager.cancel(R.id.nf_taildrop_transfers)
            manager.cancel(R.id.nf_taildrop_inbox)
        }
    }

    private fun notify(id: Int, builder: NotificationCompat.Builder) {
        // The VPN remains usable when notification permission is denied.
        try { manager.notify(id, builder.build()) } catch (_: SecurityException) { }
    }

    companion object { private const val CHANNEL = "taildrop" }
}
