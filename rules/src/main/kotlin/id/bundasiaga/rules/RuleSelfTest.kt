package id.bundasiaga.rules

import kotlin.random.Random

/**
 * Memeriksa invarian keselamatan pada konfigurasi nyata dengan menjalankan mesin pada banyak
 * pemeriksaan acak bersemai. Dipakai tes unit, alat tanda tangan (konfigurasi yang gagal tidak
 * pernah ditandatangani), dan aplikasi sebelum memasang pembaruan jarak jauh (konfigurasi yang
 * gagal tidak pernah dipakai, meski tanda tangannya sah).
 */
object RuleSelfTest {

    private val FORBIDDEN_IN_STABILIZATION = listOf("mg", "dosis", "suntik", "injeksi", "infus", "tablet", "obat")

    /** Mengembalikan masalah yang mudah dibaca; kosong berarti konfigurasi lolos. */
    fun check(config: RuleConfig, iterations: Int = 3_000, seed: Int = 42): List<String> {
        val problems = mutableListOf<String>()
        fun problem(msg: String) { if (problems.size < 20) problems += msg }
        val engine = RuleEngine(config)
        val merahRuleIds = config.rules.filter { it.level == Level.MERAH }.map { it.id }.toSet()

        // Kasus kewarasan tetap.
        val allTidak = complete(config)
        engine.evaluate(allTidak).let {
            if (it.level != Level.HIJAU) problem("semua jawaban TIDAK dengan tekanan darah normal menghasilkan ${it.level} (${it.aturanTerpicu.map { h -> h.id }})")
        }
        for (q in config.questions.filter { engine.isCritical(it, allTidak.usiaKehamilanMinggu) }) {
            val p = if (q.code == GJN) allTidak.copy(gerakJanin = GerakJanin.BELUM)
            else allTidak.copy(jawaban = allTidak.jawaban + (Tanda.valueOf(q.code) to Jawaban.BELUM_DIPERIKSA))
            val lv = engine.evaluate(p).level
            if (lv != Level.KUNING) problem("pertanyaan kritis ${q.code} tidak dijawab menghasilkan $lv, seharusnya KUNING")
        }
        val stab = (config.stabilization.steps + config.stabilization.bidanReminder).joinToString(" ").lowercase()
        FORBIDDEN_IN_STABILIZATION.forEach {
            if (Regex("\\b$it\\b").containsMatchIn(stab)) problem("panduan stabilisasi menyebut '$it'")
        }

        val r = Random(seed)
        repeat(iterations) {
            val p = randomInput(config, r)
            val h = engine.evaluate(p)
            if (h != engine.evaluate(p.copy())) problem("tidak deterministik untuk $p")
            if (h.alasanManusia.any { "{" in it }) problem("templat belum terisi di ${h.alasanManusia}")
            if (h.level == Level.HIJAU) {
                if (h.aturanTerpicu.isNotEmpty()) problem("HIJAU padahal ada aturan terpicu: $p")
                if (h.dataBelumLengkap.any { d -> config.questions.any { it.label == d } }) problem("HIJAU padahal data kurang: $p")
                if (Tanda.entries.any { p.jawab(it) == Jawaban.YA }) problem("HIJAU padahal ada tanda YA: $p")
            }
            if (h.level == Level.MERAH && h.aturanTerpicu.none { it.id in merahRuleIds }) {
                problem("MERAH tanpa aturan MERAH yang eksplisit: $p")
            }
            for (w in worsen(config, p)) {
                val after = engine.evaluate(w).level
                if (after < h.level) problem("tidak monoton: ${h.level} -> $after\ndari $p\nmenjadi $w")
            }
            val onlyMissing = p.copy(
                jawaban = p.jawaban.mapValues { (_, j) -> if (j == Jawaban.YA) Jawaban.BELUM_DIPERIKSA else j },
                gerakJanin = if (p.gerakJanin == GerakJanin.NORMAL) GerakJanin.NORMAL else GerakJanin.BELUM,
                tdStatus = TdStatus.BELUM_DIPERIKSA, tdSistolik = null, tdDiastolik = null,
            )
            if (engine.evaluate(onlyMissing).level == Level.MERAH) problem("MERAH hanya dari data tidak lengkap: $onlyMissing")
        }
        return problems
    }

    /** Setiap pertanyaan dijawab TIDAK / normal, tekanan darah 110/70. */
    fun complete(config: RuleConfig): Pemeriksaan = Pemeriksaan(
        fase = config.fase,
        usiaKehamilanMinggu = if (config.fase == Fase.HAMIL) 30 else null,
        hariNifas = if (config.fase == Fase.NIFAS) 5 else null,
        tdStatus = TdStatus.TERUKUR, tdSistolik = 110, tdDiastolik = 70,
        jawaban = askedSigns(config).associateWith { Jawaban.TIDAK },
        gerakJanin = if (config.question(GJN) != null) GerakJanin.NORMAL else GerakJanin.BELUM,
    )

    fun askedSigns(config: RuleConfig): List<Tanda> =
        config.questions.mapNotNull { q -> Tanda.entries.firstOrNull { it.name == q.code } }

    /** Pemeriksaan acak yang dibatasi pada apa yang bisa benar-benar ditanyakan konfigurasi ini. */
    fun randomInput(config: RuleConfig, r: Random): Pemeriksaan {
        val status = TdStatus.entries.random(r)
        // Hanya bacaan yang masuk akal (sistolik jauh di atas diastolik); angka yang tidak masuk
        // akal adalah jalur terpisah (dianggap "belum diukur").
        val sys = r.nextInt(90, 200)
        val hasFetal = config.question(GJN) != null
        val askAmount = config.question(Tanda.PDR.name)?.bleedingAmount == true
        return Pemeriksaan(
            fase = config.fase,
            usiaKehamilanMinggu = if (config.fase == Fase.HAMIL && r.nextInt(8) != 0) r.nextInt(4, 43) else null,
            hariNifas = if (config.fase == Fase.NIFAS && r.nextInt(8) != 0) r.nextInt(0, 43) else null,
            tdStatus = status,
            tdSistolik = if (status == TdStatus.TERUKUR) sys else null,
            tdDiastolik = if (status == TdStatus.TERUKUR) r.nextInt(40, minOf(sys - 25, 130)) else null,
            jawaban = askedSigns(config).associateWith { Jawaban.entries.random(r) },
            subPerdarahan = if (askAmount && r.nextBoolean()) SubPerdarahan.entries.random(r) else null,
            gerakJanin = if (hasFetal) GerakJanin.entries.random(r) else GerakJanin.BELUM,
        )
    }

    /** Setiap cara "menambah satu tanda bahaya" pada suatu masukan. */
    fun worsen(config: RuleConfig, p: Pemeriksaan): List<Pemeriksaan> = buildList {
        for (t in askedSigns(config)) {
            if (p.jawab(t) != Jawaban.YA) add(p.copy(jawaban = p.jawaban + (t to Jawaban.YA)))
        }
        if (config.question(GJN) != null) {
            when (p.gerakJanin) {
                GerakJanin.NORMAL, GerakJanin.BELUM -> {
                    add(p.copy(gerakJanin = GerakJanin.BERKURANG))
                    add(p.copy(gerakJanin = GerakJanin.TIDAK_ADA))
                }
                GerakJanin.BERKURANG -> add(p.copy(gerakJanin = GerakJanin.TIDAK_ADA))
                GerakJanin.TIDAK_ADA -> Unit
            }
        }
        if (p.jawab(Tanda.PDR) == Jawaban.YA && p.subPerdarahan != null) {
            // Jumlah yang tidak ditentukan berarti "tidak diketahui", jadi hanya bandingkan antar jumlah yang ditentukan.
            when (p.subPerdarahan) {
                SubPerdarahan.BERCAK -> add(p.copy(subPerdarahan = SubPerdarahan.SEDANG))
                SubPerdarahan.SEDANG -> add(p.copy(subPerdarahan = SubPerdarahan.BANYAK))
                SubPerdarahan.BANYAK -> Unit
            }
        }
        if (p.tdStatus == TdStatus.TERUKUR) {
            add(p.copy(tdSistolik = p.tdSistolik!! + 15))
            if (p.tdDiastolik!! + 10 < p.tdSistolik) add(p.copy(tdDiastolik = p.tdDiastolik + 10))
        }
    }
}
