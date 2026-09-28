package com.phoneai.core.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Uses only installed TextToSpeech voices that explicitly do not require a network connection. */
class OfflineTtsController(
    context: Context,
    private val onStateChanged: (String) -> Unit
) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var offlineVoice: Voice? = null
    private var sakhaOfflineVoice: Voice? = null
    private var ready = false
    private val doneCallbacks = ConcurrentHashMap<String, () -> Unit>()

    init {
        tts = TextToSpeech(appContext, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ready = false
            onStateChanged("Озвучка: TTS-движок Android не запустился")
            return
        }

        val engine = tts ?: return
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                utteranceId ?: return
                doneCallbacks.remove(utteranceId)?.invoke()
            }

            @Deprecated("Deprecated in Android API")
            override fun onError(utteranceId: String?) {
                utteranceId ?: return
                doneCallbacks.remove(utteranceId)?.invoke()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId ?: return
                doneCallbacks.remove(utteranceId)?.invoke()
            }
        })

        val voices = engine.voices.orEmpty()
        sakhaOfflineVoice = chooseSakhaOfflineVoice(voices)
        offlineVoice = chooseOfflineVoice(voices)
        val voice = offlineVoice
        if (voice == null) {
            ready = false
            onStateChanged("Озвучка: офлайн-голос не установлен")
            return
        }

        engine.voice = voice
        engine.language = voice.locale
        ready = true
        onStateChanged("Озвучка: офлайн • ${voice.locale.displayLanguage} • ${voice.name}")
    }

    fun canSpeakOffline(): Boolean = ready && offlineVoice != null

    fun canSpeakSakhaOffline(): Boolean = ready && sakhaOfflineVoice != null

    fun speak(text: String, onDone: (() -> Unit)? = null): Boolean {
        if (!canSpeakOffline() || text.isBlank()) return false
        val engine = tts ?: return false
        val selectedVoice = if (SakhaLanguage.looksLikeSakha(text)) {
            sakhaOfflineVoice ?: run {
                onStateChanged("Озвучка саха: офлайн-голос sah / sah-RU не установлен")
                return false
            }
        } else {
            offlineVoice ?: return false
        }
        engine.voice = selectedVoice
        engine.language = selectedVoice.locale
        if (SakhaLanguage.looksLikeSakha(text)) {
            onStateChanged("Озвучка саха: офлайн • ${selectedVoice.locale.toLanguageTag()} • ${selectedVoice.name}")
        }
        val clipped = text.take(MAX_SPEAK_CHARS)
        val utteranceId = "phoneai-local-${UUID.randomUUID()}"
        if (onDone != null) doneCallbacks[utteranceId] = onDone
        val result = engine.speak(clipped, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            doneCallbacks.remove(utteranceId)
            return false
        }
        return true
    }

    fun stop() {
        doneCallbacks.clear()
        tts?.stop()
    }

    fun shutdown() {
        doneCallbacks.clear()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun chooseSakhaOfflineVoice(voices: Set<Voice>): Voice? =
        voices
            .filter { !it.isNetworkConnectionRequired }
            .firstOrNull { voice ->
                val tag = voice.locale.toLanguageTag().lowercase(Locale.ROOT)
                voice.locale.language.equals("sah", ignoreCase = true) || tag.startsWith("sah")
            }


    private fun chooseOfflineVoice(voices: Set<Voice>): Voice? {
        val offline = voices.filter { !it.isNetworkConnectionRequired }
        if (offline.isEmpty()) return null

        val preferred = Locale.getDefault()
        return offline.firstOrNull { it.locale.toLanguageTag() == preferred.toLanguageTag() }
            ?: offline.firstOrNull { it.locale.language == preferred.language }
            ?: offline.firstOrNull { it.locale.language == "ru" }
            ?: offline.first()
    }

    companion object {
        private const val MAX_SPEAK_CHARS = 3500
    }
}
