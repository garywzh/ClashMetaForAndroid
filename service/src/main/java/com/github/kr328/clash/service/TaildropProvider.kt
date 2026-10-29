package com.github.kr328.clash.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.OpenableColumns
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.TaildropStatus
import kotlinx.serialization.json.*
import java.io.FileNotFoundException
import java.net.URLConnection
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// Grant-only, read-only URIs stream the inbox without making cache copies or
// exposing native state paths. Tokens expire and cannot cross VPN/login sessions.
class TaildropProvider : ContentProvider() {
    private data class Grant(val name: String, val size: Long, val session: String, val expires: Long)

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == Process.myUid()) { "Private operation" }
        require(method == "share" && !arg.isNullOrBlank())
        val session = requireNotNull(extras?.getString("session"))
        val size = extras.getLong("size", -1)
        grants.entries.removeAll { it.value.expires < SystemClock.elapsedRealtime() }
        require(grants.size < 128) { "Too many shared files; try again later" }
        val token = UUID.randomUUID().toString()
        grants[token] = Grant(arg, size, session, SystemClock.elapsedRealtime() + 60 * 60 * 1000)
        val uri = Uri.Builder().scheme("content").authority(requireNotNull(context).packageName + ".taildrop")
            .appendPath(token).appendPath(arg).build()
        return Bundle().apply { putParcelable("uri", uri) }
    }

    private fun grant(uri: Uri): Grant {
        val token = uri.pathSegments.firstOrNull()
        val grant = token?.let { grants[it] }
        if (grant == null || grant.expires < SystemClock.elapsedRealtime() || uri.pathSegments.getOrNull(1) != grant.name) {
            throw FileNotFoundException("Share expired; share the file again from Taildrop")
        }
        val current = json.decodeFromString(TaildropStatus.serializer(), Clash.tailscaleCommand("taildrop-session"))
        if (!current.running || current.session != grant.session) throw FileNotFoundException("Taildrop session ended")
        return grant
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val grant = grant(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { when (it) {
                OpenableColumns.DISPLAY_NAME -> grant.name
                OpenableColumns.SIZE -> grant.size.takeIf { size -> size >= 0 }
                else -> null
            } }.toTypedArray())
        }
    }

    override fun getType(uri: Uri): String = URLConnection.guessContentTypeFromName(grant(uri).name) ?: "application/octet-stream"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        val grant = grant(uri)
        val pipe = ParcelFileDescriptor.createReliablePipe()
        try {
            val request = buildJsonObject {
                put("name", grant.name)
                put("session", grant.session)
                put("fd", pipe[1].detachFd())
            }
            val result = json.decodeFromString(TaildropStatus.serializer(), Clash.tailscaleCommand("taildrop-save", request.toString()))
            if (result.error.isNotBlank()) throw FileNotFoundException(result.error)
            return pipe[0]
        } catch (e: Exception) {
            pipe[0].close()
            pipe[1].close()
            throw e
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    companion object {
        private val grants = ConcurrentHashMap<String, Grant>()
        private val json = Json { ignoreUnknownKeys = true }
        fun revoke(name: String) { grants.entries.removeAll { it.value.name == name } }
    }
}
