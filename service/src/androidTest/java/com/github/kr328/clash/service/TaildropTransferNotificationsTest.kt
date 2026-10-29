package com.github.kr328.clash.service

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.github.kr328.clash.service.clash.module.TaildropModule
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class TaildropTransferNotificationsTest {
    @Test fun activeTransfersDoNotAutoCancelButCompletedFilesDo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")).use { it.readBytes() }
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel("taildrop", "Taildrop", NotificationManager.IMPORTANCE_LOW))
        val id = R.id.nf_taildrop_transfers
        try {
            val active = TaildropModule.builder(context, ongoing = true).build()
            assertTrue(active.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(0, active.flags and Notification.FLAG_AUTO_CANCEL)
            manager.notify(id, active)
            val deadline = System.currentTimeMillis() + 3000
            while (manager.activeNotifications.none { it.id == id } && System.currentTimeMillis() < deadline) Thread.sleep(20)
            val posted = manager.activeNotifications.single { it.id == id }.notification
            assertTrue(posted.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(0, posted.flags and Notification.FLAG_AUTO_CANCEL)

            val completed = TaildropModule.builder(context).build()
            assertEquals(0, completed.flags and Notification.FLAG_ONGOING_EVENT)
            assertTrue(completed.flags and Notification.FLAG_AUTO_CANCEL != 0)
            manager.notify(id, completed)
            val inbox = TaildropModule.builder(context, R.id.nf_taildrop_inbox, "saved-id").build()
            assertEquals(0, inbox.flags and Notification.FLAG_ONGOING_EVENT)
            assertTrue(inbox.flags and Notification.FLAG_AUTO_CANCEL != 0)
        } finally { manager.cancel(id) }
    }
}
