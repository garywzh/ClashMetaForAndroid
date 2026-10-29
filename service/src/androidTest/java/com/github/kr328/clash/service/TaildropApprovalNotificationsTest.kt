package com.github.kr328.clash.service

import android.app.NotificationManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.github.kr328.clash.core.model.TaildropOffer
import com.github.kr328.clash.core.model.TaildropStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 23)
class TaildropApprovalNotificationsTest {
    @Test fun separateRequestsKeepIndependentActionsAndAreRemovedWhenAnsweredOrStopped() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")).use { it.readBytes() }
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val notifications = TaildropApprovalNotifications(context)
        val status = TaildropStatus(session = "vpn-one", running = true, offers = listOf(
            TaildropOffer("one", "same.txt", 10, "MacBook"),
            TaildropOffer("two", "same.txt", -1, "Desktop"),
        ))
        fun active() = manager.activeNotifications.filter { it.id == R.id.nf_taildrop_approval }
        fun awaitCount(count: Int) {
            val deadline = System.currentTimeMillis() + 3000
            while (active().size != count && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(count, active().size)
        }
        try {
            notifications.update(status)
            awaitCount(2)
            val pending = active().sortedBy { it.tag }
            assertNotEquals(pending[0].notification.actions[0].actionIntent, pending[1].notification.actions[0].actionIntent)
            pending.forEach { notification ->
                assertEquals(2, notification.notification.actions.size)
                assertNotEquals(notification.notification.actions[0].actionIntent, notification.notification.actions[1].actionIntent)
                assertEquals(notification.notification.deleteIntent, notification.notification.actions[0].actionIntent)
            }
            Thread.sleep(1500)
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "cmd statusbar expand-notifications")).use { it.readBytes() }
            Thread.sleep(1000)
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                context.openFileOutput("taildrop-approval-notifications.png", 0).use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            repeat(3) { notifications.update(status) }
            awaitCount(2)
            notifications.update(status.copy(offers = status.offers.drop(1)))
            awaitCount(1)
            assertTrue(active().single().tag.endsWith(":two"))
            notifications.update(status.copy(running = false))
            awaitCount(0)
        } finally {
            notifications.clear()
            instrumentation.uiAutomation.executeShellCommand("cmd statusbar collapse").close()
        }
    }
}
