package com.github.kr328.clash.service

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.TaildropFile
import com.github.kr328.clash.core.model.TaildropStatus
import com.github.kr328.clash.core.model.TaildropTransfer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileNotFoundException
import java.net.URLConnection
import java.util.UUID

// This coordinator runs only when polled by the existing VPN module or Activity.
// Pending MediaStore rows stay hidden until the native copy has closed its FD.
internal class TaildropDownloads(
    private val context: Context,
    private val native: (String, String) -> TaildropStatus = { command, content ->
        json.decodeFromString(TaildropStatus.serializer(), Clash.tailscaleCommand(command, content))
    },
    private val journal: AtomicFile = AtomicFile(File(context.filesDir, "taildrop-downloads.json")),
) {
    @Serializable
    private data class Entry(
        val id: String,
        val name: String,
        val size: Long,
        var session: String = "",
        var job: String = "",
        var uri: String = "",
        var displayName: String = name,
        var state: String = "waiting",
        var error: String = "",
        var cleanup: Boolean = true,
    )

    private val resolver = context.contentResolver
    private val serializer = ListSerializer(Entry.serializer())
    private val entries = try {
        json.decodeFromString(serializer, journal.openRead().bufferedReader().use { it.readText() }).toMutableList()
    } catch (_: FileNotFoundException) { mutableListOf() }
    private val speeds = TaildropSpeedTracker()
    private var initialized = false
    private var lastPrune = -15_000L
    private var lastWritten: String? = null

    private fun persist() {
        val content = json.encodeToString(serializer, entries)
        if (content == lastWritten) return
        val stream = journal.startWrite()
        try {
            stream.write(content.toByteArray(Charsets.UTF_8))
            journal.finishWrite(stream)
            lastWritten = content
        } catch (e: Exception) {
            journal.failWrite(stream)
            throw e
        }
    }

    private fun invoke(command: String, entry: Entry): TaildropStatus = native(command, buildJsonObject {
        put("name", entry.name); put("fileId", entry.id); put("session", entry.session)
        put("id", entry.job)
    }.toString())

    private fun removePending(entry: Entry) {
        if (entry.uri.isNotEmpty()) resolver.delete(Uri.parse(entry.uri), null, null)
        entry.uri = ""
    }

    private fun recoverOrphans() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val known = entries.map { it.uri }.toSet()
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        @Suppress("DEPRECATION")
        val pendingCollection = MediaStore.setIncludePending(collection)
        resolver.query(pendingCollection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.IS_PENDING}=1 AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?",
            arrayOf(DIRECTORY, context.packageName), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                if (uri.toString() !in known) resolver.delete(uri, null, null)
            }
        }
    }

    private fun start(entry: Entry, session: String): TaildropStatus? {
        try {
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { context.getString(R.string.taildrop_downloads_legacy) }
            if (entry.uri.isNotEmpty()) removePending(entry)
            entry.session = session
            entry.job = "download-" + UUID.randomUUID()
            entry.state = "allocating"
            entry.error = ""
            persist()
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, entry.name)
                put(MediaStore.MediaColumns.MIME_TYPE, URLConnection.guessContentTypeFromName(entry.name) ?: "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, DIRECTORY)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            entry.uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)).toString()
            entry.state = "saving"
            persist()
            val result = resolver.openFileDescriptor(Uri.parse(entry.uri), "w").use { descriptor ->
                requireNotNull(descriptor)
                native("taildrop-save", buildJsonObject {
                    put("name", entry.name); put("fileId", entry.id); put("session", session)
                    put("id", entry.job); put("download", true); put("size", entry.size)
                    put("fd", descriptor.detachFd())
                }.toString())
            }
            check(result.error.isEmpty()) { result.error }
            return result
        } catch (e: Exception) {
            try { removePending(entry) } catch (_: Exception) { }
            entry.state = "failed"
            entry.error = e.message ?: context.getString(R.string.taildrop_download_failed)
            persist()
            return null
        }
    }

    private fun publish(entry: Entry) {
        var verified = false
        try {
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            val uri = Uri.parse(entry.uri)
            resolver.openFileDescriptor(uri, "r").use { descriptor ->
                check(descriptor != null && (entry.size < 0 || descriptor.statSize == entry.size)) {
                    context.getString(R.string.taildrop_download_incomplete)
                }
            }
            verified = true
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) entry.displayName = it.getString(0)
            }
            entry.state = "saved"
            entry.error = ""
            persist()
        } catch (e: Exception) {
            // Publication is idempotent. Never erase a potentially published file.
            entry.state = if (verified) "publish_failed" else "failed"
            if (!verified) try { removePending(entry) } catch (_: Exception) { }
            entry.error = e.message ?: context.getString(R.string.taildrop_download_failed)
            persist()
        }
    }

    @Synchronized fun status(): TaildropStatus {
        var status = native("taildrop-status", "")
        if (!initialized) {
            recoverOrphans()
            initialized = true
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastPrune >= 15_000) {
            lastPrune = now
            entries.filter { it.state == "saved" }.forEach { entry ->
                val exists = resolver.query(Uri.parse(entry.uri), arrayOf(OpenableColumns.SIZE), null, null, null)?.use { it.moveToFirst() } == true
                if (!exists) entry.state = "deleted"
            }
        }
        for (entry in entries.toList()) {
            if (status.running && status.files.any { it.id == entry.id }) entry.session = status.session
            if (entry.state == "saving" || entry.state == "allocating") {
                val job = status.transfers.firstOrNull { it.id == entry.job }
                when {
                    job?.state == "done" -> {
                        // Journal this before publishing, so a crash cannot make a second copy.
                        entry.state = "publishing"
                        persist()
                    }
                    !status.running || status.session != entry.session || job == null -> {
                        invoke("taildrop-cancel", entry)
                        removePending(entry)
                        entry.state = "waiting"
                    }
                    job.finished -> {
                        removePending(entry)
                        entry.state = "failed"
                        entry.error = job.error.ifEmpty { context.getString(R.string.taildrop_download_cancelled) }
                    }
                }
            }
            if (entry.state == "publishing") publish(entry)
            if (entry.state == "saved" || entry.state == "deleted") {
                if (entry.cleanup && status.running) {
                    val source = status.files.firstOrNull { it.id == entry.id }
                    if (source == null && status.error.isEmpty()) entry.cleanup = false
                    else if (source != null) {
                        entry.session = status.session
                        if (invoke("taildrop-delete", entry).error.isEmpty()) {
                            entry.cleanup = false
                            TaildropProvider.revoke(entry.name)
                        }
                    }
                }
                if (entry.state == "deleted" && !entry.cleanup) entries.remove(entry)
            }
            if (status.running && status.error.isEmpty() && entry.uri.isEmpty() &&
                entry.state in listOf("waiting", "failed") && status.files.none { it.id == entry.id }) entries.remove(entry)
        }
        if (status.running) {
            for (file in status.files) {
                if (entries.count { it.state == "saving" } >= 2) break
                var entry = entries.firstOrNull { it.id == file.id }
                if (entry == null) {
                    entry = Entry(file.id, file.name, file.size)
                    entries += entry
                }
                if (entry.state == "waiting") {
                    start(entry, status.session)?.let { result ->
                        status = status.copy(transfers = (status.transfers + result.transfers).distinctBy { it.id })
                    }
                }
            }
        }
        persist()
        val files = entries.asReversed().filter { it.state == "saved" }.map {
            TaildropFile(it.displayName, it.size, it.id, it.uri, "saved")
        } + status.files.mapNotNull { file ->
            val entry = entries.firstOrNull { it.id == file.id }
            when (entry?.state) {
                "saving", "publishing", "saved", "deleted" -> null
                "failed", "publish_failed" -> file.copy(state = "failed", error = entry.error)
                else -> file
            }
        }
        val transfers = status.transfers.map { transfer ->
            val entry = entries.firstOrNull { it.job == transfer.id }
            if (transfer.direction != "download" || entry == null) transfer
            else when (entry.state) {
                "publishing" -> transfer.copy(state = "saving")
                "publish_failed", "failed" -> transfer.copy(state = "failed", error = entry.error)
                else -> transfer
            }
        }
        return speeds.update(status.copy(files = files, transfers = transfers), now)
    }

    @Synchronized fun retry(id: String) {
        val entry = requireNotNull(entries.firstOrNull { it.id == id })
        check(entry.state == "failed" || entry.state == "publish_failed")
        entry.state = if (entry.state == "publish_failed") "publishing" else "waiting"
        entry.error = ""
        persist()
    }

    @Synchronized fun delete(id: String) {
        val entry = requireNotNull(entries.firstOrNull { it.id == id })
        if (entry.state == "saved" || entry.state == "publish_failed") {
            if (entry.uri.isNotEmpty()) resolver.delete(Uri.parse(entry.uri), null, null)
        } else {
            check(invoke("taildrop-delete", entry).error.isEmpty()) { context.getString(R.string.taildrop_download_delete_failed) }
            entry.cleanup = false
        }
        entry.state = "deleted"
        if (!entry.cleanup) entries.remove(entry)
        persist()
    }

    @Synchronized fun stop() {
        entries.filter { it.state == "saving" || it.state == "allocating" }.forEach {
            invoke("taildrop-cancel", it)
            removePending(it)
            it.state = "waiting"
        }
        persist()
    }

    companion object {
        private const val DIRECTORY = "Download/Taildrop/"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        @Volatile private var instance: TaildropDownloads? = null
        fun get(context: Context): TaildropDownloads = instance ?: synchronized(this) {
            instance ?: TaildropDownloads(context.applicationContext).also { instance = it }
        }

        fun query(context: Context): TaildropStatus = try { get(context).status() } catch (e: Exception) {
            val status = try {
                json.decodeFromString(TaildropStatus.serializer(), Clash.tailscaleCommand("taildrop-status"))
            } catch (_: Exception) { TaildropStatus() }
            status.copy(error = e.message ?: context.getString(R.string.taildrop_download_failed))
        }
    }
}
