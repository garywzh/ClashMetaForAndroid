package com.github.kr328.clash.service

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.github.kr328.clash.core.model.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class TaildropDownloadsTest {
    private lateinit var context: Context
    private lateinit var journal: AtomicFile
    private lateinit var bridge: Bridge

    private class Bridge {
        var payload = "Taildrop Downloads integration\n".toByteArray()
        var source: TaildropFile? = TaildropFile("same-name.txt", payload.size.toLong(), UUID.randomUUID().toString())
        var running = true
        var fail = false
        var pause = false
        var saves = 0
        val jobs = mutableListOf<TaildropTransfer>()

        fun snapshot() = TaildropStatus(session = "test-session", running = running,
            files = listOfNotNull(source), transfers = jobs.toList())

        fun command(command: String, content: String): TaildropStatus {
            val data = if (content.isEmpty()) buildJsonObject {} else Json.parseToJsonElement(content).jsonObject
            when (command) {
                "taildrop-save" -> {
                    saves++
                    val descriptor = ParcelFileDescriptor.adoptFd(data.getValue("fd").jsonPrimitive.int)
                    if (fail) {
                        descriptor.close()
                        return snapshot().copy(error = "Simulated write failure")
                    }
                    ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { it.write(payload) }
                    jobs += TaildropTransfer(data.getValue("id").jsonPrimitive.content, source!!.name, "download",
                        payload.size.toLong(), payload.size.toLong(), if (pause) "saving" else "done")
                }
                "taildrop-delete" -> {
                    check(data.getValue("fileId").jsonPrimitive.content == source?.id)
                    source = null
                }
                "taildrop-cancel" -> {
                    val id = data.getValue("id").jsonPrimitive.content
                    jobs.replaceAll { if (it.id == id) it.copy(state = "cancelled") else it }
                }
            }
            return snapshot()
        }
    }

    private fun coordinator() = TaildropDownloads(context, bridge::command, journal)

    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        journal = AtomicFile(File(context.filesDir, "taildrop-test-${UUID.randomUUID()}.json"))
        bridge = Bridge()
    }

    @Suppress("DEPRECATION")
    @After fun cleanup() {
        context.contentResolver.delete(MediaStore.setIncludePending(MediaStore.Downloads.EXTERNAL_CONTENT_URI),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf("Download/Taildrop/"))
        journal.delete()
    }

    @Suppress("DEPRECATION")
    private fun pendingCount(): Int = context.contentResolver.query(MediaStore.setIncludePending(MediaStore.Downloads.EXTERNAL_CONTENT_URI),
        arrayOf(MediaStore.MediaColumns._ID),
        "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.IS_PENDING}=1",
        arrayOf("Download/Taildrop/"), null)!!.use { it.count }

    @Test fun writesPublishesAndRemovesStagingOnlyAfterSuccess() {
        val inbox = coordinator()
        val first = inbox.status()
        assertTrue(first.files.isEmpty())
        assertNotNull(bridge.source)
        assertEquals(1, pendingCount())
        val file = inbox.status().files.single()
        assertEquals("saved", file.state)
        assertEquals(0, pendingCount())
        assertNull(bridge.source)
        context.contentResolver.openInputStream(Uri.parse(file.uri))!!.use {
            assertArrayEquals(bridge.payload, it.readBytes())
        }
        repeat(4) { inbox.status() }
        assertEquals(1, bridge.saves)
        bridge.running = false
        assertEquals(file, inbox.status().files.single())
    }

    @Test fun failureKeepsOriginalAndRetriesOnlyWhenRequested() {
        bridge.fail = true
        val inbox = coordinator()
        val failed = inbox.status().files.single()
        assertEquals("failed", failed.state)
        assertNotNull(bridge.source)
        assertEquals(0, pendingCount())
        repeat(3) { inbox.status() }
        assertEquals(1, bridge.saves)
        bridge.fail = false
        inbox.retry(failed.id)
        inbox.status()
        assertEquals("saved", inbox.status().files.single().state)
        assertEquals(2, bridge.saves)
    }

    @Test fun sameNameAndSizeFromAnotherDeliveryNeverOverwriteEarlierDownload() {
        val inbox = coordinator()
        inbox.status()
        val original = inbox.status().files.single()
        bridge.source = TaildropFile(original.name, original.size, UUID.randomUUID().toString())
        inbox.status()
        val files = inbox.status().files
        assertEquals(2, files.size)
        assertEquals(2, files.map { it.uri }.distinct().size)
        files.forEach { file -> context.contentResolver.openInputStream(Uri.parse(file.uri))!!.use {
            assertArrayEquals(bridge.payload, it.readBytes())
        } }
    }

    @Test fun stopRemovesPartialDownloadAndNextSessionRetriesIt() {
        bridge.pause = true
        val inbox = coordinator()
        inbox.status()
        assertEquals(1, pendingCount())
        inbox.stop()
        assertEquals(0, pendingCount())
        assertNotNull(bridge.source)
        bridge.jobs.clear()
        bridge.pause = false
        val restarted = coordinator()
        restarted.status()
        assertEquals("saved", restarted.status().files.single().state)
    }

    @Test fun crashDuringCopyDoesNotLeavePartialOrDuplicatePublishedFiles() {
        bridge.pause = true
        coordinator().status()
        bridge.jobs.clear()
        bridge.pause = false
        val restarted = coordinator()
        restarted.status()
        assertEquals(1, pendingCount())
        assertEquals(1, restarted.status().files.size)
        assertEquals(0, pendingCount())
    }

    @Test fun publishedFileIsReconciledInPlaceAfterCrashBeforeJournalCommit() {
        val inbox = coordinator()
        val source = bridge.source
        inbox.status()
        val file = inbox.status().files.single()
        val data = journal.openRead().bufferedReader().use { it.readText() }
            .replace("\"state\":\"saved\"", "\"state\":\"publishing\"")
            .replace("\"cleanup\":false", "\"cleanup\":true")
        journal.baseFile.writeText(data)
        bridge.source = source
        bridge.jobs.clear()
        val recovered = coordinator().status().files.single()
        assertEquals(file.uri, recovered.uri)
        assertEquals(1, bridge.saves)
        assertNull(bridge.source)
    }

    @Test fun deletingFromInboxDeletesTheActualDownload() {
        val inbox = coordinator()
        inbox.status()
        val file = inbox.status().files.single()
        inbox.delete(file.id)
        assertTrue(inbox.status().files.isEmpty())
        val exists = context.contentResolver.query(Uri.parse(file.uri), null, null, null, null)?.use { it.moveToFirst() } == true
        assertFalse(exists)
    }

    @Test fun emptyFilesArePublishedNormally() {
        bridge.payload = byteArrayOf()
        bridge.source = bridge.source!!.copy(size = 0)
        val inbox = coordinator()
        inbox.status()
        val file = inbox.status().files.single()
        assertEquals("saved", file.state)
        context.contentResolver.openInputStream(Uri.parse(file.uri))!!.use { assertEquals(-1, it.read()) }
        assertNull(bridge.source)
    }

    @Test fun incompleteCopiesAreNotPublishedAndKeepTheOriginal() {
        bridge.source = bridge.source!!.copy(size = bridge.payload.size.toLong() + 1)
        val inbox = coordinator()
        inbox.status()
        val failed = inbox.status().files.single()
        assertEquals("failed", failed.state)
        assertNotNull(bridge.source)
        assertEquals(0, pendingCount())
        repeat(3) { inbox.status() }
        assertEquals(1, bridge.saves)
    }

    @Test fun unjournaledPendingRowsAreCleanedAfterCrash() {
        bridge.source = null
        context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "orphan.txt")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Taildrop/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })
        assertEquals(1, pendingCount())
        coordinator().status()
        assertEquals(0, pendingCount())
    }
}
