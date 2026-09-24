package dev.retrobit.assistant.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterGoldenTest {
    private val router = RepoFiles.router()

    @Test
    fun goldenContract() {
        val rep = runGolden(router)
        println(rep.summary(router.catalog.size))
        assertTrue(rep.failures.joinToString("\n", prefix = "\n"), rep.failures.isEmpty())
    }

    @Test
    fun normalizerMatchesPython() {
        val n = router.normalizer
        assertEquals("buka whatsapp", n.normalize("Tolong buka WA dong!"))
        assertEquals("timer 25 menit", n.normalize("timer dua puluh lima menit ya"))
        assertEquals("alarm jam 7:30", n.normalize("alarm jam 7.30"))
        assertEquals("volume 50 persen", n.normalize("volume 50%"))
    }
}
