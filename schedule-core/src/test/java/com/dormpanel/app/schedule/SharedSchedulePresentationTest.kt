package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test

class SharedSchedulePresentationTest {
    private fun state(mode: SharedMode) = SharedSchedulePresentation(mode = mode, ready = true)
    @Test fun protocolActionMatrix() {
        val expected = mapOf(
            SharedMode.UNCONFIGURED to emptySet(), SharedMode.UNBOUND to setOf(SharedAction.CREATE, SharedAction.JOIN),
            SharedMode.V1 to emptySet(), SharedMode.V2 to setOf(SharedAction.SYNC), SharedMode.V3 to setOf(SharedAction.SYNC))
        for ((mode, primary) in expected) assertEquals(mode.name, primary, state(mode).primary)
        assertTrue(SharedAction.CREATE_V2 in state(SharedMode.UNBOUND).actions)
        assertTrue(SharedAction.MIGRATE_V1 in state(SharedMode.V1).actions)
        assertTrue(SharedAction.MIGRATE_V3 in state(SharedMode.V2).actions)
        val v3 = state(SharedMode.V3).actions
        assertTrue(SharedAction.ENDPOINT in v3)
        for (a in listOf(SharedAction.CREATE_V2, SharedAction.JOIN_V2, SharedAction.MIGRATE_V3, SharedAction.RECOVER, SharedAction.CANCEL_MIGRATION)) assertFalse(a in v3)
    }
    @Test fun conflictRecoveryTransitionAndUnknownAreContextual() {
        val normal = state(SharedMode.V3)
        assertFalse(SharedAction.CONFLICT in normal.actions)
        assertTrue(SharedAction.CONFLICT in normal.copy(conflict = true).primary)
        assertEquals(setOf(SharedAction.RECOVER), normal.copy(recovery = true).primary)
        assertFalse(SharedAction.CREATE in normal.copy(recovery = true).actions)
        assertTrue(SharedAction.CANCEL_MIGRATION in normal.copy(transition = true, migrated = true).actions)
        assertFalse(SharedAction.CANCEL_MIGRATION in normal.copy(transition = true).actions)
        assertEquals(setOf(SharedAction.SWITCH), state(SharedMode.V2).copy(transition = true).primary)
        assertEquals(setOf(SharedAction.DETAILS), state(SharedMode.UNKNOWN).actions)
    }
    @Test fun factualDailyCopyAndStaleCheck() {
        val normal = state(SharedMode.V3).copy(result = "OBSERVED")
        assertTrue(normal.summary().contains("检查成功"))
        for (word in listOf("Epoch", "Writer", "DAG", "Descriptor", "全设备", "未知设备")) assertFalse(normal.summary().contains(word))
        assertTrue(normal.copy(pending = true).summary().contains("待发布"))
        assertTrue(normal.copy(confirming = true).summary().contains("待服务器回读确认"))
        assertTrue(normal.copy(result = "NETWORK").summary().contains("检查网络"))
        val recovery = normal.copy(recovery = true).summary()
        assertTrue(recovery.contains("记录保留")); assertFalse(recovery.contains("检查成功")); assertFalse(recovery.contains("课表已保存"))
        for (result in listOf("BUDGET", "DISCOVERING", "PARTIAL")) {
            val summary = normal.copy(result = result).summary()
            assertTrue(summary.contains("尚未完成")); assertFalse(summary.contains("检查成功"))
        }
        val stale = normal.copy(lastSuccess = 1).summary(86_400_002)
        assertTrue(stale.contains("本机课表仍可使用")); assertFalse(stale.contains("数据过期"))
        assertFalse(state(SharedMode.UNCONFIGURED).summary().contains("离线"))
    }
    @Test fun retainedReceiptIsNotAnUnconfirmedPublication() {
        for (phase in listOf(null, "RECEIPTED", "READBACK_CONFIRMED")) assertFalse(v3AwaitingReadback(phase))
        for (phase in listOf("STAGED", "ATTEMPTED", "UNCERTAIN")) assertTrue(v3AwaitingReadback(phase))
        assertTrue(state(SharedMode.V3).copy(receipting = true).summary().contains("服务器已回读，待确认本机回执"))
    }
}
