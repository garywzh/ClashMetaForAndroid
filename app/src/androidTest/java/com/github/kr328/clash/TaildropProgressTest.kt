package com.github.kr328.clash

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.kr328.clash.core.model.*
import com.github.kr328.clash.design.TaildropDesign
import com.github.kr328.clash.design.R
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaildropProgressTest {
    @Test fun eachFileHasIndependentReceiveAndDeclineActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, TaildropActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TaildropActivity
        instrumentation.waitForIdleSync()
        lateinit var design: TaildropDesign
        val first = TaildropOffer("one", "meeting-notes.pdf", 2_500_000, "MacBook")
        val second = TaildropOffer("two", "holiday-video.mp4", -1, "Desktop")
        val status = TaildropStatus(running = true, targets = listOf(TaildropTarget("peer", "MacBook", true)),
            offers = listOf(first, second))
        try {
            instrumentation.runOnMainSync {
                activity.cancel()
                design = TaildropDesign(activity)
                activity.setContentView(design.root)
                design.update(status)
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(300)
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                context.openFileOutput("taildrop-approval.png", 0).use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            instrumentation.runOnMainSync {
                val accept = descendants(design.root).filter { it.id == R.id.accept_view }.toList()
                val reject = descendants(design.root).filter { it.id == R.id.reject_view }.toList()
                assertEquals(2, accept.size)
                assertEquals(2, reject.size)
                accept.first().performClick()
                assertEquals(TaildropDesign.Request.Decide(first, true), design.requests.tryReceive().getOrNull())
                reject.last().performClick()
                assertEquals(TaildropDesign.Request.Decide(second, false), design.requests.tryReceive().getOrNull())
                design.update(status.copy(offers = listOf(second)))
                assertEquals(1, descendants(design.root).count { it.id == R.id.accept_view })
                descendants(design.root).single { it.id == R.id.accept_view }.performClick()
                assertEquals(TaildropDesign.Request.Decide(second, true), design.requests.tryReceive().getOrNull())
                design.update(status.copy(offers = emptyList()))
                assertTrue(descendants(design.root).none { it.id == R.id.accept_view })
            }
        } finally { instrumentation.runOnMainSync { activity.finishAndRemoveTask() } }
    }

    @Test fun rendersKnownAndUnknownProgressAndSavedFiles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, TaildropActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TaildropActivity
        instrumentation.waitForIdleSync()
        lateinit var design: TaildropDesign
        try {
            instrumentation.runOnMainSync {
                // Freeze polling; exercise the production design with deterministic transfer data.
                activity.cancel()
                design = TaildropDesign(activity)
                activity.setContentView(design.root)
                design.update(TaildropStatus(running = true,
                    targets = listOf(TaildropTarget("peer", "MacBook", true)),
                    incoming = listOf(TaildropTransfer("in", "holiday-video.mp4", "receive",
                        100_000_000, 42_000_000, "receiving", speed = 2_000_000)),
                    transfers = listOf(TaildropTransfer("out", "project-archive.zip", "send",
                        -1, 8_000_000, "sending", speed = 600_000),
                        TaildropTransfer("download-1", "meeting-notes.pdf", "download",
                            2_500_000, 2_500_000, "done"),
                        TaildropTransfer("sent-1", "sent-record.txt", "send", 100, 100, "done")),
                    files = listOf(TaildropFile("meeting-notes.pdf", 2_500_000, "saved",
                        "content://media/external/downloads/1", "saved"))))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val views = descendants(design.root).toList()
                val bars = views.filterIsInstance<ProgressBar>().filter { it.visibility == View.VISIBLE }
                assertEquals(2, bars.size)
                assertEquals(42, bars.single { !it.isIndeterminate }.progress)
                assertEquals(1, bars.count { it.isIndeterminate })
                val text = views.filterIsInstance<TextView>().joinToString("\n") { it.text }
                assertTrue(text.contains("42%"))
                assertTrue(text.contains("/s"))
                assertTrue(text.contains("Download/Taildrop"))
                assertEquals(1, views.filterIsInstance<TextView>().count { it.text.toString().contains("Download/Taildrop") })
                assertEquals(1, views.filterIsInstance<TextView>().count { it.text.toString() == "meeting-notes.pdf" })
                assertFalse(views.filterIsInstance<TextView>().any { it.text.toString() == context.getString(R.string.taildrop_transfers) })
                assertFalse(views.filterIsInstance<TextView>().any { it.text.toString() == context.getString(R.string.taildrop_inbox) })
                design.root.findViewById<View>(R.id.primary_action).performClick()
                assertEquals(TaildropDesign.Request.Send, design.requests.tryReceive().getOrNull())
            }
            // Screenshot the same native layout that the assertions inspect.
            Thread.sleep(300)
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                context.openFileOutput("taildrop-progress.png", 0).use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            instrumentation.runOnMainSync { design.root.findViewById<View>(R.id.more_action).performClick() }
            val deadline = System.currentTimeMillis() + 3000
            var clicked = false
            while (!clicked && System.currentTimeMillis() < deadline) {
                val node = instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText(context.getString(R.string.taildrop_clear_finished))?.firstOrNull()
                clicked = generateSequence(node) { it.parent }.any { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                if (!clicked) Thread.sleep(50)
            }
            assertTrue("Finished history must be clearable from the overflow menu", clicked)
            assertEquals(TaildropDesign.Request.Dismiss, design.requests.tryReceive().getOrNull())
            instrumentation.runOnMainSync {
                val saved = TaildropFile("meeting-notes.pdf", 2_500_000,
                    "saved", "content://media/external/downloads/1", "saved")
                design.update(TaildropStatus(files = listOf(saved)))
                assertTrue(descendants(design.root).filterIsInstance<ProgressBar>().none())
                fun fileRow(): View {
                    val title = descendants(design.root).filterIsInstance<TextView>().single { it.text == saved.name }
                    return generateSequence(title as View) { it.parent as? View }.first { it.isClickable }
                }
                fileRow().performClick()
                assertEquals(TaildropDesign.Request.FileActions(saved), design.requests.tryReceive().getOrNull())
                assertFalse(fileRow().isLongClickable)
                for (state in listOf("waiting", "failed")) {
                    val pending = saved.copy(state = state, uri = "")
                    design.update(TaildropStatus(files = listOf(pending)))
                    fileRow().performClick()
                    assertEquals(TaildropDesign.Request.FileActions(pending), design.requests.tryReceive().getOrNull())
                    assertFalse(fileRow().isLongClickable)
                }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finishAndRemoveTask() }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
