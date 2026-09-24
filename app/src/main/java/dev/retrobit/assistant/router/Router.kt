package dev.retrobit.assistant.router

import java.util.Locale

/** Memetakan teks slot ke nilai kanonik; null = slot tidak valid (pola gagal, lanjut ke chat). */
interface EntityResolver {
    fun app(raw: String): String?
    fun contact(raw: String): String?
}

data class Route(
    val kind: Kind,
    val normalized: String,
    val intent: IntentSpec? = null,
    val slots: Map<String, Any> = emptyMap(),
    val pattern: String? = null,
    val candidates: List<String> = emptyList(),
) {
    enum class Kind { COMMAND, CHAT, AMBIGUOUS }
}

/** Router tingkat 1–2. Tingkat 3 (fuzzy) ada di resolver aplikasi/kontak; tingkat 4 = LLM. */
class Router(
    val normalizer: Normalizer,
    val catalog: List<IntentSpec>,
    private val resolver: EntityResolver,
) {
    fun resolve(slot: Slot, raw: String): Any? {
        if (slot.maxWords != null && raw.split(' ').size > slot.maxWords) return null
        return when (slot.type) {
            "number" -> raw.toIntOrNull()
            "clock" -> {
                val parts = raw.split(":", limit = 2)
                val hh = parts[0].toInt()
                val mm = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toInt() else 0
                if (hh <= 23 && mm <= 59) String.format(Locale.ROOT, "%d:%02d", hh, mm) else null
            }
            "enum" -> slot.values[raw]
            "app_name" -> resolver.app(raw)
            "contact" -> resolver.contact(raw)
            else -> raw
        }
    }

    private class Hit(val literal: Int, val intent: IntentSpec, val values: Map<String, Any>, val source: String)

    fun route(utterance: String): Route {
        val text = normalizer.normalize(utterance)
        val subject = " $text"
        val hits = ArrayList<Hit>()
        for (intent in catalog) {
            var best: Hit? = null
            for (cp in intent.patterns) {
                val m = cp.regex.matcher(subject)
                if (!m.matches()) continue
                val values = LinkedHashMap<String, Any>()
                var captured = 0
                var ok = true
                for (name in cp.slots) {
                    val raw = m.group(name) ?: continue
                    val v = resolve(intent.slots.getValue(name), raw)
                    if (v == null) {
                        ok = false
                        break
                    }
                    values[name] = v
                    captured += raw.length
                }
                if (!ok) continue
                val literal = text.length - captured
                val b = best
                if (b == null || literal > b.literal) best = Hit(literal, intent, values, cp.source)
            }
            best?.let { hits += it }
        }
        if (hits.isEmpty()) return Route(Route.Kind.CHAT, text)
        val sorted = hits.sortedByDescending { it.literal }
        val top = sorted.filter { it.literal == sorted[0].literal }
        if (top.size > 1) {
            return Route(Route.Kind.AMBIGUOUS, text, candidates = top.map { it.intent.id }.sorted())
        }
        val h = top[0]
        return Route(Route.Kind.COMMAND, text, h.intent, h.values, h.source)
    }
}
