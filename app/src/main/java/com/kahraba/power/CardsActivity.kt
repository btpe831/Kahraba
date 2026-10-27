package com.kahraba.power

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch

/** Settings > Cards: show or hide each main-screen card, and drag the lower ones into any order. */
class CardsActivity : ThemedActivity() {

    /** A row in the list: a section label, or a card (movable or fixed at the top). */
    private sealed class Row {
        data class Label(val text: String) : Row()
        data class Card(val key: String, val name: String, val movable: Boolean) : Row()
    }

    private val rows = mutableListOf<Row>()
    private lateinit var adapter: Adapter
    private lateinit var touch: ItemTouchHelper
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cards)
        setupHeader("Arrange cards")
        buildRows()

        val list = findViewById<RecyclerView>(R.id.cardList)
        list.layoutManager = LinearLayoutManager(this)
        adapter = Adapter()
        list.adapter = adapter
        touch = ItemTouchHelper(DragCallback())
        touch.attachToRecyclerView(list)

        findViewById<View>(R.id.resetOrder).setOnClickListener {
            Prefs.setCardOrder(this, null)
            buildRows()
            adapter.notifyItemRangeChanged(0, rows.size)
        }
    }

    private fun buildRows() {
        val names = Prefs.CARDS.toMap()
        rows.clear()
        rows += Row.Label("Always at the top")
        for (key in CardOrder.FIXED) rows += Row.Card(key, names[key] ?: key, movable = false)
        rows += Row.Label("Below the Check button")
        for (key in Prefs.cardOrder(this)) rows += Row.Card(key, names[key] ?: key, movable = true)
    }

    private fun saveOrder() {
        Prefs.setCardOrder(this, rows.filterIsInstance<Row.Card>().filter { it.movable }.map { it.key })
    }

    // ---- List ----

    private inner class Holder(val view: View) : RecyclerView.ViewHolder(view)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Label) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(if (viewType == 0) labelView() else cardView())

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: Holder, position: Int) {
            when (val row = rows[position]) {
                is Row.Label -> (holder.view as TextView).text = row.text.uppercase()
                is Row.Card -> {
                    val v = holder.view as LinearLayout
                    val handle = v.getChildAt(0) as TextView
                    val name = v.getChildAt(1) as TextView
                    val switch = v.getChildAt(2) as MaterialSwitch
                    handle.visibility = if (row.movable) View.VISIBLE else View.GONE
                    handle.setOnTouchListener { _, e ->
                        if (e.actionMasked == MotionEvent.ACTION_DOWN) touch.startDrag(holder)
                        false
                    }
                    name.text = row.name
                    switch.setOnCheckedChangeListener(null)
                    switch.isChecked = Prefs.cardOn(this@CardsActivity, row.key)
                    switch.setOnCheckedChangeListener { _, on ->
                        Prefs.sp(this@CardsActivity).edit().putBoolean(row.key, on).apply()
                    }
                }
            }
        }
    }

    private fun labelView() = TextView(this).apply {
        textSize = 13f
        letterSpacing = 0.14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color(R.attr.appAccent))
        setPadding(px(4), px(22), 0, px(8))
        layoutParams = RecyclerView.LayoutParams(-1, -2)
    }

    private fun cardView() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(8), px(6), px(14), px(6))
        minimumHeight = px(60)
        background = GradientDrawable().apply {
            cornerRadius = 20 * dp
            setColor(color(R.attr.appSurface))
            setStroke(px(1), color(R.attr.appBorder))
        }
        layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(0, px(4), 0, px(4)) }
        addView(TextView(this@CardsActivity).apply {
            text = "≡"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(color(R.attr.appMuted))
            contentDescription = "Drag to move"
        }, LinearLayout.LayoutParams(px(44), px(48)))
        addView(TextView(this@CardsActivity).apply {
            textSize = 17f
            setTextColor(color(R.attr.appText))
            setPadding(px(6), 0, px(8), 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(MaterialSwitch(this@CardsActivity))
    }

    // ---- Dragging ----

    private inner class DragCallback : ItemTouchHelper.Callback() {
        private fun movable(position: Int) = (rows.getOrNull(position) as? Row.Card)?.movable == true

        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
            if (movable(vh.bindingAdapterPosition)) makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) else 0

        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (!movable(from) || !movable(to)) return false
            rows.add(to, rows.removeAt(from))
            adapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

        override fun isLongPressDragEnabled() = true

        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(vh, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                vh?.itemView?.apply { elevation = 8 * dp; alpha = 0.95f }
            }
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            vh.itemView.elevation = 0f
            vh.itemView.alpha = 1f
            saveOrder()
        }
    }

    private fun color(attr: Int) = TypedValue().also { theme.resolveAttribute(attr, it, true) }.data
    private fun px(v: Int) = (v * dp).toInt()
}
