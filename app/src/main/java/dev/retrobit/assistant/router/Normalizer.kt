package dev.retrobit.assistant.router

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.util.Locale

/** Katalog intent tidak valid. Pesan berisi berkas dan alasannya. */
class CatalogError(message: String) : Exception(message)

/** YAML 1.1 (SnakeYAML) — sama dengan PyYAML yang dipakai tools/intent_lab.py. */
internal fun loadYaml(text: String): Any? = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(text)

/**
 * Tingkat 1: normalisasi. Port langsung dari `Normalizer` di tools/intent_lab.py;
 * perilakunya dikunci oleh testdata/golden_intents.json (RouterGoldenTest).
 */
class Normalizer(
    abbreviations: Map<String, String>,
    prefixFillers: List<String>,
    suffixFillers: List<String>,
    private val units: Map<String, Int>,
    private val multipliers: Map<String, Int>,
) {
    // Frasa terpanjang dulu. sortedByDescending stabil, sama seperti sorted() Python.
    private val abbreviationRules: List<Pair<Regex, String>> =
        abbreviations.keys.sortedByDescending { it.split(" ").size }.map { key ->
            Regex("(?<!\\S)" + Regex.escape(key) + "(?!\\S)") to Regex.escapeReplacement(abbreviations.getValue(key))
        }
    private val prefixFillers = prefixFillers.sortedByDescending { it.split(" ").size }
    private val suffixFillers = suffixFillers.sortedByDescending { it.split(" ").size }

    fun normalize(text: String): String {
        var s = text.lowercase(Locale.ROOT)
        s = s.replace("%", " persen ")
        s = CLOCK.replace(s, "$1:$2")
        s = NON_WORD.replace(s, " ")
        s = s.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }.joinToString(" ")
        for ((rx, rep) in abbreviationRules) s = rx.replace(s, rep)
        s = numbers(s)
        s = stripFillers(s)
        return s
    }

    private fun numbers(s: String): String {
        val words = s.split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var i = 0
        while (i < words.size) {
            val first = units[words[i]]
            if (first == null) {
                out += words[i]
                i++
                continue
            }
            var j = i + 1
            var prevSmall = first < 100
            while (j < words.size) {
                val w = words[j]
                if (w in multipliers) {
                    prevSmall = false
                } else if (w in units && !prevSmall) {
                    prevSmall = units.getValue(w) < 100
                } else {
                    break
                }
                j++
            }
            out += parseNumber(words.subList(i, j)).toString()
            i = j
        }
        return out.joinToString(" ")
    }

    private fun parseNumber(words: List<String>): Int {
        var total = 0
        var current = 0
        for (w in words) {
            val u = units[w]
            when {
                u != null -> if (u >= 100) {
                    total += current + u
                    current = 0
                } else {
                    current += u
                }
                w == "belas" -> {
                    total += current + 10
                    current = 0
                }
                w == "ribu" -> {
                    total = (total + current) * 1000
                    current = 0
                }
                else -> {
                    total += current * multipliers.getValue(w)
                    current = 0
                }
            }
        }
        return total + current
    }

    private fun stripFillers(input: String): String {
        var s = input
        var changed = true
        while (changed && s.isNotEmpty()) {
            changed = false
            for (f in prefixFillers) {
                if (s == f || s.startsWith("$f ")) {
                    val rest = s.substring(f.length).trim()
                    if (rest.isNotEmpty()) {
                        s = rest
                        changed = true
                        break
                    }
                }
            }
            for (f in suffixFillers) {
                if (s.endsWith(" $f")) {
                    s = s.substring(0, s.length - f.length).trim()
                    changed = true
                    break
                }
            }
        }
        return s
    }

    companion object {
        // Python: \b(\d{1,2})[.:](\d{2})\b — \b ditulis eksplisit agar sama di JVM & ICU Android.
        private val CLOCK = Regex("(?<![\\p{L}\\p{N}_])([0-9]{1,2})[.:]([0-9]{2})(?![\\p{L}\\p{N}_])")
        // Python: [^\w: ]|_  (\w Unicode = huruf + angka + '_')
        private val NON_WORD = Regex("[^\\p{L}\\p{N}: ]")

        fun fromYaml(text: String): Normalizer {
            val d = loadYaml(text) as? Map<*, *> ?: throw CatalogError("_normalizer.yaml: harus mapping")
            fun strMap(x: Any?): Map<String, String> =
                (x as Map<*, *>).entries.associate { it.key.toString() to it.value.toString() }
            fun intMap(x: Any?): Map<String, Int> =
                (x as Map<*, *>).entries.associate { it.key.toString() to (it.value as Number).toInt() }
            val numbers = d["numbers"] as Map<*, *>
            return Normalizer(
                abbreviations = strMap(d["abbreviations"]),
                prefixFillers = (d["prefix_fillers"] as List<*>).map { it.toString() },
                suffixFillers = (d["suffix_fillers"] as List<*>).map { it.toString() },
                units = intMap(numbers["units"]),
                multipliers = intMap(numbers["multipliers"]),
            )
        }
    }
}
