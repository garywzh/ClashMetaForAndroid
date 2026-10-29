package com.github.kr328.clash

import android.content.ClipData
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.core.model.*
import com.github.kr328.clash.design.TaildropDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.util.withClash
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.*
import kotlin.coroutines.resume

class TaildropActivity : BaseActivity<TaildropDesign>() {
    private val json = Json { ignoreUnknownKeys = true }
    private val pending = mutableListOf<Uri>()
    private var refreshQueued = false

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        savedInstanceState?.getParcelableArrayList<Uri>("taildrop.pending")?.let { pending.addAll(it) }
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelableArrayList("taildrop.pending", ArrayList(pending))
        super.onSaveInstanceState(outState)
    }

    private suspend fun command(name: String, data: JsonObject = buildJsonObject {}): TaildropStatus =
        json.decodeFromString(TaildropStatus.serializer(), withClash { tailscaleCommand("taildrop-$name", data.toString()) })

    @Suppress("DEPRECATION")
    private fun readShare() {
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        // The exported Activity accepts content grants, never raw filesystem paths.
        if (pending.isEmpty()) pending.addAll(uris.filter { it.scheme == "content" }.distinct())
        intent.action = null
    }

    override suspend fun main() {
        readShare()
        var status = command("status")
        val design = TaildropDesign(this)
        setContentDesign(design)
        design.update(status, pending.size)
        intent.getStringExtra("taildrop.openId")?.let { id ->
            intent.removeExtra("taildrop.openId")
            status.files.firstOrNull { it.id == id && it.state == "saved" }?.let { showFileActions(it, status.session) }
        }
        if (pending.isNotEmpty() && status.targets.any { it.online }) design.requests.trySend(TaildropDesign.Request.Send)
        launch {
            while (isActive) {
                delay(1000)
                if (activityStarted && !refreshQueued) {
                    refreshQueued = true
                    design.requests.trySend(TaildropDesign.Request.Refresh)
                }
            }
        }
        while (isActive) {
            select<Unit> {
                events.onReceive { }
                design.requests.onReceive { request ->
                    try {
                        when (request) {
                            TaildropDesign.Request.Send -> {
                                if (pending.isEmpty()) pending.addAll(startActivityForResult(ActivityResultContracts.OpenMultipleDocuments(), arrayOf("*/*")).distinct())
                                if (pending.isNotEmpty()) {
                                    status = command("status")
                                    val targets = status.targets.filter { it.online }
                                    if (targets.isEmpty()) error(getString(R.string.taildrop_no_devices))
                                    val target = chooseTarget(targets)
                                    if (target != null) {
                                        for (uri in pending.toList()) {
                                            val metadata = metadata(uri)
                                            val result = command("send", buildJsonObject {
                                                put("uri", uri.toString()); put("name", metadata.name); put("size", metadata.size)
                                                put("target", target.id); put("session", status.session)
                                            })
                                            checkResult(result)
                                            pending.remove(uri)
                                        }
                                    }
                                }
                            }
                            TaildropDesign.Request.Refresh -> refreshQueued = false
                            TaildropDesign.Request.Configure -> startActivity(Intent(this@TaildropActivity, TailscaleActivity::class.java))
                            TaildropDesign.Request.Dismiss -> checkResult(command("dismiss"))
                            is TaildropDesign.Request.TransferActions -> {
                                if (chooseFileAction(request.transfer.name, listOf(R.string.taildrop_cancel_transfer)) != null) {
                                    checkResult(command(
                                        if (request.transfer.direction == "receive") "cancel-incoming" else "cancel",
                                        buildJsonObject { put("id", request.transfer.id); put("name", request.transfer.name); put("session", status.session) }
                                    ))
                                }
                            }
                            is TaildropDesign.Request.FileActions -> showFileActions(request.file, status.session)
                            is TaildropDesign.Request.Decide -> checkResult(command(
                                if (request.accept) "accept" else "reject",
                                buildJsonObject { put("id", request.offer.id); put("session", status.session) }
                            ))
                        }
                        status = command("status")
                        design.update(status, pending.size)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        design.showToast(e.message ?: getString(R.string.taildrop_failed), ToastDuration.Long)
                        design.update(status, pending.size)
                    }
                }
            }
        }
    }

    private fun checkResult(status: TaildropStatus) {
        check(status.error.isEmpty()) { status.error }
    }

    private suspend fun chooseTarget(targets: List<TaildropTarget>): TaildropTarget? = suspendCancellableCoroutine { continuation ->
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.taildrop_send_to)
            .setItems(targets.map { it.name }.toTypedArray()) { _, index -> if (continuation.isActive) continuation.resume(targets[index]) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> if (continuation.isActive) continuation.resume(null) }
            .setOnCancelListener { if (continuation.isActive) continuation.resume(null) }.show()
        continuation.invokeOnCancellation { dialog.dismiss() }
    }

    private suspend fun metadata(uri: Uri): TaildropFile = withContext(Dispatchers.IO) {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null).use { cursor ->
            check(cursor != null && cursor.moveToFirst()) { "Cannot read file information" }
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            TaildropFile(
                name = if (nameIndex >= 0) cursor.getString(nameIndex) ?: "File" else "File",
                size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1,
            )
        }
    }

    private suspend fun chooseFileAction(name: String, actions: List<Int>): Int? = suspendCancellableCoroutine { continuation ->
        val dialog = MaterialAlertDialogBuilder(this).setTitle(name)
            .setItems(actions.map { getString(it) }.toTypedArray()) { _, index ->
                if (continuation.isActive) continuation.resume(actions[index])
            }.setOnCancelListener { if (continuation.isActive) continuation.resume(null) }.show()
        continuation.invokeOnCancellation { dialog.dismiss() }
    }

    private suspend fun showFileActions(file: TaildropFile, session: String) {
        val actions = buildList {
            if (file.state == "saved") add(R.string.taildrop_open)
            if (file.state == "failed") add(R.string.taildrop_retry_download)
            add(R.string.taildrop_save)
            add(R.string.taildrop_share)
            add(R.string.delete)
        }
        val choice = chooseFileAction(file.name, actions)
        when (choice) {
            R.string.taildrop_open -> viewFile(file)
            R.string.taildrop_retry_download -> checkResult(command("retry-download", buildJsonObject { put("id", file.id) }))
            R.string.taildrop_save -> {
                val uri = startActivityForResult(ActivityResultContracts.CreateDocument("application/octet-stream"), file.name) ?: return
                if (file.state == "saved") {
                    withContext(Dispatchers.IO) {
                        require(uri != Uri.parse(file.uri)) { "Choose a different file" }
                        try {
                            contentResolver.openInputStream(Uri.parse(file.uri)).use { input ->
                                checkNotNull(input)
                                contentResolver.openOutputStream(uri, "w").use { output -> checkNotNull(output); input.copyTo(output) }
                            }
                        } catch (e: Exception) {
                            try { contentResolver.delete(uri, null, null) } catch (_: Exception) { }
                            throw e
                        }
                    }
                } else checkResult(command("save", buildJsonObject { put("uri", uri.toString()); put("name", file.name); put("fileId", file.id); put("session", session) }))
            }
            R.string.taildrop_share -> {
                val uri = if (file.state == "saved") Uri.parse(file.uri) else withContext(Dispatchers.IO) {
                    val extras = Bundle().apply { putString("session", session); putLong("size", file.size) }
                    @Suppress("DEPRECATION")
                    contentResolver.call(Uri.parse("content://$packageName.taildrop"), "share", file.name, extras)?.getParcelable<Uri>("uri")
                } ?: return
                val share = Intent(Intent.ACTION_SEND).setType(contentResolver.getType(uri) ?: "application/octet-stream")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .apply { clipData = ClipData.newRawUri(file.name, uri) }
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(share, getString(R.string.taildrop_share)))
            }
            R.string.delete -> MaterialAlertDialogBuilder(this).setTitle(R.string.delete).setMessage(file.name)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ -> launch {
                    try {
                        if (file.state == "saved" || file.state == "failed") checkResult(command("delete-download", buildJsonObject { put("id", file.id) }))
                        else checkResult(command("delete", buildJsonObject { put("name", file.name); put("fileId", file.id); put("session", session) }))
                        design?.requests?.trySend(TaildropDesign.Request.Refresh)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { design?.showToast(e.message ?: getString(R.string.taildrop_failed), ToastDuration.Long) }
                } }.show()
        }
    }

    private suspend fun viewFile(file: TaildropFile) {
        val uri = Uri.parse(file.uri)
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentResolver.getType(uri) ?: "application/octet-stream")
                .apply { clipData = ClipData.newRawUri(file.name, uri) }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: ActivityNotFoundException) {
            design?.showToast(R.string.taildrop_no_viewer, ToastDuration.Long)
        } catch (e: Exception) {
            design?.showToast(e.message ?: getString(R.string.taildrop_failed), ToastDuration.Long)
        }
    }
}
