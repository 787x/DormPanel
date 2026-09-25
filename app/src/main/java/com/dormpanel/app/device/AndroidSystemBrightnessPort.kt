package com.dormpanel.app.device

import android.content.Context
import android.provider.Settings

/** X08E Android 9 reports a display setting range of 1..255 in dumpsys display. */
class AndroidSystemBrightnessPort(context: Context) : SystemBrightnessPort {
    private val app = context.applicationContext
    override fun read(): SystemBrightnessState {
        val resolver = app.contentResolver
        return SystemBrightnessState(
            canWrite = Settings.System.canWrite(app),
            automatic = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
            raw = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 1),
        )
    }
    override fun setBrightness(raw: Int): Boolean = runCatching {
        Settings.System.canWrite(app) && Settings.System.putInt(app.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS, raw.coerceIn(1, 255))
    }.getOrDefault(false)
    override fun setAutomatic(enabled: Boolean): Boolean = runCatching {
        Settings.System.canWrite(app) && Settings.System.putInt(app.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            if (enabled) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
    }.getOrDefault(false)
}
