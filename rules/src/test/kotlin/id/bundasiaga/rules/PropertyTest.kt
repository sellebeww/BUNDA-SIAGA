package id.bundasiaga.rules

import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pemeriksaan acak atas invarian keselamatan (docs/CLINICAL_REVIEW.md, "Kebijakan level"). Bersemai, sehingga kegagalan bisa diulang. */
class PropertyTest {
    private val config = RuleConfigLoader.loadDefaultHamil()
    private val engine = RuleEngine(config)
    private val iterations = 20_000

    private fun randomInput(r: Random): Pemeriksaan {
        val status = TdStatus.entries.random(r)
        // Hanya bacaan yang masuk akal (sistolik jauh di atas diastolik); angka yang tidak masuk
        // akal adalah jalur terpisah (dianggap "belum diukur") yang dicakup kasus emas G36.
        val sys = r.nextInt(90, 200)
        return Pemeriksaan(
            usiaKehamilanMinggu = if (r.nextInt(8) == 0) null else r.nextInt(4, 43),
            tdStatus = status,
            tdSistolik = if (status == TdStatus.TERUKUR) sys else null,
            tdDiastolik = if (status == TdStatus.TERUKUR) r.nextInt(40, minOf(sys - 25, 130)) else null,
            jawaban = Tanda.entries.associateWith { Jawaban.entries.random(r) },
            subPerdarahan = if (r.nextBoolean()) SubPerdarahan.entries.random(r) else null,
            gerakJanin = GerakJanin.entries.random(r),
        )
    }

    /** Setiap cara "menambah satu tanda bahaya" pada suatu masukan. */
    private fun worsen(p: Pemeriksaan): List<Pemeriksaan> = buildList {
        for (t in Tanda.entries) {
            if (p.jawab(t) != Jawaban.YA) add(p.copy(jawaban = p.jawaban + (t to Jawaban.YA)))
        }
        when (p.gerakJanin) {
            GerakJanin.NORMAL, GerakJanin.BELUM -> {
                add(p.copy(gerakJanin = GerakJanin.BERKURANG))
                add(p.copy(gerakJanin = GerakJanin.TIDAK_ADA))
            }
            GerakJanin.BERKURANG -> add(p.copy(gerakJanin = GerakJanin.TIDAK_ADA))
            GerakJanin.TIDAK_ADA -> Unit
        }
        if (p.jawab(Tanda.PDR) == Jawaban.YA) {
            val next = when (p.subPerdarahan) {
                null, SubPerdarahan.BERCAK -> SubPerdarahan.SEDANG
                SubPerdarahan.SEDANG -> SubPerdarahan.BANYAK
                SubPerdarahan.BANYAK -> null
            }
            // Jumlah yang tidak ditentukan berarti "tidak diketahui", jadi hanya bandingkan antar jumlah yang ditentukan.
            if (next != null && p.subPerdarahan != null) add(p.copy(subPerdarahan = next))
        }
        if (p.tdStatus == TdStatus.TERUKUR) {
            add(p.copy(tdSistolik = p.tdSistolik!! + 15))
            if (p.tdDiastolik!! + 10 < p.tdSistolik!!) add(p.copy(tdDiastolik = p.tdDiastolik + 10))
        }
    }

    @Test
    fun monotonic_addingDangerSignNeverLowersLevel() {
        val r = Random(42)
        repeat(iterations) {
            val p = randomInput(r)
            val base = engine.evaluate(p).level
            for (w in worsen(p)) {
                val after = engine.evaluate(w).level
                assertTrue(after >= base, "level turun $base -> $after\ndari $p\nmenjadi $w")
            }
        }
    }

    @Test
    fun deterministic_sameInputSameOutput() {
        val r = Random(7)
        repeat(iterations) {
            val p = randomInput(r)
            assertEquals(engine.evaluate(p), engine.evaluate(p.copy()))
            assertEquals(engine.evaluate(p), RuleEngine(RuleConfigLoader.loadDefaultHamil()).evaluate(p))
        }
    }

    @Test
    fun hijau_onlyWhenCompleteAndNothingFired() {
        val r = Random(3)
        repeat(iterations) {
            val p = randomInput(r)
            val h = engine.evaluate(p)
            if (h.level == Level.HIJAU) {
                assertTrue(h.aturanTerpicu.isEmpty(), "HIJAU padahal ada aturan terpicu: $p")
                assertTrue(h.dataBelumLengkap.none { it in config.questions.map { q -> q.label } }, "HIJAU padahal data kurang: $p")
                assertTrue(Tanda.entries.none { p.jawab(it) == Jawaban.YA }, "HIJAU padahal ada tanda YA: $p")
            }
        }
    }

    @Test
    fun merah_onlyFromExplicitDangerRules() {
        val r = Random(11)
        repeat(iterations) {
            val p = randomInput(r)
            val h = engine.evaluate(p)
            if (h.level == Level.MERAH) {
                assertTrue(h.aturanTerpicu.any { it.level == Level.MERAH && it.id.startsWith("R-M") })
            }
        }
    }

    @Test
    fun incompleteDataAloneNeverMerah() {
        // Tidak ada tanda YA, gerakan janin tidak abnormal, tekanan darah belum diukur: hanya data yang kurang.
        val r = Random(5)
        repeat(iterations) {
            val p = randomInput(r).let { base ->
                base.copy(
                    jawaban = base.jawaban.mapValues { (_, j) -> if (j == Jawaban.YA) Jawaban.BELUM_DIPERIKSA else j },
                    gerakJanin = if (base.gerakJanin == GerakJanin.NORMAL) GerakJanin.NORMAL else GerakJanin.BELUM,
                    tdStatus = TdStatus.BELUM_DIPERIKSA, tdSistolik = null, tdDiastolik = null,
                )
            }
            assertTrue(engine.evaluate(p).level != Level.MERAH, "MERAH dari data tidak lengkap: $p")
        }
    }

    @Test
    fun belumDiperiksaIsNotTidak() {
        val allTidak = Pemeriksaan(
            usiaKehamilanMinggu = 30, tdStatus = TdStatus.TIDAK_ADA_ALAT,
            jawaban = Tanda.entries.associateWith { Jawaban.TIDAK }, gerakJanin = GerakJanin.NORMAL,
        )
        assertEquals(Level.HIJAU, engine.evaluate(allTidak).level)
        for (q in config.questions.filter { engine.isCritical(it, 30) && it.code != "GJN" }) {
            val p = allTidak.copy(jawaban = allTidak.jawaban + (Tanda.valueOf(q.code) to Jawaban.BELUM_DIPERIKSA))
            assertEquals(Level.KUNING, engine.evaluate(p).level, "${q.code} belum diperiksa tidak boleh HIJAU")
        }
    }

    @Test
    fun everyResultHasReasonsAndVersion() {
        val r = Random(9)
        repeat(2_000) {
            val h = engine.evaluate(randomInput(r))
            assertEquals(h.aturanTerpicu.size, h.alasanManusia.size)
            assertTrue(h.level == Level.HIJAU || h.alasanManusia.isNotEmpty())
            assertTrue(h.saran.isNotEmpty())
            assertTrue(h.alasanManusia.none { "{" in it }, "templat belum terisi: ${h.alasanManusia}")
            assertEquals(config.version, h.versiAturan)
        }
    }
}
