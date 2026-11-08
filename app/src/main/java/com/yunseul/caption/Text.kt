package com.yunseul.caption

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 구글 무료 번역 (키 없음) */
object Translator {
    fun toKorean(text: String, src: String = "ja"): String? = try {
        val q = URLEncoder.encode(text, "UTF-8")
        val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=$src&tl=ko&dt=t&q=$q")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 4000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        val body = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        c.disconnect()
        val parts = JSONArray(body).getJSONArray(0)
        buildString {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONArray(i) ?: continue
                append(p.optString(0, ""))
            }
        }.trim().ifEmpty { null }
    } catch (e: Exception) {
        null
    }
}

/** Whisper 일본어 환각(무음·음악 구간에서 지어내는 문장) 걸러내기 */
object Hallucination {
    private val phrases = listOf(
        "ご視聴ありがとうございました", "ご視聴ありがとうございます",
        "チャンネル登録", "高評価", "字幕作成", "字幕制作", "字幕：", "字幕:",
        "おやすみなさい。おやすみなさい", "Thank you for watching",
    )

    fun isBad(text: String, noSpeech: Float): Boolean {
        if (text.isBlank()) return true
        if (noSpeech > 0.6f) return true
        if (phrases.any { text.contains(it) }) return true
        // 기호/문장부호만 있는 경우 (♪, ..., 「」 등)
        val letters = text.count { Character.isLetterOrDigit(it) }
        if (letters == 0) return true
        // 같은 글자 반복 폭주 ("ああああああ…", "はいはいはい…")
        if (text.length >= 16 && text.toSet().size.toFloat() / text.length < 0.15f) return true
        return false
    }
}
