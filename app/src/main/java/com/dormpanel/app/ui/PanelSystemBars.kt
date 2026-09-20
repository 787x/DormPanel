package com.dormpanel.app.ui

import android.view.View
import android.view.Window
import android.view.WindowInsets

/** Keep card dialogs and the Activity in the same immersive panel presentation. */
@Suppress("DEPRECATION")
fun Window.hidePanelSystemBars() {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        insetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        insetsController?.systemBarsBehavior =
            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    } else {
        decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }
}
