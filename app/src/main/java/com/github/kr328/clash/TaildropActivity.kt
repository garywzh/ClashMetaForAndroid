package com.github.kr328.clash

import android.content.ClipData
import android.content.Intent
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
        if (pending.isNotEmpty() && status.targets.any { it.online }) design.requests.trySend(TaildropDesign.Request.Send)
        launch {
            while (isActive) {
                delay(1500)
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
                            is TaildropDesign.Request.Cancel -> checkResult(command(
                                if (request.transfer.direction == "receive") "cancel-incoming" else "cancel",
                                buildJsonObject { put("id", request.transfer.id); put("name", request.transfer.name); put("session", status.session) }
                            ))
                            is TaildropDesign.Request.Open -> openFile(request.file, status.session)
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

    private suspend fun openFile(file: TaildropFile, session: String) {
        val choice = suspendCancellableCoroutine<Int?> { continuation ->
            val dialog = MaterialAlertDialogBuilder(this).setTitle(file.name)
                .setItems(arrayOf(getString(R.string.taildrop_save), getString(R.string.taildrop_share), getString(R.string.delete))) { _, index ->
                    if (continuation.isActive) continuation.resume(index)
                }.setOnCancelListener { if (continuation.isActive) continuation.resume(null) }.show()
            continuation.invokeOnCancellation { dialog.dismiss() }
        }
        when (choice) {
            0 -> {
                val uri = startActivityForResult(ActivityResultContracts.CreateDocument("application/octet-stream"), file.name) ?: return
                checkResult(command("save", buildJsonObject { put("uri", uri.toString()); put("name", file.name); put("session", session) }))
            }
            1 -> {
                val uri = withContext(Dispatchers.IO) {
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
            2 -> MaterialAlertDialogBuilder(this).setTitle(R.string.delete).setMessage(file.name)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ -> launch {
                    try {
                        checkResult(command("delete", buildJsonObject { put("name", file.name); put("session", session) }))
                        design?.requests?.trySend(TaildropDesign.Request.Refresh)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { design?.showToast(e.message ?: getString(R.string.taildrop_failed), ToastDuration.Long) }
                } }.show()
        }
    }
}
