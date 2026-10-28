package com.dormpanel.phone

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import com.dormpanel.app.schedule.*
import org.hamcrest.Matchers.*
import org.junit.Assert.*
import org.junit.Test
import android.view.View
import android.view.ViewGroup

class CourseColorVisualTest {
    @Test fun importedWeekDayManagementPickerAndNarrowLargeFont() {
        val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
        require(c.packageName.endsWith(".pr68testbed"))
        val dark=InstrumentationRegistry.getArguments().getString("dark")=="true"
        i.runOnMainSync{androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(if(dark)2 else 1)}
        fun shot(name:String){i.waitForIdleSync();val b=i.uiAutomation.takeScreenshot();java.io.File(c.getExternalFilesDir(null),"pr68-final-$name-${if(dark)"dark" else "light"}.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        c.getSharedPreferences("com.dormpanel.phone.TimetableActivity",0).edit().putString("date",java.time.LocalDate.now().with(java.time.DayOfWeek.MONDAY).toString()).putBoolean("weekView",true).commit()
        try { launchPhoneTestActivity().use{scenario->
            Thread.sleep(800)
            var key=""
            scenario.onActivity {activity->
                val colors=CourseColors.snapshot(c,activity.source.state)
                val group=CourseBatch.candidates(activity.source.state).single{it.originalTitle=="高等数学 B-1"}
                key=colors.key(group)!!;CourseColors.save(c,key,null);activity.source.refresh()
            }
            Thread.sleep(200);shot("week")
            scenario.onActivity {activity->
                val colors=CourseColors.snapshot(c,activity.source.state)
                val found=mutableListOf<Int>()
                fun visit(view:View){
                    if(view is CourseCard){
                        val item=CourseCard::class.java.getDeclaredField("item").apply{isAccessible=true}.get(view) as ClassOccurrence
                        val shape=(view.background as android.graphics.drawable.RippleDrawable).getDrawable(0) as android.graphics.drawable.GradientDrawable
                        assertEquals(CourseColors.background(colors.rgb(item),dark),shape.color!!.defaultColor)
                        if(item.entry.title=="高等数学 B-1")found.add(colors.rgb(item))
                    }
                    if(view is ViewGroup)for(n in 0 until view.childCount)visit(view.getChildAt(n))
                }
                visit(activity.window.decorView);assertEquals(3,found.size);assertEquals(1,found.distinct().size)
            }
            onView(withText("下周 ›")).perform(click());Thread.sleep(200);shot("next-week")
            onView(withText("‹ 上周")).perform(click())
            onView(withText("日视图")).perform(click());shot("day")
            scenario.onActivity{(TimetableActivity::class.java.getDeclaredField("editors").apply{isAccessible=true}.get(it) as PhoneEditors).courseManagement()}
            shot("management")
            onView(withText(startsWith("高等数学 B-1\n"))).perform(click());shot("editor")
            onView(withText(startsWith("课程颜色 ·"))).perform(scrollTo(),click())
            onView(withHint("自定义颜色 #RRGGBB")).perform(scrollTo(),replaceText("#B49138"),closeSoftKeyboard());shot("custom-picker")
            onView(withText("使用此颜色")).perform(click());onView(withText("取消")).perform(click())
            scenario.onActivity{assertNull(CourseColors.snapshot(c,it.source.state).manual(key))}
            onView(withText(startsWith("高等数学 B-1\n"))).perform(click())
            onView(withText(startsWith("课程颜色 ·"))).perform(scrollTo(),click())
            onView(withHint("自定义颜色 #RRGGBB")).perform(scrollTo(),replaceText("#B49138"),closeSoftKeyboard())
            onView(withText("使用此颜色")).perform(click());onView(withText("保存修改")).perform(click())
            scenario.onActivity{assertEquals(0xb49138,CourseColors.snapshot(c,it.source.state).manual(key))}
            onView(withText(startsWith("高等数学 B-1\n"))).perform(click())
            onView(withText(startsWith("课程颜色 ·"))).perform(scrollTo(),click());onView(withText("使用自动颜色")).perform(click());onView(withText("保存修改")).perform(click())
            scenario.onActivity{assertNull(CourseColors.snapshot(c,it.source.state).manual(key))}
            onView(withText("返回")).perform(click())
            lateinit var narrow:PhoneEditors
            scenario.onActivity {activity->
                val wrapped=android.view.ContextThemeWrapper(activity,activity.theme)
                wrapped.applyOverrideConfiguration(android.content.res.Configuration().apply{fontScale=1.6f})
                narrow=PhoneEditors(wrapped,activity.source);narrow.courseManagement()
                val dialogs=PhoneEditors::class.java.getDeclaredField("dialogs").apply{isAccessible=true}.get(narrow) as List<*>
                (dialogs.last() as android.app.Dialog).window!!.setLayout(activity.dp(280),ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            Thread.sleep(300);shot("narrow-large-font")
            scenario.onActivity{narrow.close()}
        } } finally {i.runOnMainSync{androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(-1)}}
    }
}
