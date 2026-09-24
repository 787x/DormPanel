package com.dormpanel.app.schedule

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.OpenableColumns
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.dormpanel.app.appearance.*
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Local SAF adapter and preview UI. All content reads, hashing and parsing run on the worker. */
class ScheduleImportUi(private val context: Context, private val source: ScheduleSource,
    private val appearance: AppearanceController, private val webDav: WebDavSyncController,
    private val launchPicker: () -> Unit) {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val dialogs = mutableSetOf<AlertDialog>()
    private var closed = false
    private var busy = false
    private var receiver: TemporaryLanUploadServer? = null
    private var receiveDialog: AlertDialog? = null
    private var receiveTick: Runnable? = null
    private val importer = IcsScheduleImporter()
    private val themeListener: (AppearanceState) -> Unit = { state -> dialogs.forEach { theme(it, state) } }
    init { appearance.addListener(themeListener) }
    fun choose() { if (!closed && !busy && source.ready) launchPicker() }
    fun receive() {
        if (closed || busy || !source.ready) return
        stopReceiving()
        val address = TemporaryLanUploadServer.deviceAddress()
        if (address == null) {
            show(AlertDialog.Builder(context).setTitle("Network unavailable")
                .setMessage("Connect DormPanel to a trusted local network, then retry receiving.")
                .setNegativeButton("Close", null).setPositiveButton("Retry") { _, _ -> receive() }.create())
            return
        }
        lateinit var started: TemporaryLanUploadServer
        val parsed = AtomicReference<ImportPreview>()
        val session = runCatching { TemporaryLanUploadServer.start(address,
            { artifact -> runCatching { importer.parse(artifact) }.onSuccess(parsed::set).isSuccess },
            { handler.post { if (!closed && receiver === started) {
                val result = parsed.getAndSet(null)
                stopReceiving(); if (result != null) preview(result)
            } } }, { handler.post { if (!closed && receiver === started) {
                stopReceiving(); message("Receive timetable", "The receive URL expired. Start a new session to retry.")
            } } }) }.getOrElse {
            message("Receive timetable", "Could not start a local receiver. Check the network and retry."); return
        }
        started = session
        receiver = session
        val qr = ImageView(context).apply {
            contentDescription = "QR code for the displayed receive URL"
            setBackgroundColor(Color.WHITE)
            setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
        }
        val url = context.scheduleLabel(session.url, 17f).apply { setTextIsSelectable(true); maxWidth = context.dp(490) }
        val remaining = context.scheduleLabel("", 18f)
        val details = context.scheduleColumn().apply {
            addView(context.scheduleLabel("Scan with your phone, or open:", 21f))
            addView(url); addView(remaining)
            addView(context.scheduleLabel("Use only on a trusted local network.", 16f))
        }
        val row = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL
            addView(qr, LinearLayout.LayoutParams(context.dp(340), context.dp(340)))
            addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val dialog = show(AlertDialog.Builder(context).setTitle("Receive timetable")
            .setView(row).setNegativeButton("Stop receiving") { _, _ -> stopReceiving() }.create())
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).minHeight = context.dp(56)
        receiveDialog = dialog
        dialog.setOnDismissListener { dialogs -= dialog; if (receiveDialog === dialog) stopReceiving() }
        val tick = object : Runnable { override fun run() {
            if (receiver !== session || closed) return
            val seconds = ((session.expiresAt - System.currentTimeMillis()).coerceAtLeast(0) + 999) / 1000
            remaining.text = "Expires in %02d:%02d".format(seconds / 60, seconds % 60)
            handler.postDelayed(this, 1000)
        } }
        receiveTick = tick; tick.run()
        worker.execute {
            val bitmap = runCatching { qrBitmap(session.url) }.getOrNull()
            handler.post { if (!closed && receiver === session && bitmap != null) qr.setImageBitmap(bitmap) }
        }
    }
    private fun qrBitmap(payload: String): Bitmap {
        val size = 320
        val matrix = ReceiveQr.encode(payload, size)
        val pixels = IntArray(size * size) { index -> if (matrix[index % size, index / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }
    private fun stopReceiving() {
        val dialog = receiveDialog; receiveDialog = null
        receiveTick?.let(handler::removeCallbacks); receiveTick = null
        receiver?.close(); receiver = null
        dialog?.dismiss()
    }
    fun selected(uri: Uri) {
        if (closed || busy) return
        busy = true
        val progress = show(AlertDialog.Builder(context).setTitle("Reading timetable")
            .setMessage("Reading and checking the selected ICS…").setCancelable(false).create())
        worker.execute {
            val result = runCatching {
                val resolver = context.applicationContext.contentResolver
                var filename = "timetable.ics"
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) filename = cursor.getString(nameIndex)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) importCheck(cursor.getLong(sizeIndex) <= ImportLimits.BYTES, "ICS exceeds the 1 MiB size limit.")
                    }
                }
                val artifact = resolver.openInputStream(uri)?.use { ScheduleArtifact.read(filename, it, resolver.getType(uri)) }
                    ?: throw ScheduleImportException("Cannot open the selected document.")
                importer.parse(artifact)
            }
            handler.post { if (!closed) {
                busy = false; progress.dismiss()
                result.onSuccess(::preview).onFailure { message("Import not available", errorText(it)) }
            } }
        }
    }
    private fun date(millis: Long) = Instant.ofEpochMilli(millis).atZone(source.clock.zone())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    private data class RemoteChoice(val mode: WebDavMode, val remote: String, val item: WebDavItem,
        val targetId: String? = null)
    fun preview(preview: ImportPreview) = preview(preview, null)
    private fun preview(preview: ImportPreview, remote: RemoteChoice?) {
        if (closed) return
        val fields = context.scheduleColumn().apply { setPadding(context.dp(16), 0, context.dp(16), 0) }
        val row = LinearLayout(context)
        val summary = context.scheduleColumn()
        summary.addView(context.scheduleLabel("${preview.filename}\nCalendar: ${preview.calendarName ?: "Unnamed"}\n" +
            "Source zones: ${preview.timezones}\n${preview.seriesCount} series · ${preview.occurrences.size} classes\n" +
            "${date(preview.firstStart)} – ${date(preview.lastEnd)}\nDisplay zone: ${source.clock.zone()}", 17f))
        val samples = context.scheduleColumn()
        preview.occurrences.take(4).forEach { item -> samples.addView(context.scheduleLabel(
            "${item.title}\n${date(item.start)} – ${context.scheduleTime(Instant.ofEpochMilli(item.end))}\n${item.periodLabel.orEmpty()} ${item.location}", 16f)) }
        row.addView(summary, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(samples, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); fields.addView(row)
        val name = EditText(context).apply { hint = "Timetable name"; contentDescription = hint; setSingleLine(); setText(preview.calendarName ?: preview.filename.substringBeforeLast('.')) }
        fields.addView(name)
        val sources = source.state.sources.filter { webDav.binding(it.id) == null || it.id == remote?.targetId }
        val target = Spinner(context).apply {
            contentDescription = "Import target"; minimumHeight = context.dp(48)
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                listOf("Import as new timetable") + sources.map { "Replace: ${it.displayName}" })
            if (remote?.targetId != null) setSelection(sources.indexOfFirst { it.id == remote.targetId } + 1)
            isEnabled = remote?.targetId == null
        }
        fields.addView(target)
        val unchanged = context.scheduleLabel("", 16f); fields.addView(unchanged)
        target.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val existing = sources.getOrNull(position - 1)
                unchanged.text = if (existing?.sha256 == preview.sha256) "Unchanged: this source already contains the exact file. No classes will be duplicated." else ""
                if (existing != null) name.setText(existing.displayName)
            }
        }
        fields.addView(context.scheduleLabel(preview.warnings.joinToString("\n"), 16f))
        val error = context.scheduleLabel("", 16f); fields.addView(error)
        val dialog = show(AlertDialog.Builder(context).setTitle("Timetable import preview")
            .setView(ScrollView(context).apply { addView(fields) }).setNegativeButton("Cancel", null).setPositiveButton("Import", null).create())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (busy || !source.ready) return@setOnClickListener
            if (name.text.isBlank()) { error.text = "Enter a timetable name."; return@setOnClickListener }
            val existing = sources.getOrNull(target.selectedItemPosition - 1)
            if (remote == null && existing != null && webDav.binding(existing.id) != null) {
                error.text = "This source is managed by WebDAV. Use its WebDAV controls."; return@setOnClickListener
            }
            fun commit() {
                if (closed || busy) return
                busy = true
                dialog.setCancelable(false); dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                source.import(preview, name.text.toString(), existing?.id) { result -> if (!closed) {
                    busy = false
                    result.onSuccess {
                        remote?.let { choice -> webDav.bind(WebDavBinding(it.source.id, choice.mode, choice.remote,
                            autoSync = webDav.binding(it.source.id)?.autoSync ?: false,
                            etag = choice.item.etag, lastModified = choice.item.lastModified,
                            currentFile = choice.item.url, lastSuccess = System.currentTimeMillis())) }
                        dialog.dismiss(); message(if (it.unchanged) "Timetable unchanged" else "Timetable imported",
                        "${it.source.displayName} · ${it.source.occurrenceCount} classes") }
                        .onFailure { error.text = errorText(it); dialog.setCancelable(true)
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true; dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true }
                } }
            }
            if (existing == null || existing.sha256 == preview.sha256) commit()
            else show(AlertDialog.Builder(context).setTitle("Replace ${existing.displayName}?")
                .setMessage("Replace all ${existing.occurrenceCount} imported classes in this source with ${preview.occurrences.size} classes from ${preview.filename}?")
                .setNegativeButton("Cancel", null).setPositiveButton("Replace source") { _, _ -> commit() }.create())
        }
    }
    fun sources() {
        if (closed || busy) return
        val fields = context.scheduleColumn()
        val dialog = AlertDialog.Builder(context).setTitle("Imported timetables").setNegativeButton("Close", null)
            .setView(ScrollView(context).apply { addView(fields) }).create()
        fields.addView(context.scheduleButton("Add WebDAV timetable") { dialog.dismiss(); webDavSetup() })
        if (source.state.sources.isEmpty()) fields.addView(context.scheduleLabel("No imported timetables"))
        source.state.sources.forEach { item ->
            fields.addView(context.scheduleLabel("${item.displayName} · ${item.filename}\nImported ${date(item.importedAt)} · ${item.occurrenceCount} classes\n${date(item.firstStart)} – ${date(item.lastEnd)}", 18f))
            webDav.binding(item.id)?.let { binding ->
                val remote = runCatching { android.net.Uri.parse(binding.remote).path }.getOrNull() ?: "Remote timetable"
                val filename = binding.currentFile?.let { runCatching { WebDavClient().run { filename(url(it)) } }.getOrNull() }
                fields.addView(context.scheduleLabel("WebDAV ${if (binding.mode == WebDavMode.FILE) "file" else "folder · following newest ICS"} · $remote\n" +
                    "Current: ${filename ?: item.filename}\n" +
                    "Last synced: ${if (binding.lastSuccess == 0L) "Never" else date(binding.lastSuccess)} · Auto sync: ${if (binding.autoSync) "On" else "Off"}\n" +
                    "Status: ${binding.lastError ?: "Up to date"}", 16f))
                val controls = LinearLayout(context)
                controls.addView(context.scheduleButton("Sync now") { webDav.sync(item.id) { result -> if (!closed) {
                    dialog.dismiss(); sources()
                    message("WebDAV sync", result.fold({ if (it) "Timetable updated." else "Already up to date." },
                        { errorText(it) }))
                } } })
                controls.addView(context.scheduleButton(if (binding.autoSync) "Auto sync Off" else "Auto sync On") {
                    webDav.bind(binding.copy(autoSync = !binding.autoSync)); dialog.dismiss(); sources()
                })
                controls.addView(context.scheduleButton("Edit selection") { dialog.dismiss(); webDavSetup(item.id) })
                fields.addView(controls)
            }
            fields.addView(context.scheduleButton("Delete ${item.displayName}") {
                if (busy) return@scheduleButton
                show(AlertDialog.Builder(context).setTitle("Delete imported timetable?")
                    .setMessage("Remove ${item.displayName} and its ${item.occurrenceCount} imported classes?")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete source") { _, _ ->
                        busy = true
                        source.deleteImport(item.id) { result -> if (!closed) { busy = false
                            result.onSuccess { webDav.remove(item.id); dialog.dismiss(); sources() }.onFailure { message("Delete failed", errorText(it)) }
                        } }
                    }.create())
            })
        }
        show(dialog)
    }
    private fun webDavSetup(targetId: String? = null) {
        if (closed) return
        val (savedUrl, savedUser) = webDav.settings.publicAccount()
        val fields = context.scheduleColumn().apply { setPadding(context.dp(16), 0, context.dp(16), 0) }
        fun field(label: String, value: String, secret: Boolean = false) = EditText(context).apply {
            hint = label; contentDescription = label; setSingleLine(); setText(value)
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            fields.addView(this)
        }
        val url = field("WebDAV server URL", savedUrl)
        val user = field("Username", savedUser)
        val password = field("Password (leave blank to keep saved password)", "", true)
        val warning = context.scheduleLabel("HTTP sends username and password without encryption. Use HTTPS when available.", 16f)
        fields.addView(warning)
        val status = context.scheduleLabel("", 16f); fields.addView(status)
        val dialog = show(AlertDialog.Builder(context).setTitle("WebDAV timetable")
            .setView(fields).setNegativeButton("Close", null).create())
        fun account(): WebDavAccount? {
            val saved = webDav.settings.account()
            val enteredUrl = url.text.toString().trim()
            val enteredUser = user.text.toString()
            val pass = password.text.toString().ifBlank {
                if (saved?.baseUrl == enteredUrl && saved.username == enteredUser) saved.password else ""
            }
            if (pass.isEmpty()) { status.text = "Enter a password for this WebDAV account."; return null }
            val candidate = WebDavAccount(enteredUrl, enteredUser, pass)
            return runCatching { WebDavClient().url(candidate.baseUrl); candidate }.onFailure { status.text = errorText(it) }.getOrNull()
        }
        fields.addView(context.scheduleButton("Test connection") {
            val candidate = account() ?: return@scheduleButton
            status.text = "Testing…"
            webDav.test(candidate) { result -> if (!closed) status.text = result.fold({ "Connection successful." }, { errorText(it) }) }
        })
        fields.addView(context.scheduleButton("Browse") {
            val candidate = account() ?: return@scheduleButton
            runCatching { webDav.settings.saveAccount(candidate) }.onFailure { status.text = errorText(it) }.onSuccess {
                dialog.dismiss(); browse(candidate, candidate.baseUrl, targetId)
            }
        })
    }
    private fun browse(account: WebDavAccount, folder: String, targetId: String?) {
        if (closed) return
        val fields = context.scheduleColumn().apply { setPadding(context.dp(16), 0, context.dp(16), 0) }
        val status = context.scheduleLabel("Loading folder…", 16f)
        fields.addView(status)
        val dialog = show(AlertDialog.Builder(context).setTitle("WebDAV · ${android.net.Uri.parse(folder).encodedPath}")
            .setView(ScrollView(context).apply { addView(fields) }).setNegativeButton("Close", null).create())
        val client = WebDavClient()
        val base = client.url(account.baseUrl)
        val current = client.url(folder)
        if (current != base) {
            val parent = current.resolve("../")
            if (parent != null && parent.scheme == base.scheme && parent.host == base.host && parent.port == base.port &&
                parent.encodedPath.startsWith(base.encodedPath.trimEnd('/') + "/"))
                fields.addView(context.scheduleButton("[..]") { dialog.dismiss(); browse(account, parent.toString(), targetId) })
        }
        fields.addView(context.scheduleButton("Follow newest ICS in this folder") {
            dialog.dismiss(); remotePreview(account, WebDavMode.FOLDER_LATEST_ICS, folder, targetId)
        })
        webDav.browse(account, folder) { result -> if (!closed && dialog.isShowing) {
            result.onSuccess { items ->
                status.text = if (items.isEmpty()) "No folders or ICS files." else "Select a folder or ICS file."
                items.forEach { item -> fields.addView(context.scheduleButton("${if (item.folder) "📁" else "📄"} ${item.name}") {
                    dialog.dismiss()
                    if (item.folder) browse(account, item.url, targetId)
                    else remotePreview(account, WebDavMode.FILE, item.url, targetId)
                }) }
            }.onFailure { status.text = errorText(it) }
        } }
    }
    private fun remotePreview(account: WebDavAccount, mode: WebDavMode, remote: String, targetId: String?) {
        val progress = show(AlertDialog.Builder(context).setTitle("Reading WebDAV timetable")
            .setMessage("Downloading and checking ICS…").setCancelable(false).create())
        webDav.initial(account, mode, remote) { result -> if (!closed) {
            progress.dismiss()
            result.onSuccess { (parsed, item) -> preview(parsed, RemoteChoice(mode, remote, item, targetId)) }
                .onFailure { message("WebDAV import unavailable", errorText(it)) }
        } }
    }
    private fun message(title: String, text: String) { if (!closed) show(AlertDialog.Builder(context).setTitle(title).setMessage(text).setPositiveButton("OK", null).create()) }
    private fun errorText(error: Throwable) = when (error) {
        is ScheduleImportException, is WebDavException -> error.message ?: "Unable to complete the request."
        else -> "Unable to complete the request. Existing timetables have been kept."
    }
    private fun theme(dialog: AlertDialog, state: AppearanceState) { dialog.window?.decorView?.let {
        it.setBackgroundColor(PanelPalette.forMode(state.themeMode).surface); applyAppearanceTree(it, state)
    } }
    private fun show(dialog: AlertDialog): AlertDialog {
        dialogs += dialog; dialog.setOnDismissListener { dialogs -= dialog }; dialog.show()
        dialog.window?.setLayout(minOf(context.dp(1000), context.resources.displayMetrics.widthPixels - context.dp(48)), ViewGroup.LayoutParams.WRAP_CONTENT)
        theme(dialog, appearance.state); return dialog
    }
    fun close() { closed = true; stopReceiving(); dialogs.toList().forEach { it.dismiss() }; dialogs.clear(); appearance.removeListener(themeListener); worker.shutdownNow()
        webDav.settings.clearIfUnused() }
}
