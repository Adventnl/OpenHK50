package org.openhk50.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView

@SuppressLint("ViewConstructor")
internal class PageViewport(
    context: Context, private val onGestureEnd: () -> Unit
) : ScrollView(context) {
    private val center = FrameLayout(context)
    private var restoreY: Int? = null
    val position get() = restoreY ?: scrollY
    var touchInProgress = false
        private set

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        addView(center, LayoutParams(-1, -2))
    }

    fun show(page: View, width: Int, y: Int) {
        restoreY = y
        center.removeAllViews()
        center.addView(page, FrameLayout.LayoutParams(width, -2,
            android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Restore after ScrollView lays out the new content and knows its scroll range.
        restoreY?.let { scrollTo(0, it) }
        restoreY = null
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) touchInProgress = true
        val handled = super.dispatchTouchEvent(event)
        if (!handled || event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (touchInProgress) {
                touchInProgress = false
                onGestureEnd()
            }
        }
        return handled
    }
}
