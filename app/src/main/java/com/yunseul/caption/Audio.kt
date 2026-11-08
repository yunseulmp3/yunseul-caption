package com.yunseul.caption

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * 임의 샘플레이트 → 16kHz 모노 변환.
 * 63탭 블랙맨 윈도우 sinc 로우패스(컷오프 = 출력 나이퀴스트의 90%) 후 선형 보간.
 * 블록 경계에서 상태 유지 → 클릭/불연속 없음.
 */
class Resampler(inRate: Int, outRate: Int = 16000) {
    private val passthrough = inRate == outRate
    private val step = inRate.toDouble() / outRate
    private val taps = design(63, 0.45 * outRate / inRate)
    private val ring = FloatArray(taps.size)
    private var ri = 0
    private var t = 0.0     // 다음 출력 위치 (현재 블록 첫 샘플 = 0)
    private var prev = 0f   // 이전 블록 마지막 필터 출력 (위치 -1)

    fun process(input: FloatArray, n: Int): FloatArray {
        if (passthrough) return input.copyOf(n)
        if (n == 0) return FloatArray(0)
        val f = FloatArray(n)
        for (i in 0 until n) {
            ring[ri] = input[i]
            var acc = 0f
            var k = ri
            for (j in taps.indices) {
                acc += taps[j] * ring[k]
                k = if (k == 0) ring.size - 1 else k - 1
            }
            ri = (ri + 1) % ring.size
            f[i] = acc
        }
        val out = FloatArray((n / step).toInt() + 2)
        var m = 0
        while (t <= n - 1 && m < out.size) {
            val i0 = floor(t).toInt()
            val frac = (t - i0).toFloat()
            val a = if (i0 < 0) prev else f[i0]
            val b = if (i0 + 1 <= n - 1) f[i0 + 1] else a
            out[m++] = a + (b - a) * frac
            t += step
        }
        t -= n
        prev = f[n - 1]
        return out.copyOf(m)
    }

    private fun design(n: Int, fc: Double): FloatArray {
        val h = DoubleArray(n)
        val mid = (n - 1) / 2.0
        for (i in 0 until n) {
            val x = i - mid
            val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            val w = 0.42 - 0.5 * cos(2 * PI * i / (n - 1)) + 0.08 * cos(4 * PI * i / (n - 1))
            h[i] = sinc * w
        }
        val s = h.sum()
        return FloatArray(n) { (h[it] / s).toFloat() }
    }
}

/**
 * VAD 기반 발화 분할기.
 * - 말소리 시작 전 256ms 프리롤 포함 (첫 음절 안 잘리게)
 * - 무음 480ms 지속되면 문장 끝으로 보고 내보냄
 * - 8초 넘게 계속 말하면 4~8초 구간에서 말소리 확률이 가장 낮은 지점(숨 쉬는 곳)에서 자름
 */
class Segmenter(private val vad: Vad, private val onSegment: (FloatArray) -> Unit) {

    companion object {
        const val FRAME = 512                 // 32ms @16k
        private const val ON = 0.5f            // 말 시작 판정
        private const val OFF = 0.35f          // 말 유지 판정 (히스테리시스)
        private const val END_SILENCE = 15     // 480ms
        private const val TAIL = 4             // 문장 끝 128ms 여유
        private const val PREROLL = 8          // 256ms
        private const val MAX = 250            // 8s
        private const val MIN_SPEECH = 8       // 256ms 미만 말소리는 버림 (잡음)
    }

    private val pending = FloatArray(FRAME)
    private var pendingN = 0
    private val preroll = ArrayDeque<FloatArray>()
    private val seg = ArrayList<FloatArray>()
    private val probs = ArrayList<Float>()
    private var inSpeech = false
    private var silenceRun = 0

    fun feed(x: FloatArray) {
        var i = 0
        while (i < x.size) {
            val c = minOf(FRAME - pendingN, x.size - i)
            System.arraycopy(x, i, pending, pendingN, c)
            pendingN += c
            i += c
            if (pendingN == FRAME) {
                frame(pending.copyOf())
                pendingN = 0
            }
        }
    }

    private fun frame(f: FloatArray) {
        val p = vad.prob(f)
        if (!inSpeech) {
            if (p >= ON) {
                inSpeech = true
                silenceRun = 0
                for (pf in preroll) { seg.add(pf); probs.add(0f) }
                preroll.clear()
                seg.add(f); probs.add(p)
            } else {
                preroll.addLast(f)
                if (preroll.size > PREROLL) preroll.removeFirst()
            }
            return
        }
        seg.add(f); probs.add(p)
        silenceRun = if (p >= OFF) 0 else silenceRun + 1

        if (silenceRun >= END_SILENCE) {
            emit(seg.size - silenceRun + TAIL)
            // 남은 무음 꼬리는 다음 프리롤로
            preroll.clear()
            for (pf in seg.takeLast(PREROLL)) preroll.addLast(pf)
            seg.clear(); probs.clear()
            inSpeech = false
            silenceRun = 0
        } else if (seg.size >= MAX) {
            var cut = MAX / 2
            for (k in MAX / 2 until seg.size - 5) if (probs[k] < probs[cut]) cut = k
            emit(cut)
        }
    }

    /** seg[0, end) 를 하나로 합쳐 내보내고 seg에서 제거 */
    private fun emit(end: Int) {
        val e = end.coerceIn(0, seg.size)
        val speech = (0 until e).count { probs[it] >= OFF }
        if (speech >= MIN_SPEECH) {
            val out = FloatArray(e * FRAME)
            for (k in 0 until e) System.arraycopy(seg[k], 0, out, k * FRAME, FRAME)
            onSegment(out)
        }
        repeat(e) { seg.removeAt(0); probs.removeAt(0) }
    }
}
