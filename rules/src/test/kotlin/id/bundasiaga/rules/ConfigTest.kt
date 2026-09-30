package id.bundasiaga.rules

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigTest {
    private val config = RuleConfigLoader.loadDefaultHamil()

    @Test
    fun everyRuleIsDraftAndUnverified() {
        assertEquals("DRAFT_NEEDS_CLINICAL_REVIEW", config.status)
        config.rules.forEach {
            assertEquals("DRAFT_NEEDS_CLINICAL_REVIEW", it.status, it.id)
            assertEquals(false, it.verified, it.id)
        }
        assertEquals("needs_review", config.stabilization.status)
    }

    @Test
    fun everySignHasAQuestionInSomeFase() {
        val nifas = RuleConfigLoader.loadDefault(Fase.NIFAS)
        Tanda.entries.forEach {
            assertTrue(config.question(it.name) != null || nifas.question(it.name) != null, "tidak ada pertanyaan untuk $it")
        }
        assertTrue(config.question("GJN") != null)
        assertTrue(nifas.question("GJN") == null)
    }

    @Test
    fun bothShippedConfigsPassTheSelfTest() {
        for (fase in Fase.entries) {
            val cfg = RuleConfigLoader.loadDefault(fase)
            assertEquals(fase, cfg.fase)
            assertTrue(cfg.seq >= 1, "seq $fase")
            assertEquals(emptyList(), RuleSelfTest.check(cfg, iterations = 20_000), "$fase")
        }
    }

    @Test
    fun nifasIsDraftAndUnverified() {
        val n = RuleConfigLoader.loadDefault(Fase.NIFAS)
        assertEquals("DRAFT_NEEDS_CLINICAL_REVIEW", n.status)
        n.rules.forEach { assertEquals(false, it.verified, it.id); assertEquals("DRAFT_NEEDS_CLINICAL_REVIEW", it.status, it.id) }
        assertEquals("needs_review", n.stabilization.status)
    }

    private fun mutate(fase: Fase, from: String, to: String): String {
        val text = RuleConfigLoader.defaultText(fase)
        assertTrue(from in text, "teks contoh '$from' tidak ditemukan")
        return text.replace(from, to)
    }

    @Test
    fun rejectsConfigsThatWouldSilentlyMisbehave() {
        // Aturan pada tanda yang tidak pernah ditanyakan fase ini tidak akan pernah terpicu.
        assertFailsWith<RuleConfigException> { RuleConfigLoader.parse(mutate(Fase.NIFAS, """"yes": "PMD" }""", """"yes": "KTB" }""")) }
        // Operator khusus kehamilan di NIFAS.
        assertFailsWith<RuleConfigException> {
            RuleConfigLoader.parse(mutate(Fase.NIFAS, """{ "notBpBelow": [140, 90] }""", """{ "gaAtLeast": 20, "orUnknown": true }"""))
        }
        // Kode pertanyaan tidak dikenal.
        assertFailsWith<RuleConfigException> { RuleConfigLoader.parse(mutate(Fase.NIFAS, """"code": "PYD"""", """"code": "XYZ"""")) }
        // Membutuhkan mesin yang lebih baru dari aplikasi ini.
        assertFailsWith<RuleConfigException> { RuleConfigLoader.parse(mutate(Fase.NIFAS, """"engine": 2""", """"engine": 99""")) }
        // Jumlah perdarahan tanpa pertanyaan jumlahnya.
        assertFailsWith<RuleConfigException> {
            RuleConfigLoader.parse(mutate(Fase.HAMIL, """"critical": true, "bleedingAmount": true""", """"critical": true"""))
        }
    }

    @Test
    fun specCriticalQuestions() {
        val engine = RuleEngine(config)
        val critical30 = config.questions.filter { engine.isCritical(it, 30) }.map { it.code }.toSet()
        assertEquals(setOf("KEJ", "KSD", "PDR", "SKH", "PDG", "SSK", "NPT", "KTB", "GJN"), critical30)
        val critical12 = config.questions.filter { engine.isCritical(it, 12) }.map { it.code }.toSet()
        assertEquals(critical30 - "GJN", critical12)
        assertTrue(config.questions.first { it.code == "GJN" }.let { engine.isCritical(it, null) })
    }

    @Test
    fun stabilizationHasNoDrugDoses() {
        val text = (config.stabilization.steps + config.stabilization.bidanReminder).joinToString(" ").lowercase()
        listOf("mg", "dosis", "suntik", "injeksi", "infus", "tablet", "obat").forEach {
            assertTrue(Regex("\\b$it\\b").find(text) == null, "panduan stabilisasi menyebut '$it'")
        }
        val nifas = RuleConfigLoader.loadDefault(Fase.NIFAS).stabilization
        val t2 = (nifas.steps + nifas.bidanReminder).joinToString(" ").lowercase()
        listOf("mg", "dosis", "suntik", "injeksi", "infus", "tablet", "obat", "pijat").forEach {
            assertTrue(Regex("\\b$it\\b").find(t2) == null, "panduan stabilisasi nifas menyebut '$it'")
        }
    }

    @Test
    fun rejectsUnknownOperatorAndHijauRules() {
        assertFailsWith<RuleConfigException> { RuleConfigLoader.parseCondition(kotlinx.serialization.json.Json.parseToJsonElement("""{"nope":1}""")) }
        assertFailsWith<RuleConfigException> { RuleConfigLoader.parseCondition(kotlinx.serialization.json.Json.parseToJsonElement("""{"yes":"XYZ"}""")) }
    }
}
