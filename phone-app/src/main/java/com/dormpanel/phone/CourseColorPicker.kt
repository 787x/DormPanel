package com.dormpanel.phone

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog

internal object CourseColorPicker {
    fun show(context: Context, initial: Int, manual: Int?, title: String, automatic: Int, accept: (Int?) -> Unit): AlertDialog {
        var draft: Int? = manual
        val hsv = FloatArray(3)
        Color.colorToHSV(initial or (0xff shl 24),hsv)
        val column = context.scheduleColumn()
        val preview = context.scheduleLabel("当前课程预览",18f)
        val hex = EditText(context).apply { hint = "自定义颜色 #RRGGBB"; setSingleLine() }
        var updating = false
        var squareView: View? = null
        var hueBar: SeekBar? = null
        fun update(rgb: Int?) {
            draft = rgb
            val value = rgb ?: automatic
            Color.colorToHSV(value or (0xff shl 24),hsv)
            squareView?.invalidate(); hueBar?.progress = hsv[0].toInt()
            hex.error = null
            val dark = context.resources.configuration.uiMode and 48 == 32
            preview.setBackgroundColor(CourseColors.background(value,dark)); preview.setTextColor(CourseColors.ink(dark))
            preview.text = if (rgb == null) "自动颜色 · $title" else "手动颜色 · $title"
            updating = true; hex.setText("#%06X".format(value and 0xffffff)); updating = false
        }
        column.addView(preview)
        CourseColors.palette.toList().chunked(4).forEach { colors ->
            val row = LinearLayout(context)
            colors.forEach { rgb -> row.addView(context.scheduleButton("●") { update(rgb) }.apply {
                setTextColor(rgb or (0xff shl 24)); contentDescription = "预设颜色 #%06X".format(rgb)
            },LinearLayout.LayoutParams(0,context.dp(48),1f)) }
            column.addView(row)
        }
        val square = object : View(context) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                paint.shader = LinearGradient(0f,0f,width.toFloat(),0f,Color.WHITE,Color.HSVToColor(floatArrayOf(hsv[0],1f,1f)),Shader.TileMode.CLAMP)
                canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
                paint.shader = LinearGradient(0f,0f,0f,height.toFloat(),Color.TRANSPARENT,Color.BLACK,Shader.TileMode.CLAMP)
                canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
                paint.shader = null; paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = context.dp(2).toFloat()
                canvas.drawCircle(hsv[1]*width,(1-hsv[2])*height,context.dp(7).toFloat(),paint); paint.style = Paint.Style.FILL
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_UP) {
                    parent.requestDisallowInterceptTouchEvent(true)
                    hsv[1] = (event.x/width).coerceIn(0f,1f); hsv[2] = (1-event.y/height).coerceIn(0f,1f)
                    update(Color.HSVToColor(hsv) and 0xffffff); invalidate()
                    if (event.action == MotionEvent.ACTION_UP) performClick()
                    return true
                }
                return super.onTouchEvent(event)
            }
            override fun performClick(): Boolean { super.performClick(); return true }
        }.apply { contentDescription = "自定义饱和度和亮度，亦可输入十六进制颜色" }
        squareView = square
        column.addView(square,LinearLayout.LayoutParams(-1,context.dp(160)))
        column.addView(SeekBar(context).apply {
            hueBar = this
            max = 359; progress = hsv[0].toInt(); contentDescription = "自定义色相"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) { hsv[0]=progress.toFloat(); update(Color.HSVToColor(hsv) and 0xffffff); square.invalidate() } }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        })
        column.addView(hex)
        hex.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!updating) {
                    val text = s.toString().removePrefix("#")
                    if (text.matches(Regex("[0-9a-fA-F]{6}"))) {
                        draft = text.toInt(16); Color.colorToHSV(draft!! or (0xff shl 24),hsv); hueBar?.progress = hsv[0].toInt()
                        val dark = context.resources.configuration.uiMode and 48 == 32
                        preview.setBackgroundColor(CourseColors.background(draft!!,dark)); preview.text = "手动颜色 · $title"; square.invalidate(); hex.error = null
                    } else hex.error = "请输入六位十六进制颜色"
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        update(manual)
        val dialog = AlertDialog.Builder(context).setTitle("课程颜色")
            .setView(ScrollView(context).apply { addView(column) })
            .setNeutralButton("使用自动颜色") { _, _ -> accept(null) }
            .setNegativeButton("取消",null).setPositiveButton("使用此颜色",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (hex.error == null) { accept(draft); dialog.dismiss() }
        } }
        return dialog
    }
}
