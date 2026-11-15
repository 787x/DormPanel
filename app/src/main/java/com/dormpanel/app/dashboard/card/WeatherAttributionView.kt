package com.dormpanel.app.dashboard.card

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.dormpanel.app.weather.WeatherAttribution

internal const val QWEATHER_WEBSITE = "https://www.qweather.com"

internal fun View.openWeatherLink(url: String) {
    val uri = Uri.parse(url)
    if (uri.scheme != "http" && uri.scheme != "https") return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: ActivityNotFoundException) {
        AlertDialog.Builder(context).setTitle("设备没有可用的浏览器")
            .setMessage(url).setPositiveButton("关闭", null).show()
    }
}

internal fun TextView.showWeatherAttribution(attribution: WeatherAttribution?) {
    val content = (attribution?.sources.orEmpty() + attribution?.statements.orEmpty() +
        attribution?.licenses.orEmpty() + listOfNotNull(attribution?.link)).joinToString("\n")
    visibility = if (content.isEmpty()) View.GONE else View.VISIBLE
    if (text.toString() == content) return
    val spans = SpannableString(content)
    Linkify.addLinks(spans, Linkify.WEB_URLS)
    spans.getSpans(0, spans.length, URLSpan::class.java).forEach { old ->
        val start = spans.getSpanStart(old)
        val end = spans.getSpanEnd(old)
        val flags = spans.getSpanFlags(old)
        spans.removeSpan(old)
        spans.setSpan(object : URLSpan(old.url) {
            override fun onClick(widget: View) = widget.openWeatherLink(url)
        }, start, end, flags)
    }
    movementMethod = LinkMovementMethod.getInstance()
    text = spans
}
