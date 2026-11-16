package com.dormpanel.app.schedule

import android.content.Context
import androidx.appcompat.app.AlertDialog

/** Shared global import-account flow for manual settings and the local phone portal. */
internal fun saveImportAccount(
    context: Context,
    settings: WebDavSettings,
    sync: WebDavSyncController,
    previous: WebDavAccount?,
    account: WebDavAccount,
    done: (String?) -> Unit,
): AlertDialog? {
    val publicAccount = settings.publicAccount()
    fun stillCurrent() = settings.account().let { current ->
        settings.publicAccount() == publicAccount &&
            if (previous == null) current == null else WebDavCredentials.sameConfiguration(previous, current)
    }
    if (!stillCurrent()) {
        done("账号已变化，请重新填写")
        return null
    }
    val client = WebDavClient()
    val oldUrl = publicAccount.first
    val old = oldUrl.takeIf(String::isNotBlank)?.let { runCatching { client.url(it) }.getOrNull() }
    val next = client.url(account.baseUrl)
    val originChanged = oldUrl.isNotBlank() &&
        (old == null || old.scheme != next.scheme || old.host != next.host || old.port != next.port)
    val bindings = settings.bindings().map { it.sourceId }.toSet()
    fun save(removeBindings: Boolean) {
        if (!stillCurrent() || (originChanged && settings.bindings().any { it.sourceId !in bindings })) {
            done("账号或导入绑定已变化，请重新填写")
            return
        }
        if (removeBindings && runCatching { sync.clearBindingsForAccountChange() }.isFailure) {
            done("无法移除旧导入绑定，账号未保存")
            return
        }
        val saved = runCatching { settings.saveAccount(account) }.isSuccess
        done(when {
            saved -> null
            removeBindings -> "旧导入绑定已移除，账号保存失败"
            else -> "无法保存 WebDAV 账号"
        })
    }
    if (!originChanged || bindings.isEmpty()) {
        save(false)
        return null
    }
    return AlertDialog.Builder(context).setTitle("WebDAV 服务器已改变")
        .setMessage("有 ${bindings.size} 个文件导入绑定指向旧服务器。移除这些绑定后，本机课程内容保留。之后可重新选择导入来源。独立 v3 访问端点保持原有配置。")
        .setNegativeButton("保留当前账号") { _, _ -> done("已取消账号更换") }
        .setPositiveButton("移除导入绑定并保存") { _, _ -> save(true) }
        .setOnCancelListener { done("已取消账号更换") }
        .show()
}
