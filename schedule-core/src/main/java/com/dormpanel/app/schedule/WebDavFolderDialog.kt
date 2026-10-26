package com.dormpanel.app.schedule

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.*
import java.util.concurrent.Executors

/** Shared, read-only navigator. Callbacks only update drafts or open import previews. */
object WebDavFolderDialog {
    fun show(context: Context, account: WebDavAccount, initialFolder: String = account.baseUrl,
             importSelected: ((WebDavMode, String) -> Unit)? = null, selected: (String) -> Unit): AlertDialog {
        val client = WebDavClient()
        val root = WebDavFolders.checked(client, account.baseUrl, account.baseUrl)
        require(client.url(root).pathSegments.none { it in listOf("dormpanel-sync-v2", "dormpanel-sync-v3") }) { "请将服务器地址设为共享父目录或其上级" }
        var current = runCatching { WebDavFolders.checked(client,root,initialFolder) }.getOrDefault(root)
        var generation = 0; var active = true; var verified = false
        val worker = Executors.newSingleThreadExecutor(); val main = Handler(Looper.getMainLooper())
        fun dp(value: Int) = (value*context.resources.displayMetrics.density).toInt()
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(8)) }
        val crumbs = LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL }
        column.addView(HorizontalScrollView(context).apply { addView(crumbs) })
        val path = TextView(context).apply { maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.MIDDLE }.also { column.addView(it) }
        val status = TextView(context).apply { maxLines=3 }.also { column.addView(it) }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val height = minOf(dp(240), (context.resources.displayMetrics.heightPixels*0.34).toInt())
        column.addView(ScrollView(context).apply { addView(list) }, LinearLayout.LayoutParams(-1,height))
        val dialog = AlertDialog.Builder(context).setTitle(if(importSelected==null) "选择共享父目录" else "选择 WebDAV 课程文件或跟踪目录")
            .setView(column).setPositiveButton(if(importSelected==null) "选择此文件夹" else "关闭浏览", null)
            .setNegativeButton("取消",null).setNeutralButton("上一级",null).create()
        fun control(text: String, action: () -> Unit) = Button(context).apply {
            this.text=text;isAllCaps=false;minHeight=dp(48);setOnClickListener { action() }
        }
        fun load(target: String) {
            val safe = WebDavFolders.checked(client,root,target)
            current = safe; verified = false; val ticket = ++generation
            path.text = "当前文件夹："+android.net.Uri.decode(client.url(safe).encodedPath)
            status.text = "正在加载…"; list.removeAllViews(); dialog.getButton(-1)?.isEnabled = false
            dialog.getButton(-3)?.isEnabled = safe != root
            crumbs.removeAllViews(); crumbs.addView(control("起始目录") { load(root) })
            val baseParts=client.url(root).pathSegments.filter(String::isNotEmpty)
            val parts=client.url(safe).pathSegments.filter(String::isNotEmpty).drop(baseParts.size)
            parts.forEachIndexed { i,name ->
                val u=client.url(root).newBuilder().apply { parts.take(i+1).forEach { addPathSegment(it) };addPathSegment("") }.build().toString()
                crumbs.addView(control(name) { load(u) })
            }
            worker.execute {
                val result = runCatching {
                    val items = client.list(account,safe, strict = true)
                    check(items.any { it.folder && WebDavFolders.checked(client,root,it.url) == safe }) { "服务器未确认当前路径为文件夹" }
                    WebDavFolders.entries(client,root,safe,items,importSelected!=null)
                }
                main.post {
                    if (!active || generation != ticket) return@post
                    result.onSuccess { children ->
                        verified = true; dialog.getButton(-1).isEnabled = true
                        status.text = if (children.isEmpty()) "当前目录为空" else "点击文件夹进入；选择操作需单独确认"
                        children.forEach { child -> list.addView(control("${if(child.folder) "📁" else "📄"} ${child.name}") {
                            if(child.folder)load(child.url) else { dialog.dismiss();importSelected?.invoke(WebDavMode.FILE,child.url) }
                        }) }
                        if(importSelected!=null) for((label,mode) in listOf("ICS" to WebDavMode.FOLDER_LATEST_ICS,"CSV" to WebDavMode.FOLDER_LATEST_CSV,"XLS" to WebDavMode.FOLDER_LATEST_XLS)) {
                            list.addView(control("跟踪当前目录最新 $label（下一步预览）") { dialog.dismiss();importSelected(mode,safe) })
                        }
                    }.onFailure { status.text = "无法列出目录：${it.message}"; list.addView(control("重试") { load(safe) }) }
                }
            }
        }
        dialog.setOnDismissListener { active = false; generation++; worker.shutdownNow() }
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener { if(importSelected!=null)dialog.dismiss() else if (verified) { selected(current); dialog.dismiss() } }
            dialog.getButton(-3).setOnClickListener { if (current != root) load(client.url(current).resolve("../")!!.toString()) }
            load(current)
        }
        dialog.show(); return dialog
    }
}
