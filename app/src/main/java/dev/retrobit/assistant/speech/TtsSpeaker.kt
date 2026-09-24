package dev.retrobit.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/** TTS bahasa Indonesia; memilih suara yang TIDAK butuh jaringan bila ada. */
class TtsSpeaker(context: Context, private val onDone: () -> Unit) {
    private val indonesian = Locale.forLanguageTag("id-ID")
    private var ready = false
    var status: String = "memuat…"
        private set
    var voiceName: String? = null
        private set

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { code ->
        if (code == TextToSpeech.SUCCESS) setup() else status = "mesin TTS gagal dimuat ($code)"
    }

    private fun setup() {
        val res = tts.setLanguage(indonesian)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            status = "suara bahasa Indonesia belum terpasang"
            return
        }
        val voices: Set<Voice> = try { tts.voices.orEmpty() } catch (_: Exception) { emptySet() }
        val best = voices
            .filter { it.locale.language == "id" || it.locale.language == "in" }
            .sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }.thenByDescending { it.quality })
            .firstOrNull()
        if (best != null) {
            tts.voice = best
            voiceName = best.name + if (best.isNetworkConnectionRequired) " (online)" else " (offline)"
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = onDone()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onDone()
        })
        ready = true
        status = "siap"
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) {
            onDone()
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rbit-${System.nanoTime()}")
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() = tts.shutdown()
}
