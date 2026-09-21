package com.dormpanel.app.apps

import android.annotation.SuppressLint
import android.content.Context
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.dormpanel.app.R
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.dashboard.card.*
import com.dormpanel.app.dashboard.model.*

class AppCardProvider(private val source: InstalledAppSource, private val icons: AppIcons,
    private val appearance: AppearanceController,
) : DashboardCardProvider {
    override val typeKey = "app"
    override val displayMetadata = CardDisplayMetadata("App", "App shortcut", R.string.category_apps, R.string.app_shortcut)
    override val sizePolicy = ExplicitCardSizePolicy(listOf(CardSize(1, 1), CardSize(2, 1), CardSize(2, 2)))
    override val defaultSize = CardSize(2, 1)
    override fun createView(context: Context): View = AppCardView(context, appearance, source, icons)
    override fun bind(view: View, card: PlacedCard, interactions: CardInteractionScope) = (view as AppCardView).bind(card, interactions)
}

@SuppressLint("ViewConstructor")
class AppCardView(context: Context, appearance: AppearanceController,
    private val source: InstalledAppSource, private val icons: AppIcons,
) : DashboardCardView(context, appearance) {
    private val icon = ImageView(context)
    private val title = label(20f)
    private val detail = label(14f, true)
    private var dialog: AlertDialog? = null
    private val listener: (List<InstalledApp>) -> Unit = { refresh() }
    init {
        gravity = Gravity.CENTER; setPadding(8.dp, 6.dp, 8.dp, 6.dp)
        title.gravity = Gravity.CENTER; detail.gravity = Gravity.CENTER
        addView(icon, LayoutParams(32.dp, 32.dp)); addView(title); addView(detail)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); source.addListener(listener) }
    override fun onDetachedFromWindow() { source.removeListener(listener); icons.cancel(icon); dialog?.dismiss(); super.onDetachedFromWindow() }
    override fun render() {
        val identity = AppConfiguration.decode(card.configurationJson).component
        val app = source.apps.firstOrNull { it.component == identity }
        val large = card.size.rowSpan > 1
        val small = card.size.columnSpan == 1
        val iconSize = if (large) 72.dp else if (small) 36.dp else 44.dp
        icon.layoutParams = LayoutParams(iconSize, iconSize)
        title.textSize = if (small) 16f else 22f; title.maxLines = if (large) 2 else 1
        title.text = app?.label ?: context.getString(R.string.state_unavailable)
        detail.text = app?.packageName ?: identity
        detail.show(large)
        icons.bind(icon, app?.component ?: "")
        describe(title.text, if (app == null) identity else context.getString(R.string.app_shortcut))
    }
    override fun primaryAction() = launchApp(context, source, AppConfiguration.decode(card.configurationJson).component)
    override fun secondaryAction() {
        dialog = AlertDialog.Builder(context).setTitle(title.text)
            .setItems(arrayOf(context.getString(R.string.apps_open), context.getString(R.string.apps_settings))) { _, action ->
                if (action == 0) primaryAction()
                else openAppSettings(context, source, AppConfiguration.decode(card.configurationJson).component)
            }.show()
    }
}
