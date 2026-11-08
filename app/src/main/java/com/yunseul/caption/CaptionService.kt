package com.yunseul.caption

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicReference

/**
 * 핵심 파이프라인:
 * 다른 앱 재생음 캡처 → 16k 모노 → VAD 발화 분할 → Whisper(일본어) → 구글 번역 → 자막창
 */
class CaptionService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.yunseul.caption.STOP"
        private const val CH = "caption"
        private const val TAG = "YunseulCaption"
        private const val LANG = "ja"

        @Volatile var running = false
            private set
    }

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var overlay: Overlay? = null
    private var whisper: Whisper? = null
    private var vad: Vad? = null
    private class Chunk(val pcm: FloatArray, val at: Long)
    private val queue = LinkedBlockingDeque<Chunk>()            // 확정 문장
    private val partial = AtomicReference<Chunk?>(null)          // 말하는 중 미리보기 (최신 1개만)
    @Volatile private var lastKo = ""
    private var captureThread: Thread? = null
    private var asrThread: Thread? = null
    @Volatile private var alive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (alive) return START_NOT_STICKY

        goForeground()

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = runCatching { mpm.getMediaProjection(code, data) }.getOrNull()
        if (mp == null) { stopSelf(); return START_NOT_STICKY }
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))
        projection = mp

        alive = true
        running = true
        overlay = Overlay(this).also { it.show(); it.status("모델 불러오는 중…") }

        asrThread = Thread({ asrLoop() }, "asr").also { it.start() }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "자막 실행 중", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("윤슬 자막 실행 중")
            .setContentText("일본어 → 한국어 실시간 자막")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "중지", stop).build())
            .build()
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
            (if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        startForeground(7, n, type)
    }

    // ---------------- 음성인식 + 번역 스레드 ----------------
    private fun asrLoop() {
        val ov = overlay ?: return
        val spec = Models.selected(this)
        val w = Whisper.load(Models.file(this, spec).path)
        val v = Vad.load(Models.file(this, Models.VAD).path)
        if (w == null || v == null) {
            ov.status("모델을 못 불러왔어. 앱에서 모델을 다시 받아줘.")
            w?.close(); v?.close()
            return
        }
        whisper = w; vad = v

        if (!startCapture(v)) {
            ov.status("소리 캡처를 시작 못 했어. 마이크(녹음) 권한을 확인해줘.")
            return
        }
        ov.status("듣는 중… 영상을 틀어줘")

        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        var lastText = ""
        var lastPartialText = ""
        while (alive) {
            // 확정 문장이 우선, 없으면 미리보기
            var isFinal = true
            var c: Chunk? = queue.pollFirst()
            if (c == null) { c = partial.getAndSet(null); isFinal = false }
            if (c == null) {
                try { Thread.sleep(40) } catch (e: InterruptedException) { break }
                continue
            }
            // Whisper는 1초 미만이면 불안정 → 무음 패딩
            val pcm = if (c.pcm.size < 16000) c.pcm.copyOf(16000) else c.pcm
            val t0 = System.currentTimeMillis()
            val r = w.transcribe(pcm, LANG, threads) ?: continue
            val asrMs = System.currentTimeMillis() - t0
            Log.i(TAG, "${if (isFinal) "final" else "partial"} ${c.pcm.size / 16}ms audio → ${asrMs}ms nsp=${r.noSpeech}: ${r.text}")
            if (!alive) break
            if (Hallucination.isBad(r.text, r.noSpeech)) continue

            if (!isFinal) {
                // 확정이 이미 기다리고 있으면 미리보기는 버림
                if (queue.isNotEmpty() || r.text == lastPartialText) continue
                lastPartialText = r.text
                ov.caption(r.text, partial = true, info = "인식 ${asrMs / 100 / 10.0}초")
                translateAsync(ov, r.text, partial = true)
                continue
            }

            lastPartialText = ""
            if (r.text == lastText) continue
            lastText = r.text
            val lag = (System.currentTimeMillis() - c.at) / 100 / 10.0
            ov.caption(r.text, partial = false, info = "처리 ${lag}초")
            translateAsync(ov, r.text, partial = false)
        }
    }

    // 번역은 인식을 막지 않게 별도 스레드. 늦게 도착한 옛 번역은 버림.
    private val trSeq = java.util.concurrent.atomic.AtomicLong(0)
    private val trPool = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun translateAsync(ov: Overlay, ja: String, partial: Boolean) {
        val id = trSeq.incrementAndGet()
        trPool.execute {
            if (id != trSeq.get()) return@execute          // 더 새 문장이 이미 들어옴
            val ko = Translator.toKorean(ja, LANG)
            if (!alive || id != trSeq.get()) return@execute
            if (ko != null) { lastKo = ko; ov.translation(ja, ko, partial) }
            else if (!partial) ov.translation(ja, "(번역 실패 — 인터넷 연결 확인)", false)
        }
    }

    // ---------------- 캡처 스레드 ----------------
    private fun startCapture(v: Vad): Boolean {
        val mp = projection ?: return false
        val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        // 16k 모노로 바로 받는 게 제일 좋고, 기기가 거부하면 48k/44.1k 스테레오로 받아서 변환
        val tries = listOf(
            16000 to AudioFormat.CHANNEL_IN_MONO,
            48000 to AudioFormat.CHANNEL_IN_STEREO,
            44100 to AudioFormat.CHANNEL_IN_STEREO,
        )
        var rec: AudioRecord? = null
        var rate = 0
        var ch = 1
        for ((sr, mask) in tries) {
            val r = try {
                val min = AudioRecord.getMinBufferSize(sr, mask, AudioFormat.ENCODING_PCM_16BIT)
                val chs = if (mask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
                AudioRecord.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr)
                            .setChannelMask(mask)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(min, sr * chs * 2 / 2))   // 0.5초 버퍼
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build()
            } catch (e: Exception) { Log.w(TAG, "AudioRecord $sr/$mask 실패: $e"); null }
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                rec = r; rate = sr; ch = if (mask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
                break
            }
            r?.release()
        }
        if (rec == null) return false
        Log.i(TAG, "capture ${rate}Hz ${ch}ch")
        record = rec

        val rs = Resampler(rate)
        val segmenter = Segmenter(v,
            onPartial = { seg -> partial.set(Chunk(seg, System.currentTimeMillis())) },
            onSegment = { seg ->
                partial.set(null)
                // 인식이 밀리면 오래된 문장부터 버림 → 자막이 영상보다 계속 늦어지는 것 방지
                while (queue.size >= 2) queue.pollFirst()
                queue.offerLast(Chunk(seg, System.currentTimeMillis()))
            })

        rec.startRecording()
        captureThread = Thread({
            val buf = ShortArray(rate * ch / 10)   // 100ms
            val mono = FloatArray(buf.size / ch)
            while (alive) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) { if (n < 0) break else continue }
                val frames = n / ch
                if (ch == 1) for (i in 0 until frames) mono[i] = buf[i] / 32768f
                else for (i in 0 until frames) mono[i] = (buf[2 * i] + buf[2 * i + 1]) / 65536f
                segmenter.feed(rs.process(mono, frames))
            }
        }, "capture").also { it.start() }
        return true
    }

    override fun onDestroy() {
        alive = false
        running = false
        runCatching { record?.stop() }
        captureThread?.join(1000)
        runCatching { record?.release() }
        asrThread?.interrupt()
        asrThread?.join(3000)
        trPool.shutdownNow()
        whisper?.close()
        vad?.close()
        overlay?.remove()
        runCatching { projection?.stop() }
        super.onDestroy()
    }
}
