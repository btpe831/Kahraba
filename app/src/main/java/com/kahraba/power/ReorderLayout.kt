package com.kahraba.power

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs

/**
 * A vertical list of cards that can be rearranged in place: press and hold a card, then drag it
 * up or down. The other cards slide out of the way, and the screen scrolls when the card is
 * dragged near the top or bottom. Taps and scrolling work as usual.
 */
class ReorderLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** Called with the children in their new order after a card is dropped. */
    var onReordered: ((List<View>) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastRawY = 0f
    private var pressed: View? = null
    private var dragged: View? = null
    private var grabOffset = 0f
    private var orderAtStart = emptyList<View>()

    // A swap waits for the next layout pass before the next one, and the card it passed
    // slides into its new place from where it was.
    private var waitingForLayout = false
    private var passed: View? = null
    private var passedTop = 0

    private val startDrag = Runnable { begin() }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (dragged != null) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    lastRawY = e.rawY
                    follow()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drop()
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastRawY = e.rawY
                pressed = cardAt(e.y)
                if (pressed != null) postDelayed(startDrag, ViewConfiguration.getLongPressTimeout().toLong())
                super.dispatchTouchEvent(e)
                // Keep receiving this gesture even if no card handles taps, so a hold can start a drag.
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                lastRawY = e.rawY
                if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) removeCallbacks(startDrag)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                removeCallbacks(startDrag)
        }
        return super.dispatchTouchEvent(e)
    }

    private fun cardAt(y: Float): View? =
        (0 until childCount).map { getChildAt(it) }
            .firstOrNull { it.visibility == VISIBLE && y >= it.top && y < it.bottom }

    private fun begin() {
        val card = pressed ?: return
        if (card.visibility != VISIBLE) return
        dragged = card
        orderAtStart = children()
        // From now on the screen doesn't scroll by itself, and the card under the finger
        // doesn't treat this as a tap.
        parent?.requestDisallowInterceptTouchEvent(true)
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        super.dispatchTouchEvent(cancel)
        cancel.recycle()

        grabOffset = downY - card.top
        card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        card.elevation = 12 * dp
        card.animate().scaleX(1.03f).scaleY(1.03f).alpha(0.94f).setDuration(120).start()
        follow()
        postOnAnimation(autoScroll)
    }

    /** Moves the card under the finger, and swaps it with a neighbour once it passes its middle. */
    private fun follow() {
        val card = dragged ?: return
        val wantTop = fingerY() - grabOffset
        card.translationY = wantTop - card.top
        if (waitingForLayout) return
        val middle = wantTop + card.height / 2f
        val i = indexOfChild(card)
        val below = neighbour(i, +1)
        val above = neighbour(i, -1)
        when {
            below != null && middle > below.top + below.height / 2f -> swap(card, below)
            above != null && middle < above.top + above.height / 2f -> swap(card, above)
        }
    }

    private fun swap(card: View, other: View) {
        passed = other
        passedTop = other.top
        waitingForLayout = true
        val target = indexOfChild(other)
        removeView(card)
        addView(card, target)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (!waitingForLayout) return
        waitingForLayout = false
        passed?.let {
            it.translationY = (passedTop - it.top).toFloat()
            it.animate().translationY(0f).setDuration(150).start()
        }
        passed = null
        // Not during layout: a further swap would change the layout again.
        post { follow() }
    }

    private fun drop() {
        val card = dragged ?: return
        dragged = null
        removeCallbacks(autoScroll)
        card.animate().translationY(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(150)
            .withEndAction { card.elevation = 0f }.start()
        val order = children()
        if (order != orderAtStart) onReordered?.invoke(order)
    }

    /** Scrolls the screen while the card is held near its top or bottom edge. */
    private val autoScroll = object : Runnable {
        override fun run() {
            if (dragged == null) return
            val scroll = scrollParent()
            if (scroll != null) {
                val loc = IntArray(2)
                scroll.getLocationOnScreen(loc)
                val top = loc[1] + 90 * dp
                // The bottom bar covers the bottom of the screen.
                val bottom = loc[1] + scroll.height - 150 * dp
                val maxStep = 16 * dp
                val step = when {
                    lastRawY < top -> -maxStep * ((top - lastRawY) / (90 * dp)).coerceIn(0.2f, 1f)
                    lastRawY > bottom -> maxStep * ((lastRawY - bottom) / (90 * dp)).coerceIn(0.2f, 1f)
                    else -> 0f
                }
                if (step != 0f) {
                    scroll.scrollBy(0, step.toInt())
                    follow()
                }
            }
            postOnAnimation(this)
        }
    }

    private fun neighbour(index: Int, direction: Int): View? {
        var i = index + direction
        while (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v.visibility == VISIBLE) return v
            i += direction
        }
        return null
    }

    private fun fingerY(): Float {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return lastRawY - loc[1]
    }

    private fun scrollParent(): ScrollView? {
        var p = parent
        while (p != null) {
            if (p is ScrollView) return p
            p = p.parent
        }
        return null
    }

    private fun children() = (0 until childCount).map { getChildAt(it) }
}
