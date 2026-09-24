package dev.retrobit.assistant

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.retrobit.assistant.chat.CircuitBreaker
import dev.retrobit.assistant.chat.Connectivity
import dev.retrobit.assistant.chat.GeminiClient
import dev.retrobit.assistant.chat.GeminiException
import dev.retrobit.assistant.chat.Turn
import dev.retrobit.assistant.device.AppDirectory
import dev.retrobit.assistant.device.ContactDirectory
import dev.retrobit.assistant.router.EntityResolver
import dev.retrobit.assistant.router.Normalizer
import dev.retrobit.assistant.router.Route
import dev.retrobit.assistant.router.Router
import dev.retrobit.assistant.router.loadCatalog
import dev.retrobit.assistant.speech.AsrController
import dev.retrobit.assistant.speech.TtsSpeaker
import dev.retrobit.assistant.tools.ToolExecutor
import dev.retrobit.assistant.tools.ToolOutcome
import dev.retrobit.assistant.tools.WeatherClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

enum class Role { USER, ASSISTANT }

/** origin: "perintah" | "online" | "offline" (kosong untuk pesan pengguna) */
data class ChatMessage(val id: Long, val role: Role, val text: String, val origin: String = "")

enum class Phase { IDLE, LISTENING, THINKING, SPEAKING }

class AssistantViewModel(app: Application) : AndroidViewModel(app), AsrController.Listener {
    val settings = Settings(app)
    private val main = Handler(Looper.getMainLooper())
    private val ids = AtomicLong()
    private val store = ChatStore(app)

    val messages = mutableStateListOf<ChatMessage>()
    var phase by mutableStateOf(Phase.IDLE)
        private set
    var partial by mutableStateOf("")
        private set
    /** Keras suara mikrofon 0..1, untuk animasi gelombang. */
    var level by mutableFloatStateOf(0f)
        private set
    var online by mutableStateOf(false)
        private set
    var pendingPrompt by mutableStateOf<String?>(null)
        private set
    var catalogError by mutableStateOf<String?>(null)
        private set
    var apiKeyMissing by mutableStateOf(settings.apiKey.isBlank())
        private set
    var onboarded by mutableStateOf(settings.onboarded)
        private set
    /** Pesan singkat yang hilang sendiri (galat ASR, "disalin", dll.), bukan balon chat. */
    var notice by mutableStateOf<String?>(null)
        private set
    /** Sedang mendengarkan lanjutan otomatis (mode percakapan). */
    var followUpListening by mutableStateOf(false)
        private set

    private var pending: ToolOutcome.Confirm? = null
    private var lastSpoken = ""
    private var job: Job? = null
    private var noticeJob: Job? = null
    private var autoListenDone = false
    private var lastInputVoice = false
    private var wantFollowUp = false
    private var retriedOnline = false
    private var foreground = false

    private val gemini = GeminiClient()
    private val breaker = CircuitBreaker()
    private val connectivity = Connectivity(app) { v -> main.post { online = v } }
    private val asr = AsrController(app, this)
    private val tts = TtsSpeaker(app) { main.post { onSpeechDone() } }

    private val normalizer: Normalizer
    val router: Router?
    val apps: AppDirectory
    val contacts: ContactDirectory
    private val tools: ToolExecutor

    init {
        online = connectivity.online
        val assets = app.assets
        normalizer = Normalizer.fromYaml(assets.open("intents/_normalizer.yaml").bufferedReader().readText())
        apps = AppDirectory(app, normalizer)
        contacts = ContactDirectory(app, normalizer)
        router = try {
            val files = assets.list("intents").orEmpty().map { name ->
                name to assets.open("intents/$name").bufferedReader().readText()
            }
            Router(normalizer, loadCatalog(files), object : EntityResolver {
                override fun app(raw: String): String? = apps.resolve(raw)?.label
                // Tanpa izin kontak: terima dulu, tool yang akan meminta izin.
                override fun contact(raw: String): String? =
                    if (!contacts.hasPermission()) raw else contacts.resolve(raw)?.name
            })
        } catch (e: Exception) {
            catalogError = e.message
            null
        }
        tools = ToolExecutor(app, apps, contacts, WeatherClient()) { settings.city }
        val saved = store.load()
        messages += saved
        ids.set(saved.maxOfOrNull { it.id } ?: 0L)
    }

    // ---- siklus hidup & pemicu --------------------------------------------------------

    fun setForeground(v: Boolean) {
        foreground = v
        if (!v) {
            // Jangan mendengarkan di latar belakang.
            wantFollowUp = false
            if (phase == Phase.LISTENING) stopListening()
        }
    }

    fun onPermissionsResult(micGranted: Boolean) {
        if (micGranted && settings.autoListen && !autoListenDone) {
            autoListenDone = true
            startListening()
        }
    }

    /** Dari tile Quick Settings, widget, atau pintasan ikon. */
    fun requestListen() {
        autoListenDone = true
        if (onboarded) startListening()
    }

    fun finishOnboarding(apiKey: String) {
        if (apiKey.isNotBlank()) settings.apiKey = apiKey
        settings.onboarded = true
        onboarded = true
        apiKeyMissing = settings.apiKey.isBlank()
    }

    // ---- ASR --------------------------------------------------------------------------

    fun startListening(followUp: Boolean = false) {
        job?.let { if (it.isActive && phase == Phase.THINKING) return }
        tts.stop()
        wantFollowUp = false
        partial = ""
        level = 0f
        followUpListening = followUp
        phase = Phase.LISTENING
        val offline = settings.alwaysOffline || !connectivity.online
        asr.start(preferOnDevice = offline)
    }

    fun stopListening() {
        asr.stop()
        if (phase == Phase.LISTENING) phase = Phase.IDLE
        partial = ""
        level = 0f
        followUpListening = false
    }

    override fun onReady() {}

    override fun onLevel(rmsDb: Float) {
        level = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
    }

    override fun onPartial(text: String) {
        partial = text
    }

    override fun onFinal(text: String) {
        partial = ""
        level = 0f
        phase = Phase.IDLE
        followUpListening = false
        retriedOnline = false
        submit(text, byVoice = true)
    }

    override fun onAsrError(message: String, code: Int) {
        val wasFollowUp = followUpListening
        partial = ""
        level = 0f
        phase = Phase.IDLE
        followUpListening = false
        val silence = code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        // Pengenal on-device gagal padahal internet ada (mis. jaringan baru tervalidasi saat
        // aplikasi dibuka): ulangi sekali dengan pengenal default.
        if (!silence && asr.lastModeOnDevice && connectivity.online && !settings.alwaysOffline && !retriedOnline) {
            retriedOnline = true
            startListening(wasFollowUp)
            return
        }
        retriedOnline = false
        if (wasFollowUp && silence) return // lanjutan otomatis, pengguna diam: selesai dengan tenang
        if (code == SpeechRecognizer.ERROR_CLIENT) return // dibatalkan oleh kita sendiri
        showNotice(message)
    }

    val asrInfo: String
        get() = buildString {
            append(if (asr.isAvailable) "pengenal suara ada" else "TIDAK ada pengenal suara")
            append(if (asr.isOnDeviceAvailable) ", on-device tersedia" else ", on-device tidak tersedia")
        }
    val ttsInfo: String get() = "TTS: ${tts.status}" + (tts.voiceName?.let { " · $it" } ?: "")
    val lastAsrOnDevice: Boolean get() = asr.lastModeOnDevice

    // ---- alur utama -------------------------------------------------------------------

    fun submit(raw: String, byVoice: Boolean = false) {
        val text = raw.trim()
        if (text.isEmpty()) return
        lastInputVoice = byVoice
        add(Role.USER, text)
        job?.cancel()
        job = viewModelScope.launch { handle(text) }
    }

    private suspend fun handle(text: String) {
        val norm = normalizer.normalize(text)
        pending?.let { p ->
            pending = null
            pendingPrompt = null
            when (norm) {
                in YES -> return reply(p.run(), "perintah")
                in NO -> return reply(ToolOutcome.Done("Dibatalkan."), "perintah")
                else -> {} // ucapan baru: lupakan konfirmasi lama
            }
        }
        val r = router ?: return answerChat(text)
        val route = r.route(text)
        when (route.kind) {
            Route.Kind.CHAT -> answerChat(text)
            Route.Kind.AMBIGUOUS -> reply(
                ToolOutcome.Done("Maksudnya yang mana: ${route.candidates.joinToString(" atau ")}?"), "perintah",
                followUp = lastInputVoice,
            )
            Route.Kind.COMMAND -> runCommand(route)
        }
    }

    private suspend fun runCommand(route: Route) {
        val intent = route.intent!!
        when (intent.id) {
            "cancel" -> {
                tts.stop()
                phase = Phase.IDLE
                return reply(ToolOutcome.Done("Oke, batal."), "perintah")
            }
            "stop_listening" -> return reply(ToolOutcome.Done("Baik. Sampai jumpa."), "perintah")
            "repeat" -> return reply(
                ToolOutcome.Done(lastSpoken.ifBlank { "Belum ada yang saya ucapkan." }), "perintah",
                followUp = lastInputVoice && settings.conversationMode,
            )
        }
        if (intent.needsNetwork && (!connectivity.online || settings.alwaysOffline) && intent.id in NETWORK_ONLY) {
            return reply(ToolOutcome.Done("Perintah ini butuh internet, dan sekarang sedang offline."), "perintah")
        }
        phase = Phase.THINKING
        val out = try {
            tools.execute(intent.id, route.slots, route.normalized)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolOutcome.Done("Perintah gagal: ${e.message ?: e.javaClass.simpleName}")
        }
        when (out) {
            is ToolOutcome.Done -> reply(out, "perintah")
            is ToolOutcome.Confirm -> {
                pending = out
                pendingPrompt = out.prompt
                // Pertanyaan konfirmasi: bila pengguna tadi bicara, langsung dengarkan jawabannya.
                reply(ToolOutcome.Done(out.prompt + " Katakan ya atau batal."), "perintah", followUp = lastInputVoice)
            }
        }
    }

    fun confirmPending(yes: Boolean) {
        val p = pending ?: return
        pending = null
        pendingPrompt = null
        stopListening()
        viewModelScope.launch {
            add(Role.USER, if (yes) "Ya" else "Batal")
            reply(if (yes) p.run() else ToolOutcome.Done("Dibatalkan."), "perintah")
        }
    }

    /** Tombol berhenti: hentikan apa pun yang sedang terjadi. */
    fun stopAll() {
        wantFollowUp = false
        job?.cancel()
        gemini.abort()
        tts.stop()
        stopListening()
        phase = Phase.IDLE
        persist()
    }

    // ---- chat: online dulu, offline bila gagal (docs/architecture.md §4.10) -----------

    private fun offlineReason(): String? = when {
        settings.alwaysOffline -> "mode selalu offline aktif"
        !connectivity.online -> "tidak ada internet"
        settings.apiKey.isBlank() -> "API key Gemini belum diisi (Pengaturan)"
        breaker.isOpen -> "Gemini diistirahatkan ${breaker.secondsLeft} detik setelah galat/kuota"
        else -> null
    }

    private suspend fun answerChat(text: String) {
        offlineReason()?.let { return replyOffline(it) }
        phase = Phase.THINKING
        val raw = messages.filter { it.text.isNotBlank() }.dropLast(1).takeLast(10)
            .map { Turn(if (it.role == Role.USER) "user" else "model", it.text) } + Turn("user", text)
        // Gemini menolak giliran berperan sama berturut-turut → gabungkan; mulai dari "user".
        val history = ArrayList<Turn>()
        for (t in raw.dropWhile { it.role != "user" }) {
            val last = history.lastOrNull()
            if (last != null && last.role == t.role) history[history.size - 1] = Turn(t.role, last.text + "\n" + t.text) else history += t
        }
        val msgId = add(Role.ASSISTANT, "", "online")
        val sb = StringBuilder()
        try {
            gemini.stream(settings.apiKey, settings.model, SYSTEM_PROMPT, history, settings.grounding).collect { delta ->
                sb.append(delta)
                update(msgId, sb.toString())
            }
            breaker.success()
            if (sb.isBlank()) {
                update(msgId, "(Gemini tidak memberi jawaban)")
                phase = Phase.IDLE
            } else {
                speak(cleanForSpeech(sb.toString()), followUp = lastInputVoice && settings.conversationMode)
            }
            persist()
        } catch (e: CancellationException) {
            if (sb.isBlank()) messages.removeAll { it.id == msgId } else update(msgId, "$sb …")
            persist()
            throw e
        } catch (e: GeminiException) {
            if (e.httpCode == 429) breaker.openFor((e.retryAfterSec ?: 60L) * 1000) else breaker.failure()
            onlineFailed(msgId, sb, "Gemini ${e.httpCode}: ${e.message}")
        } catch (e: Exception) {
            breaker.failure()
            onlineFailed(msgId, sb, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun onlineFailed(msgId: Long, partialAnswer: StringBuilder, reason: String) {
        if (partialAnswer.isNotBlank()) {
            update(msgId, "$partialAnswer … (terputus)")
        } else {
            messages.removeAll { it.id == msgId }
        }
        replyOffline(reason)
    }

    private fun replyOffline(reason: String) {
        // v0.x belum membawa LLM offline (ADR 0003: Gemma 4 E2B menyusul di Fase 3).
        val spoken = "Maaf, saya belum bisa menjawab pertanyaan saat offline. Perintah seperti buka aplikasi, timer, atau senter tetap bisa."
        add(Role.ASSISTANT, "$spoken\n\nAlasan: $reason", "offline")
        persist()
        speak(spoken)
    }

    // ---- util -------------------------------------------------------------------------

    private fun reply(out: ToolOutcome.Done, origin: String, followUp: Boolean = false) {
        add(Role.ASSISTANT, out.display, origin)
        persist()
        speak(out.spoken, followUp)
    }

    private fun speak(text: String, followUp: Boolean = false) {
        lastSpoken = text
        wantFollowUp = followUp
        if (!settings.speakReplies) {
            phase = Phase.IDLE
            if (followUp) main.postDelayed({ onSpeechDone(force = true) }, 300)
            return
        }
        phase = Phase.SPEAKING
        tts.speak(text)
    }

    private fun onSpeechDone(force: Boolean = false) {
        if (phase != Phase.SPEAKING && !force) return
        phase = Phase.IDLE
        if (wantFollowUp && foreground) {
            wantFollowUp = false
            startListening(followUp = true)
        }
        wantFollowUp = false
    }

    fun showNotice(text: String) {
        notice = text
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(3500)
            notice = null
        }
    }

    private fun add(role: Role, text: String, origin: String = ""): Long {
        val id = ids.incrementAndGet()
        messages += ChatMessage(id, role, text, origin)
        return id
    }

    private fun update(id: Long, text: String) {
        val i = messages.indexOfFirst { it.id == id }
        if (i >= 0) messages[i] = messages[i].copy(text = text)
    }

    private fun persist() {
        val snapshot = messages.filter { it.text.isNotBlank() }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                store.save(snapshot)
            } catch (_: Exception) {
            }
        }
    }

    fun clearHistory() {
        stopAll()
        messages.clear()
        pending = null
        pendingPrompt = null
        viewModelScope.launch(Dispatchers.IO) { store.clear() }
    }

    fun saveSettings(
        apiKey: String, model: String, grounding: Boolean, alwaysOffline: Boolean, city: String,
        autoListen: Boolean, speak: Boolean, conversation: Boolean,
    ) {
        settings.apiKey = apiKey
        settings.model = model
        settings.grounding = grounding
        settings.alwaysOffline = alwaysOffline
        settings.city = city
        settings.autoListen = autoListen
        settings.speakReplies = speak
        settings.conversationMode = conversation
        apiKeyMissing = settings.apiKey.isBlank()
        breaker.success()
    }

    fun testTts() = speak("Halo, ini suara Rbit Asisten. Sekarang jam berapa? Coba tanyakan saja.")

    override fun onCleared() {
        asr.stop()
        tts.shutdown()
        connectivity.close()
    }

    companion object {
        private val YES = setOf("ya", "iya", "yes", "oke", "ok", "lanjut", "betul", "benar", "boleh", "kirim", "telepon", "ya kirim", "ya telepon")
        private val NO = setOf("tidak", "jangan", "batal", "batalkan", "no", "cancel", "stop", "tidak jadi", "enggak")
        private val NETWORK_ONLY = setOf("weather", "search_web")

        private const val SYSTEM_PROMPT =
            "Kamu adalah Rbit, asisten suara berbahasa Indonesia di ponsel Android pengguna. " +
                "Jawabanmu akan DIUCAPKAN oleh TTS, jadi: jawab singkat (1–3 kalimat kecuali diminta rinci), " +
                "tanpa markdown, tanpa daftar berpoin, tanpa emoji, tulis angka seperti diucapkan bila perlu. " +
                "Jika pertanyaan butuh data terkini dan kamu tidak yakin, katakan terus terang."

        fun cleanForSpeech(s: String): String =
            s.replace(Regex("[*_#`>]+"), "").replace(Regex("\\[(.*?)]\\((.*?)\\)"), "$1").replace(Regex("\\s+"), " ").trim()
    }
}
