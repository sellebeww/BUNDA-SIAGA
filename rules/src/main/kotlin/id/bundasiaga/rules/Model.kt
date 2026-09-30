package id.bundasiaga.rules

/** Jawaban tiga keadaan. BELUM_DIPERIKSA tidak pernah dianggap TIDAK. */
enum class Jawaban { YA, TIDAK, BELUM_DIPERIKSA }

enum class Fase { HAMIL, NIFAS }

/** Berurut menurut tingkat keparahan; perbandingan bergantung pada urutan ini. */
enum class Level { HIJAU, KUNING, MERAH }

enum class TdStatus { TERUKUR, BELUM_DIPERIKSA, TIDAK_ADA_ALAT }

enum class SubPerdarahan { BERCAK, SEDANG, BANYAK }

/** Gerakan janin. BELUM berarti "belum diperiksa", yaitu belum dijawab. */
enum class GerakJanin { NORMAL, BERKURANG, TIDAK_ADA, BELUM }

/** Pertanyaan tanda bahaya ya/tidak (gerakan janin dimodelkan terpisah oleh [GerakJanin]). */
enum class Tanda {
    KEJ, KSD, PDR, SKH, PDG, BKK, NPU, NPT, SSK, KTB, DMM, MNT, CRB, MGL,
    // Khusus nifas (NIFAS): payudara, betis, suasana hati murung, pikiran menyakiti diri atau bayi.
    PYD, BTS, SDH, PMD,
}

data class Pemeriksaan(
    val fase: Fase = Fase.HAMIL,
    val usiaKehamilanMinggu: Int? = null,
    /** Khusus NIFAS: hari sejak melahirkan (0 = hari melahirkan). */
    val hariNifas: Int? = null,
    val tdStatus: TdStatus = TdStatus.BELUM_DIPERIKSA,
    val tdSistolik: Int? = null,
    val tdDiastolik: Int? = null,
    val suhuC: Double? = null,
    val jawaban: Map<Tanda, Jawaban> = emptyMap(),
    val subPerdarahan: SubPerdarahan? = null,
    val gerakJanin: GerakJanin = GerakJanin.BELUM,
) {
    fun jawab(t: Tanda): Jawaban = jawaban[t] ?: Jawaban.BELUM_DIPERIKSA
}

data class AturanTerpicu(
    val id: String,
    val level: Level,
    val alasan: String,
    val status: String,
)

data class Hasil(
    val level: Level,
    val aturanTerpicu: List<AturanTerpicu>,
    val alasanManusia: List<String>,
    /** Pasangan label -> nilai berurutan yang benar-benar dipakai untuk keputusan. */
    val dataDipakai: List<Pair<String, String>>,
    val dataBelumLengkap: List<String>,
    val saran: List<String>,
    val versiAturan: String,
    val statusAturan: String,
)
