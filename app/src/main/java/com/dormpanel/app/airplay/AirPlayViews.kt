package com.dormpanel.app.airplay

import android.content.Context
import android.text.InputFilter
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.dormpanel.app.R
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.appearance.AppearanceState
import com.dormpanel.app.appearance.applyAppearanceTree
import com.dormpanel.app.dashboard.card.CardDisplayMetadata
import com.dormpanel.app.dashboard.card.CardInteractionScope
import com.dormpanel.app.dashboard.card.DashboardCardProvider
import com.dormpanel.app.dashboard.card.DashboardCardView
import com.dormpanel.app.dashboard.catalog.CardAddCandidate
import com.dormpanel.app.dashboard.catalog.CardCatalog
import com.dormpanel.app.dashboard.catalog.CardCategory
import com.dormpanel.app.dashboard.model.CardSize
import com.dormpanel.app.dashboard.model.ExplicitCardSizePolicy
import com.dormpanel.app.dashboard.model.PlacedCard

internal fun AirPlayPhase.label(context: Context): String = context.getString(when (this) {
    AirPlayPhase.OFF -> R.string.airplay_off
    AirPlayPhase.STARTING -> R.string.airplay_starting
    AirPlayPhase.WAITING -> R.string.airplay_waiting
    AirPlayPhase.CONNECTED -> R.string.airplay_connected
    AirPlayPhase.PLAYING -> R.string.airplay_playing
    AirPlayPhase.PAUSED -> R.string.airplay_paused
    AirPlayPhase.OFFLINE -> R.string.airplay_offline
    AirPlayPhase.FAILED -> R.string.airplay_failed
})

internal fun showAirPlaySettings(
    context: Context,
    appearance: AppearanceController,
    controller: AirPlayController,
) {
    val settings = controller.settings
    val dp = context.resources.displayMetrics.density
    val name = EditText(context).apply {
        setSingleLine()
        hint = context.getString(R.string.airplay_name)
        filters = arrayOf(InputFilter.LengthFilter(48))
        setText(settings.name)
    }
    val boot = SwitchCompat(context).apply {
        setText(R.string.airplay_boot)
        minHeight = (48 * dp).toInt()
        isChecked = settings.autoStart
    }
    val status = TextView(context).apply { maxLines = 3 }
    val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (24 * dp).toInt()
        setPadding(padding, 0, padding, padding)
        addView(name)
        addView(boot)
        addView(status)
        addView(TextView(context).apply { setText(R.string.airplay_connect_help) })
    }
    applyAppearanceTree(body, appearance.state)
    val dialog = AlertDialog.Builder(context)
        .setTitle(R.string.airplay_settings)
        .setView(body)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(android.R.string.ok, null)
        .create()
    val listener: (AirPlayState) -> Unit = {
        status.text = listOfNotNull(it.phase.label(context), it.detail).joinToString(" · ")
    }
    dialog.setOnDismissListener { controller.removeListener(listener) }
    dialog.show()
    controller.addListener(listener)
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val value = name.text.toString()
        if (AirPlayController.validName(value) == null) {
            name.error = context.getString(R.string.airplay_invalid_name)
        } else if (controller.configure(value, controller.settings.enabled, boot.isChecked)) {
            dialog.dismiss()
        } else {
            name.error = context.getString(R.string.airplay_save_failed)
        }
    }
}

private const val CARD_TYPE = "airplay_music"

internal class AirPlayCatalog(context: Context) : CardCatalog {
    override val candidates = listOf(CardAddCandidate(CARD_TYPE, CardCategory.SYSTEM, CARD_TYPE,
        context.getString(R.string.airplay_card), context.getString(R.string.airplay_card_description), "{}"))
    override fun addListener(listener: (List<CardAddCandidate>) -> Unit) = listener(candidates)
    override fun removeListener(listener: (List<CardAddCandidate>) -> Unit) = Unit
}

internal class AirPlayCardProvider(
    private val appearance: AppearanceController,
    private val source: AirPlayController,
) : DashboardCardProvider {
    override val typeKey = CARD_TYPE
    override val displayMetadata = CardDisplayMetadata("", "", R.string.airplay_card, R.string.airplay_card_description)
    override val defaultSize = CardSize(3, 1)
    override val sizePolicy = ExplicitCardSizePolicy(listOf(defaultSize, CardSize(4, 1), CardSize(4, 3), CardSize(5, 3)))
    override fun createView(context: Context): View = AirPlayCardView(context, appearance, source)
    override fun bind(view: View, card: PlacedCard, interactions: CardInteractionScope) =
        (view as AirPlayCardView).bind(card, interactions)
}

private class AirPlayCardView(
    context: Context,
    appearance: AppearanceController,
    private val source: AirPlayController,
) : DashboardCardView(context, appearance) {
    private val title = label(21f).apply { maxLines = 1 }
    private val status = label(14f, secondary = true).apply { maxLines = 1 }
    private val credits = label(15f, secondary = true).apply { maxLines = 1 }
    private val artwork = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1_000 }
    private val buttons = row()
    private var artworkShown: android.graphics.Bitmap? = null
    private val actions = mapOf(
        AirPlayCommand.PREVIOUS to R.string.airplay_previous,
        AirPlayCommand.PLAY to R.string.airplay_play,
        AirPlayCommand.PAUSE to R.string.airplay_pause,
        AirPlayCommand.NEXT to R.string.airplay_next,
    ).mapValues { (command, label) ->
        Button(context).apply {
            setText(label)
            isAllCaps = false
            minWidth = 48.dp
            minimumWidth = 48.dp
            minHeight = 48.dp
            minimumHeight = 48.dp
            setPadding(4.dp, 0, 4.dp, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener {
                if (interactions.enabled) {
                    interactions.claimGesture()
                    source.command(command)
                }
            }
            buttons.addView(this, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
    }
    private val listener: (AirPlayState) -> Unit = { refresh() }

    init {
        val header = row()
        header.addView(artwork, LayoutParams(60.dp, 60.dp).apply { marginEnd = 12.dp })
        header.addView(column().apply {
            addView(title)
            addView(credits)
            addView(status)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(header)
        addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, 12.dp))
        addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        source.addListener(listener)
    }

    override fun onDetachedFromWindow() {
        source.removeListener(listener)
        artwork.setImageDrawable(null)
        artworkShown = null
        super.onDetachedFromWindow()
    }

    override fun applyAppearance(state: AppearanceState) {
        super.applyAppearance(state)
        applyAppearanceTree(buttons, state)
    }

    override fun render() {
        val snapshot = source.state
        val expanded = card.size.rowSpan >= 3
        val image = snapshot.artwork.takeIf { expanded && isAttachedToWindow }
        if (artworkShown !== image) {
            artwork.setImageBitmap(image)
            artworkShown = image
        }
        artwork.show(image != null)
        title.text = snapshot.track.title.ifBlank { context.getString(R.string.airplay_card) }
        status.text = listOfNotNull(snapshot.phase.label(context), snapshot.detail).joinToString(" · ")
        credits.text = listOf(snapshot.track.artist, snapshot.track.album).filter(String::isNotBlank).joinToString(" · ")
        credits.show(expanded && credits.text.isNotBlank())
        val report = snapshot.progress
        progress.show(expanded && report != null)
        if (report != null) {
            progress.progress = (report.positionMs * 1_000 / report.durationMs).toInt()
            progress.contentDescription = context.getString(R.string.airplay_progress,
                time(report.positionMs), time(report.durationMs))
        }
        var hasControls = false
        actions.forEach { (command, button) ->
            val visible = expanded && interactions.enabled && !interactions.isResizePreview &&
                command in snapshot.commands && when (command) {
                    AirPlayCommand.PLAY -> snapshot.phase != AirPlayPhase.PLAYING
                    AirPlayCommand.PAUSE -> snapshot.phase == AirPlayPhase.PLAYING
                    else -> true
                }
            button.show(visible)
            interactions.claimFromDown(button)
            hasControls = hasControls || visible
        }
        buttons.show(hasControls)
        describe(title.text, status.text, credits.text)
    }

    private fun time(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1_000 % 60)
}
