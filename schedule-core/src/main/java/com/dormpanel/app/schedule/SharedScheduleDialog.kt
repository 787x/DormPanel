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
        label(if (publisher) "X08E 单写者：手动发布完整课表。手机仅下载，双向编辑留待 PR32。\n使用已配置的 WebDAV 账号；请选择独立且已存在的目录。" else
            "绑定后为只读跟随模式，禁止本地新增、导入、编辑、删除和修改学期。PR31 不支持非空课表或自定义配置自动合并。离线继续显示缓存。")
        val public = sync.accounts.publicAccount()
        val url = if (!publisher) input("WebDAV 服务地址", public.first) else null
        val user = if (!publisher) input("用户名", public.second) else null
        val password = if (!publisher) input("密码（留空保留已保存密码）", "", true) else null
        val folder = input("共享目录完整 URL", sync.settings.folder.ifBlank { public.first })
        folder.isEnabled = if (publisher) sync.settings.publishedRevision == null else !source.readOnly
        val status = label("")
        var publishButton: Button? = null
        var synchronizeButton: Button? = null
        fun refresh() {
            val time = sync.settings.lastSuccess
            status.text = sync.status + (if (time > 0) "\n上次成功：${Instant.ofEpochMilli(time)}" else "") +
                if (time > 0 && System.currentTimeMillis() - time > 24 * 60 * 60 * 1000L) "\n数据可能过期（超过 24 小时未验证）" else ""
            publishButton?.text = if (sync.settings.publishedRevision == null) "首次创建共享课表" else "发布新版快照"
            folder.isEnabled = !sync.busy && if (publisher) sync.settings.publishedRevision == null else !source.readOnly
            publishButton?.isEnabled = !sync.busy
            synchronizeButton?.isEnabled = !sync.busy && source.readOnly
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
        fun button(text: String, action: () -> Unit): Button {
            return Button(context).apply { this.text = text; isAllCaps = false; minHeight = 48
                setOnClickListener { if (save()) action() }
                fields.addView(this)
            }
        }
        if (publisher) {
            button("诊断服务器条件写入（创建独立探测文件）") { sync.diagnose() }
            publishButton = button(if (sync.settings.publishedRevision == null) "首次创建共享课表" else "发布新版快照") { sync.publish() }
        } else {
            button("测试连接 / 检查远端快照") {
                sync.inspect { remote ->
                    val v = remote.snapshot.state
                    AlertDialog.Builder(context).setTitle("已验证的远端课表")
                        .setMessage("Dataset: ${remote.head.datasetId}\nRevision: ${remote.head.revision}\n发布：${Instant.ofEpochMilli(remote.snapshot.createdAt)}\n本地课程 ${v.entries.size} · 日历事件 ${v.events.size}\n来源 ${v.sources.size} · 导入实例 ${v.imported.size}\n日期调整 ${v.dayAdjustments.size} · 课程编辑 ${v.classOverrides.size}\n学期配置 ${v.profiles.size}\n\n确认绑定并以只读模式下载？现有非空数据会被拒绝，不会合并或清空。")
                        .setNegativeButton("取消", null).setPositiveButton("确认下载并绑定") { _, _ -> sync.bind(remote) }.show()
                }
            }
            synchronizeButton = button("立即同步") { sync.sync() }
        }
        refresh()
        val previous = sync.changed
        val listener: () -> Unit = { previous?.invoke(); refresh() }
        sync.changed = listener
        val dialog = AlertDialog.Builder(context).setTitle(if (publisher) "共享课表发布 · WebDAV" else "共享课表订阅 · WebDAV")
            .setView(ScrollView(context).apply { addView(fields) }).setNegativeButton("关闭", null).create()
        dialog.setOnDismissListener { if (sync.changed === listener) sync.changed = previous; password?.text?.clear() }
        dialog.show()
        return dialog
    }
}
