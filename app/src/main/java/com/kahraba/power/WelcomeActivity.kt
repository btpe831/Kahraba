package com.kahraba.power

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton

/** First launch: four short screens on what the app does. The main screen's tour follows. */
class WelcomeActivity : ThemedActivity() {

    private class Slide(val icon: Int, val title: String, val text: String)

    private val slides = listOf(
        Slide(R.drawable.ic_bolt, "Kahraba or moteur?",
            "This app tells you which one you're on, using your phone's light sensor and a halogen bulb."),
        Slide(R.drawable.ic_moteur, "Check in under a minute",
            "Tap Check now and lay the phone face-up under a halogen light. Don't touch it until it beeps:\n1 long beep = moteur · 4 short = kahraba"),
        Slide(R.drawable.ic_place, "Teach your room",
            "A wobble in the light proves moteur. To say kahraba for sure, do one check on each in the same spot, and confirm them."),
        Slide(R.drawable.ic_lock, "Halogen bulbs only",
            "LED bulbs, solar inverters and UPS smooth the power and hide the difference.\nNothing you do here leaves your phone."),
    )

    private lateinit var pager: ViewPager2
    private lateinit var dots: LinearLayout
    private lateinit var next: MaterialButton
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)
        pager = findViewById(R.id.welcomePager)
        dots = findViewById(R.id.welcomeDots)
        next = findViewById(R.id.welcomeNext)

        pager.adapter = SlideAdapter()
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = render(position)
        })
        next.setOnClickListener {
            if (pager.currentItem < slides.size - 1) pager.currentItem += 1 else finish()
        }
        findViewById<View>(R.id.welcomeSkip).setOnClickListener { finish() }
        render(0)
    }

    /** Done (Get started, Skip or Back): the main screen shows its tour next. */
    override fun finish() {
        Tour.setWelcomeSeen(this)
        super.finish()
    }

    private fun render(position: Int) {
        next.text = if (position == slides.size - 1) "Get started" else "Next"
        dots.removeAllViews()
        for (i in slides.indices) {
            val on = i == position
            dots.addView(View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 4 * dp
                    setColor(color(if (on) R.attr.appAccent else R.attr.appOutline))
                }
            }, LinearLayout.LayoutParams(px(if (on) 22 else 8), px(8)).apply { marginStart = px(4); marginEnd = px(4) })
        }
    }

    private inner class SlideHolder(view: View) : RecyclerView.ViewHolder(view)

    private inner class SlideAdapter : RecyclerView.Adapter<SlideHolder>() {
        override fun getItemCount() = slides.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = SlideHolder(slideView())

        override fun onBindViewHolder(holder: SlideHolder, position: Int) {
            val s = slides[position]
            val root = holder.itemView as LinearLayout
            ((root.getChildAt(0) as FrameLayout).getChildAt(0) as ImageView).setImageResource(s.icon)
            (root.getChildAt(1) as TextView).text = s.title
            (root.getChildAt(2) as TextView).text = s.text
        }
    }

    private fun slideView() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(px(12), 0, px(12), px(24))
        layoutParams = ViewGroup.LayoutParams(-1, -1)
        // Icon in a soft circle.
        addView(FrameLayout(this@WelcomeActivity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color(R.attr.appSurfaceAlt))
                setStroke(px(1), color(R.attr.appBorder))
            }
            addView(ImageView(this@WelcomeActivity).apply {
                setColorFilter(color(R.attr.appAccent))
            }, FrameLayout.LayoutParams(px(60), px(60), Gravity.CENTER))
        }, LinearLayout.LayoutParams(px(132), px(132)))
        addView(TextView(this@WelcomeActivity).apply {
            textSize = 28f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.attr.appText))
            setPadding(0, px(32), 0, 0)
        })
        addView(TextView(this@WelcomeActivity).apply {
            textSize = 17f
            gravity = Gravity.CENTER
            setLineSpacing(4 * dp, 1f)
            setTextColor(color(R.attr.appMuted))
            setPadding(0, px(14), 0, 0)
        })
    }

    private fun color(attr: Int) = TypedValue().also { theme.resolveAttribute(attr, it, true) }.data
    private fun px(v: Int) = (v * dp).toInt()
}
