package com.dormpanel.phone

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DaySelectionHighlightTest {
    @Test fun daySelectionKeepsBlueBackgroundAndTodayMarkerWithoutSmallDot() {
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        check(c.packageName.endsWith(".pr72testbed"))
        ActivityScenario.launch<TimetableActivity>(android.content.Intent(c,TimetableActivity::class.java)).use { scenario ->
            scenario.onActivity { a ->
                val day=LocalDate.now()
                val selected=day.plusDays(if(day.dayOfWeek.value==7) -1 else 1)
                val mode=TimetableActivity::class.java.getDeclaredField("weekView").apply { isAccessible=true }
                mode.setBoolean(a,false)
                TimetableActivity::class.java.getDeclaredField("selected").apply { isAccessible=true }.set(a,selected)
                TimetableActivity::class.java.getDeclaredMethod("render").apply { isAccessible=true }.invoke(a)
                fun children(v: android.view.View): List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup) (0 until v.childCount).flatMap { children(v.getChildAt(it)) } else emptyList()
                val buttons=children(a.findViewById(android.R.id.content)).filterIsInstance<android.widget.Button>()
                val todayButton=buttons.first { it.contentDescription?.toString()=="$day 今天" }
                val selectedButton=buttons.first { it.contentDescription?.toString()=="$selected 已选择" }
                val dark=a.resources.configuration.uiMode and 48==32
                val blue=if(dark) 0xff263e53.toInt() else 0xffdceafa.toInt()
                assertEquals(blue,selectedButton.backgroundTintList!!.defaultColor)
                assertNotEquals(blue,todayButton.backgroundTintList?.defaultColor)
                assertTrue(todayButton.text.toString().startsWith("●"))
                assertFalse(selectedButton.text.toString().contains("·"))
                assertTrue(selectedButton.isSelected); assertFalse(todayButton.isSelected)
                mode.setBoolean(a,true)
                TimetableActivity::class.java.getDeclaredMethod("render").apply { isAccessible=true }.invoke(a)
                var weekButtons=children(a.findViewById(android.R.id.content)).filterIsInstance<android.widget.Button>()
                assertEquals(blue,weekButtons.first { it.contentDescription?.toString()=="$day 今天" }.backgroundTintList!!.defaultColor)
                assertTrue(weekButtons.first { it.contentDescription?.toString()=="$selected 已选择" }.text.toString().contains("·"))
                TimetableActivity::class.java.getDeclaredField("selected").apply { isAccessible=true }.set(a,day)
                TimetableActivity::class.java.getDeclaredMethod("render").apply { isAccessible=true }.invoke(a)
                weekButtons=children(a.findViewById(android.R.id.content)).filterIsInstance<android.widget.Button>()
                val chosenToday=weekButtons.first { it.contentDescription?.toString()=="$day 今天 已选择" }
                assertTrue(chosenToday.text.toString().startsWith("●")); assertFalse(chosenToday.text.toString().contains("·"))
                mode.setBoolean(a,false)
                TimetableActivity::class.java.getDeclaredMethod("render").apply { isAccessible=true }.invoke(a)
                assertFalse(children(a.findViewById(android.R.id.content)).filterIsInstance<android.widget.Button>().any { it.text.toString().contains("·") })
            }
        }
    }
}
