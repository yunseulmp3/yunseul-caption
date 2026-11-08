package com.yunseul.caption

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 영상 위에 떠 있는 자막창.
 * - 끌어서 위치 이동 (위치 기억)
 * - 두 번 탭: 일본어 원문 줄 보이기/숨기기
 * - 8초간 새 자막 없으면 자동으로 사라짐
 */
@SuppressLint("ClickableViewAccessibility")
class Overlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val prefs = Prefs.of(ctx)
    private val main = Handler(Looper.getMainLooper())
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    private val ja = TextView(ctx).apply {
        setTextColor(0xD9FFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setShadowLayer(4f, 0f, 1f, 0xFF000000.toInt())
        gravity = Gravity.CENTER
    }
    private val ko = TextView(ctx).apply {
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        setShadowLayer(4f, 0f, 1f, 0xFF000000.toInt())
        paint.isFakeBoldText = true
        gravity = Gravity.CENTER
    }
    private val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val p = dp(10f).toInt()
        setPadding(p + p / 2, p, p + p / 2, p)
        background = GradientDrawable().apply {
            cornerRadius = dp(14f)
            setColor(0xB3000000.toInt())
        }
        addView(ja)
        addView(ko)
    }

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        x = prefs.getInt("ox", 0)
        y = prefs.getInt("oy", dp(140f).toInt())
    }

    private var added = false
    private val hideRunnable = Runnable { root.visibility = View.INVISIBLE }

    init {
        ja.visibility = if (prefs.getBoolean("showJa", true)) View.VISIBLE else View.GONE
        val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val show = ja.visibility != View.VISIBLE
                ja.visibility = if (show) View.VISIBLE else View.GONE
                prefs.edit().putBoolean("showJa", show).apply()
                return true
            }
        })
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        root.setOnTouchListener { _, e ->
            gd.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt()
                    lp.y = startY - (e.rawY - downY).toInt()   // BOTTOM 기준이라 위로 끌면 y 증가
                    if (added) wm.updateViewLayout(root, lp)
                }
                MotionEvent.ACTION_UP -> prefs.edit().putInt("ox", lp.x).putInt("oy", lp.y).apply()
            }
            true
        }
    }

    fun show() = main.post {
        if (!added) { wm.addView(root, lp); added = true }
    }

    fun remove() = main.post {
        main.removeCallbacks(hideRunnable)
        if (added) { runCatching { wm.removeView(root) }; added = false }
    }

    /** 상태 메시지 (로딩 중 등) — 사라지지 않음 */
    fun status(msg: String) = main.post {
        fitWidth()
        main.removeCallbacks(hideRunnable)
        root.visibility = View.VISIBLE
        ja.text = ""
        ja.visibility = View.GONE
        ko.text = msg
    }

    fun caption(jaText: String, koText: String?) = main.post {
        fitWidth()
        ja.visibility = if (prefs.getBoolean("showJa", true)) View.VISIBLE else View.GONE
        ja.text = jaText
        ko.text = koText ?: "…"
        root.visibility = View.VISIBLE
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, 8000)
    }

    /** 화면 회전 대응: 매번 화면 폭 기준으로 최대 폭 갱신 */
    private fun fitWidth() {
        val w = ctx.resources.displayMetrics.widthPixels - dp(48f).toInt()
        ja.maxWidth = w
        ko.maxWidth = w
    }
}
