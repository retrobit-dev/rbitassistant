package dev.retrobit.assistant.device

import android.content.Context
import android.content.Intent
import dev.retrobit.assistant.router.Normalizer

/**
 * Aplikasi yang bisa dibuka (punya ikon launcher). Label dinormalisasi dengan
 * normalizer yang sama dengan ucapan, lalu dicocokkan: persis → alias → satu kata
 * unik → fuzzy (tingkat 3, ambang 0,8).
 */
class AppDirectory(private val context: Context, private val norm: Normalizer) {
    data class App(val label: String, val pkg: String)

    private var cache: List<Pair<String, App>> = emptyList()
    private var loadedAt = 0L

    private fun apps(): List<Pair<String, App>> {
        val now = System.currentTimeMillis()
        if (cache.isEmpty() || now - loadedAt > 60_000) {
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            cache = pm.queryIntentActivities(launcher, 0)
                .map { ri -> App(ri.loadLabel(pm).toString(), ri.activityInfo.packageName) }
                .filter { it.pkg != context.packageName }
                .distinctBy { it.pkg }
                .map { norm.normalize(it.label) to it }
            loadedAt = now
        }
        return cache
    }

    val count: Int get() = apps().size

    fun resolve(raw: String): App? {
        val q = ALIASES[raw] ?: raw
        val all = apps()
        all.firstOrNull { it.first == q }?.let { return it.second }
        if (!q.contains(' ')) {
            val byWord = all.filter { q in it.first.split(' ') }
            if (byWord.size == 1) return byWord[0].second
        }
        val best = all.maxByOrNull { similarity(it.first, q) } ?: return null
        return if (similarity(best.first, q) >= 0.8) best.second else null
    }

    fun byLabel(label: String): App? = apps().firstOrNull { it.second.label == label }?.second

    companion object {
        // Singkatan/salah dengar umum → label (setelah normalisasi). "wa" sudah diurus normalizer.
        private val ALIASES = mapOf(
            "ig" to "instagram",
            "yt" to "youtube",
            "yutub" to "youtube",
            "fb" to "facebook",
            "ml" to "mobile legends",
            "tik tok" to "tiktok",
            "peta" to "maps",
            "camera" to "kamera",
            "gallery" to "galeri",
            "foto" to "galeri",
            "calculator" to "kalkulator",
            "setting" to "pengaturan",
            "settings" to "pengaturan",
        )
    }
}
