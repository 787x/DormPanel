package com.dormpanel.phone

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.dormpanel.app.schedule.ScheduleStores

internal object WidgetTitleSettings {
    private fun prefs(context: Context) = context.getSharedPreferences("today-widget-titles",Context.MODE_PRIVATE)
    fun get(context: Context,id: Int) = prefs(context).getString("title-$id",null)?.takeIf { it.isNotBlank() }
    fun tomorrow(context: Context,id: Int) = prefs(context).getBoolean("tomorrow-$id",true)
    fun periods(context: Context,id: Int) = prefs(context).getBoolean("periods-$id",true)
    fun weekdays(context: Context,id: Int) = prefs(context).getBoolean("weekdays-$id",false)
    fun save(context: Context,id: Int,value: String, tomorrow: Boolean? = null, periods: Boolean? = null, weekdays: Boolean? = null): Boolean {
        val edit=prefs(context).edit().putString("title-$id",value.trim().take(80))
        if (tomorrow!=null) edit.putBoolean("tomorrow-$id",tomorrow)
        if (periods!=null) edit.putBoolean("periods-$id",periods)
        if (weekdays!=null) edit.putBoolean("weekdays-$id",weekdays)
        return edit.commit()
    }
    fun remove(context: Context,ids: IntArray) { prefs(context).edit().apply { ids.forEach { remove("title-$it"); remove("tomorrow-$it"); remove("periods-$it"); remove("weekdays-$it") } }.apply() }
}

/** Launcher configuration only stores display preferences; never writes course or sync data. */
class WidgetTitleActivity : AppCompatActivity() {
    private lateinit var name: EditText
    private lateinit var tomorrow: CheckBox
    private var displayMode: Spinner? = null
    private var daysMode: Spinner? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        val provider=AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider
        if (id==AppWidgetManager.INVALID_APPWIDGET_ID || provider?.packageName != packageName) { finish(); return }
        val initial=WidgetTitleSettings.get(this,id) ?: TodayCourses.title(null)
        val padding=(20*resources.displayMetrics.density).toInt()
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(padding,padding,padding,padding) }
        body.addView(TextView(this).apply { text="课表名称"; textSize=20f })
        name=EditText(this).apply { this.id=android.R.id.edit; filters=arrayOf(android.text.InputFilter.LengthFilter(80)); setSingleLine(true); hint="输入桌面显示的课表名称"; setText(savedInstanceState?.getString("name") ?: initial) }
        body.addView(name)
        tomorrow=CheckBox(this).apply { this.id=android.R.id.checkbox; text="今日课程结束后显示明日课程"; isChecked=savedInstanceState?.getBoolean("tomorrow") ?: WidgetTitleSettings.tomorrow(this@WidgetTitleActivity,id) }
        if (!CollectionWidgets.isCollection(this,id)) body.addView(tomorrow)
        if (CollectionWidgets.isWeek(this,id)) {
            body.addView(TextView(this).apply { text="周视图显示方式"; textSize=16f })
            displayMode=Spinner(this).apply {
                this.id=R.id.widget_display_mode
                adapter=ArrayAdapter(this@WidgetTitleActivity,android.R.layout.simple_spinner_dropdown_item,listOf("按节次（默认）","按实际时间"))
                setSelection(savedInstanceState?.getInt("displayMode") ?: if(WidgetTitleSettings.periods(this@WidgetTitleActivity,id)) 0 else 1)
            }
            body.addView(displayMode)
            body.addView(TextView(this).apply { text="显示天数"; textSize=16f })
            daysMode=Spinner(this).apply {
                this.id=R.id.widget_days_mode
                adapter=ArrayAdapter(this@WidgetTitleActivity,android.R.layout.simple_spinner_dropdown_item,listOf("7 天（周一至周日）","5 天（周一至周五）"))
                setSelection(savedInstanceState?.getInt("daysMode") ?: if(WidgetTitleSettings.weekdays(this@WidgetTitleActivity,id)) 1 else 0)
            }
            body.addView(daysMode)
            body.addView(TextView(this).apply { text="按节次使用有效统一节次表并压缩课间；节次表缺失或不统一时，自动改用实际时间。" })
        }
        body.addView(Button(this).apply { text="保存"; setOnClickListener {
            if (name.text.isBlank()) { name.error="请输入课表名称"; return@setOnClickListener }
            if (!WidgetTitleSettings.save(this@WidgetTitleActivity,id,name.text.toString(),if(CollectionWidgets.isCollection(this@WidgetTitleActivity,id)) null else tomorrow.isChecked,displayMode?.let { it.selectedItemPosition==0 },daysMode?.let { it.selectedItemPosition==1 })) { name.error="名称保存失败，请重试"; return@setOnClickListener }
            TodayWidgetUpdates.request(applicationContext)
            setResult(Activity.RESULT_OK,Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id)); finish()
        } })
        setContentView(body)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(body) { view,insets ->
            val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(padding+bars.left,padding+bars.top,padding+bars.right,padding+bars.bottom); insets
        }
        if (savedInstanceState==null && WidgetTitleSettings.get(this,id)==null) {
            val weak=java.lang.ref.WeakReference(this)
            ScheduleStores.create(applicationContext).loadPresentation { result ->
                weak.get()?.let { activity ->
                    if (!activity.isFinishing && !activity.isDestroyed && activity.name.text.toString()==initial)
                        activity.name.setText(TodayCourses.title(result.getOrNull()))
                }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { if (::name.isInitialized) outState.putString("name",name.text.toString()); if (::tomorrow.isInitialized) outState.putBoolean("tomorrow",tomorrow.isChecked); displayMode?.let { outState.putInt("displayMode",it.selectedItemPosition) }; daysMode?.let { outState.putInt("daysMode",it.selectedItemPosition) }; super.onSaveInstanceState(outState) }
}
