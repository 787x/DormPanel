package com.dormpanel.phone

import com.dormpanel.app.schedule.ClassOccurrence

/** One identity palette: cards use its theme tints, widgets use its accent. */
internal object CourseColors {
    private data class Color(val accent: Int, val dark: Int, val light: Int)
    private val colors = listOf(
        Color(R.drawable.widget_accent_0,0x294d68,0xdceaf4), Color(R.drawable.widget_accent_1,0x48583b,0xe4ecd9),
        Color(R.drawable.widget_accent_2,0x654351,0xf1dfe7), Color(R.drawable.widget_accent_3,0x574b70,0xe9e1f2),
        Color(R.drawable.widget_accent_4,0x635334,0xf0e7d4), Color(R.drawable.widget_accent_5,0x365c58,0xdaece7))
    fun index(item: ClassOccurrence) = Math.floorMod((item.sourceId.orEmpty()+"|"+item.seriesId).hashCode(),colors.size)
    fun accentDrawable(item: ClassOccurrence) = colors[index(item)].accent
    fun background(item: ClassOccurrence, dark: Boolean) = colors[index(item)].let { if (dark) it.dark else it.light } or (0xff shl 24)
}
