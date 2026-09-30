package id.bundasiaga.rules

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Kasus emas. Tabel yang mudah dibaca:
 *   uk    = usia kehamilan dalam minggu, null = tidak diketahui
 *   td    = "sis/dia" (terukur) | "ALAT" (tidak ada alat) | "BELUM" (belum diukur)
 *   ya    = tanda yang dijawab YA
 *   belum = tanda yang dibiarkan BELUM_DIPERIKSA; tanda lain semuanya TIDAK
 *   gjn   = gerakan janin (bawaan NORMAL)
 *   rules = himpunan persis ID aturan yang terpicu
 *
 * Ini kasus rekayasa sebagai ilustrasi, bukan hasil yang sudah divalidasi secara klinis.
 */
class GoldenCasesTest {

    private data class G(
        val id: String,
        val desc: String,
        val uk: Int?,
        val td: String,
        val ya: String = "",
        val belum: String = "",
        val gjn: GerakJanin = GerakJanin.NORMAL,
        val sub: SubPerdarahan? = null,
        val expect: Level,
        val rules: String,
    )

    private val cases = listOf(
        //  id    deskripsi                                          uk    td          ya              belum        diharapkan
        G("G01", "Seed (a) Ibu Yuliana: SKH + bengkak, TD 168/112", 34, "168/112", ya = "SKH BKK", expect = Level.MERAH, rules = "R-M04 R-M06 R-M07 R-K01 R-K02"),
        G("G02", "Seed (b) normal, semua dijawab, TD normal", 30, "110/70", expect = Level.HIJAU, rules = ""),
        G("G03", "Seed (c) TD 146/94 tanpa gejala", 32, "146/94", expect = Level.KUNING, rules = "R-K01"),
        G("G04", "Seed (d) data belum lengkap (kejang & ketuban)", 26, "120/80", belum = "KEJ KTB", expect = Level.KUNING, rules = "R-U01"),
        G("G05", "Seed (e) perdarahan 36 minggu", 36, "ALAT", ya = "PDR", sub = SubPerdarahan.SEDANG, expect = Level.MERAH, rules = "R-M03"),
        G("G06", "Seed (f) kader tanpa tensi, sakit kepala hebat", 33, "ALAT", ya = "SKH", expect = Level.MERAH, rules = "R-M06"),
        G("G07", "Kejang saja", 20, "BELUM", ya = "KEJ", expect = Level.MERAH, rules = "R-M01"),
        G("G08", "Pingsan saja, usia tidak diketahui", null, "BELUM", ya = "KSD", expect = Level.MERAH, rules = "R-M02"),
        G("G09", "Bercak darah 12 minggu", 12, "BELUM", ya = "PDR", sub = SubPerdarahan.BERCAK, expect = Level.KUNING, rules = "R-K06"),
        G("G10", "Perdarahan banyak 12 minggu", 12, "BELUM", ya = "PDR", sub = SubPerdarahan.BANYAK, expect = Level.MERAH, rules = "R-M03"),
        G("G11", "Perdarahan sedang 12 minggu (tak ada aturan khusus)", 12, "BELUM", ya = "PDR", sub = SubPerdarahan.SEDANG, expect = Level.KUNING, rules = "R-U02"),
        G("G12", "Perdarahan, usia tidak diketahui", null, "BELUM", ya = "PDR", sub = SubPerdarahan.BERCAK, expect = Level.MERAH, rules = "R-M03"),
        G("G13", "TD 160/100 tepat di ambang berat", 30, "160/100", expect = Level.MERAH, rules = "R-M04 R-K01"),
        G("G14", "TD 150/110 diastolik di ambang berat", 30, "150/110", expect = Level.MERAH, rules = "R-M04 R-K01"),
        G("G15", "TD 139/89 tepat di bawah ambang", 30, "139/89", expect = Level.HIJAU, rules = ""),
        G("G16", "SKH + TD terukur normal: turun ke KUNING", 30, "120/80", ya = "SKH", expect = Level.KUNING, rules = "R-M06K"),
        G("G17", "SKH + pandangan kabur walau TD normal", 30, "120/80", ya = "SKH PDG", expect = Level.MERAH, rules = "R-M05 R-M06K"),
        G("G18", "SKH + nyeri ulu hati 16 minggu", 16, "BELUM", ya = "SKH NPU", expect = Level.MERAH, rules = "R-M05"),
        G("G19", "SKH saja 12 minggu (jaring pengaman)", 12, "BELUM", ya = "SKH", expect = Level.KUNING, rules = "R-U02"),
        G("G20", "TD 145/92 + bengkak", 28, "145/92", ya = "BKK", expect = Level.MERAH, rules = "R-M07 R-K01 R-K02"),
        G("G21", "Sesak napas berat", 24, "ALAT", ya = "SSK", expect = Level.MERAH, rules = "R-M08"),
        G("G22", "Nyeri perut hebat", 24, "ALAT", ya = "NPT", expect = Level.MERAH, rules = "R-M09"),
        G("G23", "Ketuban pecah dini", 34, "ALAT", ya = "KTB", expect = Level.MERAH, rules = "R-M10"),
        G("G24", "Gerakan janin tidak ada 30 minggu", 30, "ALAT", gjn = GerakJanin.TIDAK_ADA, expect = Level.MERAH, rules = "R-M11"),
        G("G25", "Gerakan janin tidak ada 24 minggu", 24, "ALAT", gjn = GerakJanin.TIDAK_ADA, expect = Level.KUNING, rules = "R-U02"),
        G("G26", "Gerakan janin berkurang", 32, "ALAT", gjn = GerakJanin.BERKURANG, expect = Level.KUNING, rules = "R-K03"),
        G("G27", "Demam + menggigil", 30, "ALAT", ya = "DMM MGL", expect = Level.MERAH, rules = "R-M12 R-K04"),
        G("G28", "Demam saja", 30, "ALAT", ya = "DMM", expect = Level.KUNING, rules = "R-K04"),
        G("G29", "Muntah terus", 10, "ALAT", ya = "MNT", expect = Level.KUNING, rules = "R-K05"),
        G("G30", "Bengkak 14 minggu (jaring pengaman)", 14, "ALAT", ya = "BKK", expect = Level.KUNING, rules = "R-U02"),
        G("G31", "Kader tanpa tensi, semua TIDAK -> HIJAU", 30, "ALAT", expect = Level.HIJAU, rules = ""),
        G("G32", "Gerakan janin belum diperiksa pada 30 minggu", 30, "ALAT", gjn = GerakJanin.BELUM, expect = Level.KUNING, rules = "R-U01"),
        G("G33", "Gerakan janin belum diperiksa pada 12 minggu (tidak kritis)", 12, "ALAT", gjn = GerakJanin.BELUM, expect = Level.HIJAU, rules = ""),
        G("G34", "Semua belum diperiksa: KUNING, bukan MERAH", null, "BELUM", belum = "ALL", gjn = GerakJanin.BELUM, expect = Level.KUNING, rules = "R-U01"),
        G("G35", "Data tidak lengkap + kejang: MERAH dari kejang saja", 30, "BELUM", ya = "KEJ", belum = "KSD PDR", expect = Level.MERAH, rules = "R-M01"),
        G("G36", "TD tidak masuk akal (80/120) diabaikan", 30, "80/120", expect = Level.HIJAU, rules = ""),
        G("G37", "Tidak kritis belum diperiksa tetap HIJAU", 30, "ALAT", belum = "BKK NPU DMM MGL CRB MNT", expect = Level.HIJAU, rules = ""),
        G("G38", "TD 146/94 pada 16 minggu (aturan tambahan R-K07)", 16, "146/94", expect = Level.KUNING, rules = "R-K07"),
    )

    private fun build(g: G): Pemeriksaan {
        val ya = g.ya.split(" ").filter { it.isNotBlank() }.toSet()
        val belum = if (g.belum == "ALL") Tanda.entries.map { it.name }.toSet()
        else g.belum.split(" ").filter { it.isNotBlank() }.toSet()
        val jawaban = Tanda.entries.associateWith {
            when (it.name) {
                in ya -> Jawaban.YA
                in belum -> Jawaban.BELUM_DIPERIKSA
                else -> Jawaban.TIDAK
            }
        }
        val (status, s, d) = when (g.td) {
            "ALAT" -> Triple(TdStatus.TIDAK_ADA_ALAT, null, null)
            "BELUM" -> Triple(TdStatus.BELUM_DIPERIKSA, null, null)
            else -> g.td.split("/").let { Triple(TdStatus.TERUKUR, it[0].toInt(), it[1].toInt()) }
        }
        return Pemeriksaan(
            usiaKehamilanMinggu = g.uk, tdStatus = status, tdSistolik = s, tdDiastolik = d,
            jawaban = jawaban, subPerdarahan = g.sub, gerakJanin = g.gjn,
        )
    }

    @Test
    fun goldenCases() {
        val engine = RuleEngine(RuleConfigLoader.loadDefaultHamil())
        val failures = mutableListOf<String>()
        val table = StringBuilder("\n| id | kasus | level | aturan terpicu |\n|---|---|---|---|\n")
        for (g in cases) {
            val h = engine.evaluate(build(g))
            val fired = h.aturanTerpicu.map { it.id }.sorted()
            val expected = g.rules.split(" ").filter { it.isNotBlank() }.sorted()
            table.append("| ${g.id} | ${g.desc} | ${h.level} | ${fired.joinToString(" ")} |\n")
            if (h.level != g.expect || fired != expected) {
                failures += "${g.id} ${g.desc}: dapat ${h.level} $fired, seharusnya ${g.expect} $expected"
            }
        }
        println(table)
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        assertEquals(true, cases.size >= 25)
    }

    @Test
    fun seedCaseExplainsItself() {
        val engine = RuleEngine(RuleConfigLoader.loadDefaultHamil())
        val h = engine.evaluate(build(cases.first()))
        assertEquals(true, h.alasanManusia.any { "168/112" in it })
        assertEquals(true, h.dataDipakai.contains("Tekanan darah" to "168/112"))
        assertEquals("hamil-0.1.0-draft", h.versiAturan)
        assertEquals(true, h.saran.first().startsWith("Segera rujuk"))
    }

    @Test
    fun kaderWithoutDeviceGetsBpAdvice() {
        val engine = RuleEngine(RuleConfigLoader.loadDefaultHamil())
        val h = engine.evaluate(build(cases.first { it.id == "G31" }))
        assertEquals(true, h.saran.any { "tensi" in it })
    }
}
