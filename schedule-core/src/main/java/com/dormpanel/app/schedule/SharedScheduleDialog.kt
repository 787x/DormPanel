package com.dormpanel.app.schedule

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.*
import java.time.Instant

/** Settings surface only; transport, validation and persistence belong to the controller/core. */
object SharedScheduleDialog {
    fun show(context: Context, source: ScheduleSource, sync: SharedScheduleController, publisher: Boolean): AlertDialog {
        var active = true
        var previewDialog: AlertDialog? = null
        val fields = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        fun label(text: String) = TextView(context).apply { this.text = text; textSize = 16f; fields.addView(this) }
        fun input(hint: String, value: String, secret: Boolean = false) = EditText(context).apply {
            this.hint = hint; contentDescription = hint; setText(value); setSingleLine()
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            fields.addView(this)
        }
        label(if (publisher) "X08E 与手机双端可编辑。保存后自动同步，离线修改保留在本地。\n使用已配置的 WebDAV 账号；填写已有共享目录完整 URL。未绑定时可创建新课表，或预览并绑定已有课表（要求本地为空）。" else
            "绑定后支持双端编辑与自动同步。真正冲突会保留双方数据并等待处理。首次绑定仍要求空课表，已有 v1 绑定请明确切换到 v2。")
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
        var bindButton: Button? = null
        var inspectButton: Button? = null
        var conflictButton: Button? = null
        fun refresh() {
            val time = sync.settings.lastSuccess
            status.text = sync.status + (if (time > 0) "\n上次成功：${Instant.ofEpochMilli(time)}" else "") +
                if (time > 0 && System.currentTimeMillis() - time > 24 * 60 * 60 * 1000L) "\n数据可能过期（超过 24 小时未验证）" else ""
            publishButton?.text = if (!bound()) "创建新的共享课表" else "立即同步"
            folder.isEnabled = !sync.busy && !source.applyingSnapshot && !source.error && !bound()
            publishButton?.isEnabled = !sync.busy && source.ready
            bindButton?.isEnabled = !sync.busy && source.ready && !bound()
            bindButton?.visibility = if (bound()) android.view.View.GONE else android.view.View.VISIBLE
            inspectButton?.isEnabled = !sync.busy && source.ready
            if (bound()) previewDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.visibility = android.view.View.GONE
            synchronizeButton?.isEnabled = !sync.busy && source.state.sync != null
            conflictButton?.isEnabled = !sync.busy
        }
        refresh()
        fun save(): Boolean = runCatching {
            check(!sync.busy && !source.applyingSnapshot && !source.error) { "正在检查本地状态，请稍候" }
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
            return Button(context).apply { this.text = text; isAllCaps = false; minHeight = (48 * context.resources.displayMetrics.density).toInt()
                setOnClickListener { if (!saveRequired || save()) action() }
                fields.addView(this)
            }
        }
        fun inspectAndConfirm() {
            sync.inspect { remote ->
                if (!active) return@inspect
                val canBind = !bound()
                val v = remote.snapshot.state
                val details = buildString {
                    append("Dataset: ${remote.head.datasetId}\nCommit: ${remote.head.revision}\n协议：Append-only v2")
                    append("\n本地课程 ${v.entries.size} · 日历事件 ${v.events.size}\n来源 ${v.sources.size} · 导入实例 ${v.imported.size}")
                    append("\n日期调整 ${v.dayAdjustments.size} · 课程编辑 ${v.classOverrides.size}")
                    append("\n远端历史：${remote.witness.size} 个已验证提交 · 单一分支 · 祖先闭包完整")
                    append("\n暂不可验证文件 ${remote.unverifiable.size}（不计为完成同步）")
                    append("\n\n导入来源：")
                    v.sources.forEach { append("\n${it.displayName.take(160)} · ${it.filename.take(160)} · ${it.kind.take(80)} · ${it.occurrenceCount} 项") }
                    append("\n\n学期配置 ${v.profiles.size}（自定义 ${v.customProfileKeys.size}）：")
                    v.profiles.toSortedMap().forEach { (key, profile) ->
                        append("\n${key.take(160)} · ${profile.timezone} · 首周 ${profile.week1Monday} · ${profile.phases.size} 个时段阶段")
                    }
                    append(if (canBind) "\n\n确认前将重新检查完整版本图。绑定仅接收已有课程，不创建远端根提交。首次绑定要求空课表；已有本地数据请先保留，已有 v1 绑定请使用迁移入口。"
                        else "\n\n本机已绑定；本次仅检查远端，不下载覆盖本地课程、不重新绑定，也不发布远端提交。")
                }
                val content = TextView(context).apply { text = details; textSize = 16f; setPadding(24, 12, 24, 12); setTextIsSelectable(true) }
                val preview = AlertDialog.Builder(context).setTitle("已验证的远端课表")
                    .setView(ScrollView(context).apply { addView(content) })
                    .setNegativeButton(if (canBind) "取消" else "关闭预览", null)
                if (canBind) preview.setPositiveButton("确认下载并绑定") { _, _ ->
                    if (active && !bound()) sync.bind(remote)
                }
                previewDialog = preview.show()
            }
        }
        if (publisher) {
            publishButton = button("创建新的共享课表") { sync.publish() }
            bindButton = button("绑定已有共享课表（预览）") { inspectAndConfirm() }
            button("诊断 v2（在独立随机测试目录写入合成提交）") { sync.diagnose() }
        } else {
            inspectButton = button("测试连接 / 检查远端快照") { inspectAndConfirm() }
            synchronizeButton = button("立即同步") { sync.sync() }
        }
        if (!publisher) {
            button("创建新的 v2 数据集（使用本地课程）") { sync.publish() }
            button("诊断 v2（独立随机测试目录）") { sync.diagnose() }
        }
        button("将已有 v1 绑定切换到 v2") {
            AlertDialog.Builder(context).setTitle("切换同步协议")
                .setMessage("必须先停止所有设备上的 v1 自动同步，之后所有编辑端均使用 v2。旧客户端无法看到 v2 修改，禁止继续写入旧目录。迁移仅读取旧远端文件，保留课程身份、账号及历史。不能证明共同基线或存在待确认上传时会停止。确认所有旧客户端已停止？")
                .setNegativeButton("取消", null).setPositiveButton("旧客户端已停止，切换") { _, _ -> sync.migrate() }.show()
        }
        conflictButton = button("查看 / 处理同步冲突", false) {
            sync.loadConflicts { items ->
                if (items.isEmpty()) {
                    AlertDialog.Builder(context).setMessage("当前没有待处理冲突。可立即同步检查远端。")
                        .setPositiveButton("关闭", null).show()
                } else {
                    val explanation = "\n\n多个远端分支时，远端栏展示待确认方案；选择后才会发布。选择仅处理冲突及必要关联记录，独立修改仍自动合并。保留本地采用本机当前值或删除；采用远端采用已展示的方案。处理后会再次检查远端。"
                    val builder = AlertDialog.Builder(context).setTitle("同步冲突 · ${items.size} 项")
                        .setPositiveButton(if (items.size > 4) "全部保留本地" else "保留本地") { _, _ -> sync.resolve(ScheduleMerge.Choice.LOCAL) }
                        .setNeutralButton(if (items.size > 4) "全部采用远端" else "采用远端") { _, _ -> sync.resolve(ScheduleMerge.Choice.REMOTE) }
                        .setNegativeButton("暂不处理", null)
                    fun summary(item: ScheduleMerge.Conflict) = item.description.take(12000) +
                        if (item.description.length > 12000) "\n摘要较长，完整冲突输入仍已保存；可暂不处理。" else ""
                    if (items.size <= 4) builder.setMessage(items.joinToString("\n\n", transform = ::summary) + explanation)
                    else {
                        val choices = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
                        choices.addView(TextView(context).apply { text = "选择一项查看；下方操作将处理全部 ${items.size} 项。"; textSize = 16f })
                        val list = ListView(context).apply {
                            adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1,
                                items.mapIndexed { index, item -> "${index + 1}. ${item.description.lineSequence().first().take(120)}" })
                            setOnItemClickListener { _, _, index, _ ->
                                AlertDialog.Builder(context).setTitle("冲突 ${index + 1} / ${items.size}")
                                    .setMessage(summary(items[index]) + explanation).setPositiveButton("返回列表", null).show()
                            }
                        }
                        choices.addView(list, LinearLayout.LayoutParams(-1, (240 * context.resources.displayMetrics.density).toInt()))
                        builder.setView(choices)
                    }
                    builder.show()
                }
            }
        }
        refresh()
        val previous = sync.changed
        val listener: () -> Unit = { previous?.invoke(); refresh() }
        sync.changed = listener
        val dialog = AlertDialog.Builder(context).setTitle(if (publisher) "共享课表 · WebDAV" else "共享课表 · WebDAV")
            .setView(ScrollView(context).apply { addView(fields) }).setNegativeButton("关闭", null).create()
        dialog.setOnDismissListener { active = false; previewDialog?.dismiss(); if (sync.changed === listener) sync.changed = previous; password?.text?.clear() }
        dialog.show()
        return dialog
    }
}
