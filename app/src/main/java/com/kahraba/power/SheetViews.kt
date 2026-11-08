package com.kahraba.power

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ScrollView
import kotlin.math.abs

/**
 * Dragging the home screen's panels with a finger (1.5.0): Today and Your power follow it, and settle
 * where they're let go (or flung). The views below only spot a vertical drag and pass it on.
 */
interface SheetDrag {
    /** A vertical drag began; false to leave it alone (e.g. while a check runs). */
    fun dragStart(): Boolean
    /** The finger is [dy] pixels below where it went down (negative: above). */
    fun dragBy(dy: Float)
    /** Let go, moving at [velocityY] pixels a second (negative: up). */
    fun dragEnd(velocityY: Float)
}

/** Tracks one drag: where it began, how fast it's going, and whether it's ours. */
private class DragTracker(ctx: Context) {
    val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    var downX = 0f
    var downY = 0f
    var dragging = false
    private var velocity: VelocityTracker? = null

    fun down(e: MotionEvent) {
        downX = e.rawX
        downY = e.rawY
        dragging = false
        velocity?.recycle()
        velocity = VelocityTracker.obtain()
        add(e)
    }

    /** Screen coordinates: the panels move under the finger, so their own coordinates would drift. */
    fun add(e: MotionEvent) {
        val c = MotionEvent.obtain(e)
        c.setLocation(e.rawX, e.rawY)
        velocity?.addMovement(c)
        c.recycle()
    }

    fun dy(e: MotionEvent) = e.rawY - downY
    fun vertical(e: MotionEvent) = abs(dy(e)) > slop && abs(dy(e)) > abs(e.rawX - downX)

    fun speed(): Float {
        val v = velocity ?: return 0f
        v.computeCurrentVelocity(1000)
        return v.yVelocity
    }

    fun done() {
        dragging = false
        velocity?.recycle()
        velocity = null
    }
}

/** Cancels what the views under the finger were doing, once the drag is ours. */
private fun cancelCopy(e: MotionEvent): MotionEvent = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }

/** The home screen: any vertical drag, wherever it starts (even on the big button), moves the panels. */
class DragFrame @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : FrameLayout(ctx, attrs) {
    var sheet: SheetDrag? = null
    private val t = DragTracker(ctx)

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> t.down(e)
            else -> t.add(e)
        }
        if (!t.dragging && e.actionMasked == MotionEvent.ACTION_MOVE && t.vertical(e) && sheet?.dragStart() == true) {
            t.dragging = true
            val c = cancelCopy(e)
            super.dispatchTouchEvent(c)
            c.recycle()
        }
        if (t.dragging) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> sheet?.dragBy(t.dy(e))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    sheet?.dragEnd(t.speed())
                    t.done()
                }
            }
            return true
        }
        return super.dispatchTouchEvent(e)
    }
}

/** Your power: scrolls as usual, but pulled down while already at the top, it follows the finger down. */
class PullScrollView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : ScrollView(ctx, attrs) {
    var sheet: SheetDrag? = null
    private val t = DragTracker(ctx)

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> t.down(e)
            else -> t.add(e)
        }
        if (!t.dragging && e.actionMasked == MotionEvent.ACTION_MOVE && scrollY == 0 && t.dy(e) > 0 &&
            t.vertical(e) && sheet?.dragStart() == true
        ) {
            t.dragging = true
            val c = cancelCopy(e)
            super.dispatchTouchEvent(c)
            c.recycle()
        }
        if (t.dragging) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> sheet?.dragBy(t.dy(e))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    sheet?.dragEnd(t.speed())
                    t.done()
                }
            }
            return true
        }
        return super.dispatchTouchEvent(e)
    }
}
