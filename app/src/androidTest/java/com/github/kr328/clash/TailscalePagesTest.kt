package com.github.kr328.clash

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.kr328.clash.core.model.*
import com.github.kr328.clash.design.*
import com.github.kr328.clash.design.R
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.github.kr328.clash.util.withClash
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TailscalePagesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val connected = TailscaleStatus(enabled = true, state = "Running", ips = listOf("100.101.102.103", "fd7a:115c:a1e0::1234"),
        acceptRoutes = true, subnetRoutes = listOf("192.168.10.0/24", "10.20.0.0/16"),
        forwards = listOf(TailscaleForwardRule("one", "Remote service", true, 8080, 8080)),
        forwardStatus = listOf(TailscaleForwardStatus("one", listening = true)))

    @Test fun overviewOverflowShowsLogoutOnlyForASignedInNode() {
        val activity = start(TailscaleActivity::class.java)
        lateinit var design: TailscaleDesign
        try {
            instrumentation.runOnMainSync {
                activity.cancel()
                design = TailscaleDesign(activity, connected)
                activity.setContentView(design.root)
                design.root.findViewById<View>(R.id.more_action).performClick()
            }
            clickPopup(R.string.tailscale_logout)
            assertEquals(TailscaleDesign.Request.Logout, design.requests.tryReceive().getOrNull())
            instrumentation.runOnMainSync {
                design.update(TailscaleStatus())
                design.root.findViewById<View>(R.id.more_action).performClick()
            }
            await { instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(context.getString(R.string.tailscale_usage))?.isNotEmpty() == true }
            assertTrue(instrumentation.uiAutomation.rootInActiveWindow!!.findAccessibilityNodeInfosByText(context.getString(R.string.tailscale_logout)).isEmpty())
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        } finally { instrumentation.runOnMainSync { activity.finishAndRemoveTask() } }
    }

    @Test fun editingEitherSettingsPagePreservesTheOtherSwitch() {
        val json = Json { ignoreUnknownKeys = true }
        fun status() = json.decodeFromString(TailscaleStatus.serializer(), runBlocking {
            withTimeout(5000) { withClash { tailscaleCommand("status", "") } }
        })
        fun settings(value: TailscaleSettings) = runBlocking {
            withTimeout(5000) { withClash { tailscaleCommand("settings", json.encodeToString(TailscaleSettings.serializer(), value)) } }
        }
        // Keep an Activity visible: the app intentionally unbinds its remote service when hidden.
        val overview = start(TailscaleActivity::class.java)
        var original: TailscaleStatus? = null
        var child: SubnetRoutesActivity? = null
        try {
            original = status()
            org.junit.Assume.assumeTrue("Do not alter a running VPN", original.state == "Stopped")
            settings(TailscaleSettings(false, false))
            child = start(SubnetRoutesActivity::class.java)
            await {
                var ready = false
                instrumentation.runOnMainSync { ready = texts(child!!.window.decorView).contains(context.getString(R.string.tailscale_accept_routes)) }
                ready
            }
            instrumentation.runOnMainSync { clickLabel(child!!.window.decorView, R.string.tailscale_accept_routes) }
            await { status().acceptRoutes }
            assertFalse(status().enabled)
            instrumentation.runOnMainSync { child!!.finish() }
            await { overview.hasWindowFocus() }
            child = null
            instrumentation.runOnMainSync { clickLabel(overview.window.decorView, R.string.tailscale_enable) }
            await { status().enabled }
            assertTrue(status().acceptRoutes)
        } finally {
            original?.let { settings(TailscaleSettings(it.enabled, it.acceptRoutes)) }
            instrumentation.runOnMainSync { child?.finish(); overview.finishAndRemoveTask() }
        }
    }

    @Test fun overviewGroupsConnectionAndFeaturesAndOnlyOffersRelevantLogin() {
        val activity = start(TailscaleActivity::class.java)
        lateinit var design: TailscaleDesign
        try {
            instrumentation.runOnMainSync {
                activity.cancel()
                design = TailscaleDesign(activity, connected)
                activity.setContentView(design.root)
                val text = texts(design.root)
                assertTrue(text.contains(context.getString(R.string.tailscale_connection)))
                assertTrue(text.contains(context.getString(R.string.tailscale_features)))
                assertFalse(text.contains(context.getString(R.string.tailscale_scope_summary)))
                assertFalse(text.contains("192.168.10.0/24"))
                assertFalse(text.contains(context.getString(R.string.tailscale_logout)))
                assertEquals(View.GONE, design.root.findViewById<View>(R.id.login_view).visibility)
                design.root.findViewById<View>(R.id.copy_view).performClick()
                assertEquals(TailscaleDesign.Request.CopyAddress, design.requests.tryReceive().getOrNull())
                clickLabel(design.root, R.string.tailscale_routes)
                assertEquals(TailscaleDesign.Request.SubnetRoutes, design.requests.tryReceive().getOrNull())
                clickLabel(design.root, R.string.tailscale_file_transfer)
                assertEquals(TailscaleDesign.Request.Taildrop, design.requests.tryReceive().getOrNull())
            }
            screenshot("tailnet-overview-connected.png")
            instrumentation.runOnMainSync {
                for ((status, state, login) in listOf(
                    Triple(TailscaleStatus(), R.string.tailscale_disabled, false),
                    Triple(TailscaleStatus(enabled = true, state = "Stopped"), R.string.tailscale_start_clash, false),
                    Triple(TailscaleStatus(enabled = true, state = "NeedsLogin"), R.string.tailscale_needs_login, true),
                    Triple(TailscaleStatus(enabled = true, state = "NeedsMachineAuth"), R.string.tailscale_needs_approval, false)
                )) {
                    design.update(status)
                    assertEquals(context.getString(state), design.root.findViewById<TextView>(R.id.state_view).text.toString())
                    assertEquals(if (login) View.VISIBLE else View.GONE, design.root.findViewById<View>(R.id.login_view).visibility)
                }
                design.update(TailscaleStatus(enabled = true, state = "NeedsLogin"))
                design.root.findViewById<View>(R.id.login_view).performClick()
                assertEquals(TailscaleDesign.Request.Login, design.requests.tryReceive().getOrNull())
            }
            screenshot("tailnet-overview-login.png")
        } finally { instrumentation.runOnMainSync { activity.finishAndRemoveTask() } }
    }

    @Test fun subnetPageSeparatesRoutesAndExposesAnOfflineEditableSwitch() {
        val activity = start(SubnetRoutesActivity::class.java)
        lateinit var design: SubnetRoutesDesign
        try {
            instrumentation.runOnMainSync {
                activity.cancel()
                design = SubnetRoutesDesign(activity, connected)
                activity.setContentView(design.root)
                assertTrue(texts(design.root).containsAll(connected.subnetRoutes))
            }
            screenshot("tailnet-subnets-connected.png")
            instrumentation.runOnMainSync {
                design.update(TailscaleStatus(acceptRoutes = true))
                assertFalse(texts(design.root).contains("192.168.10.0/24"))
                clickLabel(design.root, R.string.tailscale_accept_routes)
            }
            val deadline = System.currentTimeMillis() + 3000
            var request: SubnetRoutesDesign.Request? = null
            while (request == null && System.currentTimeMillis() < deadline) {
                request = design.requests.tryReceive().getOrNull()
                Thread.sleep(20)
            }
            assertEquals(SubnetRoutesDesign.Request.Save(false), request)
            instrumentation.runOnMainSync {
                design.update(connected.copy(subnetError = "Test route failure"))
                assertTrue(texts(design.root).contains("Test route failure"))
                design.update(connected.copy(acceptRoutes = false))
                assertTrue(texts(design.root).contains(context.getString(R.string.tailscale_routes_off)))
            }
            screenshot("tailnet-subnets-off.png")
        } finally { instrumentation.runOnMainSync { activity.finishAndRemoveTask() } }
    }

    @Test fun portForwardingMovesAddToToolbarAndKeepsRulesEditableOffline() {
        val activity = start(PortForwardingActivity::class.java)
        lateinit var design: PortForwardingDesign
        try {
            instrumentation.runOnMainSync {
                activity.cancel()
                design = PortForwardingDesign(activity)
                activity.setContentView(design.root)
                design.update(connected)
                assertTrue(texts(design.root).contains("Remote service"))
                assertFalse(texts(design.root).contains(context.getString(R.string.port_forward_start_vpn)))
                assertTrue(design.root.findViewById<View>(R.id.primary_action).isEnabled)
            }
            screenshot("tailnet-forwards-connected.png")
            instrumentation.runOnMainSync {
                design.update(TailscaleStatus())
                assertTrue(texts(design.root).contains(context.getString(R.string.port_forward_empty)))
                design.root.findViewById<View>(R.id.primary_action).performClick()
            }
            await {
                instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(context.getString(R.string.port_forward_add))?.isNotEmpty() == true
            }
            instrumentation.runOnMainSync { design.dismissDialogs() }
            screenshot("tailnet-forwards-empty.png")
        } finally {
            instrumentation.runOnMainSync { design.dismissDialogs(); activity.finishAndRemoveTask() }
        }
    }

    @Test fun allFeatureEntrypointsLaunchTheirActualActivities() {
        val activity = start(TailscaleActivity::class.java)
        try {
            for ((title, target) in listOf(
                R.string.tailscale_routes to SubnetRoutesActivity::class.java,
                R.string.tailscale_port_forwarding to PortForwardingActivity::class.java,
                R.string.tailscale_file_transfer to TaildropActivity::class.java
            )) {
                val monitor = instrumentation.addMonitor(target.name, null, false)
                try {
                    await {
                        var found = false
                        instrumentation.runOnMainSync {
                            found = descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text == context.getString(title) }
                        }
                        found
                    }
                    instrumentation.runOnMainSync { clickLabel(activity.window.decorView, title) }
                    val child = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
                    assertNotNull("Feature did not open: $target", child)
                    instrumentation.runOnMainSync { child!!.finish() }
                    instrumentation.waitForIdleSync()
                    await { activity.hasWindowFocus() }
                } finally { instrumentation.removeMonitor(monitor) }
            }
        } finally { instrumentation.runOnMainSync { activity.finishAndRemoveTask() } }
    }

    private fun <T : BaseActivity<*>> start(type: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        val activity = instrumentation.startActivitySync(Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as T
        instrumentation.waitForIdleSync()
        return activity
    }

    private fun clickLabel(root: View, resource: Int) {
        val label = descendants(root).filterIsInstance<TextView>().first { it.text == context.getString(resource) }
        generateSequence(label as View) { it.parent as? View }.first { it.isClickable }.performClick()
    }

    private fun clickPopup(resource: Int) = await {
        val node = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(context.getString(resource))?.firstOrNull()
        generateSequence(node) { it.parent }.any { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    private fun texts(root: View) = descendants(root).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }.map { it.text.toString() }.toList()

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(300)
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            context.openFileOutput(name, 0).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail("Timed out waiting for Tailscale UI")
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
