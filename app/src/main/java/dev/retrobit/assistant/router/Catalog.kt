package dev.retrobit.assistant.router

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Tingkat 2: bahasa pola (lihat docs/intents.md). Port dari tools/intent_lab.py.
 *
 *   kata      literal
 *   {slot}    menangkap slot
 *   (a|b c)   alternatif
 *   [ ... ]   opsional
 */
private val TOKEN_RE: Pattern = Pattern.compile("\\s*(\\{[a-z_]+\\}|[()\\[\\]|]|[^\\s()\\[\\]|{}]+)")
private val SLOT_NAME = Regex("[a-z][a-z0-9]*")

internal fun tokenize(pattern: String): List<String> {
    val p = pattern.trim()
    val m = TOKEN_RE.matcher(p)
    val out = ArrayList<String>()
    var pos = 0
    while (pos < p.length) {
        m.region(pos, p.length)
        if (!m.lookingAt() || m.end() == pos) throw CatalogError("pola tidak bisa diurai di posisi $pos: '$pattern'")
        out += m.group(1)!!
        pos = m.end()
    }
    return out
}

class CompiledPattern(val source: String, val regex: Pattern, val slots: List<String>)

private class PatternCompiler(
    private val t: List<String>,
    private val slotRegex: Map<String, String>,
    private val source: String,
) {
    private var i = 0
    private val seen = ArrayList<String>()

    fun compile(): CompiledPattern {
        val body = seq(emptySet())
        if (i != t.size) throw CatalogError("token berlebih '${t[i]}' di pola '$source'")
        return CompiledPattern(source, Pattern.compile("^" + body + "$"), seen.toList())
    }

    private fun seq(stop: Set<String>): String {
        val sb = StringBuilder()
        var n = 0
        while (i < t.size && t[i] !in stop) {
            sb.append(item())
            n++
        }
        if (n == 0) throw CatalogError("urutan kosong di pola '$source'")
        return sb.toString()
    }

    private fun alternatives(close: String): String {
        val parts = arrayListOf(seq(setOf("|", close)))
        while (i < t.size && t[i] == "|") {
            i++
            parts += seq(setOf("|", close))
        }
        expect(close)
        return parts.joinToString("|")
    }

    private fun item(): String {
        val tok = t[i]
        i++
        return when {
            tok == "(" -> "(?:" + alternatives(")") + ")"
            tok == "[" -> "(?:" + alternatives("]") + ")?"
            tok == ")" || tok == "]" || tok == "|" -> throw CatalogError("'$tok' tanpa pasangan di pola '$source'")
            tok.startsWith("{") -> {
                val name = tok.substring(1, tok.length - 1)
                val rx = slotRegex[name] ?: throw CatalogError("slot '{$name}' tidak dideklarasikan (pola '$source')")
                if (name in seen) throw CatalogError("slot '{$name}' muncul dua kali di pola '$source'")
                seen += name
                " (?<$name>$rx)"
            }
            else -> " " + Pattern.quote(tok)
        }
    }

    private fun expect(tok: String) {
        if (i >= t.size || t[i] != tok) throw CatalogError("kurang '$tok' di pola '$source'")
        i++
    }
}

class Slot(val name: String, val type: String, val values: Map<String, String>, val maxWords: Int?) {
    fun regex(): String = when (type) {
        "number" -> "\\d+"
        "clock" -> "\\d{1,2}(?::\\d{2})?"
        "enum" -> values.keys.sortedByDescending { it.length }.joinToString("|") { Pattern.quote(it) }
        else -> "\\S+(?: \\S+)*?"
    }
}

class IntentSpec(
    val id: String,
    val file: String,
    val slots: Map<String, Slot>,
    val patterns: List<CompiledPattern>,
    val examples: List<String>,
    val action: String,
    val confirmation: String,
    val needsNetwork: Boolean,
)

private val REQUIRED_KEYS = listOf("id", "patterns", "slots", "examples", "action", "confirmation", "needs_network")
private val CONFIRMATION_VALUES = setOf("never", "always")
private val SLOT_TYPES = setOf("app_name", "contact", "number", "clock", "enum", "text")

/**
 * @param files pasangan (nama berkas, isi). Berkas berawalan '_' dilewati; urutan
 *   diurutkan menurut nama, sama seperti sorted(glob) di Python (penting untuk tie-break).
 */
fun loadCatalog(files: List<Pair<String, String>>): List<IntentSpec> {
    val intents = ArrayList<IntentSpec>()
    val seenIds = HashMap<String, String>()
    for ((fileName, text) in files.sortedBy { it.first }) {
        if (fileName.startsWith("_") || !fileName.endsWith(".yaml")) continue
        val rel = "intents/$fileName"
        val data = try {
            loadYaml(text)
        } catch (e: Exception) {
            throw CatalogError("$rel: YAML tidak valid (${e.message})")
        } as? Map<*, *> ?: throw CatalogError("$rel: harus berupa mapping")
        val missing = REQUIRED_KEYS.filter { it !in data }
        if (missing.isNotEmpty()) throw CatalogError("$rel: kunci wajib hilang: ${missing.joinToString(", ")}")
        val id = data["id"].toString()
        if (fileName.removeSuffix(".yaml") != id) throw CatalogError("$rel: nama berkas harus sama dengan id '$id'")
        seenIds[id]?.let { throw CatalogError("$rel: id '$id' sudah dipakai di $it") }
        seenIds[id] = rel
        val confirmation = data["confirmation"]
        if (confirmation !in CONFIRMATION_VALUES) throw CatalogError("$rel: confirmation harus salah satu $CONFIRMATION_VALUES")
        val needsNetwork = data["needs_network"] as? Boolean ?: throw CatalogError("$rel: needs_network harus true/false")

        val slots = LinkedHashMap<String, Slot>()
        for ((k, v) in (data["slots"] as? Map<*, *>).orEmpty()) {
            val name = k as? String
            if (name == null || !SLOT_NAME.matches(name)) {
                throw CatalogError("$rel: nama slot '$k' harus [a-z][a-z0-9]* (named group Java)")
            }
            val spec = v as? Map<*, *> ?: throw CatalogError("$rel: slot '$name' harus mapping")
            val type = spec["type"]
            if (type !in SLOT_TYPES) throw CatalogError("$rel: slot '$name' bertipe '$type', harus salah satu $SLOT_TYPES")
            val values = LinkedHashMap<String, String>()
            if (type == "enum") {
                val pairs: List<Pair<Any?, Any?>> = when (val raw = spec["values"]) {
                    is List<*> -> raw.map { it to it }
                    is Map<*, *> -> raw.entries.map { it.key to it.value }
                    else -> throw CatalogError("$rel: slot enum '$name' butuh 'values' (list atau map)")
                }
                for ((ek, ev) in pairs) {
                    if (ek !is String || ev !is String) {
                        throw CatalogError("$rel: slot enum '$name': nilai $ek: $ev bukan string — beri tanda kutip")
                    }
                    values[ek] = ev
                }
            }
            slots[name] = Slot(name, type as String, values, (spec["max_words"] as? Number)?.toInt())
        }

        val slotRegex = slots.mapValues { it.value.regex() }
        val patterns = (data["patterns"] as List<*>).map { p ->
            val src = p.toString()
            try {
                PatternCompiler(tokenize(src), slotRegex, src).compile()
            } catch (e: CatalogError) {
                throw CatalogError("$rel: ${e.message}")
            } catch (e: PatternSyntaxException) {
                throw CatalogError("$rel: pola '$src' menghasilkan regex tidak valid (${e.description})")
            }
        }
        val used = patterns.flatMap { it.slots }.toSet()
        val unused = slots.keys - used
        if (unused.isNotEmpty()) throw CatalogError("$rel: slot dideklarasikan tapi tidak dipakai pola mana pun: ${unused.sorted()}")
        val examples = (data["examples"] as? List<*>).orEmpty().map { it.toString() }
        if (examples.isEmpty()) throw CatalogError("$rel: minimal satu contoh (examples)")

        intents += IntentSpec(id, rel, slots, patterns, examples, data["action"].toString(), confirmation as String, needsNetwork)
    }
    if (intents.isEmpty()) throw CatalogError("intents/: tidak ada katalog intent")
    return intents
}
