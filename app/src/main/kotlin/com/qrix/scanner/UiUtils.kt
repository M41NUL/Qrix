package com.qrix.scanner

import android.app.Activity
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Bars {
    /** Dark icons on light backgrounds and the other way round. */
    fun apply(a: Activity, light: Boolean? = null) {
        val l = light ?: (ColorUtils.calculateLuminance(ContextCompat.getColor(a, R.color.qx_bg)) > 0.5)
        val c = WindowCompat.getInsetsController(a.window, a.window.decorView)
        c.isAppearanceLightStatusBars = l
        c.isAppearanceLightNavigationBars = l
    }
}

object RecordBinder {
    private val fmt = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())

    fun bind(v: View, r: Record, onClick: () -> Unit) {
        val type = CodeUtils.typeOf(r.text, r.format)
        v.findViewById<TextView>(R.id.recTitle).text = CodeUtils.titleOf(r.text, type)
        v.findViewById<TextView>(R.id.recSub).text =
            "$type • ${r.format} • ${fmt.format(Date(r.time))}"
        v.findViewById<ImageView>(R.id.recIcon).setImageResource(CodeUtils.iconFor(type))
        v.findViewById<View>(R.id.recFav).visibility = if (r.fav) View.VISIBLE else View.GONE
        v.setOnClickListener { onClick() }
        Fx.press(v, 0.98f)
    }

    fun open(c: Context, r: Record) =
        c.startActivity(ResultActivity.intent(c, r.text, r.format, r.id, r.kind, r.time))
}

class RecordAdapter(
    private val onClick: (Record) -> Unit,
    private val onSelectionChanged: () -> Unit = {}
) : RecyclerView.Adapter<RecordAdapter.VH>() {

    private var items: MutableList<Record> = mutableListOf()
    private val selected = LinkedHashSet<Long>()

    var selecting = false
        private set

    class VH(v: View) : RecyclerView.ViewHolder(v)

    val selectedCount get() = selected.size
    val totalCount get() = items.size

    fun submit(list: List<Record>) {
        items = list.toMutableList()
        selected.retainAll(items.map { it.id }.toSet())
        if (selected.isEmpty()) selecting = false
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun selectedRecords(): List<Record> = items.filter { it.id in selected }

    fun startSelecting(id: Long) {
        selecting = true
        selected.add(id)
        notifyItemRangeChanged(0, items.size, SEL)
        onSelectionChanged()
    }

    private fun toggle(id: Long) {
        if (!selected.remove(id)) selected.add(id)
        if (selected.isEmpty()) selecting = false
        notifyItemRangeChanged(0, items.size, SEL)
        onSelectionChanged()
    }

    fun selectAll() {
        if (selected.size == items.size) selected.clear() else selected.addAll(items.map { it.id })
        if (selected.isEmpty()) selecting = false
        notifyItemRangeChanged(0, items.size, SEL)
        onSelectionChanged()
    }

    fun clearSelection() {
        if (!selecting) return
        selecting = false
        selected.clear()
        notifyItemRangeChanged(0, items.size, SEL)
        onSelectionChanged()
    }

    fun removeAt(pos: Int): Record {
        val r = items.removeAt(pos)
        selected.remove(r.id)
        notifyItemRemoved(pos)
        return r
    }

    fun removeSelected(): List<Record> {
        val gone = selectedRecords()
        for (i in items.indices.reversed()) {
            if (items[i].id in selected) {
                items.removeAt(i)
                notifyItemRemoved(i)
            }
        }
        selected.clear()
        selecting = false
        notifyItemRangeChanged(0, items.size, SEL)
        onSelectionChanged()
        return gone
    }

    fun isEmpty() = items.isEmpty()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_record, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        val v = holder.itemView
        RecordBinder.bind(v, r) { if (selecting) toggle(r.id) else onClick(r) }
        v.setOnLongClickListener {
            if (!selecting) {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                startSelecting(r.id)
            } else toggle(r.id)
            true
        }
        showSelection(v, r.id in selected, animate = false)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) return onBindViewHolder(holder, position)
        val r = items[position]
        holder.itemView.findViewById<View>(R.id.recFav).visibility = if (r.fav) View.VISIBLE else View.GONE
        showSelection(holder.itemView, r.id in selected, animate = true)
    }

    private fun showSelection(v: View, on: Boolean, animate: Boolean) {
        val check = v.findViewById<View>(R.id.recCheck)
        val icon = v.findViewById<View>(R.id.recIcon)
        val was = check.visibility == View.VISIBLE
        v.setBackgroundResource(if (on) R.drawable.bg_card_selected else R.drawable.bg_card)
        check.visibility = if (on) View.VISIBLE else View.GONE
        icon.visibility = if (on) View.INVISIBLE else View.VISIBLE
        if (animate && on && !was) Fx.pop(check)
        if (animate && !on && was) Fx.pop(icon)
    }

    private companion object {
        const val SEL = "sel"
    }
}
