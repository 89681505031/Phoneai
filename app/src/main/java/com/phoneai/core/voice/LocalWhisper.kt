package com.phoneai.core.voice

import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Thin lifecycle-safe wrapper around the official whisper.cpp Android library. */
class LocalWhisper {
    suspend fun transcribe(model: File, audio: FloatArray): String = withContext(Dispatchers.IO) {
        require(model.exists() && model.isFile) { "Whisper model not found" }
        require(audio.isNotEmpty()) { "No microphone audio was recorded" }

        val context = WhisperContext.createContextFromFile(model.absolutePath)
        try {
            context.transcribeData(audio, printTimestamp = false).trim()
        } finally {
            context.release()
        }
    }

    fun systemInfo(): String = runCatching { WhisperContext.getSystemInfo() }.getOrDefault("whisper.cpp")
}
