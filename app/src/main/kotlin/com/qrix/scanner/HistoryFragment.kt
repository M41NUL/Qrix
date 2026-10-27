package com.qrix.scanner

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlin.math.abs
import kotlin.math.min

class HistoryFragment : Fragment(R.layout.fragment_history) {

    private lateinit var adapter: RecordAdapter
    private lateinit var tabs: MaterialButtonToggleGroup
    private lateinit var empty: View
    private lateinit var barNormal: View
    private lateinit var barSelect: View
    private lateinit var txtSel: TextView
    private var barShowsSelect = false

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = adapter.clearSelection()
    }

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        val main = requireActivity() as MainActivity
        tabs = v.findViewById(R.id.chips)
        empty = v.findViewById(R.id.empty)
        barNormal = v.findViewById(R.id.barNormal)
        barSelect = v.findViewById(R.id.barSelect)
        txtSel = v.findViewById(R.id.txtSelCount)

        val pending = main.pendingHistoryKind
        main.pendingHistoryKind = -1
        tabs.check(
            when (pending) {
                Record.SCANNED -> R.id.chipScanned
                Record.GENERATED -> R.id.chipGenerated
                else -> R.id.chipAll
            }
        )
        tabs.addOnButtonCheckedListener { _, _, checked -> if (checked) reload() }

        adapter = RecordAdapter({ RecordBinder.open(requireContext(), it) }) { onSelectionChanged() }
        val rv = v.findViewById<RecyclerView>(R.id.list)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        rv.itemAnimator?.apply { removeDuration = 220; moveDuration = 220 }

        attachSwipe(rv)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        v.findViewById<View>(R.id.btnClear).setOnClickListener { confirmClear() }
        v.findViewById<View>(R.id.btnCancelSel).setOnClickListener { adapter.clearSelection() }
        v.findViewById<View>(R.id.btnSelectAll).setOnClickListener { adapter.selectAll() }
        v.findViewById<View>(R.id.btnDeleteSel).setOnClickListener { deleteSelected() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    // ------------------------------------------------------------- selection

    private fun onSelectionChanged() {
        if (!isAdded || view == null) return
        val sel = adapter.selecting
        backCallback.isEnabled = sel
        txtSel.text = "${adapter.selectedCount} selected"
        if (sel != barShowsSelect) {
            barShowsSelect = sel
            crossfade(if (sel) barNormal else barSelect, if (sel) barSelect else barNormal)
        }
    }

    private fun crossfade(out: View, into: View) {
        into.animate().cancel()
        out.animate().cancel()
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
            out.isVisible = false
            into.isVisible = true
            into.alpha = 1f
            return
        }
        into.alpha = 0f
        into.isVisible = true
        into.animate().alpha(1f).setDuration(180).start()
        out.animate().alpha(0f).setDuration(120).withEndAction {
            out.isVisible = false
            out.alpha = 1f
        }.start()
    }

    private fun deleteSelected() {
        val ctx = requireContext()
        val gone = adapter.removeSelected()
        if (gone.isEmpty()) return
        gone.forEach { HistoryStore.delete(ctx, it.id) }
        empty.isVisible = adapter.isEmpty()
        Snackbar.make(requireView(), "${gone.size} deleted", Snackbar.LENGTH_LONG)
            .setAction("Undo") {
                gone.forEach { HistoryStore.restore(ctx, it) }
                reload()
            }.show()
    }

    // ----------------------------------------------------------------- swipe

    private fun attachSwipe(rv: RecyclerView) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val red = 0xFFEF4444.toInt()
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red }
        val icon = ContextCompat.getDrawable(ctx, R.drawable.ic_delete)!!.mutate().apply {
            setTint(0xFFFFFFFF.toInt())
        }
        val rect = RectF()

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                r: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder
            ) = false

            override fun getSwipeDirs(r: RecyclerView, vh: RecyclerView.ViewHolder) =
                if (adapter.selecting) 0 else super.getSwipeDirs(r, vh)

            override fun getSwipeThreshold(vh: RecyclerView.ViewHolder) = 0.4f

            override fun onChildDraw(
                c: Canvas, r: RecyclerView, vh: RecyclerView.ViewHolder,
                dX: Float, dY: Float, state: Int, active: Boolean
            ) {
                if (state == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
                    val v = vh.itemView
                    val p = (abs(dX) / v.width).coerceIn(0f, 1f)
                    val top = v.top.toFloat()
                    val bottom = v.bottom.toFloat()
                    if (dX > 0) rect.set(v.left.toFloat(), top, v.left + dX, bottom)
                    else rect.set(v.right + dX, top, v.right.toFloat(), bottom)

                    bgPaint.alpha = (255 * min(1f, p * 4f)).toInt()
                    c.drawRoundRect(rect, 16 * dp, 16 * dp, bgPaint)

                    val size = 24 * dp
                    val gap = 22 * dp
                    if (abs(dX) > gap + size * 0.6f) {
                        // The icon grows as you pull and gives a little pop once it will delete.
                        val armed = p >= 0.4f
                        val scale = (0.6f + p * 1.2f).coerceAtMost(if (armed) 1.25f else 1f)
                        val cx = if (dX > 0) v.left + gap + size / 2 else v.right - gap - size / 2
                        val cy = (top + bottom) / 2f
                        val half = size / 2f
                        c.save()
                        c.scale(scale, scale, cx, cy)
                        icon.alpha = (255 * min(1f, p * 3f)).toInt()
                        icon.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
                        icon.draw(c)
                        c.restore()
                    }
                    v.alpha = 1f - p * 0.55f
                }
                super.onChildDraw(c, r, vh, dX, dY, state, active)
            }

            override fun clearView(r: RecyclerView, vh: RecyclerView.ViewHolder) {
                vh.itemView.alpha = 1f
                super.clearView(r, vh)
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val rec = adapter.removeAt(pos)
                HistoryStore.delete(ctx, rec.id)
                empty.isVisible = adapter.isEmpty()
                Snackbar.make(requireView(), "Deleted", Snackbar.LENGTH_LONG)
                    .setAction("Undo") {
                        HistoryStore.restore(ctx, rec)
                        reload()
                    }.show()
            }
        }).attachToRecyclerView(rv)
    }

    // ------------------------------------------------------------------ list

    private fun currentKind(): Int? = when (tabs.checkedButtonId) {
        R.id.chipScanned -> Record.SCANNED
        R.id.chipGenerated -> Record.GENERATED
        else -> null
    }

    private fun favoritesOnly() = tabs.checkedButtonId == R.id.chipFav

    private fun currentList(): List<Record> =
        if (favoritesOnly()) HistoryStore.favorites(requireContext())
        else HistoryStore.list(requireContext(), currentKind())

    private fun reload() {
        adapter.clearSelection()
        val list = currentList()
        adapter.submit(list)
        empty.isVisible = list.isEmpty()
    }

    private fun confirmClear() {
        val kind = currentKind()
        val favs = favoritesOnly()
        if (currentList().isEmpty()) return
        val what = when {
            favs -> "all favorites"
            kind == Record.SCANNED -> "all scanned codes"
            kind == Record.GENERATED -> "all generated codes"
            else -> "your entire history"
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Clear history?")
            .setMessage("This will delete $what. This can't be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                if (favs) HistoryStore.clearFavorites(requireContext())
                else HistoryStore.clear(requireContext(), kind)
                reload()
            }
            .show()
    }
}
