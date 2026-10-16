package app.instantrecorder.transcribe

/** Thin JNI surface over whisper.cpp (see app/src/main/cpp/whisper_jni.cpp). */
object WhisperLib {
    init {
        System.loadLibrary("whisper_jni")
    }

    /** Returns a native context pointer, or 0 if the model could not be loaded. */
    external fun nativeInit(modelPath: String): Long
    external fun nativeFree(ctx: Long)

    /** Blocking. [audio] is 16 kHz mono PCM in [-1, 1]. Returns UTF-8 text or null on failure. */
    external fun nativeTranscribe(ctx: Long, audio: FloatArray, threads: Int, language: String): ByteArray?

    /** Progress (0..100) of the transcription currently running. */
    external fun nativeProgress(): Int
    external fun nativeAbort()
    external fun nativeSystemInfo(): String
}
