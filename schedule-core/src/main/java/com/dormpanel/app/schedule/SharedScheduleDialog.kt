package com.dormpanel.app.schedule

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.*
import java.time.Instant

/** Settings surface only; transport, validation and persistence belong to the controller/core. */
object SharedScheduleDialog {
    fun show(context: Context, source: ScheduleSource, sync: SharedScheduleController, publisher: Boolean): AlertDialog {
        val fields = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        fun label(text: String) = TextView(context).apply { this.text = text; textSize = 16f; fields.addView(this) }
        fun input(hint: String, value: String, secret: Boolean = false) = EditText(context).apply {
            this.hint = hint; contentDescription = hint; setText(value); setSingleLine()
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            fields.addView(this)
        }
        label(if (publisher) "X08E 与手机双端可编辑。保存后自动同步，离线修改保留在本地。\n使用已配置的 WebDAV 账号；请选择独立且已存在的目录。" else
            "绑定后支持双端编辑与自动同步。真正冲突会保留双方数据并等待处理。首次绑定仍要求空课表，已有 PR31 绑定无需重绑。")
        val public = sync.accounts.publicAccount()
        val url = if (!publisher) input("WebDAV 服务地址", public.first) else null
        val user = if (!publisher) input("用户名", public.second) else null
        val password = if (!publisher) input("密码（留空保留已保存密码）", "", true) else null
        val folder = input("共享目录完整 URL", sync.settings.folder.ifBlank { public.first })
        fun bound() = source.state.sync != null || sync.settings.publishedRevision != null || sync.settings.pendingRevision != null
        folder.isEnabled = !bound()
        val status = label("")
        var publishButton: Button? = null
        var synchronizeButton: Button? = null
        var conflictButton: Button? = null
        fun refresh() {
            val time = sync.settings.lastSuccess
            status.text = sync.status + (if (time > 0) "\n上次成功：${Instant.ofEpochMilli(time)}" else "") +
                if (time > 0 && System.currentTimeMillis() - time > 24 * 60 * 60 * 1000L) "\n数据可能过期（超过 24 小时未验证）" else ""
            publishButton?.text = if (!bound()) "首次创建共享课表" else "立即同步"
            folder.isEnabled = !sync.busy && !bound()
            publishButton?.isEnabled = !sync.busy
            synchronizeButton?.isEnabled = !sync.busy && source.state.sync != null
            conflictButton?.isEnabled = !sync.busy
        }
        refresh()
        fun save(): Boolean = runCatching {
            check(!sync.busy) { "同步中，请稍候" }
            if (!publisher) {
                val old = sync.accounts.account()
                val secret = password!!.text.toString().ifBlank { old?.password ?: "" }
                val account = WebDavAccount(url!!.text.toString(), user!!.text.toString(), secret)
                SharedScheduleTransport().folder(account, folder.text.toString())
                sync.accounts.saveAccount(account)
            }
            sync.settings.folder = folder.text.toString()
        }.onFailure { status.text = "设置无效，请检查地址与凭据。" }.isSuccess
        fun button(text: String, saveRequired: Boolean = true, action: () -> Unit): Button {
            return Button(context).apply { this.text = text; isAllCaps = false; minHeight = 48
                setOnClickListener { if (!saveRequired || save()) action() }
                fields.addView(this)
            }
        }
        if (publisher) {
            button("诊断服务器条件写入（创建独立探测文件）") { sync.diagnose() }
            publishButton = button(if (sync.settings.publishedRevision == null) "首次创建共享课表" else "立即同步") { sync.publish() }
        } else {
            button("测试连接 / 检查远端快照") {
                sync.inspect { remote ->
                    val v = remote.snapshot.state
                    AlertDialog.Builder(context).setTitle("已验证的远端课表")
                        .setMessage("Dataset: ${remote.head.datasetId}\nRevision: ${remote.head.revision}\n发布：${Instant.ofEpochMilli(remote.snapshot.createdAt)}\n本地课程 ${v.entries.size} · 日历事件 ${v.events.size}\n来源 ${v.sources.size} · 导入实例 ${v.imported.size}\n日期调整 ${v.dayAdjustments.size} · 课程编辑 ${v.classOverrides.size}\n学期配置 ${v.profiles.size}\n\n确认下载并绑定双向共享？首次绑定要求空课表；已有 PR31 数据请直接同步。")
                        .setNegativeButton("取消", null).setPositiveButton("确认下载并绑定") { _, _ -> sync.bind(remote) }.show()
                }
            }
            synchronizeButton = button("立即同步") { sync.sync() }
        }
        conflictButton = button("查看 / 处理同步冲突", false) {
            sync.loadConflicts { items ->
                if (items.isEmpty()) {
                    AlertDialog.Builder(context).setMessage("当前没有待处理冲突。可立即同步检查远端。")
                        .setPositiveButton("关闭", null).show()
                } else {
                    AlertDialog.Builder(context).setTitle("同步冲突 · ${items.size} 项")
                        .setMessage(items.joinToString("\n\n") { it.description } +
                            "\n\n以下选择仅处理冲突记录及必要的关联记录；其他独立修改仍自动合并。保留本地：采用本机值或删除；采用远端：采用已保存的远端值或删除。处理后重新检查最新远端再发布。")
                        .setPositiveButton("保留本地") { _, _ -> sync.resolve(ScheduleMerge.Choice.LOCAL) }
                        .setNeutralButton("采用远端") { _, _ -> sync.resolve(ScheduleMerge.Choice.REMOTE) }
                        .setNegativeButton("暂不处理", null).show()
                }
            }
        }
        refresh()
        val previous = sync.changed
        val listener: () -> Unit = { previous?.invoke(); refresh() }
        sync.changed = listener
        val dialog = AlertDialog.Builder(context).setTitle(if (publisher) "共享课表 · WebDAV" else "共享课表 · WebDAV")
            .setView(ScrollView(context).apply { addView(fields) }).setNegativeButton("关闭", null).create()
        dialog.setOnDismissListener { if (sync.changed === listener) sync.changed = previous; password?.text?.clear() }
        dialog.show()
        return dialog
    }
}
