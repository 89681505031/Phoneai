package com.phoneai.core.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

class LightweightWakeListener(private val context: Context) {
    private val active = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var spotter: LocalKeywordSpotter? = null

    val isActive: Boolean get() = active.get()

    @SuppressLint("MissingPermission")
    fun start(
        pack: WakeWordPackManager.Pack,
        onKeyword: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (!active.compareAndSet(false, true)) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            active.set(false)
            onError(SecurityException("Нет разрешения RECORD_AUDIO"))
            return
        }

        worker = Thread({
            try {
                val localSpotter = LocalKeywordSpotter(pack)
                spotter = localSpotter
                val min = AudioRecord.getMinBufferSize(
                    pack.sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(pack.sampleRate / 5 * 2)
                val audio = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    pack.sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    min * 2,
                )
                recorder = audio
                require(audio.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord не инициализирован" }
                val shorts = ShortArray((pack.sampleRate / 10).coerceAtLeast(800))
                audio.startRecording()
                while (active.get()) {
                    val n = audio.read(shorts, 0, shorts.size)
                    if (n < 0) error("AudioRecord.read=$n")
                    if (n == 0) continue
                    val floats = FloatArray(n)
                    for (i in 0 until n) floats[i] = shorts[i] / 32768.0f
                    val keyword = localSpotter.accept(floats, pack.sampleRate)
                    if (!keyword.isNullOrBlank() && active.compareAndSet(true, false)) {
                        runCatching { audio.stop() }
                        onKeyword(keyword)
                        break
                    }
                }
            } catch (t: Throwable) {
                if (active.getAndSet(false)) onError(t)
            } finally {
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                recorder = null
                runCatching { spotter?.release() }
                spotter = null
                worker = null
            }
        }, "PhoneAI-KWS").apply { start() }
    }

    fun cancel() {
        if (!active.getAndSet(false)) return
        runCatching { recorder?.stop() }
        runCatching { worker?.join(500L) }
    }
}
