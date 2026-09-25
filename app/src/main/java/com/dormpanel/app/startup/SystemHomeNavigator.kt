package com.dormpanel.app.startup

import android.content.Context
import android.content.Intent

class SystemHomeNavigator(
    private val context: Context,
    private val resolves: (Intent) -> Boolean = { it.resolveActivity(context.packageManager) != null },
    private val launches: (Intent) -> Unit = context::startActivity,
) {
    fun openHome(): Boolean {
        val intent = homeIntent()
        if (!resolves(intent)) return false
        return try {
            launches(intent)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    companion object {
        fun homeIntent(): Intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
