package com.dormpanel.app.schedule

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.dormpanel.app.appearance.*
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/** Local SAF adapter and preview UI. All content reads, hashing and parsing run on the worker. */
class ScheduleImportUi(private val context: Context, private val source: ScheduleSource,
    private val appearance: AppearanceController, private val launchPicker: () -> Unit) {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val dialogs = mutableSetOf<AlertDialog>()
    private var closed = false
    private var busy = false
    private val importer = IcsScheduleImporter()
    private val themeListener: (AppearanceState) -> Unit = { state -> dialogs.forEach { theme(it, state) } }
    init { appearance.addListener(themeListener) }
    fun choose() { if (!closed && !busy && source.ready) launchPicker() }
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
    fun preview(preview: ImportPreview) {
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
        val sources = source.state.sources.toList()
        val target = Spinner(context).apply {
            contentDescription = "Import target"; minimumHeight = context.dp(48)
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                listOf("Import as new timetable") + sources.map { "Replace: ${it.displayName}" })
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
            fun commit() {
                if (closed || busy) return
                busy = true
                dialog.setCancelable(false); dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                source.import(preview, name.text.toString(), existing?.id) { result -> if (!closed) {
                    busy = false
                    result.onSuccess { dialog.dismiss(); message(if (it.unchanged) "Timetable unchanged" else "Timetable imported",
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
        if (source.state.sources.isEmpty()) fields.addView(context.scheduleLabel("No imported timetables"))
        source.state.sources.forEach { item ->
            fields.addView(context.scheduleLabel("${item.displayName} · ${item.filename}\nImported ${date(item.importedAt)} · ${item.occurrenceCount} classes\n${date(item.firstStart)} – ${date(item.lastEnd)}", 18f))
            fields.addView(context.scheduleButton("Delete ${item.displayName}") {
                if (busy) return@scheduleButton
                show(AlertDialog.Builder(context).setTitle("Delete imported timetable?")
                    .setMessage("Remove ${item.displayName} and its ${item.occurrenceCount} imported classes?")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete source") { _, _ ->
                        busy = true
                        source.deleteImport(item.id) { result -> if (!closed) { busy = false
                            result.onSuccess { dialog.dismiss(); sources() }.onFailure { message("Delete failed", errorText(it)) }
                        } }
                    }.create())
            })
        }
        show(dialog)
    }
    private fun message(title: String, text: String) { if (!closed) show(AlertDialog.Builder(context).setTitle(title).setMessage(text).setPositiveButton("OK", null).create()) }
    private fun errorText(error: Throwable) = (error as? ScheduleImportException)?.message ?: "Unable to complete the import. Existing timetables have been kept."
    private fun theme(dialog: AlertDialog, state: AppearanceState) { dialog.window?.decorView?.let {
        it.setBackgroundColor(PanelPalette.forMode(state.themeMode).surface); applyAppearanceTree(it, state)
    } }
    private fun show(dialog: AlertDialog): AlertDialog {
        dialogs += dialog; dialog.setOnDismissListener { dialogs -= dialog }; dialog.show()
        dialog.window?.setLayout(minOf(context.dp(1000), context.resources.displayMetrics.widthPixels - context.dp(48)), ViewGroup.LayoutParams.WRAP_CONTENT)
        theme(dialog, appearance.state); return dialog
    }
    fun close() { closed = true; dialogs.toList().forEach { it.dismiss() }; dialogs.clear(); appearance.removeListener(themeListener); worker.shutdownNow() }
}
