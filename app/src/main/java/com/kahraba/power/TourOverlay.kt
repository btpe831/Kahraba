package com.kahraba.power

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import kotlin.math.abs

/**
 * A guided tour over a screen: dims everything except one highlighted view, with a short tip
 * beside it and Next / Skip. Between steps the screen glides to the next view and the highlight
 * slides and resizes to follow it. Blocks other taps while it's showing.
 */
class TourOverlay(
    private val activity: Activity,
    private val steps: List<Step>,
    /** Scrolled to bring each step's view into place; null if the screen doesn't scroll. */
    private val scroll: ScrollView?,
    private val onDone: () -> Unit,
) : FrameLayout(activity) {

    /** One stop: what to highlight (null highlights nothing) and where to scroll first (null: stay). */
    class Step(val title: String, val text: String, val target: () -> View?, val scrollY: (() -> Int)? = null)

    private val dp = resources.displayMetrics.density
    private var index = -1
    private val hole = RectF()
    private val from = RectF()
    private val live = RectF()
    private val path = Path()
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB8000000.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * dp
        color = color(R.attr.appAccent)
    }
    private val bubble = LinearLayout(activity)
    private val counter = TextView(activity)
    private val title = TextView(activity)
    private val text = TextView(activity)
    private val next = MaterialButton(activity)
    private var moving: ValueAnimator? = null
    private val ease = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    init {
        setWillNotDraw(false)
        isClickable = true // swallow taps meant for the screen underneath
        isFocusable = true

        bubble.orientation = LinearLayout.VERTICAL
        bubble.setPadding(px(20), px(16), px(20), px(12))
        bubble.background = GradientDrawable().apply {
            cornerRadius = 22 * dp
            setColor(color(R.attr.appSurface))
            setStroke(px(1), color(R.attr.appBorder))
        }
        bubble.elevation = 6 * dp
        counter.textSize = 12f
        counter.letterSpacing = 0.1f
        counter.setTextColor(color(R.attr.appMuted))
        title.textSize = 20f
        title.setTypeface(title.typeface, Typeface.BOLD)
        title.setTextColor(color(R.attr.appText))
        title.setPadding(0, px(4), 0, 0)
        text.textSize = 15f
        text.setLineSpacing(3 * dp, 1f)
        text.setTextColor(color(R.attr.appText))
        text.setPadding(0, px(6), 0, 0)
        bubble.addView(counter)
        bubble.addView(title)
        bubble.addView(text)

        val buttons = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        buttons.addView(TextView(activity).apply {
            this.text = "Skip"
            textSize = 15f
            setTextColor(color(R.attr.appMuted))
            setPadding(0, px(12), px(12), px(12))
            setOnClickListener { finish() }
        })
        buttons.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        next.cornerRadius = px(24)
        next.setOnClickListener {
            if (moving != null) return@setOnClickListener
            if (index < steps.size - 1) show(index + 1) else finish()
        }
        buttons.addView(next)
        bubble.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) })

        addView(bubble, LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(px(16), 0, px(16), 0) })
        bubble.alpha = 0f
    }

    fun start(parent: ViewGroup) {
        parent.addView(this, ViewGroup.LayoutParams(-1, -1))
        alpha = 0f
        animate().alpha(1f).setDuration(200).start()
        // Once laid out, so the highlight can start from the whole screen.
        post { show(0) }
    }

    fun finish() {
        moving?.cancel()
        animate().alpha(0f).setDuration(180).withEndAction {
            (parent as? ViewGroup)?.removeView(this)
        }.start()
        onDone()
    }

    private fun show(i: Int) {
        val first = index < 0
        index = i
        // Fade the tip out, then glide to the next step and bring the tip back.
        bubble.animate().alpha(0f).setDuration(if (first) 0L else 140L).withEndAction { glideTo(i) }.start()
    }

    private fun glideTo(i: Int) {
        val s = steps[i]
        counter.text = "${i + 1} OF ${steps.size}"
        title.text = s.title
        text.text = s.text
        next.text = if (i == steps.size - 1) "Done" else "Next"

        // From the current highlight (or the whole screen at the start).
        if (hole.isEmpty) from.set(0f, 0f, width.toFloat(), height.toFloat()) else from.set(hole)
        val startY = scroll?.scrollY ?: 0
        val endY = s.scrollY?.invoke()?.let { y -> scroll?.let { y.coerceIn(0, maxScroll(it)) } } ?: startY
        val distance = abs(endY - startY)

        moving?.cancel()
        moving = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (380 + (distance / dp / 3).toLong()).coerceAtMost(750)
            interpolator = ease
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                if (scroll != null && endY != startY) scroll.scrollTo(0, (startY + (endY - startY) * f).toInt())
                // Follow the target as it scrolls, blending from where the highlight was.
                if (measure(s.target(), live)) {
                    hole.set(
                        lerp(from.left, live.left, f), lerp(from.top, live.top, f),
                        lerp(from.right, live.right, f), lerp(from.bottom, live.bottom, f),
                    )
                } else {
                    hole.setEmpty()
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (moving !== animation) return
                    moving = null
                    if (measure(s.target(), live)) hole.set(live) else hole.setEmpty()
                    invalidate()
                    placeBubble()
                    bubble.animate().alpha(1f).setDuration(180).start()
                }
            })
            start()
        }
    }

    /** Where [target] is on this overlay, with some room around it; false if it's not on screen. */
    private fun measure(target: View?, out: RectF): Boolean {
        if (target == null || !target.isShown || target.width == 0) return false
        val t = IntArray(2)
        val me = IntArray(2)
        target.getLocationInWindow(t)
        getLocationInWindow(me)
        val pad = 8 * dp
        out.set(
            t[0] - me[0] - pad, t[1] - me[1] - pad,
            t[0] - me[0] + target.width + pad, t[1] - me[1] + target.height + pad,
        )
        return out.bottom > 0 && out.top < height
    }

    /** The tip goes below the highlight if it fits, otherwise above, and always fully on screen. */
    private fun placeBubble() {
        bubble.measure(
            MeasureSpec.makeMeasureSpec(width - px(32), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val h = bubble.measuredHeight
        val gap = 14 * dp
        val y = when {
            hole.isEmpty -> (height - h) / 2f
            hole.bottom + gap + h < height - gap -> hole.bottom + gap
            else -> hole.top - gap - h
        }
        bubble.translationY = y.coerceIn(gap, (height - h - gap).coerceAtLeast(gap))
    }

    override fun onDraw(canvas: Canvas) {
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        val r = 22 * dp
        if (!hole.isEmpty) path.addRoundRect(hole, r, r, Path.Direction.CW)
        canvas.drawPath(path, dim)
        if (!hole.isEmpty) canvas.drawRoundRect(hole, r, r, ring)
    }

    override fun onDetachedFromWindow() {
        moving?.cancel()
        super.onDetachedFromWindow()
    }

    private fun maxScroll(s: ScrollView): Int = ((s.getChildAt(0)?.height ?: 0) - s.height).coerceAtLeast(0)
    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
    private fun color(attr: Int) = TypedValue().also { activity.theme.resolveAttribute(attr, it, true) }.data
    private fun px(v: Int) = (v * dp).toInt()
}
