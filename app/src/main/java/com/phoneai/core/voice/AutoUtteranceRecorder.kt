package com.phoneai.core.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.max
import kotlin.math.sqrt
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot voice-activity recorder for hands-free mode.
 *
 * It waits for speech, keeps a short pre-roll so the first syllable is not lost,
 * and automatically finishes after trailing silence. Audio never leaves the device.
 */
class AutoUtteranceRecorder {
    private val active = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null

    val isActive: Boolean
        get() = active.get()

    @SuppressLint("MissingPermission")
    fun start(
        maxWaitMs: Long,
        onState: (State) -> Unit,
        onUtterance: (FloatArray) -> Unit,
        onTimeout: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        check(!active.get()) { "Hands-free recorder is already active" }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) { "Microphone buffer is not available" }
        val bufferBytes = max(minBuffer * 2, FRAME_SAMPLES * 2 * 4)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not be initialized" }

        audioRecord = recorder
        active.set(true)
        worker = Thread({
            val frame = ShortArray(FRAME_SAMPLES)
            val preRoll = ArrayDeque<ShortArray>()
            val captured = ArrayList<Short>(SAMPLE_RATE * 8)
            val startedAt = System.nanoTime()
            var noiseFloor = INITIAL_NOISE_FLOOR
            var calibratedFrames = 0
            var loudFrames = 0
            var silentFrames = 0
            var speechStarted = false

            try {
                recorder.startRecording()
                onState(State.WAITING_FOR_SPEECH)

                while (active.get()) {
                    val read = recorder.read(frame, 0, frame.size)
                    if (read <= 0) {
                        if (read < 0) error("Microphone read failed: $read")
                        continue
                    }

                    val rms = rms(frame, read)
                    if (!speechStarted) {
                        if (calibratedFrames < CALIBRATION_FRAMES) {
                            noiseFloor = (noiseFloor * calibratedFrames + rms) / (calibratedFrames + 1)
                            calibratedFrames++
                        } else {
                            noiseFloor = (noiseFloor * 0.985f) + (rms * 0.015f)
                        }

                        preRoll.addLast(frame.copyOf(read))
                        while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()

                        val threshold = max(MIN_SPEECH_RMS, noiseFloor * NOISE_MULTIPLIER)
                        if (rms >= threshold) loudFrames++ else loudFrames = 0

                        if (loudFrames >= SPEECH_START_FRAMES) {
                            speechStarted = true
                            onState(State.SPEECH_DETECTED)
                            preRoll.forEach { chunk -> chunk.forEach { captured.add(it) } }
                            preRoll.clear()
                            silentFrames = 0
                        } else if (elapsedMs(startedAt) >= maxWaitMs) {
                            active.set(false)
                            onTimeout()
                            break
                        }
                    } else {
                        frame.copyOf(read).forEach { captured.add(it) }
                        val threshold = max(MIN_SPEECH_RMS * 0.78f, noiseFloor * SILENCE_MULTIPLIER)
                        if (rms < threshold) silentFrames++ else silentFrames = 0

                        val durationMs = captured.size * 1000L / SAMPLE_RATE
                        if (silentFrames >= TRAILING_SILENCE_FRAMES || durationMs >= MAX_UTTERANCE_MS) {
                            active.set(false)
                            onState(State.UTTERANCE_READY)
                            val shorts = captured.toShortArray()
                            val floats = FloatArray(shorts.size) { i -> shorts[i] / 32768.0f }
                            onUtterance(floats)
                            break
                        }
                    }
                }
            } catch (t: Throwable) {
                if (active.getAndSet(false)) onError(t)
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
                audioRecord = null
            }
        }, "PhoneAI-VAD").also { it.start() }
    }

    fun cancel() {
        if (!active.getAndSet(false)) return
        runCatching { audioRecord?.stop() }
        worker?.join(500)
        worker = null
        audioRecord = null
    }

    private fun rms(samples: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        for (i in 0 until count) {
            val v = samples[i] / 32768.0
            sum += v * v
        }
        return sqrt(sum / count).toFloat()
    }

    private fun elapsedMs(startedAtNs: Long): Long = (System.nanoTime() - startedAtNs) / 1_000_000L

    enum class State {
        WAITING_FOR_SPEECH,
        SPEECH_DETECTED,
        UTTERANCE_READY,
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val FRAME_MS = 30
        private const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
        private const val PRE_ROLL_FRAMES = 10 // 300 ms
        private const val CALIBRATION_FRAMES = 24 // ~720 ms
        private const val SPEECH_START_FRAMES = 3 // ~90 ms
        private const val TRAILING_SILENCE_FRAMES = 30 // ~900 ms
        private const val MAX_UTTERANCE_MS = 20_000L
        private const val INITIAL_NOISE_FLOOR = 0.004f
        private const val MIN_SPEECH_RMS = 0.014f
        private const val NOISE_MULTIPLIER = 3.2f
        private const val SILENCE_MULTIPLIER = 2.2f
    }
}
