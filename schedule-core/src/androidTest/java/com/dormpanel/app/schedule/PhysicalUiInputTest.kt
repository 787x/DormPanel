package com.dormpanel.app.schedule

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opt-in native accessibility text input for OEM keyboards; never reads/writes app storage. */
class PhysicalUiInputTest {
    @Test fun enterText() {
        val args = InstrumentationRegistry.getArguments()
        val field = args.getString("pr36Field")
        org.junit.Assume.assumeTrue(field != null)
        val text = args.getString("pr36Text")
        val progress = args.getString("pr36Progress")?.toFloat()
        require((text == null) != (progress == null))
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun set(node: AccessibilityNodeInfo): Boolean {
            if (node.contentDescription?.toString() == field && progress != null) {
                return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
                    putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, progress)
                })
            }
            if (node.isEditable && node.contentDescription?.toString() == field) {
                return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                })
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { if (set(child)) return true } finally { child.recycle() }
            }
            return false
        }
        var observed = ui.rootInActiveWindow
        repeat(50) { if (observed == null) { android.os.SystemClock.sleep(100); observed = ui.rootInActiveWindow } }
        val root = requireNotNull(observed)
        try {
            require(root.packageName?.toString() in listOf("com.dormpanel.app", "com.dormpanel.timetable"))
            assertTrue("Requested editable field was not found", set(root))
        } finally { root.recycle() }
    }
}
