package com.dormpanel.app.schedule.testing

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

/** Must be called on the UI thread. Goes through actual ViewGroup dispatch, not a gesture oracle. */
object SwipeEvents {
    class Stroke(private val view: View, private val x: Float = view.width*.5f, private val y: Float = view.height*.4f) {
        private val start = SystemClock.uptimeMillis()
        private var elapsed = 0L
        private var dx = 0f
        private var dy = 0f
        init { send(MotionEvent.ACTION_DOWN) }
        private fun send(action: Int) {
            val event=MotionEvent.obtain(start,start+elapsed,action,x+dx,y+dy,0)
            view.dispatchTouchEvent(event); event.recycle()
        }
        fun move(x: Float, y: Float = 0f) { dx=x; dy=y; elapsed+=200; send(MotionEvent.ACTION_MOVE) }
        fun end(cancel: Boolean = false) { elapsed+=300; send(if(cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP) }
    }
    fun nativeDrag(from: Float, to: Float) = androidx.test.espresso.action.GeneralSwipeAction(
        androidx.test.espresso.action.Swipe.SLOW,
        { v -> val p=IntArray(2); v.getLocationOnScreen(p); floatArrayOf(p[0]+v.width*from,p[1]+v.height*.55f) },
        { v -> val p=IntArray(2); v.getLocationOnScreen(p); floatArrayOf(p[0]+v.width*to,p[1]+v.height*.55f) },
        androidx.test.espresso.action.Press.FINGER)
    fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()

    fun drag(view: View, dx: Float, dy: Float = 0f, duration: Long = 800, cancel: Boolean = false,
        during: (() -> Unit)? = null, x: Float = view.width * .5f, y: Float = view.height * .4f) {
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, part: Float) {
            val e = MotionEvent.obtain(start,start+(duration*part).toLong(),action,x+dx*part,y+dy*part,0)
            view.dispatchTouchEvent(e); e.recycle()
        }
        send(MotionEvent.ACTION_DOWN,0f)
        for (i in 1..8) send(MotionEvent.ACTION_MOVE,i/8f)
        during?.invoke()
        send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP,1f)
    }

    fun multiTouch(view: View) {
        val start = SystemClock.uptimeMillis()
        val x = view.width*.5f; val y = view.height*.4f
        val down = MotionEvent.obtain(start,start,MotionEvent.ACTION_DOWN,x,y,0)
        view.dispatchTouchEvent(down); down.recycle()
        val props = Array(2) { i -> MotionEvent.PointerProperties().apply { id=i; toolType=MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(2) { i -> MotionEvent.PointerCoords().apply { this.x=x+i*20; this.y=y; pressure=1f; size=1f } }
        val second = MotionEvent.obtain(start,start+20,MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,props,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
        view.dispatchTouchEvent(second); second.recycle()
        val move = MotionEvent.obtain(start,start+100,MotionEvent.ACTION_MOVE,x-view.width*.4f,y,0)
        view.dispatchTouchEvent(move); move.recycle()
        val up = MotionEvent.obtain(start,start+150,MotionEvent.ACTION_UP,x-view.width*.4f,y,0)
        view.dispatchTouchEvent(up); up.recycle()
    }
}
