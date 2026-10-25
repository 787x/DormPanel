package com.dormpanel.app.schedule

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch

/** Test APK only. Gives the HTTPS probe the same foreground network state as a real UI. */
class V3HttpsProbeActivity : Activity() {
    companion object {
        @Volatile var resumed=CountDownLatch(1)
        @Volatile var current=WeakReference<V3HttpsProbeActivity>(null)
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text="PR55 隔离 HTTPS 验收\n不操作日用数据库或绑定";textSize=24f;keepScreenOn=true })
    }
    override fun onResume() { super.onResume();current=WeakReference(this);resumed.countDown() }
}
