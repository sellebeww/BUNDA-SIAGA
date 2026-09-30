package id.bundasiaga.rules

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Kasus emas untuk konfigurasi nifas (NIFAS). Gaya tabel sama seperti
 * [GoldenCasesTest]: setiap tanda yang ditanyakan dan tidak tercantum di `ya` atau `belum` adalah TIDAK.
 * Kasus rekayasa sebagai ilustrasi, bukan hasil yang sudah divalidasi secara klinis.
 */
class NifasGoldenTest {
    private val config = RuleConfigLoader.loadDefault(Fase.NIFAS)
    private val engine = RuleEngine(config)

    private data class G(
        val id: String, val desc: String, val hari: Int?, val td: String,
        val ya: String = "", val belum: String = "", val expect: Level, val rules: String,
    )

    private val cases = listOf(
        G("N01", "Semua dijawab, TD normal", 5, "110/70", expect = Level.HIJAU, rules = ""),
        G("N02", "Kader tanpa tensi, semua TIDAK", 3, "ALAT", expect = Level.HIJAU, rules = ""),
        G("N03", "Perdarahan banyak hari ke-1", 1, "ALAT", ya = "PDR", expect = Level.MERAH, rules = "N-M03"),
        G("N04", "Kejang", 4, "BELUM", ya = "KEJ", expect = Level.MERAH, rules = "N-M01"),
        G("N05", "Sakit kepala hebat tanpa tensi", 6, "ALAT", ya = "SKH", expect = Level.MERAH, rules = "N-M06"),
        G("N06", "Sakit kepala hebat, TD normal", 6, "120/80", ya = "SKH", expect = Level.KUNING, rules = "N-M06K"),
        G("N07", "TD 165/112", 2, "165/112", expect = Level.MERAH, rules = "N-M04 N-K01"),
        G("N08", "TD 146/94 tanpa gejala", 10, "146/94", expect = Level.KUNING, rules = "N-K01"),
        G("N09", "TD 146/94 + bengkak", 10, "146/94", ya = "BKK", expect = Level.MERAH, rules = "N-M07 N-K01 N-K02"),
        G("N10", "Demam saja", 4, "ALAT", ya = "DMM", expect = Level.KUNING, rules = "N-K03"),
        G("N11", "Demam + cairan berbau", 4, "ALAT", ya = "DMM CRB", expect = Level.MERAH, rules = "N-M10 N-K03 N-K04"),
        G("N12", "Cairan berbau saja", 8, "ALAT", ya = "CRB", expect = Level.KUNING, rules = "N-K04"),
        G("N13", "Payudara bengkak merah nyeri", 12, "ALAT", ya = "PYD", expect = Level.KUNING, rules = "N-K05"),
        G("N14", "Nyeri betis satu sisi", 9, "ALAT", ya = "BTS", expect = Level.KUNING, rules = "N-K06"),
        G("N15", "Sedih/murung", 20, "ALAT", ya = "SDH", expect = Level.KUNING, rules = "N-K07"),
        G("N16", "Pikiran menyakiti diri/bayi", 20, "ALAT", ya = "PMD", expect = Level.MERAH, rules = "N-M11"),
        G("N17", "Menggigil saja (jaring pengaman)", 3, "ALAT", ya = "MGL", expect = Level.KUNING, rules = "N-U02"),
        G("N18", "Demam belum ditanyakan", 3, "ALAT", belum = "DMM", expect = Level.KUNING, rules = "N-U01"),
        G("N19", "Semua belum diperiksa: KUNING, bukan MERAH", null, "BELUM", belum = "ALL", expect = Level.KUNING, rules = "N-U01"),
        G("N20", "Non-kritis belum diperiksa tetap HIJAU", 7, "ALAT", belum = "MGL CRB BKK NPU PYD BTS SDH PMD", expect = Level.HIJAU, rules = ""),
        G("N21", "Sesak napas", 2, "ALAT", ya = "SSK", expect = Level.MERAH, rules = "N-M08"),
        G("N22", "Hari tidak diketahui, nyeri perut hebat", null, "ALAT", ya = "NPT", expect = Level.MERAH, rules = "N-M09"),
    )

    private fun build(g: G): Pemeriksaan {
        val ya = g.ya.split(" ").filter { it.isNotBlank() }.toSet()
        val asked = RuleSelfTest.askedSigns(config)
        val belum = if (g.belum == "ALL") asked.map { it.name }.toSet() else g.belum.split(" ").filter { it.isNotBlank() }.toSet()
        val (status, s, d) = when (g.td) {
            "ALAT" -> Triple(TdStatus.TIDAK_ADA_ALAT, null, null)
            "BELUM" -> Triple(TdStatus.BELUM_DIPERIKSA, null, null)
            else -> g.td.split("/").let { Triple(TdStatus.TERUKUR, it[0].toInt(), it[1].toInt()) }
        }
        return Pemeriksaan(
            fase = Fase.NIFAS, hariNifas = g.hari, tdStatus = status, tdSistolik = s, tdDiastolik = d,
            jawaban = asked.associateWith {
                when (it.name) {
                    in ya -> Jawaban.YA
                    in belum -> Jawaban.BELUM_DIPERIKSA
                    else -> Jawaban.TIDAK
                }
            },
        )
    }

    @Test
    fun goldenCases() {
        val failures = mutableListOf<String>()
        for (g in cases) {
            val h = engine.evaluate(build(g))
            val fired = h.aturanTerpicu.map { it.id }.sorted()
            val expected = g.rules.split(" ").filter { it.isNotBlank() }.sorted()
            if (h.level != g.expect || fired != expected) failures += "${g.id} ${g.desc}: dapat ${h.level} $fired, seharusnya ${g.expect} $expected"
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun resultTalksAboutDaysNotWeeks() {
        val h = engine.evaluate(build(cases.first { it.id == "N03" }))
        assertTrue(h.dataDipakai.contains("Hari setelah melahirkan" to "Hari ke-1"), h.dataDipakai.toString())
        assertTrue(h.dataDipakai.none { it.first == "Usia kehamilan" })
        assertTrue(h.alasanManusia.first().contains("hari ke-1"))
        assertEquals("nifas-0.1.0-draft", h.versiAturan)
    }

    @Test
    fun wrongFaseIsRejected() {
        assertFailsWith<IllegalArgumentException> { engine.evaluate(Pemeriksaan(fase = Fase.HAMIL)) }
        assertFailsWith<IllegalArgumentException> { RuleEngine(RuleConfigLoader.loadDefaultHamil()).evaluate(Pemeriksaan(fase = Fase.NIFAS)) }
    }

    @Test
    fun noFetalQuestionAndNoBleedingAmount() {
        assertTrue(engine.questionsFor(null).none { it.code == GJN })
        assertEquals(false, config.question("PDR")!!.bleedingAmount)
        assertEquals(true, RuleConfigLoader.loadDefaultHamil().question("PDR")!!.bleedingAmount)
    }
}
