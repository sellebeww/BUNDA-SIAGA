package id.bundasiaga.rules

/**
 * Mesin aturan deterministik dan luring. Tanpa I/O, tanpa jam, tanpa keacakan:
 * [Pemeriksaan] dan [RuleConfig] yang sama selalu menghasilkan [Hasil] yang sama.
 *
 * Kebijakan level (docs/CLINICAL_REVIEW.md, "Kebijakan level"):
 *   - level = level tertinggi di antara aturan yang terpicu (bukan skor, tidak pernah dijumlah);
 *   - jawaban kritis yang hilang menaikkan level minimal ke KUNING, tidak pernah ke MERAH;
 *   - HIJAU hanya bila setiap pertanyaan kritis terjawab dan tidak ada yang terpicu.
 */
class RuleEngine(val config: RuleConfig) {

    companion object {
        /** 1 = HAMIL saja; 2 = menambah NIFAS, `bleedingAmount`, dan kolom `seq`/`engine`. */
        const val ENGINE_VERSION = 2
    }

    val fase: Fase get() = config.fase

    fun evaluate(input: Pemeriksaan): Hasil {
        require(input.fase == config.fase) { "pemeriksaan berfase ${input.fase}, aturannya ${config.fase}" }
        val bpInvalid = input.tdStatus == TdStatus.TERUKUR && !bpPlausible(input)
        // Angka yang tidak masuk akal dianggap "belum diukur", bukan dipercaya.
        val p = if (bpInvalid) input.copy(tdStatus = TdStatus.BELUM_DIPERIKSA) else input

        val hits = mutableListOf<AturanTerpicu>()
        val covered = mutableSetOf<String>()
        val ruleSuggestions = mutableListOf<String>()
        for (rule in config.rules) {
            if (rule.condition.eval(p)) {
                hits += AturanTerpicu(rule.id, rule.level, fill(rule.reason, p), rule.status)
                covered += rule.condition.referencedSigns()
                ruleSuggestions += rule.suggestions.map { fill(it, p) }
            }
        }

        // Jaring pengaman: tanda yang dijawab YA tidak boleh berakhir HIJAU hanya karena tidak ada
        // aturan khusus yang mencakupnya (mis. sakit kepala hebat pada usia 12 minggu).
        val uncoveredSigns = presentSigns(p).filter { it !in covered }
        if (uncoveredSigns.isNotEmpty()) {
            val m = config.unmatchedSign
            val names = uncoveredSigns.joinToString(", ") { config.label(it).lowercase() }
            hits += AturanTerpicu(m.id, m.level, m.reason.replace("{tanda}", names), m.status)
        }

        val missing = missingCritical(p)
        if (missing.isNotEmpty() && hits.none { it.level == Level.MERAH }) {
            val m = config.incomplete
            val names = missing.joinToString(", ") { config.label(it).lowercase() }
            hits += AturanTerpicu(m.id, m.level, m.reason.replace("{list}", names), m.status)
        }

        val level = hits.maxOfOrNull { it.level } ?: Level.HIJAU
        check(level != Level.HIJAU || missing.isEmpty()) { "HIJAU dengan data tidak lengkap" }

        val belumLengkap = buildList {
            missing.forEach { add(config.label(it)) }
            if (bpInvalid) add("Angka tekanan darah tidak masuk akal (tidak dipakai)")
        }

        val saran = buildList {
            addAll(config.advice[level].orEmpty())
            addAll(ruleSuggestions)
            if (p.tdStatus != TdStatus.TERUKUR) config.bpMissingAdvice[p.tdStatus]?.let { add(it) }
        }.distinct()

        return Hasil(
            level = level,
            aturanTerpicu = hits,
            alasanManusia = hits.map { it.alasan },
            dataDipakai = dataDipakai(p, bpInvalid),
            dataBelumLengkap = belumLengkap,
            saran = saran,
            versiAturan = config.version,
            statusAturan = config.status,
        )
    }

    /** Pertanyaan yang diajukan, berurutan, untuk usia kehamilan tertentu. */
    fun questionsFor(usiaKehamilanMinggu: Int?): List<Pertanyaan> = config.questions.filter { q ->
        val from = q.askFromWeek ?: return@filter true
        usiaKehamilanMinggu == null || usiaKehamilanMinggu >= from
    }

    fun isCritical(q: Pertanyaan, usiaKehamilanMinggu: Int?): Boolean = when {
        q.critical -> true
        q.criticalFromWeek != null -> usiaKehamilanMinggu == null || usiaKehamilanMinggu >= q.criticalFromWeek
        else -> false
    }

    private fun missingCritical(p: Pemeriksaan): List<String> = config.questions
        .filter { isCritical(it, p.usiaKehamilanMinggu) }
        .filter { q ->
            if (q.code == GJN) p.gerakJanin == GerakJanin.BELUM
            else p.jawab(Tanda.valueOf(q.code)) == Jawaban.BELUM_DIPERIKSA
        }
        .map { it.code }

    private fun presentSigns(p: Pemeriksaan): List<String> = buildList {
        Tanda.entries.forEach { if (p.jawab(it) == Jawaban.YA) add(it.name) }
        if (p.gerakJanin == GerakJanin.BERKURANG || p.gerakJanin == GerakJanin.TIDAK_ADA) add(GJN)
    }

    private fun bpPlausible(p: Pemeriksaan): Boolean {
        val s = p.tdSistolik ?: return false
        val d = p.tdDiastolik ?: return false
        val b = config.bpBounds
        return s in b.sysMin..b.sysMax && d in b.diaMin..b.diaMax && s > d
    }

    private fun fill(template: String, p: Pemeriksaan): String {
        val bp = p.measuredBp()
        return template
            .replace("{sis}", bp?.first?.toString() ?: "-")
            .replace("{dia}", bp?.second?.toString() ?: "-")
            .replace("{uk}", p.usiaKehamilanMinggu?.toString() ?: "tidak diketahui")
            .replace("{hari}", p.hariNifas?.toString() ?: "tidak diketahui")
    }

    private fun dataDipakai(p: Pemeriksaan, bpInvalid: Boolean): List<Pair<String, String>> = buildList {
        if (p.fase == Fase.NIFAS) {
            add("Hari setelah melahirkan" to (p.hariNifas?.let { "Hari ke-$it" } ?: "Tidak diketahui"))
        } else {
            add("Usia kehamilan" to (p.usiaKehamilanMinggu?.let { "$it minggu" } ?: "Tidak diketahui"))
        }
        add(
            "Tekanan darah" to when {
                bpInvalid -> "Tidak dipakai (angka tidak masuk akal)"
                p.tdStatus == TdStatus.TERUKUR -> "${p.tdSistolik}/${p.tdDiastolik}"
                p.tdStatus == TdStatus.TIDAK_ADA_ALAT -> "Tidak ada alat"
                else -> "Belum diperiksa"
            },
        )
        p.suhuC?.let { add("Suhu" to "$it °C") }
        for (q in config.questions) {
            val value = if (q.code == GJN) {
                if (!isCritical(q, p.usiaKehamilanMinggu) && p.gerakJanin == GerakJanin.BELUM) continue
                when (p.gerakJanin) {
                    GerakJanin.NORMAL -> "Normal"
                    GerakJanin.BERKURANG -> "Berkurang"
                    GerakJanin.TIDAK_ADA -> "Tidak ada"
                    GerakJanin.BELUM -> "Belum diperiksa"
                }
            } else {
                val j = p.jawab(Tanda.valueOf(q.code))
                when (j) {
                    Jawaban.YA -> if (q.code == "PDR" && p.subPerdarahan != null) {
                        "Ya (${p.subPerdarahan.name.lowercase()})"
                    } else "Ya"
                    Jawaban.TIDAK -> "Tidak"
                    Jawaban.BELUM_DIPERIKSA -> "Belum diperiksa"
                }
            }
            add(q.label to value)
        }
    }
}
