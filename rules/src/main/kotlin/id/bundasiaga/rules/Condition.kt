package id.bundasiaga.rules

/**
 * Bahasa kondisi deklaratif kecil yang dipakai konfigurasi aturan.
 * Sengaja tidak ada negasi atas tanda bahaya: itulah yang menjaga mesin tetap
 * monoton (menambah satu tanda hanya bisa menambah aturan yang terpicu).
 */
sealed interface Condition {
    fun eval(p: Pemeriksaan): Boolean

    /** Tanda bahaya yang dilihat kondisi ini; "GJN" mewakili gerakan janin. */
    fun referencedSigns(): Set<String>

    data class Yes(val tanda: Tanda) : Condition {
        override fun eval(p: Pemeriksaan) = p.jawab(tanda) == Jawaban.YA
        override fun referencedSigns() = setOf(tanda.name)
    }

    data class All(val items: List<Condition>) : Condition {
        override fun eval(p: Pemeriksaan) = items.all { it.eval(p) }
        override fun referencedSigns() = items.flatMap { it.referencedSigns() }.toSet()
    }

    data class AnyOf(val items: List<Condition>) : Condition {
        override fun eval(p: Pemeriksaan) = items.any { it.eval(p) }
        override fun referencedSigns() = items.flatMap { it.referencedSigns() }.toSet()
    }

    /** Usia kehamilan >= minggu; usia tidak diketahui dianggap benar bila [orUnknown]. */
    data class GaAtLeast(val weeks: Int, val orUnknown: Boolean) : Condition {
        override fun eval(p: Pemeriksaan): Boolean {
            val ga = p.usiaKehamilanMinggu ?: return orUnknown
            return ga >= weeks
        }
        override fun referencedSigns() = emptySet<String>()
    }

    /** Usia kehamilan diketahui dan < minggu. */
    data class GaBelow(val weeks: Int) : Condition {
        override fun eval(p: Pemeriksaan): Boolean {
            val ga = p.usiaKehamilanMinggu ?: return false
            return ga < weeks
        }
        override fun referencedSigns() = emptySet<String>()
    }

    /** Tekanan darah terukur dengan sistolik >= sys ATAU diastolik >= dia. */
    data class BpAtLeast(val sys: Int, val dia: Int) : Condition {
        override fun eval(p: Pemeriksaan): Boolean {
            val bp = p.measuredBp() ?: return false
            return bp.first >= sys || bp.second >= dia
        }
        override fun referencedSigns() = emptySet<String>()
    }

    /** Tekanan darah terukur dengan sistolik < sys DAN diastolik < dia. */
    data class BpBelow(val sys: Int, val dia: Int) : Condition {
        override fun eval(p: Pemeriksaan): Boolean {
            val bp = p.measuredBp() ?: return false
            return bp.first < sys && bp.second < dia
        }
        override fun referencedSigns() = emptySet<String>()
    }

    /** Tekanan darah BUKAN (terukur dan di bawah sys/dia). Benar bila tekanan darah tidak diketahui. */
    data class NotBpBelow(val sys: Int, val dia: Int) : Condition {
        override fun eval(p: Pemeriksaan) = !BpBelow(sys, dia).eval(p)
        override fun referencedSigns() = emptySet<String>()
    }

    data class Bleeding(val sub: SubPerdarahan) : Condition {
        override fun eval(p: Pemeriksaan) = p.jawab(Tanda.PDR) == Jawaban.YA && p.subPerdarahan == sub
        override fun referencedSigns() = setOf(Tanda.PDR.name)
    }

    data class Fetal(val value: GerakJanin) : Condition {
        override fun eval(p: Pemeriksaan) = p.gerakJanin == value
        override fun referencedSigns() = setOf("GJN")
    }
}

/** Pasangan tekanan darah hanya bila benar-benar terukur dan kedua angkanya ada. */
fun Pemeriksaan.measuredBp(): Pair<Int, Int>? {
    if (tdStatus != TdStatus.TERUKUR) return null
    val s = tdSistolik ?: return null
    val d = tdDiastolik ?: return null
    return s to d
}
