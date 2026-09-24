package dev.retrobit.assistant.device

/** 1 − jarak Levenshtein / panjang terpanjang. 1.0 = sama persis. */
fun similarity(a: String, b: String): Double {
    if (a == b) return 1.0
    if (a.isEmpty() || b.isEmpty()) return 0.0
    var prev = IntArray(b.length + 1) { it }
    var cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
        cur[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
        }
        val t = prev; prev = cur; cur = t
    }
    return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
}
