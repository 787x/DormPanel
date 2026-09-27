package com.dormpanel.app.lan

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/** Keeps the QR session scoped to its Android settings dialog. */
class PhoneEntryUi(private val context: Context, private val form: SettingsEntryForm,
    private val onReceived: (Map<String, String>) -> Unit, private val onStatus: (String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var session: TemporarySettingsEntryServer? = null
    private var qrDialog: AlertDialog? = null
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun start(publicValues: Map<String, String>) {
        close()
        val address = LanAddress.deviceAddress()
        if (address == null) {
            onStatus("No suitable local IPv4 address. Connect to a trusted LAN and try again.")
            return
        }
        lateinit var started: TemporarySettingsEntryServer
        val result = runCatching { TemporarySettingsEntryServer.start(address, form, publicValues,
            { values -> handler.post { if (session === started) {
                close(); onReceived(values)
            } } },
            { handler.post { if (session === started) {
                close(); onStatus("Phone entry expired. Start a new session to retry.")
            } } }) }
        started = result.getOrElse { onStatus("Could not start phone entry. Check the LAN and try again."); return }
        session = started
        val qr = ImageView(context).apply {
            contentDescription = "QR code for the displayed phone entry URL"
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val url = TextView(context).apply {
            text = started.url; textSize = 17f; setTextIsSelectable(true)
        }
        val details = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(12), 0)
            addView(TextView(context).apply { text = "Scan with your phone, or open:"; textSize = 20f })
            addView(url)
            addView(TextView(context).apply {
                text = "Use only on a trusted local network. Credentials are sent directly to this DormPanel over the LAN.\n\nExpires after 10 minutes. Review and save on DormPanel after sending."
                textSize = 16f
            })
        }
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(qr, LinearLayout.LayoutParams(dp(280), dp(280)))
            addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val dialog = AlertDialog.Builder(context).setTitle("Fill from phone · ${form.title}").setView(row)
            .setNegativeButton("Stop phone entry", null).create()
        qrDialog = dialog
        dialog.setOnDismissListener { if (qrDialog === dialog) close() }
        dialog.show()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        (context as? Activity)?.window?.attributes?.screenBrightness?.let { brightness ->
            dialog.window?.let { window ->
                val attributes = window.attributes; attributes.screenBrightness = brightness; window.attributes = attributes
            }
        }
        Thread({
            val bitmap = runCatching { LocalQr.bitmap(started.url) }.getOrNull()
            handler.post { if (session === started && bitmap != null) qr.setImageBitmap(bitmap) }
        }, "settings-entry-qr").apply { isDaemon = true; start() }
    }

    fun close() {
        val active = session; session = null; active?.close()
        val dialog = qrDialog; qrDialog = null; dialog?.dismiss()
    }
}
