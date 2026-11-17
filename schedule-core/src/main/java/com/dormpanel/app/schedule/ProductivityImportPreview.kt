package com.dormpanel.app.schedule

private const val TASK_LIMIT = 6
private const val RECORD_LIMIT_PER_KIND = 3

/** Display only. Import and binding continue to use the original records. */
fun ProductivityRecord.importPreview(content: SharedProductivity.Content): String = when (this) {
    is ProductivityRecord.TodoList -> buildString {
        val tasks = content.tasks(id)
        val completed = tasks.count { it.completed }
        append(name.previewText(96, 2, "…（名称已省略）"))
        append("\n${tasks.size} 项任务，$completed 项已完成，${tasks.size - completed} 项未完成")
        tasks.take(TASK_LIMIT).forEach {
            append(if (it.completed) "\n☑ " else "\n☐ ")
            append(it.text.previewText(96, 2))
        }
        if (tasks.size > TASK_LIMIT) append("\n另有 ${tasks.size - TASK_LIMIT} 项任务未展示")
    }
    is ProductivityRecord.Memo -> title.previewText(96, 2, "…（标题已省略）") +
        "\n正文 ${body.codePointCount(0, body.length)} 字符" +
        if (body.isEmpty()) "" else "\n" + body.previewText(256, 8)
    is ProductivityRecord.Task -> (if (completed) "☑ " else "☐ ") + text.previewText(96, 2)
}

fun SharedProductivity.Content.importPreview(): String = buildString {
    append("${lists.size} 张清单 · ${tasks.size} 项任务 · ${memos.size} 条备忘")
    listOf(lists to "张清单", memos to "条备忘").forEach { (records, unit) ->
        records.take(RECORD_LIMIT_PER_KIND).forEach {
            append("\n\n")
            append(it.importPreview(this@importPreview))
        }
        if (records.size > RECORD_LIMIT_PER_KIND) {
            append("\n\n另有 ${records.size - RECORD_LIMIT_PER_KIND} ${unit}未展示")
        }
    }
}

private fun String.previewText(limit: Int, maxLines: Int, omission: String = "…（正文已省略）"): String {
    var end = 0
    var lines = 1
    while (end < length && end < limit) {
        val code = codePointAt(end)
        val units = Character.charCount(code)
        if (end + units > limit) break
        if (code == '\r'.code || code == '\n'.code && getOrNull(end - 1) != '\r') {
            if (lines == maxLines) break
            lines++
        }
        end += units
    }
    return if (end == length) this else substring(0, end) + omission
}
