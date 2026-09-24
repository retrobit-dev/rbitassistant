package dev.retrobit.assistant.router

import java.io.File

/**
 * Menjalankan kontrak testdata/golden_intents.json terhadap router Kotlin — pemeriksaan
 * yang sama dengan run_golden() di tools/intent_lab.py. Dipisah dari JUnit supaya bisa
 * dijalankan juga tanpa Gradle (kotlinc + main).
 *
 * JSON dibaca dengan SnakeYAML (JSON adalah YAML yang sah) agar tidak butuh pustaka JSON.
 */
object RepoFiles {
    val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "intents/_normalizer.yaml").exists() }
        ?: error("akar repo (berisi intents/_normalizer.yaml) tidak ditemukan dari ${File("").absolutePath}")

    fun text(rel: String): String = File(root, rel).readText(Charsets.UTF_8)

    fun catalogFiles(): List<Pair<String, String>> =
        File(root, "intents").listFiles()!!.filter { it.name.endsWith(".yaml") }.map { it.name to it.readText(Charsets.UTF_8) }

    fun router(): Router {
        val norm = Normalizer.fromYaml(text("intents/_normalizer.yaml"))
        return Router(norm, loadCatalog(catalogFiles()), FixtureResolver(norm, text("testdata/fixtures/device.yaml")))
    }
}

/** Resolver persis-sama seperti kelas Device di intent_lab.py (nama/alias setelah normalisasi). */
class FixtureResolver(norm: Normalizer, deviceYaml: String) : EntityResolver {
    private val apps: Map<String, String>
    private val contacts: Map<String, String>

    init {
        val d = loadYaml(deviceYaml) as Map<*, *>
        fun index(items: Any?): Map<String, String> {
            val idx = LinkedHashMap<String, String>()
            for (it in items as List<*>) {
                val m = it as Map<*, *>
                val name = m["name"].toString()
                for (alias in listOf(name) + (m["aliases"] as? List<*>).orEmpty().map { a -> a.toString() }) {
                    idx[norm.normalize(alias)] = name
                }
            }
            return idx
        }
        apps = index(d["installed_apps"])
        contacts = index(d["contacts"])
    }

    override fun app(raw: String): String? = apps[raw]
    override fun contact(raw: String): String? = contacts[raw]
}

class GoldenReport {
    var passed = 0
    var knownGaps = 0
    var chatCases = 0
    var examplesChecked = 0
    val failures = ArrayList<String>()
    val perIntent = HashMap<String, Int>()

    fun summary(intents: Int) = (if (failures.isEmpty()) "LULUS" else "GAGAL") +
        " — $passed kasus golden lulus ($chatCases jebakan chat), $knownGaps celah yang diketahui, " +
        "$examplesChecked contoh YAML, $intents intent."
}

fun runGolden(router: Router): GoldenReport {
    val rep = GoldenReport()
    val ids = router.catalog.map { it.id }.toSet()
    val data = loadYaml(RepoFiles.text("testdata/golden_intents.json")) as Map<*, *>
    val seen = HashSet<String>()
    for ((idx, c) in (data["cases"] as List<*>).withIndex()) {
        val case = c as Map<*, *>
        val utt = case["text"].toString()
        val expect = case["expect"].toString()
        val tag = "golden #${idx + 1} '$utt'"
        if (!seen.add(utt)) rep.failures += "$tag: duplikat"
        if (expect != "chat" && expect !in ids) {
            rep.failures += "$tag: intent '$expect' tidak ada di katalog"
            continue
        }
        val want = (case["slots"] as? Map<*, *>).orEmpty().entries.associate { it.key.toString() to it.value.toString() }
        val r = router.route(utt)
        val got = if (r.kind == Route.Kind.COMMAND) r.intent!!.id else r.kind.name.lowercase()
        val gotSlots = r.slots.mapValues { it.value.toString() }
        val ok = got == expect && (expect == "chat" || gotSlots == want)
        if (case["known_gap"] == true) {
            if (ok) rep.failures += "$tag: ditandai known_gap tetapi sekarang LULUS" else rep.knownGaps++
            continue
        }
        if (ok) {
            rep.passed++
            if (expect == "chat") rep.chatCases++ else rep.perIntent[expect] = (rep.perIntent[expect] ?: 0) + 1
        } else {
            var detail = "dapat $got"
            if (r.kind == Route.Kind.COMMAND) detail += " $gotSlots via pola '${r.pattern}'"
            if (r.kind == Route.Kind.AMBIGUOUS) detail += " ${r.candidates}"
            rep.failures += "$tag -> normal '${r.normalized}': harap $expect $want, $detail"
        }
    }
    for (id in ids.sorted()) {
        val n = rep.perIntent[id] ?: 0
        if (n < 3) rep.failures += "intent '$id': hanya $n kasus golden lulus, minimal 3"
    }
    if (rep.chatCases < 15) rep.failures += "hanya ${rep.chatCases} kasus 'chat' (jebakan), minimal 15"
    for (intent in router.catalog) {
        for (ex in intent.examples) {
            rep.examplesChecked++
            val r = router.route(ex)
            if (!(r.kind == Route.Kind.COMMAND && r.intent!!.id == intent.id)) {
                rep.failures += "${intent.file}: contoh '$ex' terarah ke ${r.intent?.id ?: r.kind}, bukan ${intent.id}"
            }
        }
    }
    return rep
}
