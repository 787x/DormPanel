package com.dormpanel.app.schedule

/** User-facing facts, independent of diagnostic prose and protocol implementation. */
enum class SharedMode { UNKNOWN, UNCONFIGURED, UNBOUND, V1, V2, V3 }
enum class SharedAction(val group: String) {
    SYNC("日常"), CREATE("日常"), JOIN("日常"), CONFLICT("日常"),
    ENDPOINT("连接与位置"), CERTIFICATE("连接与位置"),
    MIGRATE_V1("旧版兼容与升级"), MIGRATE_V3("旧版兼容与升级"), JOIN_MIGRATED("旧版兼容与升级"),
    CREATE_V2("旧版兼容与升级"), JOIN_V2("旧版兼容与升级"), SWITCH("更换课表与恢复"),
    RECOVER("更换课表与恢复"), CANCEL_MIGRATION("更换课表与恢复"),
    AUDIT("技术诊断"), DIAGNOSE("技术诊断"), INSPECT_V2("技术诊断"), DETAILS("技术诊断")
}
data class SharedSchedulePresentation(
    val mode: SharedMode = SharedMode.UNKNOWN,
    val pending: Boolean = false, val confirming: Boolean = false, val receipting: Boolean = false,
    val conflict: Boolean = false, val recovery: Boolean = false,
    val transition: Boolean = false, val migrated: Boolean = false,
    val result: String = "UNCHECKED", val lastSuccess: Long = 0,
    val busy: Boolean = false, val ready: Boolean = false
) {
    val bound get() = mode in setOf(SharedMode.V1, SharedMode.V2, SharedMode.V3)
    val actions: Set<SharedAction> get() = buildSet {
        add(SharedAction.DETAILS)
        if (!ready || mode == SharedMode.UNKNOWN) return@buildSet
        if (recovery) { if (mode == SharedMode.V3) add(SharedAction.RECOVER); return@buildSet }
        if (conflict) add(SharedAction.CONFLICT)
        if (transition) {
            if (mode == SharedMode.V3) { add(SharedAction.SYNC); if (migrated) add(SharedAction.CANCEL_MIGRATION) }
            else if (mode == SharedMode.V2) add(SharedAction.SWITCH)
            return@buildSet
        }
        when (mode) {
            SharedMode.UNBOUND -> addAll(listOf(SharedAction.CREATE, SharedAction.JOIN, SharedAction.CREATE_V2, SharedAction.JOIN_V2, SharedAction.CERTIFICATE, SharedAction.DIAGNOSE))
            SharedMode.V1 -> addAll(listOf(SharedAction.MIGRATE_V1, SharedAction.CERTIFICATE))
            SharedMode.V2 -> addAll(listOf(SharedAction.SYNC, SharedAction.MIGRATE_V3, SharedAction.JOIN_MIGRATED, SharedAction.SWITCH, SharedAction.CERTIFICATE, SharedAction.DIAGNOSE, SharedAction.INSPECT_V2))
            SharedMode.V3 -> { addAll(listOf(SharedAction.SYNC, SharedAction.ENDPOINT)); if (migrated) add(SharedAction.AUDIT) }
            SharedMode.UNCONFIGURED -> add(SharedAction.CERTIFICATE)
            else -> Unit
        }
    }
    val primary get() = actions.filter { it.group == "日常" || recovery && it == SharedAction.RECOVER || transition && it == SharedAction.SWITCH }.toSet()
    fun summary(now: Long = System.currentTimeMillis()): String = buildString {
        append(when {
            !ready -> "正在读取本机课表，请稍候"
            recovery -> "需要恢复未完成的操作；已停止写入，请进入恢复处理"
            mode == SharedMode.UNKNOWN -> "无法安全确定共享状态，请查看诊断；暂不创建或切换课表"
            transition -> "有未完成的课表变更，请继续处理；原数据和恢复记录保留"
            conflict -> "有修改冲突，请查看并处理；双方内容已保留"
            mode == SharedMode.UNCONFIGURED -> "尚未开启共享；请先设置连接"
            mode == SharedMode.UNBOUND -> "连接已配置，尚未加入或创建共享课表"
            mode == SharedMode.V1 -> "已开启旧版共享；请在管理中查看兼容升级步骤"
            else -> "已开启共享"
        })
        if (recovery) append("\n本机数据与待确认记录保留")
        else if (ready && mode != SharedMode.UNKNOWN) append("\n本机课表已保存" + when { receipting -> " · 服务器已回读，待确认本机回执"; confirming -> " · 有操作待服务器回读确认"; pending -> " · 有修改待发布"; else -> "" })
        if (busy) append("\n正在检查服务器")
        if ((bound || mode == SharedMode.UNBOUND) && !busy && !recovery) append(when (result) {
            "NETWORK" -> "\n无法连接服务器，请检查网络后重试"
            "ERROR" -> "\n上次检查未成功，请查看连接或诊断后重试"
            "DISCOVERING", "BUDGET", "PARTIAL", "ROTATED" -> "\n本轮检查尚未完成，进度已保存，请继续同步"
            "OBSERVED", "PUBLISHED", "VERIFIED" -> "\n最近一次服务器检查成功"
            else -> "\n服务器状态待检查"
        })
        if (bound && !recovery && lastSuccess > 0 && now - lastSuccess > 86_400_000L) append("\n超过 24 小时未成功检查服务器，本机课表仍可使用")
    }
}

internal fun v3AwaitingReadback(phase: String?) = phase != null && phase !in setOf("RECEIPTED", "READBACK_CONFIRMED")
