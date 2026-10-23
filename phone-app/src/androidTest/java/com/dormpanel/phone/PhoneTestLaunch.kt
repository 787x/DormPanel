package com.dormpanel.phone

import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference

/** Explicit opt-in test harness only. No root code or permissions enter either application. */
internal fun launchPhoneTestActivity(): ActivityScenario<TimetableActivity> {
    if (InstrumentationRegistry.getArguments().getString("pr35RootUi") != "true") return ActivityScenario.launch(TimetableActivity::class.java)
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val pkg = instrumentation.targetContext.packageName
    require(pkg == "com.dormpanel.timetable.testbed") { "Root UI assist is restricted to the isolated test package" }
    val caller = Thread.currentThread()
    val failure = AtomicReference<Throwable>()
    val completed = java.util.concurrent.atomic.AtomicBoolean(false)
    val assist = Thread {
        try {
            var waiting = false
            for (attempt in 0 until 100) {
                if (completed.get()) return@Thread
                if (caller.stackTrace.any { it.className == "android.app.Instrumentation" && it.methodName == "startActivitySync" }) { waiting = true; break }
                Thread.sleep(100)
            }
            check(waiting) { "Test framework never reached startActivitySync" }
            // executeShellCommand uses argv splitting; keep su's -c argument free of spaces.
            val script = java.io.File(instrumentation.targetContext.filesDir, "pr35-isolated-ui-launch.sh")
            script.writeText("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $pkg/com.dormpanel.phone.TimetableActivity\n")
            val command = "su -c /system/bin/sh<${script.absolutePath}"
            val fd = instrumentation.uiAutomation.executeShellCommand(command)
            val output = ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
            check(output.contains("Status: ok")) { "Root-assisted isolated Activity launch failed: ${output.take(160)}" }
        } catch (error: Throwable) { failure.set(error) }
    }.apply { isDaemon = true; start() }
    val scenario = ActivityScenario.launch(TimetableActivity::class.java)
    completed.set(true)
    assist.join(15_000)
    failure.get()?.let { scenario.close(); throw it }
    scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    return scenario
}

/** MIUI's system save-password overlay steals focus after synthetic WebDAV credentials.
 * Cancel only this identified prompt; never save test credentials or dismiss app dialogs. */
internal fun dismissTestPasswordPrompt() {
    val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return
    if (root.findAccessibilityNodeInfosByText("自动保存账号密码").isNotEmpty()) {
        root.findAccessibilityNodeInfosByText("取消").firstOrNull()?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
    }
}
