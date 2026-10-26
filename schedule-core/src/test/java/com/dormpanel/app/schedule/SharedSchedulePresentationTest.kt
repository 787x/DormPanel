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
    @Test fun failedLocalReadSuppressesOldHealthyFactsAndBusinessActions() {
        val old = state(SharedMode.V3).copy(result = "PUBLISHED", lastSuccess = 1)
        val failed = old.copy(localRead = SharedLocalRead.FAILED)
        assertTrue(failed.summary().contains("读取失败"))
        for (health in listOf("课表已保存", "检查成功", "仍可使用")) assertFalse(failed.summary().contains(health))
        assertEquals(setOf(SharedAction.DETAILS), failed.actions)
        assertTrue(failed.copy(recovery = true).actions.contains(SharedAction.RECOVER))
        assertTrue(old.summary().contains("检查成功"))
        assertTrue(SharedSchedulePresentation().summary().contains("正在读取"))
        assertFalse(old.copy(ready = false).summary().contains("检查成功"))
        assertFalse(old.copy(localRead = SharedLocalRead.LOADING).summary().contains("课表已保存"))
    }
    @Test fun confirmationPinsOperationModeBindingAndAccessScope() {
        val unbound = state(SharedMode.UNBOUND)
        for (action in listOf(SharedAction.CREATE, SharedAction.CREATE_V2, SharedAction.JOIN, SharedAction.JOIN_V2)) {
            val token = SharedConfirmation(action, SharedMode.UNBOUND, null, "A")
            token.requireMatches(unbound, "A")
            assertThrows(IllegalStateException::class.java) { token.requireMatches(unbound, "B") }
            for (mode in listOf(SharedMode.V2, SharedMode.V3)) assertThrows(IllegalStateException::class.java) { token.requireMatches(state(mode), "A") }
        }
        val v2 = state(SharedMode.V2).copy(bindingId = "dataset-A")
        for (action in listOf(SharedAction.MIGRATE_V3, SharedAction.JOIN_MIGRATED)) {
            val token = SharedConfirmation(action, v2.mode, v2.bindingId, "A")
            token.requireMatches(v2, "A")
            assertThrows(IllegalStateException::class.java) { token.requireMatches(v2.copy(bindingId = "dataset-B"), "A") }
            assertThrows(IllegalStateException::class.java) { token.requireMatches(state(SharedMode.V3), "A") }
        }
        val v1 = SharedConfirmation(SharedAction.MIGRATE_V1, SharedMode.V1, "old", "A")
        assertThrows(IllegalStateException::class.java) { v1.requireMatches(v2, "A") }
    }
    @Test fun staleConfirmationHasAnAdmissionRefusalTypeAndDoesNotImplyServerFailure() {
        val token = SharedConfirmation(SharedAction.CREATE_V2, SharedMode.UNBOUND, null, "A")
        assertThrows(SharedConfirmationRejected::class.java) { token.requireMatches(state(SharedMode.V3), "A") }
        val summary = state(SharedMode.UNBOUND).copy(result = "VERIFIED", confirmationRejected = true).summary()
        assertTrue(summary.contains("原操作未执行")); assertTrue(summary.contains("重新打开"))
        assertTrue(summary.contains("检查成功")); assertFalse(summary.contains("检查未成功")); assertFalse(summary.contains("操作完成"))
    }
    @Test fun joinFeedbackSeparatesDiscoveryFromValidationAndNetwork() {
        val unbound = state(SharedMode.UNBOUND)
        for ((code, expected) in mapOf("NO_CANDIDATE" to "尚未发现可加入", "NO_V3" to "尚未证明", "CANDIDATE_V2" to "已验证此位置的旧版", "CANDIDATE_V3" to "新版候选", "VALIDATION_FAILED" to "验证未通过", "NETWORK" to "无法连接服务器")) {
            val text = unbound.copy(result = code).summary()
            assertTrue(text, text.contains(expected)); assertFalse(text.contains("检查成功"))
        }
    }
    @Test fun checkScopeIncludesLocationUserAndTrustWithoutPassword() {
        val a = WebDavAccount("https://example.org/", "u", "secret")
        assertEquals(sharedCheckScope(a, "https://example.org/A"), sharedCheckScope(a, "https://example.org:443/A/"))
        assertNotEquals(sharedCheckScope(a, "https://example.org/A/"), sharedCheckScope(a, "https://example.org/B/"))
        assertNotEquals(sharedCheckScope(a, "https://example.org/A/"), sharedCheckScope(WebDavAccount(a.baseUrl, "other", "secret"), "https://example.org/A/"))
        assertEquals(64, sharedCheckScope(a, "https://example.org/A/").length)
        val certificate = okhttp3.tls.HeldCertificate.Builder().build().certificatePem()
        assertNotEquals(sharedCheckScope(a, "https://example.org/A/"), sharedCheckScope(WebDavAccount(a.baseUrl, a.username, a.password, certificate), "https://example.org/A/"))
    }
}
