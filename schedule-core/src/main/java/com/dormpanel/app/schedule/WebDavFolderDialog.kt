package com.dormpanel.app.schedule

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.*
import java.util.concurrent.Executors

/** Read-only navigation. Selection merely fills the caller's draft URL. */
object WebDavFolderDialog {
    fun show(context: Context, account: WebDavAccount, selected: (String) -> Unit): AlertDialog {
        val client = WebDavClient()
        val root = WebDavFolders.checked(client, account.baseUrl, account.baseUrl)
        require(client.url(root).pathSegments.filter(String::isNotEmpty).lastOrNull() != "dormpanel-sync-v2") { "请将服务器地址设为共享父目录或其上级" }
        var current = root; var generation = 0; var active = true; var verified = false
        val worker = Executors.newSingleThreadExecutor(); val main = Handler(Looper.getMainLooper())
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(24,12,24,12) }
        val path = TextView(context).also { column.addView(it) }
        val status = TextView(context).also { column.addView(it) }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(ScrollView(context).apply { addView(list) }, LinearLayout.LayoutParams(-1, (240*context.resources.displayMetrics.density).toInt()))
        val dialog = AlertDialog.Builder(context).setTitle("选择共享父目录").setView(column)
            .setPositiveButton("选择此文件夹", null).setNegativeButton("取消",null).setNeutralButton("上一级",null).create()
        fun load(target: String) {
            val safe = WebDavFolders.checked(client,root,target)
            current = safe; verified = false; val ticket = ++generation
            path.text = safe; status.text = "正在加载…"; list.removeAllViews(); dialog.getButton(-1)?.isEnabled = false
            worker.execute {
                val result = runCatching {
                    val items = client.list(account,safe, strict = true)
                    check(items.any { it.folder && WebDavFolders.checked(client,root,it.url) == safe }) { "服务器未确认当前路径为文件夹" }
                    WebDavFolders.children(client,root,safe,items)
                }
                main.post {
                    if (!active || generation != ticket) return@post
                    result.onSuccess { children ->
                        verified = true; dialog.getButton(-1).isEnabled = true
                        status.text = if (children.isEmpty()) "没有子文件夹，可选择当前目录" else "请选择文件夹进入，或选择当前目录"
                        children.forEach { child -> list.addView(Button(context).apply { text = "▸ ${child.name}"; isAllCaps = false; setOnClickListener { load(child.url) } }) }
                    }.onFailure { status.text = "无法列出目录：${it.message}"; list.addView(Button(context).apply { text = "重试"; setOnClickListener { load(safe) } }) }
                }
            }
        }
        dialog.setOnDismissListener { active = false; generation++; worker.shutdownNow() }
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener { if (verified) { selected(current); dialog.dismiss() } }
            dialog.getButton(-3).setOnClickListener { if (current != root) load(client.url(current).resolve("../")!!.toString()) }
            load(root)
        }
        dialog.show(); return dialog
    }
}
