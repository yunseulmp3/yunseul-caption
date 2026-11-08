package com.yunseul.caption

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private val REQ_CAPTURE = 10
    private val REQ_PERMS = 11

    private lateinit var overlayBtn: Button
    private lateinit var micBtn: Button
    private lateinit var modelInfo: TextView
    private lateinit var downloadBtn: Button
    private lateinit var startBtn: Button
    private lateinit var modelGroup: RadioGroup
    @Volatile private var downloading = false

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        fun title(t: String, size: Float = 16f) = TextView(this).apply {
            text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(6))
        }
        fun body(t: String) = TextView(this).apply {
            text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setLineSpacing(0f, 1.25f)
        }

        col.addView(title("윤슬 자막", 24f).apply { setPadding(0, 0, 0, dp(4)) })
        col.addView(body("영상 소리를 듣고 일본어 원문 + 한국어 번역을 화면 위에 띄워줘."))

        col.addView(title("1. 권한"))
        overlayBtn = Button(this).apply {
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        micBtn = Button(this).apply { setOnClickListener { askPerms() } }
        col.addView(overlayBtn); col.addView(micBtn)

        col.addView(title("2. 음성인식 모델"))
        modelGroup = RadioGroup(this)
        Models.choices.forEachIndexed { i, s ->
            modelGroup.addView(RadioButton(this).apply { id = 100 + i; text = s.label })
        }
        modelGroup.check(if (Models.selected(this).key == "base") 101 else 100)
        modelGroup.setOnCheckedChangeListener { _, id ->
            Prefs.of(this).edit().putString("model", if (id == 101) "base" else "small").apply()
            refresh()
        }
        col.addView(modelGroup)
        modelInfo = body("")
        col.addView(modelInfo)
        downloadBtn = Button(this).apply { setOnClickListener { download() } }
        col.addView(downloadBtn)

        col.addView(title("3. 실행"))
        startBtn = Button(this).apply {
            setOnClickListener { if (CaptionService.running) stopCaption() else startCaption() }
        }
        col.addView(startBtn)

        col.addView(title("사용 팁"))
        col.addView(body(
            "• 시작을 누르면 '화면 공유/녹화' 확인 창이 떠. '전체 화면'을 고르고 시작을 눌러줘. (소리만 쓰고 화면은 저장 안 해)\n" +
            "• 그다음 유튜브 같은 앱에서 영상을 틀면 자막이 떠.\n" +
            "• 자막창은 손가락으로 끌어서 옮길 수 있고, 두 번 탭하면 일본어 줄을 숨기거나 다시 보여줘.\n" +
            "• 끄려면 이 화면의 '중지' 또는 알림창의 '중지'.\n" +
            "• 넷플릭스·일부 OTT 앱은 소리 캡처를 막아놔서 자막이 안 떠. 유튜브·브라우저·치지직 등은 돼.\n" +
            "• 말이 끝나고 0.5초쯤 쉬는 순간에 문장 단위로 자막이 떠. 폰이 느리면 '빠름' 모델로 바꿔줘."
        ))

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun hasOverlay() = Settings.canDrawOverlays(this)
    private fun modelsReady() = Models.ready(this, Models.selected(this)) && Models.ready(this, Models.VAD)

    private fun refresh() {
        overlayBtn.text = if (hasOverlay()) "✓ 다른 앱 위에 표시 권한 있음" else "다른 앱 위에 표시 권한 허용하기"
        overlayBtn.isEnabled = !hasOverlay()
        micBtn.text = if (hasMic()) "✓ 녹음·알림 권한 있음" else "녹음·알림 권한 허용하기"
        micBtn.isEnabled = !hasMic()
        if (!downloading) {
            val s = Models.selected(this)
            modelInfo.text = if (modelsReady()) "✓ ${s.label} 준비됨" else "아직 안 받음 — 와이파이에서 받아줘 (약 ${s.approxMb}MB)"
            downloadBtn.text = if (modelsReady()) "모델 다시 받기" else "모델 받기"
            downloadBtn.isEnabled = true
            modelGroup.isEnabled = true
        }
        startBtn.text = if (CaptionService.running) "■ 중지" else "▶ 자막 시작"
        startBtn.isEnabled = CaptionService.running || (hasOverlay() && hasMic() && modelsReady() && !downloading)
    }

    private fun askPerms() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun download() {
        if (downloading) return
        downloading = true
        downloadBtn.isEnabled = false
        startBtn.isEnabled = false
        val s = Models.selected(this)
        Thread {
            try {
                for (spec in listOf(Models.VAD, s)) {
                    Models.download(this, spec) { done, total ->
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        runOnUiThread {
                            modelInfo.text = "${spec.label} 받는 중… " +
                                (if (pct >= 0) "$pct%" else "${done / 1_000_000}MB")
                        }
                    }
                }
                runOnUiThread { downloading = false; refresh() }
            } catch (e: Exception) {
                runOnUiThread {
                    downloading = false
                    refresh()
                    modelInfo.text = "받기 실패: ${e.message} — 인터넷 확인 후 다시 눌러줘"
                }
            }
        }.start()
    }

    private fun startCaption() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34)
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_CAPTURE)
    }

    private fun stopCaption() {
        startService(Intent(this, CaptionService::class.java).setAction(CaptionService.ACTION_STOP))
        startBtn.postDelayed({ refresh() }, 500)
    }

    @Deprecated("Activity result API 대신 단순하게 사용")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) return
        startForegroundService(
            Intent(this, CaptionService::class.java)
                .putExtra(CaptionService.EXTRA_CODE, resultCode)
                .putExtra(CaptionService.EXTRA_DATA, data)
        )
        startBtn.postDelayed({ refresh() }, 800)
        // 홈으로 보내서 바로 영상 앱을 열 수 있게
        moveTaskToBack(true)
    }
}
