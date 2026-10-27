package com.qrix.scanner

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Small, cheap view animations (only alpha / translation / scale, which run on the
 * GPU). Everything is skipped when the user turned animations off in system settings.
 */
object Fx {

    private fun enabled() = ValueAnimator.areAnimatorsEnabled()

    /** Slight shrink while a view is pressed. Does not consume the touch, so clicks still work. */
    @SuppressLint("ClickableViewAccessibility")
    fun press(v: View, scale: Float = 0.96f) {
        v.setOnTouchListener { view, e ->
            if (enabled()) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        view.animate().scaleX(scale).scaleY(scale).setDuration(80).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
            }
            false
        }
    }

    /** Fade + rise into place. [delay] lets neighbouring views appear one after another. */
    fun enter(v: View, delay: Long = 0L, rise: Float = 14f) {
        if (!enabled()) return
        val dp = v.resources.displayMetrics.density
        v.alpha = 0f
        v.translationY = rise * dp
        v.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delay)
            .setDuration(240)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** Small bounce, used when something is switched on (e.g. the favourite icon). */
    fun pop(v: View) {
        if (!enabled()) return
        v.animate().cancel()
        v.scaleX = 0.7f
        v.scaleY = 0.7f
        v.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220)
            .setInterpolator(OvershootInterpolator(3f))
            .start()
    }
}
