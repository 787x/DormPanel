package com.dormpanel.app.startup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dormpanel.app.MainActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!StartupPolicy(PreferencesStartupStore(context)).startAfterBoot) return
        try {
            context.startActivity(mainActivityIntent(context))
            Log.i(TAG, "Boot launch requested")
        } catch (error: RuntimeException) {
            // A failed foreground launch must leave the system Home usable.
            Log.w(TAG, "Boot launch failed", error)
        }
    }

    companion object {
        private const val TAG = "DormPanelBoot"

        fun mainActivityIntent(context: Context): Intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }
}
