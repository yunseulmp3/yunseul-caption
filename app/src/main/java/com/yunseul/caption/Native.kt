package com.yunseul.caption

/** whisper.cpp 음성인식 (JNI) */
class Whisper private constructor(private var handle: Long) {

    data class Result(val lang: String, val text: String, val noSpeech: Float)

    @Synchronized
    fun transcribe(pcm16k: FloatArray, lang: String, threads: Int): Result? {
        if (handle == 0L) return null
        val r = nativeTranscribe(handle, pcm16k, lang, threads)
        val text = r.getOrNull(1) ?: return null
        return Result(r.getOrNull(0) ?: lang, text.trim(), r.getOrNull(2)?.toFloatOrNull() ?: 0f)
    }

    @Synchronized
    fun close() {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
    }

    companion object {
        init { System.loadLibrary("yunseulcaption") }

        fun load(path: String): Whisper? {
            val h = nativeInit(path)
            return if (h != 0L) Whisper(h) else null
        }

        @JvmStatic private external fun nativeInit(path: String): Long
        @JvmStatic private external fun nativeFree(handle: Long)
        @JvmStatic private external fun nativeTranscribe(
            handle: Long, samples: FloatArray, lang: String, threads: Int
        ): Array<String?>
    }
}

/** Silero VAD — 512샘플(32ms) 프레임마다 말소리 확률 */
class Vad private constructor(private var handle: Long) {

    @Synchronized
    fun prob(frame512: FloatArray): Float {
        if (handle == 0L) return 0f
        val p = nativeProbs(handle, frame512)
        return if (p.isNotEmpty()) p[p.size - 1] else 0f
    }

    @Synchronized
    fun reset() { if (handle != 0L) nativeReset(handle) }

    @Synchronized
    fun close() {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
    }

    companion object {
        init { System.loadLibrary("yunseulcaption") }

        fun load(path: String): Vad? {
            val h = nativeInit(path)
            return if (h != 0L) Vad(h) else null
        }

        @JvmStatic private external fun nativeInit(path: String): Long
        @JvmStatic private external fun nativeFree(handle: Long)
        @JvmStatic private external fun nativeReset(handle: Long)
        @JvmStatic private external fun nativeProbs(handle: Long, samples: FloatArray): FloatArray
    }
}
