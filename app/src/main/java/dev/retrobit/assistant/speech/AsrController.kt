package dev.retrobit.assistant.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Pembungkus SpeechRecognizer (id-ID). Online: layanan pengenal default (biasanya Google).
 * Offline: createOnDeviceSpeechRecognizer bila tersedia. Wajib dipanggil di main thread.
 */
class AsrController(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onReady()
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onAsrError(message: String)
        fun onLevel(rmsDb: Float) {}
    }

    private var recognizer: SpeechRecognizer? = null
    var lastModeOnDevice = false
        private set

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)
    val isOnDeviceAvailable: Boolean get() = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun start(preferOnDevice: Boolean) {
        stop()
        val onDevice = preferOnDevice && isOnDeviceAvailable
        lastModeOnDevice = onDevice
        val r = if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.onReady()
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = listener.onLevel(rmsdB)
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) = listener.onAsrError(describe(error, onDevice))
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) listener.onAsrError("Tidak ada ucapan yang dikenali.") else listener.onFinal(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let(listener::onPartial)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            if (preferOnDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        r.startListening(intent)
    }

    fun stop() {
        recognizer?.let {
            try {
                it.cancel()
                it.destroy()
            } catch (_: Exception) {
            }
        }
        recognizer = null
    }

    private fun describe(code: Int, onDevice: Boolean): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Tidak ada ucapan yang dikenali."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Tidak terdengar suara."
        SpeechRecognizer.ERROR_AUDIO -> "Mikrofon bermasalah."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Izin mikrofon belum diberikan."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Pengenal suara butuh internet. Pasang paket bahasa Indonesia offline di pengaturan Google."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Pengenal suara sedang sibuk, coba lagi."
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Server pengenal suara bermasalah."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            if (onDevice) "Paket bahasa Indonesia offline belum terpasang." else "Bahasa Indonesia tidak didukung pengenal suara ini."
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Terlalu banyak permintaan ke pengenal suara."
        else -> "Galat pengenal suara ($code)."
    }
}
