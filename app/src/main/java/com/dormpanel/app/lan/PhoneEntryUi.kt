package com.dormpanel.app.lan

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Owns the single user-opened QR session and releases it on dismissal or backgrounding. */
internal class PhoneEntryUi(private val context: Context, private val localAddress: () -> String? = LanAddress::deviceAddress) {
    private val main = Handler(Looper.getMainLooper())
    private var session: TemporarySettingsEntryServer? = null
    private var qrDialog: AlertDialog? = null
    private var onClosed: (() -> Unit)? = null
    private val owner = context as? LifecycleOwner
    private val lifecycle = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) = close()
    }

    fun start(page: (String, Long) -> String, onPost: (Map<String, String>) -> SettingsEntryReply,
              onClosed: () -> Unit = {},
              onFinished: (Map<String, String>) -> Unit) {
        close()
        val address = localAddress()
        if (address == null) {
            toast("未找到局域网地址。请将手机和 X08E 接入同一个可信局域网。")
            return
        }
        lateinit var started: TemporarySettingsEntryServer
        started = runCatching {
            TemporarySettingsEntryServer.start(address, page, onPost,
                { values -> main.post {
                    if (session === started) {
                        close()
                        onFinished(values)
                    }
                } },
                { main.post {
                    if (session === started) {
                        close()
                        toast("扫码会话已过期，请重新打开。")
                    }
                } })
        }.getOrElse {
            toast("无法打开扫码配置，请检查局域网后重试。")
            return
        }
        session = started
        this.onClosed = onClosed
        owner?.lifecycle?.addObserver(lifecycle)

        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val qr = ImageView(context).apply {
            contentDescription = "配置门户二维码"
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val details = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(12), 0)
            addView(TextView(context).apply { text = "用手机扫码或在浏览器打开"; textSize = 22f })
            addView(TextView(context).apply { text = started.url; textSize = 17f; setTextIsSelectable(true) })
            addView(TextView(context).apply {
                text = "仅在可信局域网使用。HTTP 会明文传输凭据。\n\n会话 10 分钟后关闭。手机发起密钥生成或配置时，请在 X08E 上核对并确认。"
                textSize = 17f
            })
        }
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(qr, LinearLayout.LayoutParams(dp(280), dp(280)))
            addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val dialog = AlertDialog.Builder(context).setTitle("DormPanel · 扫码配置").setView(row)
            .setNegativeButton("关闭扫码会话", null).create()
        qrDialog = dialog
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnDismissListener { if (qrDialog === dialog) close() }
        dialog.show()
        (context as? Activity)?.window?.attributes?.screenBrightness?.let { brightness ->
            dialog.window?.let { window -> window.attributes = window.attributes.apply { screenBrightness = brightness } }
        }
        Thread({
            val bitmap = runCatching { LocalQr.bitmap(started.url) }.getOrNull()
            main.post { if (session === started && bitmap != null) qr.setImageBitmap(bitmap) }
        }, "settings-entry-qr").apply { isDaemon = true; start() }
    }

    fun isActive() = session?.isActive() == true

    fun close() {
        owner?.lifecycle?.removeObserver(lifecycle)
        val active = session
        session = null
        active?.close()
        val dialog = qrDialog
        qrDialog = null
        dialog?.dismiss()
        val closed = onClosed
        onClosed = null
        closed?.invoke()
    }

    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
