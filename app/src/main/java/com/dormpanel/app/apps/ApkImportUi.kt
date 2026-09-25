package com.dormpanel.app.apps

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.ImageView
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.dormpanel.app.appearance.AppearanceController
import com.dormpanel.app.appearance.PanelPalette
import com.dormpanel.app.appearance.applyAppearanceTree
import com.dormpanel.app.appearance.matchActivityBrightness
import com.dormpanel.app.schedule.WebDavAccount
import com.dormpanel.app.schedule.WebDavClient
import com.dormpanel.app.schedule.WebDavSettings
import com.dormpanel.app.schedule.ReceiveQr
import java.util.concurrent.Executors

class ApkImportUi(private val activity: Activity, private val controller: ApkInstallController,
    private val appearance: AppearanceController, private val webDav: WebDavSettings,
    private val pickLocal: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var dialog: AlertDialog? = null
    private var previewFile: java.io.File? = null
    private var lastResultShown = ""
    private var closed = false
    private val observer: () -> Unit = { if (!closed) showCurrent() }
    init { controller.observe(observer) }

    fun open() {
        val row = column()
        row.addView(button("Choose local APK") { dialog?.dismiss(); pickLocal() })
        row.addView(button("Browse WebDAV") { dialog?.dismiss(); browse() })
        row.addView(button("Receive from phone/computer") { dialog?.dismiss(); receiveLan() })
        show(AlertDialog.Builder(activity).setTitle("Install APK").setView(row).setNegativeButton("Close", null).create())
    }

    fun selected(uri: Uri) {
        val resolver = activity.contentResolver
        val name = runCatching { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (!it.moveToFirst()) null else {
                val display = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val size = it.getColumnIndex(OpenableColumns.SIZE)
                (if (display >= 0) it.getString(display) else null).orEmpty().ifBlank { "package.apk" } to
                    (if (size >= 0 && !it.isNull(size)) it.getLong(size) else -1L)
            }
        } }.getOrNull() ?: ("package.apk" to -1L)
        controller.receive(name.first, "Local document", name.second,
            { resolver.openInputStream(uri) ?: throw ApkRejected("Document cannot be opened.") },
            onFailure = { error("Local APK", it.message.orEmpty()) })
    }

    private fun browse(folder: String? = null) {
        val account = webDav.account()
        if (account == null) { error("WebDAV", "Configure the shared WebDAV account in Control Center first."); return }
        val current = folder ?: account.baseUrl
        val content = column(); val status = TextView(activity).apply { text = "Loading folder…"; textSize = 18f }
        content.addView(status)
        val listing = show(AlertDialog.Builder(activity).setTitle("WebDAV APKs").setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton("Close", null).create())
        val client = WebDavClient()
        if (current != account.baseUrl) {
            val parent = client.url(current).resolve("../")
            val base = client.url(account.baseUrl)
            if (parent != null && parent.scheme == base.scheme && parent.host == base.host && parent.port == base.port &&
                parent.encodedPath.startsWith(base.encodedPath.trimEnd('/') + "/"))
                content.addView(button("[..]") { listing.dismiss(); browse(parent.toString()) })
        }
        worker.execute {
            val result = runCatching { client.list(account, current).filter { item ->
                item.url.trimEnd('/') != current.trimEnd('/') && (item.folder || item.name.endsWith(".apk", true))
            }.sortedWith(compareByDescending<com.dormpanel.app.schedule.WebDavItem> { it.folder }.thenBy { it.name.lowercase() }) }
            handler.post { if (!closed && listing.isShowing) result.onSuccess { items ->
                status.text = if (items.isEmpty()) "No folders or APK files." else "Select one APK."
                items.forEach { item -> content.addView(button("${if (item.folder) "📁" else "📦"} ${item.name}") {
                    listing.dismiss()
                    if (item.folder) browse(item.url) else receiveWebDav(account, item.url, item.name, item.length ?: -1)
                }) }
            }.onFailure { status.text = it.message ?: "Folder could not be opened." } }
        }
    }

    private fun receiveWebDav(account: WebDavAccount, url: String, filename: String, length: Long) {
        controller.receiveSource(onFailure = { error("WebDAV APK", it.message.orEmpty()) }) { staging ->
            WebDavClient().downloadStream(account, url) { input, actualHint ->
                staging.validate(staging.stage(input, filename, "WebDAV", if (length > 0) length else actualHint))
            }
        }
    }

    private fun showCurrent() {
        val metadata = controller.candidate
        if (metadata == null) {
            if (previewFile != null) { previewFile = null; dialog?.dismiss(); dialog = null }
            val result = controller.installState
            if (result in setOf("Installed successfully", "Installation failed") && result != lastResultShown) {
                lastResultShown = result
                error(result, if (result == "Installed successfully") "Android installed the APK. Apps has been refreshed."
                    else controller.error ?: "Android did not install the APK.")
            }
            return
        }
        if (controller.busy) return
        if (previewFile == metadata.staged.file && dialog?.isShowing == true) return
        if (previewFile != metadata.staged.file) lastResultShown = ""
        previewFile = metadata.staged.file
        val body = column()
        val existing = if (metadata.installedVersion != null) "\nInstalled: ${metadata.installedVersion} · ${metadata.versionRelation}\nSigning certificate: ${if (metadata.signatureMatches == true) "matches" else "DIFFERENT — Android cannot update this app. Existing app and data stay untouched."}" else ""
        body.addView(TextView(activity).apply {
            text = "${metadata.label}\n${metadata.packageName}\nVersion: ${metadata.versionName} (${metadata.versionCode})\nSize: ${metadata.staged.size / 1024 / 1024.0} MiB\nSource: ${metadata.staged.source}\nAPK SHA-256: ${metadata.staged.sha256}\nCertificate SHA-256: ${metadata.signingSha256}$existing"
            textSize = 18f; setTextIsSelectable(true)
        })
        val permission = TextView(activity).apply { textSize = 18f }
        body.addView(permission)
        val grant = button("Allow APK installs in Android settings") {
            try { activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}"))) } catch (_: Exception) {
                error("Install access", "Android settings for unknown app sources are unavailable on this device.")
            }
        }
        body.addView(grant)
        val preview = show(AlertDialog.Builder(activity).setTitle("APK preview")
            .setView(ScrollView(activity).apply { addView(body) })
            .setNegativeButton("Dismiss") { _, _ -> controller.dismiss(); previewFile = null }
            .setPositiveButton("Install", null).create())
        fun refresh() {
            val allowed = controller.allowed()
            permission.text = if (allowed) "DormPanel may request installation." else "APK is valid. Allow DormPanel to install unknown apps before continuing."
            grant.visibility = if (allowed) android.view.View.GONE else android.view.View.VISIBLE
            preview.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = controller.gate() == ApkInstallGate.READY
        }
        preview.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            refresh(); if (controller.install()) { preview.dismiss(); previewFile = null }
        }
        refresh()
        permissionRefresh = ::refresh
    }
    private var permissionRefresh: (() -> Unit)? = null
    fun refreshPermission() { controller.refreshPermission(); permissionRefresh?.invoke() }

    private fun receiveLan() {
        val url = runCatching { controller.startLan() }.getOrElse { error("Receive APK", it.message ?: "Private network unavailable."); return }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(24, 12, 24, 12) }
        val qr = ImageView(activity).apply { contentDescription = "QR code for APK receive URL"; setBackgroundColor(Color.WHITE); setPadding(12, 12, 12, 12) }
        row.addView(qr, LinearLayout.LayoutParams(340, 340))
        val details = column()
        details.addView(TextView(activity).apply { text = "Open this temporary URL on your phone or computer:"; textSize = 20f })
        details.addView(TextView(activity).apply { text = url; textSize = 18f; setTextIsSelectable(true) })
        details.addView(TextView(activity).apply { text = "Expires in about 10 minutes. One valid APK only."; textSize = 17f })
        row.addView(details)
        val receiverDialog = show(AlertDialog.Builder(activity).setTitle("Receive APK")
            .setView(row).setNegativeButton("Stop receiving") { _, _ -> controller.stopLan() }.create())
        receiverDialog.setOnDismissListener { if (controller.lanUrl == url) controller.stopLan() }
        worker.execute {
            val bitmap = runCatching {
                val size = 320; val matrix = ReceiveQr.encode(url, size)
                val pixels = IntArray(size * size) { index -> if (matrix[index % size, index / size]) Color.BLACK else Color.WHITE }
                Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
            }.getOrNull()
            handler.post { if (!closed && receiverDialog.isShowing && bitmap != null) qr.setImageBitmap(bitmap) }
        }
    }
    private fun error(title: String, message: String) { if (!closed) show(AlertDialog.Builder(activity).setTitle(title).setMessage(message).setPositiveButton("OK", null).create()) }
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
    private fun button(label: String, action: () -> Unit) = Button(activity).apply { text = label; textSize = 19f; isAllCaps = false; setOnClickListener { action() } }
    private fun show(value: AlertDialog): AlertDialog {
        dialog?.dismiss(); dialog = value; value.show()
        value.window?.setLayout((activity.resources.displayMetrics.widthPixels * .78).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        value.matchActivityBrightness()
        value.window?.decorView?.let { it.setBackgroundColor(PanelPalette.forMode(appearance.state.themeMode).surface); applyAppearanceTree(it, appearance.state) }
        return value
    }
    fun close() { closed = true; dialog?.dismiss(); controller.unobserve(observer); worker.shutdownNow() }
}
