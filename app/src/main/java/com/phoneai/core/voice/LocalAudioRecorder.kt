package com.phoneai.core.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

/** Records 16 kHz mono PCM suitable for whisper.cpp and returns normalized float samples. */
class LocalAudioRecorder {
    private val recording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private val samples = ArrayList<Short>(16_000 * 8)

    val isRecording: Boolean
        get() = recording.get()

    @SuppressLint("MissingPermission")
    fun start(onError: (Throwable) -> Unit) {
        check(!recording.get()) { "Recording is already active" }

        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minimum > 0) { "Microphone buffer is not available" }
        val bufferBytes = maxOf(minimum * 4, SAMPLE_RATE * 2)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not be initialized" }

        samples.clear()
        audioRecord = recorder
        recording.set(true)
        worker = Thread({
            val buffer = ShortArray(bufferBytes / 2)
            try {
                recorder.startRecording()
                while (recording.get()) {
                    val count = recorder.read(buffer, 0, buffer.size)
                    if (count > 0) {
                        synchronized(samples) {
                            for (i in 0 until count) samples.add(buffer[i])
                        }
                    } else if (count < 0) {
                        error("Microphone read failed: $count")
                    }
                }
            } catch (t: Throwable) {
                if (recording.get()) onError(t)
            } finally {
                recording.set(false)
                runCatching { recorder.stop() }
                recorder.release()
            }
        }, "PhoneAI-Microphone").also { it.start() }
    }

    fun stop(): FloatArray {
        if (!recording.getAndSet(false)) return FloatArray(0)
        runCatching { audioRecord?.stop() }
        worker?.join(2500)
        worker = null
        audioRecord = null

        val copy = synchronized(samples) { samples.toShortArray() }
        return FloatArray(copy.size) { index -> copy[index] / 32768.0f }
    }

    fun cancel() {
        if (recording.getAndSet(false)) runCatching { audioRecord?.stop() }
        worker?.join(1200)
        worker = null
        audioRecord = null
        synchronized(samples) { samples.clear() }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
