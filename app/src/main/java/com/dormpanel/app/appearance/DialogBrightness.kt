package com.dormpanel.app.appearance

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import androidx.appcompat.app.AlertDialog

/** A Dialog owns a separate Window on API 28; copy the Activity's effective setting. */
fun Dialog.matchActivityBrightness() {
    var owner: Context? = context
    while (owner != null && owner !is Activity) owner = (owner as? ContextWrapper)?.baseContext
    val brightness = (owner as? Activity)?.window?.attributes?.screenBrightness ?: return
    window?.let { dialogWindow ->
        val attributes = dialogWindow.attributes
        if (attributes.screenBrightness != brightness) {
            attributes.screenBrightness = brightness
            dialogWindow.attributes = attributes
        }
    }
}

fun AlertDialog.Builder.showMatchingBrightness(): AlertDialog = show().also { it.matchActivityBrightness() }
