package com.github.kr328.clash.service.clash.module

import android.app.PendingIntent
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.text.format.Formatter
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.service.R
import com.github.kr328.clash.service.TaildropDownloads
import com.github.kr328.clash.service.TaildropApprovalNotifications
import kotlinx.coroutines.*

class TaildropModule(service: Service) : Module<Unit>(service) {
    private val manager = NotificationManagerCompat.from(service)

    override suspend fun run() = withContext(Dispatchers.IO) {
        manager.createNotificationChannel(NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(service.getString(R.string.taildrop_notification_channel)).build())
        val notifications = TaildropNotificationState()
        val approvals = TaildropApprovalNotifications(service)
        val wakeLock = service.getSystemService<PowerManager>()?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CMFA:Taildrop")
            ?.apply { setReferenceCounted(false) }
        var lastWake = 0L
        try {
            while (isActive) {
                val status = TaildropDownloads.query(service)
                approvals.update(status)
                val active = (status.transfers + status.incoming).filter { !it.finished }
                if (status.running && (active.isNotEmpty() || status.offers.isNotEmpty())) {
                    if (wakeLock?.isHeld == false || SystemClock.elapsedRealtime() - lastWake > 30_000) {
                        wakeLock?.acquire(60_000)
                        lastWake = SystemClock.elapsedRealtime()
                    }
                } else if (wakeLock?.isHeld == true) wakeLock.release()
                val inboxVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    service.getSystemService<NotificationManager>()?.activeNotifications
                        ?.any { it.id == R.id.nf_taildrop_inbox } == true
                val notificationStatus = status.copy(
                    files = status.files.filter { it.state == "saved" || it.state == "failed" },
                    transfers = status.transfers.filter { it.direction != "download" || !it.finished },
                )
                val update = notifications.update(notificationStatus, inboxVisible)
                when (update.transfers) {
                    TaildropNotificationState.Action.CANCEL -> manager.cancel(R.id.nf_taildrop_transfers)
                    TaildropNotificationState.Action.KEEP -> Unit
                    TaildropNotificationState.Action.SHOW -> {
                        val failed = notificationStatus.transfers.count { it.state == "failed" }
                        val finished = notificationStatus.transfers.count { it.finished }
                        val resource = when {
                            active.isNotEmpty() -> R.string.taildrop_notification_active
                            failed > 0 -> R.string.taildrop_notification_failed
                            else -> R.string.taildrop_notification_finished
                        }
                        val count = if (active.isNotEmpty()) active.size else if (failed > 0) failed else finished
                        val builder = builder(service, ongoing = active.isNotEmpty()).setContentText(service.getString(resource, count))
                        if (active.isNotEmpty()) {
                            val total = active.sumOf { it.size.coerceAtLeast(0) }
                            val done = active.sumOf { it.done }
                            val progress = if (active.all { it.size >= 0 }) "${size(done)} / ${size(total)}" else size(done)
                            val speed = if (active.any { it.speed < 0 }) "…" else "${size(active.sumOf { it.speed })}/s"
                            val detail = "$progress · $speed"
                            builder.setContentText(detail).setSubText(service.getString(resource, count))
                                .setStyle(NotificationCompat.BigTextStyle().bigText("${service.getString(resource, count)}\n$detail"))
                            builder.setProgress(100, if (total > 0) ((done.toDouble() / total) * 100).toInt().coerceIn(0, 100) else 0, active.any { it.size < 0 })
                        }
                        notify(R.id.nf_taildrop_transfers, builder)
                    }
                }
                when (update.inbox) {
                    TaildropNotificationState.Action.SHOW -> {
                        val failed = status.files.count { it.state == "failed" }
                        val saved = status.files.filter { it.state == "saved" }
                        val title = if (failed > 0) R.string.taildrop_download_failed else R.string.taildrop_download_saved
                        val text = if (failed > 0) service.getString(R.string.taildrop_notification_save_failed, failed)
                            else if (saved.size == 1) saved.first().name else service.getString(R.string.taildrop_notification_files, saved.size)
                        notify(R.id.nf_taildrop_inbox, builder(service, R.id.nf_taildrop_inbox, if (failed == 0 && saved.size == 1) saved.first().id else "")
                            .setContentTitle(service.getString(title)).setContentText(text))
                    }
                    TaildropNotificationState.Action.CANCEL -> manager.cancel(R.id.nf_taildrop_inbox)
                    TaildropNotificationState.Action.KEEP -> Unit
                }
                delay(1000)
            }
        } finally {
            approvals.clear()
            try { TaildropDownloads.get(service).stop() } catch (_: Exception) { }
            if (wakeLock?.isHeld == true) wakeLock.release()
            manager.cancel(R.id.nf_taildrop_transfers)
            manager.cancel(R.id.nf_taildrop_inbox)
        }
    }

    private fun size(bytes: Long) = Formatter.formatShortFileSize(service, bytes.coerceAtLeast(0))

    private fun notify(id: Int, builder: NotificationCompat.Builder) {
        // The VPN remains usable when notification permission is denied.
        try { manager.notify(id, builder.build()) } catch (_: SecurityException) { }
    }

    companion object {
        private const val CHANNEL = "taildrop"

        internal fun builder(context: Context, id: Int = R.id.nf_taildrop_transfers, openId: String = "", ongoing: Boolean = false) =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_logo_service)
                .setContentTitle("Taildrop")
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setContentIntent(PendingIntent.getActivity(
                    context, id,
                    Intent().setClassName(context.packageName, "com.github.kr328.clash.TaildropActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra("taildrop.openId", openId),
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
                ))
    }
}
