package com.github.kr328.clash.design

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.github.kr328.clash.core.model.TailscaleForwardRule
import com.github.kr328.clash.core.model.TailscaleStatus
import com.github.kr328.clash.design.databinding.AdapterPortForwardBinding
import com.github.kr328.clash.design.databinding.DesignTailnetBinding
import com.github.kr328.clash.design.databinding.ViewTailnetNoteBinding
import com.github.kr328.clash.design.databinding.DialogPortForwardBinding
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.util.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.UUID

class PortForwardingDesign(context: Context) : Design<PortForwardingDesign.Request>(context) {
    sealed interface Request {
        data object Refresh : Request
        data object Configure : Request
        data class Put(val rule: TailscaleForwardRule) : Request
        data class Delete(val id: String) : Request
        data class Copy(val rule: TailscaleForwardRule) : Request
    }

    private val binding = DesignTailnetBinding.inflate(context.layoutInflater, context.root, false)
    override val root: View get() = binding.root
    private val rules = preferenceScreen(context) {}
    private lateinit var statusRow: ClickablePreference
    private lateinit var emptyRow: ClickablePreference
    private var current = TailscaleStatus()
    private var rendered: TailscaleStatus? = null
    private var editor: DialogPortForwardBinding? = null
    private var editing: TailscaleForwardRule? = null
    private var editorDialog: AlertDialog? = null
    private var deleteDialog: AlertDialog? = null

    init {
        binding.surface = surface
        binding.activityBarLayout.applyFrom(context)
        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)
        binding.primaryAction.apply {
            visibility = View.VISIBLE
            setText(R.string.port_forward_add_action)
            contentDescription = context.getString(R.string.port_forward_add)
            setOnClickListener { editRule(null) }
        }
        val screen = preferenceScreen(context) {
            statusRow = clickable(R.string.tailscale, summary = R.string.port_forward_start_vpn) {
                clicked { requests.trySend(Request.Configure) }
            }
            emptyRow = clickable(R.string.port_forward_empty, summary = R.string.port_forward_empty_hint) {
                view.isClickable = false
                view.isFocusable = false
            }
            root.addView(rules.root)
            root.addView(ViewTailnetNoteBinding.inflate(context.layoutInflater, root, false).apply {
                noteView.setText(R.string.port_forward_scope)
            }.root)
        }
        binding.content.addView(screen.root)
    }

    fun update(status: TailscaleStatus) {
        current = status
        statusRow.view.visibility = if (!status.enabled || status.state != "Running") View.VISIBLE else View.GONE
        statusRow.summary = context.getString(status.connectionState())
        binding.primaryAction.isEnabled = status.forwards.size < 32
        emptyRow.view.visibility = if (status.forwards.isEmpty()) View.VISIBLE else View.GONE
        val previous = rendered
        if (previous != null && previous.forwards == status.forwards &&
            previous.forwardStatus == status.forwardStatus && previous.ips == status.ips &&
            previous.state == status.state && previous.enabled == status.enabled) return
        rendered = status
        rules.root.removeAllViews()
        status.forwards.forEach { rule ->
            val row = AdapterPortForwardBinding.inflate(context.layoutInflater, rules.root, false)
            row.titleView.text = rule.name
            val live = status.forwardStatus.firstOrNull { it.id == rule.id }
            val problem = when {
                !rule.enabled -> ""
                !status.enabled || status.state != "Running" -> ""
                live?.error?.isNotBlank() == true -> live.error
                live?.listening != true -> context.getString(R.string.port_forward_not_listening)
                else -> ""
            }
            row.summaryView.text = listOf("Tailnet :${rule.tailnetPort} → 127.0.0.1:${rule.localPort}", problem)
                .filter { it.isNotEmpty() }.joinToString("\n")
            row.switchView.isChecked = rule.enabled
            row.switchView.contentDescription = context.getString(R.string.port_forward_toggle, rule.name)
            row.switchView.setOnCheckedChangeListener { _, checked -> requests.trySend(Request.Put(rule.copy(enabled = checked))) }
            row.copyView.contentDescription = context.getString(R.string.port_forward_copy, rule.name)
            row.copyView.isEnabled = status.ips.isNotEmpty()
            row.copyView.alpha = if (row.copyView.isEnabled) 1f else 0.33f
            row.copyView.setOnClickListener { requests.trySend(Request.Copy(rule)) }
            row.root.setOnClickListener { editRule(rule) }
            rules.root.addView(row.root)
        }
    }

    private fun editRule(rule: TailscaleForwardRule?, draft: Bundle? = null) {
        if (editor != null) return
        val form = DialogPortForwardBinding.inflate(context.layoutInflater, context.root, false)
        editing = rule
        editor = form
        form.nameField.setText(draft?.getString("name") ?: rule?.name.orEmpty())
        form.tailnetField.setText(draft?.getString("tailnet") ?: rule?.tailnetPort?.toString().orEmpty())
        form.localField.setText(draft?.getString("local") ?: rule?.localPort?.toString().orEmpty())
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(if (rule == null) R.string.port_forward_add else R.string.port_forward_edit)
            .setView(form.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { editor = null; editing = null }
        if (rule != null) builder.setNeutralButton(R.string.delete) { _, _ ->
            deleteDialog = MaterialAlertDialogBuilder(context)
                .setMessage(context.getString(R.string.port_forward_delete_confirm, rule.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ -> requests.trySend(Request.Delete(rule.id)) }
                .show()
        }
        val dialog = builder.create()
        editorDialog = dialog
        dialog.setOnShowListener {
            var attempted = false
            fun validate(showErrors: Boolean): Boolean {
                val name = form.nameField.text.toString().trim()
                val local = form.localField.text.toString().toIntOrNull()
                val tailnet = form.tailnetField.text.toString().toIntOrNull()
                val duplicate = current.forwards.any { it.id != rule?.id && it.tailnetPort == tailnet }
                if (showErrors) {
                    form.nameLayout.error = if (name.isEmpty()) context.getString(R.string.port_forward_name_required) else null
                    form.localLayout.error = if (local !in 1..65535) context.getString(R.string.port_forward_invalid_port) else null
                    form.tailnetLayout.error = when {
                        tailnet !in 1..65535 -> context.getString(R.string.port_forward_invalid_port)
                        duplicate -> context.getString(R.string.port_forward_duplicate_port)
                        else -> null
                    }
                }
                return name.isNotEmpty() && local in 1..65535 && tailnet in 1..65535 && !duplicate
            }
            listOf(form.nameField, form.localField, form.tailnetField).forEach { field ->
                field.doAfterTextChanged { validate(attempted) }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                attempted = true
                if (validate(true)) {
                    val updated = TailscaleForwardRule(
                        id = rule?.id ?: UUID.randomUUID().toString(),
                        name = form.nameField.text.toString().trim(), enabled = rule?.enabled ?: true,
                        localPort = form.localField.text.toString().toInt(),
                        tailnetPort = form.tailnetField.text.toString().toInt(),
                    )
                    requests.trySend(Request.Put(updated))
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    fun saveEditor(): Bundle? = editor?.let { form -> Bundle().apply {
        putString("id", editing?.id)
        putString("name", form.nameField.text.toString())
        putString("tailnet", form.tailnetField.text.toString())
        putString("local", form.localField.text.toString())
    } }

    fun restoreEditor(draft: Bundle?) {
        if (draft == null) return
        val id = draft.getString("id")
        val rule = current.forwards.firstOrNull { it.id == id }
        if (id == null || rule != null) editRule(rule, draft)
    }

    fun dismissDialogs() {
        editorDialog?.dismiss()
        deleteDialog?.dismiss()
    }
}
