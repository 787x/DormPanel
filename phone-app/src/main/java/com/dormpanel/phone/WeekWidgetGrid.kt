package com.dormpanel.phone

import android.graphics.*
import android.text.*
import com.dormpanel.app.schedule.*
import java.time.*
import kotlin.math.*

/** Same display geometry as PhoneWeekView: real clipped instances and a common invertible axis.
 * Launcher receives small on-demand tiles, never a screen capture or a full-height bitmap.
 * At most 800*192*4 bytes per row; no bitmap is retained in the snapshot cache. */
internal class WeekWidgetGrid(val monday: LocalDate,val zone: ZoneId,val segments: List<WeekSegment>,val axis: WeekAxis,
    val colors: Map<ClassOccurrence,Int>,val today: LocalDate,val dayCount: Int) {
    val tileHeight=96
    val count get()=ceil((axis.height+16)/tileHeight).toInt()
    fun bitmap(position: Int,widthDp: Int,fontScale: Float,dark: Boolean): Bitmap {
        val width=widthDp.coerceIn(120,2000).toFloat()
        val scale=minOf(2f,800f/width)
        val image=Bitmap.createBitmap((width*scale).roundToInt(),(tileHeight*scale).roundToInt(),Bitmap.Config.ARGB_8888)
        val canvas=Canvas(image); canvas.scale(scale,scale); canvas.translate(0f,-position*tileHeight.toFloat())
        val top=position*tileHeight.toFloat(); val bottom=top+tileHeight
        val gutter=32f; val dayWidth=(width-gutter)/dayCount
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        val current=java.time.temporal.ChronoUnit.DAYS.between(monday,today).toInt()
        if(current in 0 until dayCount) { paint.color=if(dark) 0xff172735.toInt() else 0xffe8f1fa.toInt(); canvas.drawRect(gutter+current*dayWidth,top,gutter+(current+1)*dayWidth,bottom,paint) }
        paint.color=if(dark) 0x33777777 else 0x14557799
        (0..dayCount).forEach { val x=gutter+it*dayWidth; canvas.drawLine(x,top,x,bottom,paint) }
        val text=TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color=CourseColors.ink(dark); textSize=8f*fontScale.coerceAtMost(1.2f) }
        if(axis.periodLabels.isEmpty()) axis.labels.forEach { (minute,label) ->
            val y=axis.y(minute.toFloat())
            if(y+24>=top && y<bottom) {
                text.textSize=9f*fontScale.coerceAtMost(1.2f)
                canvas.drawText(label,0f,y+12,text)
                paint.color=if(dark) 0x33777777 else 0x10557799; canvas.drawLine(gutter,y,width,y,paint)
            }
        } else axis.periodLabels.forEach { period ->
            val y=axis.y(period.minute.toFloat())
            if(y+56>=top && y<bottom) {
                text.textAlign=Paint.Align.CENTER
                text.textSize=12f*fontScale.coerceAtMost(1.3f)
                canvas.drawText(period.number.toString(),16f,y+14,text)
                text.textSize=9f*fontScale.coerceAtMost(1.2f)
                canvas.drawText(period.start.toString(),16f,y+28,text)
                canvas.drawText(period.end.toString(),16f,y+40,text)
                text.textAlign=Paint.Align.LEFT
                paint.color=if(dark) 0x33777777 else 0x10557799; canvas.drawLine(gutter,y,width,y,paint)
            }
        }
        (0 until dayCount).forEach { day ->
            WeekLayout.groups(segments.filter { it.day==day }).forEach { group ->
                // Parallel lanes retain every original identity and its exact time interval.
                val lanes=group.size
                group.forEachIndexed { lane,s ->
                    val y=axis.y(s.start.toFloat()); val end=axis.y(s.end.toFloat())
                    if(end<=top || y>=bottom) return@forEachIndexed
                    val left=gutter+day*dayWidth+lane*dayWidth/lanes+1
                    val right=gutter+day*dayWidth+(lane+1)*dayWidth/lanes-1
                    val rect=RectF(left,y+1,right,max(y+2,end-1))
                    paint.color=(colors[s.item] ?: CourseColors.palette[0]) or (0xff shl 24)
                    paint.color=CourseColors.background(paint.color,dark)
                    canvas.drawRoundRect(rect,3f,3f,paint)
                    if(right-left>6) {
                        val label=s.item.entry.title.take(160)+(if(s.item.entry.location.isBlank()) "" else "\n@"+s.item.entry.location.take(100))
                        text.textSize=10f*fontScale.coerceAtMost(1.2f); text.color=CourseColors.ink(dark)
                        canvas.save(); canvas.clipRect(rect); canvas.translate(left+2,y+3)
                        StaticLayout.Builder.obtain(label,0,label.length,text,max(1,(right-left-4).toInt())).setIncludePad(false).build().draw(canvas)
                        canvas.restore()
                    }
                }
            }
        }
        return image
    }
    companion object {
        fun create(state: ScheduleState,monday: LocalDate,zone: ZoneId,colors: (ClassOccurrence)->Int,now: LocalDate,periodMode: Boolean = true,dayCount: Int = 7): WeekWidgetGrid {
            require(dayCount==5 || dayCount==7)
            val items=ScheduleProjection.week(state,monday,zone)
            require(items.size<=512) { "Widget course budget exceeded" }
            val segments=WeekLayout.segments(items,monday,zone)
            val(first,last)=WeekLayout.visibleRange(segments,dayCount)
            val profile=state.sources.mapNotNull { it.resolvedTermKey() }.distinct().mapNotNull { state.profiles[it] }.singleOrNull()
            val axes=(0 until dayCount).map { WeekAxis.periods(profile,monday.plusDays(it.toLong()),zone,first,last) }
            val axis=axes.first()?.takeIf { a -> periodMode && axes.all { it?.periodLabels==a.periodLabels } } ?: WeekAxis.time(first,last)
            return WeekWidgetGrid(monday,zone,segments,axis,items.associateWith { colors(it) },now,dayCount)
        }
    }
}
