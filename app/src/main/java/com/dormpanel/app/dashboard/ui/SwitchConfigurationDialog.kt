package com.dormpanel.app.dashboard.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.dormpanel.app.R
import com.dormpanel.app.dashboard.card.SWITCH_GROUP_CARD
import com.dormpanel.app.dashboard.card.SwitchCardConfiguration
import com.dormpanel.app.dashboard.card.SwitchChannelBinding
import com.dormpanel.app.data.DashboardSwitches
import com.dormpanel.app.ui.hidePanelSystemBars

internal class SwitchConfigurationDialog(
    private val context: Context,
    private val type: String,
    initial: SwitchCardConfiguration?,
    private val current: () -> DashboardSwitches,
    private val confirm: (String) -> Boolean,
) {
    private val grouped = type == SWITCH_GROUP_CARD
    private var sourceId = initial?.sourceId.orEmpty()
    private var deviceId = initial?.deviceId
    private val bindings = MutableList(if (grouped) 3 else 1) { initial?.channels?.getOrNull(it) }
    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20.dp, 8.dp, 20.dp, 8.dp)
    }
    private val deviceName = edit(R.string.switch_custom_device).apply { setText(initial?.name.orEmpty()) }
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val aliases = mutableListOf<EditText>()
    private val devicePicker = button("") { chooseDevice() }
    private var chooser: AlertDialog? = null
    private val dialog = AlertDialog.Builder(context)
        .setTitle(if (grouped) R.string.card_switch_group else R.string.card_switch)
        .setView(ScrollView(context).apply { addView(body) })
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.switch_save, null)
        .create()

    init {
        if (grouped) {
            body.addView(devicePicker)
            body.addView(deviceName)
            body.addView(android.widget.TextView(context).apply { setText(R.string.switch_order_hint) })
        }
        body.addView(rows)
        render()
    }

    fun show() {
        dialog.show()
        dialog.window?.hidePanelSystemBars()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { save() }
    }

    fun dismiss() {
        chooser?.dismiss()
        dialog.dismiss()
    }

    private fun chooseDevice() {
        val live = current()
        val ids = live.channels.values.mapNotNull { it.identity.deviceId }.distinct()
            .sortedWith(compareBy({ live.deviceNames[it] }, { it }))
        choose(R.string.switch_choose_device, ids.map { live.deviceNames[it] ?: it }) { index ->
            val id = ids[index]
            if (sourceId != live.sourceId || deviceId != id) {
                sourceId = live.sourceId
                deviceId = id
                bindings.fill(null)
                deviceName.setText("")
                render()
            }
        }
    }

    private fun chooseChannel(slot: Int) {
        captureAliases()
        val live = current()
        val choices = live.channels.values.filter {
            !grouped || (sourceId == live.sourceId && it.identity.deviceId == deviceId)
        }.sortedWith(compareBy({ it.name }, { it.identity.entityId }))
        val offset = if (grouped) 1 else 0
        val names = choices.map { "${it.name}  ·  ${it.identity.entityId.removePrefix("ha:")}" }
        choose(R.string.switch_choose_channel, if (grouped) listOf(body.context.getString(R.string.switch_empty_slot)) + names else names) { index ->
            val channel = choices.getOrNull(index - offset)
            if (!grouped && channel != null) {
                sourceId = live.sourceId
                deviceId = channel.identity.deviceId
            }
            val old = bindings[slot]
            val existing = bindings.indexOfFirst { it?.identity == channel?.identity && it != null }
            if (existing >= 0 && existing != slot) {
                bindings[slot] = bindings[existing]
                bindings[existing] = old
            } else {
                bindings[slot] = channel?.let { SwitchChannelBinding(it.identity, old?.name.orEmpty()) }
            }
            render()
        }
    }

    private fun choose(title: Int, labels: List<String>, selected: (Int) -> Unit) {
        if (!current().connected || labels.isEmpty()) {
            Toast.makeText(body.context, R.string.switch_choose_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        chooser = AlertDialog.Builder(body.context).setTitle(title)
            .setItems(labels.toTypedArray()) { _, index -> selected(index) }
            .setNegativeButton(android.R.string.cancel, null).show()
        chooser?.window?.hidePanelSystemBars()
    }

    private fun render() {
        val live = current()
        val context = body.context
        devicePicker.text = deviceId?.let { id ->
            live.deviceNames[id].takeIf { live.sourceId == sourceId } ?: id
        } ?: context.getString(R.string.switch_choose_device)
        rows.removeAllViews()
        aliases.clear()
        bindings.forEachIndexed { index, binding ->
            val row = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            val identity = binding?.identity
            val currentName = identity?.let { live.resolve(sourceId, it)?.name }
            val label = when {
                currentName != null -> "$currentName · ${identity.entityId.removePrefix("ha:")}"
                identity != null -> "${identity.entityId.removePrefix("ha:")} · ${context.getString(R.string.state_unavailable)}"
                else -> context.getString(R.string.switch_choose_channel)
            }
            row.addView(button(if (grouped) "${index + 1}. $label" else label) { chooseChannel(index) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val alias = edit(R.string.switch_custom_channel).apply {
                setText(binding?.name.orEmpty())
                isEnabled = binding != null
                if (grouped) contentDescription = "${index + 1}. ${context.getString(R.string.switch_custom_channel)}"
            }
            aliases += alias
            row.addView(alias, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f))
            rows.addView(row)
        }
    }

    private fun captureAliases() {
        bindings.indices.forEach { index -> bindings[index] = bindings[index]?.copy(name = aliases[index].text.toString().trim()) }
    }

    private fun save() {
        captureAliases()
        val live = current()
        val config = SwitchCardConfiguration(sourceId, deviceId, bindings.filterNotNull(),
            if (grouped) deviceName.text.toString().trim() else "")
        if (!live.connected || !config.isValid(type) || config.channels.any { live.resolve(sourceId, it.identity) == null }) {
            Toast.makeText(body.context, R.string.switch_invalid_selection, Toast.LENGTH_SHORT).show()
            render()
        } else if (confirm(config.encode())) dismiss()
    }

    private fun button(label: String, click: () -> Unit) = Button(body.context).apply {
        text = label
        textSize = 16f
        isAllCaps = false
        minHeight = 48.dp
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        setOnClickListener { click() }
    }

    private fun edit(hint: Int) = EditText(body.context).apply {
        setSingleLine()
        setHint(hint)
        textSize = 16f
        minHeight = 48.dp
        filters = arrayOf(android.text.InputFilter.LengthFilter(120))
    }

    private val Int.dp get() = (this * context.resources.displayMetrics.density).toInt()
}
