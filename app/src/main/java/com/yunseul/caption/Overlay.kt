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
    private val info = TextView(ctx).apply {
        setTextColor(0x80FFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        gravity = Gravity.END
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
        addView(info, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
    private var currentJa = ""
    private var statusShown = false

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
        info.text = ""
        ko.alpha = 1f
        ko.text = msg
        statusShown = true
    }

    /**
     * 일본어 갱신. 한국어 줄은 번역이 올 때까지 이전 번역을 흐리게 유지 (깜빡임 방지).
     * partial = 말하는 중 미리보기 (일본어 줄을 살짝 흐리게)
     */
    fun caption(jaText: String, partial: Boolean, info: String) = main.post {
        fitWidth()
        currentJa = jaText
        ja.visibility = if (prefs.getBoolean("showJa", true)) View.VISIBLE else View.GONE
        ja.text = jaText
        ja.alpha = if (partial) 0.75f else 1f
        if (statusShown || ko.text.isNullOrEmpty()) { ko.text = "…"; statusShown = false }
        ko.alpha = 0.45f
        this.info.text = info
        root.visibility = View.VISIBLE
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, 8000)
    }

    /** 번역 도착 — 지금 떠 있는 일본어에 대한 번역일 때만 반영 */
    fun translation(jaText: String, koText: String, partial: Boolean) = main.post {
        if (jaText != currentJa) return@post
        ko.text = koText
        ko.alpha = if (partial) 0.85f else 1f
    }

    /** 화면 회전 대응: 매번 화면 폭 기준으로 최대 폭 갱신 */
    private fun fitWidth() {
        val w = ctx.resources.displayMetrics.widthPixels - dp(48f).toInt()
        ja.maxWidth = w
        ko.maxWidth = w
        info.maxWidth = w
    }
}
