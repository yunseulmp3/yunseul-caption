package com.yunseul.caption

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Models {
    data class Spec(val key: String, val label: String, val file: String, val url: String, val approxMb: Int)

    private const val HF = "https://huggingface.co"

    val SMALL = Spec("small", "정확 (small · 약 180MB · 추천)", "ggml-small-q5_1.bin",
        "$HF/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin", 182)
    val BASE = Spec("base", "빠름 (base · 약 57MB)", "ggml-base-q5_1.bin",
        "$HF/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin", 57)
    val VAD = Spec("vad", "말소리 감지 (Silero VAD)", "ggml-silero-v6.2.0.bin",
        "$HF/ggml-org/whisper-vad/resolve/main/ggml-silero-v6.2.0.bin", 1)

    val choices = listOf(SMALL, BASE)

    fun file(ctx: Context, s: Spec) = File(ctx.filesDir, s.file)
    fun ready(ctx: Context, s: Spec) = file(ctx, s).let { it.exists() && it.length() > 100_000 }

    fun selected(ctx: Context): Spec =
        if (Prefs.of(ctx).getString("model", "small") == "base") BASE else SMALL

    /** 이어받기 없이 단순 다운로드. .part로 받고 끝나면 이름 변경. */
    fun download(ctx: Context, s: Spec, progress: (Long, Long) -> Unit) {
        val dst = file(ctx, s)
        val part = File(dst.path + ".part")
        var url = URL(s.url)
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "yunseul-caption")
            val code = conn.responseCode
            if (code in 300..399 && hops < 8) {
                val loc = conn.getHeaderField("Location") ?: error("redirect without location")
                url = URL(url, loc)
                conn.disconnect()
                hops++
                continue
            }
            if (code != 200) error("HTTP $code")
            break
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                var last = 0L
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    done += r
                    if (done - last > 1_000_000) { progress(done, total); last = done }
                }
                progress(done, total)
            }
        }
        conn.disconnect()
        if (total > 0 && part.length() != total) { part.delete(); error("다운로드가 중간에 끊김") }
        if (!part.renameTo(dst)) error("파일 저장 실패")
    }
}

object Prefs {
    fun of(ctx: Context) = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)!!
}
