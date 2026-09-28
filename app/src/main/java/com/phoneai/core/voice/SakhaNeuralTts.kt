package com.phoneai.core.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class SakhaNeuralTts(
    context: Context,
    private val onStateChanged: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tts: OfflineTts? = null
    private var playback: AudioTrack? = null
    private var job: Job? = null
    private val stopping = AtomicBoolean(false)

    @Synchronized
    fun reload(): Boolean {
        stop()
        tts?.release()
        tts = null
        val pack = SakhaTtsPackManager.current(appContext) ?: run {
            onStateChanged("Sakha TTS: нейросетевой пакет не установлен")
            return false
        }
        return try {
            val vits = OfflineTtsVitsModelConfig(
                model = pack.model.absolutePath,
                tokens = pack.tokens.absolutePath,
                noiseScale = 0.667f,
                noiseScaleW = 0.8f,
                lengthScale = 1.0f,
            )
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(vits = vits, numThreads = 2, provider = "cpu"),
                maxNumSentences = 1,
                silenceScale = 0.2f,
            )
            tts = OfflineTts(config = config)
            onStateChanged("Sakha TTS: нейросетевой офлайн-голос готов • ${tts?.sampleRate()} Гц")
            true
        } catch (t: Throwable) {
            onStateChanged("Sakha TTS: пакет не загрузился • ${t.message ?: t.javaClass.simpleName}")
            false
        }
    }

    fun isReady(): Boolean = tts != null

    fun speak(text: String, onDone: (() -> Unit)? = null): Boolean {
        val engine = tts ?: return false
        if (text.isBlank() || !SakhaLanguage.looksLikeSakha(text)) return false
        stop()
        stopping.set(false)
        job = scope.launch {
            try {
                onStateChanged("Sakha TTS: синтезирую речь локально…")
                val generated = engine.generate(SakhaLanguage.normalize(text).take(MAX_CHARS), speed = 1.0f)
                if (stopping.get() || generated.samples.isEmpty()) return@launch
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(generated.sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes((generated.sampleRate * 4).coerceAtLeast(16_384))
                    .build()
                playback = track
                track.play()
                var offset = 0
                while (offset < generated.samples.size && !stopping.get()) {
                    val written = track.write(
                        generated.samples,
                        offset,
                        generated.samples.size - offset,
                        AudioTrack.WRITE_BLOCKING
                    )
                    if (written <= 0) break
                    offset += written
                }
                if (!stopping.get()) {
                    val durationMs = (generated.samples.size * 1000L / generated.sampleRate).coerceAtMost(30_000L)
                    val deadline = System.currentTimeMillis() + durationMs + 500L
                    while (!stopping.get() && System.currentTimeMillis() < deadline && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        Thread.sleep(40L)
                        if (track.playbackHeadPosition >= generated.samples.size) break
                    }
                }
                onStateChanged("Sakha TTS: готов")
            } catch (t: Throwable) {
                onStateChanged("Sakha TTS: ошибка • ${t.message ?: t.javaClass.simpleName}")
            } finally {
                try { playback?.stop() } catch (_: Throwable) {}
                playback?.release()
                playback = null
                if (!stopping.get()) onDone?.invoke()
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        stopping.set(true)
        job?.cancel()
        job = null
        try { playback?.pause() } catch (_: Throwable) {}
        try { playback?.flush() } catch (_: Throwable) {}
        try { playback?.stop() } catch (_: Throwable) {}
        playback?.release()
        playback = null
    }

    fun shutdown() {
        stop()
        tts?.release()
        tts = null
        scope.cancel()
    }

    companion object { private const val MAX_CHARS = 1200 }
}
