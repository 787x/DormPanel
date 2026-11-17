package com.dormpanel.app.schedule

import org.junit.Assert.*
import org.junit.Test

class ProductivityImportPreviewTest {
    @Test fun summarizesLargeBatchesAndPreservesInput() {
        val lists = List(8) { ProductivityRecord.TodoList("list-$it", "清单 $it") }
        val tasks = lists.flatMap { list ->
            List(20) { i -> ProductivityRecord.Task("${list.id}-$i", list.id,
                "前缀\r\n" + "长".repeat(1000) + "\n未显示", i % 2 == 0, i) }
        }
        val memos = List(9) { ProductivityRecord.Memo("memo-$it", "备忘 $it", "甲".repeat(20_000)) }
        val content = SharedProductivity.Content(lists, tasks, memos)
        val summary = content.importPreview()
        assertTrue(summary.length < 5_000)
        assertTrue(summary.contains("8 张清单 · 160 项任务 · 9 条备忘"))
        assertTrue(summary.contains("20 项任务，10 项已完成，10 项未完成"))
        assertTrue(summary.contains("另有 14 项任务未展示"))
        assertTrue(summary.contains("另有 5 张清单未展示"))
        assertTrue(summary.contains("另有 6 条备忘未展示"))
        assertTrue(summary.contains("正文 20000 字符"))
        assertTrue(summary.contains("☑ 前缀\r\n"))
        assertTrue(summary.contains("☐ 前缀\r\n"))
        assertFalse(summary.contains("未显示"))
        assertTrue(content.tasks.first().text.endsWith("\n未显示"))
        assertEquals(20_000, content.memos.first().body.length)
    }

    @Test fun boundsLinesAndKeepsSurrogatePairsIntact() {
        val text = "甲".repeat(255) + "😀尾"
        val memo = ProductivityRecord.Memo("memo", "备忘", text)
        val content = SharedProductivity.Content(emptyList(), emptyList(), listOf(memo))
        val summary = memo.importPreview(content)
        assertTrue(summary.contains("正文 257 字符"))
        assertTrue(summary.endsWith("甲…（正文已省略）"))
        assertFalse(summary.any { Character.isSurrogate(it) })
        val lines = memo.copy(body = "行\r\n".repeat(12)).importPreview(content)
        assertEquals(7, "\r\n".toRegex().findAll(lines).count())
        assertTrue(lines.endsWith("…（正文已省略）"))
    }
}
