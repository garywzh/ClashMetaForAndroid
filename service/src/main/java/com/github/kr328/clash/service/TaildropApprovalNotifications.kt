package com.github.kr328.clash.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.core.model.TaildropStatus

internal class TaildropApprovalNotifications(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)
    private var session = ""
    private var shown = emptySet<String>()

    init {
        manager.createNotificationChannel(NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(context.getString(R.string.taildrop_approval_channel)).build())
    }

    fun update(status: TaildropStatus) {
        if (session != status.session || !status.running) clear()
        session = status.session
        val offers = if (status.running) status.offers else emptyList()
        val ids = offers.map { it.id }.toSet()
        (shown - ids).forEach { cancel(context, it) }
        offers.filter { it.id !in shown }.forEach { offer ->
            fun decision(accept: Boolean): PendingIntent = PendingIntent.getBroadcast(context, 0,
                Intent(context, TaildropApprovalReceiver::class.java)
                    .setAction(if (accept) TaildropApprovalReceiver.ACCEPT else TaildropApprovalReceiver.REJECT)
                    .setData(Uri.Builder().scheme("taildrop").authority("decision").appendPath(status.session).appendPath(offer.id).build())
                    .putExtra("id", offer.id).putExtra("session", status.session),
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT))
            val open = PendingIntent.getActivity(context, R.id.nf_taildrop_approval,
                Intent().setClassName(context.packageName, "com.github.kr328.clash.TaildropActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT))
            val size = if (offer.size >= 0) Formatter.formatShortFileSize(context, offer.size)
                else context.getString(R.string.taildrop_approval_unknown_size)
            val detail = "${offer.sender} · $size"
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_logo_service)
                .setContentTitle(context.getString(R.string.taildrop_approval_title))
                .setContentText(offer.name)
                .setStyle(NotificationCompat.BigTextStyle().bigText("${offer.name}\n$detail"))
                .setSubText(detail)
                .setContentIntent(open)
                .setDeleteIntent(decision(false))
                .setOnlyAlertOnce(true)
                .addAction(0, context.getString(R.string.taildrop_reject), decision(false))
                .addAction(0, context.getString(R.string.taildrop_accept), decision(true))
                .build()
            try { manager.notify(tag(offer.id), R.id.nf_taildrop_approval, notification) } catch (_: SecurityException) { }
        }
        shown = ids
    }

    fun clear() {
        shown.forEach { cancel(context, it) }
        shown = emptySet()
    }

    companion object {
        private const val CHANNEL = "taildrop-approval"
        private fun tag(id: String) = "taildrop-approval:$id"
        fun cancel(context: Context, id: String) = NotificationManagerCompat.from(context).cancel(tag(id), R.id.nf_taildrop_approval)
    }
}
