package com.solace.dshphone.capsule

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * The floating capsule: a small round bubble pinned to the screen edge that the
 * user can drag. Tapping it opens the app and hands "what's on my screen right
 * now" into the chat, e.g. 「这句怎么回」.
 */
class FloatingCapsule(private val context: Context) {

    private var added = false
    private var view: View? = null
    var onTap: (() -> Unit)? = null

    @SuppressLint("ClickableViewAccessibility")
    fun show(initialText: String = "问") {
        if (added) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            dp(52), dp(52),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(320)
        }
        val bubble = TextView(context).apply {
            text = initialText
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setBackgroundResource(android.R.drawable.presence_online)
            var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false; true }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                        if (kotlin.math.abs(dx) > 6 || kotlin.math.abs(dy) > 6) moved = true
                        params.x = startX + dx; params.y = startY + dy
                        wm.updateViewLayout(this, params)
                        true
                    }
                    MotionEvent.ACTION_UP -> { if (!moved) onTap?.invoke(); true }
                    else -> false
                }
            }
        }
        view = bubble
        runCatching { wm.addView(bubble, params); added = true }
    }

    fun dismiss() {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        added = false
    }

    fun isShowing(): Boolean = added

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
