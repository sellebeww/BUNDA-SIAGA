package id.bundasiaga.codec

/** Label berbahasa Indonesia untuk buku kode tanda v1, dipakai di sisi penerima dan skrip manual. */
object Codebook {
    val SIGN_LABELS = mapOf(
        "KEJ" to "kejang",
        "KSD" to "pingsan atau tidak sadar",
        "PDR" to "perdarahan dari jalan lahir",
        "SKH" to "sakit kepala hebat",
        "PDG" to "pandangan kabur",
        "SSK" to "sesak napas berat atau nyeri dada",
        "NPT" to "nyeri perut hebat",
        "KTB" to "air ketuban keluar sebelum waktunya",
        "BKK" to "bengkak wajah atau tangan",
        "NPU" to "nyeri ulu hati",
        "DMM" to "demam",
        "MGL" to "menggigil",
        "CRB" to "cairan berbau dari jalan lahir",
        "MNT" to "muntah terus-menerus",
        "GJB" to "gerakan janin berkurang",
        "GJT" to "gerakan janin tidak ada",
        "PYD" to "payudara bengkak, merah, dan nyeri",
        "BTS" to "nyeri atau bengkak di satu betis",
        "SDH" to "sangat sedih atau murung",
        "PMD" to "berpikir menyakiti diri atau bayi",
    )

    fun signLabel(code: String): String {
        val base = code.substringBefore(':')
        val label = SIGN_LABELS[base] ?: code
        val sub = code.substringAfter(':', "").firstOrNull()?.let { Bs1.BLEEDING_SUFFIX[it] }
        return if (sub != null) "$label (${sub.lowercase()})" else label
    }

    fun levelWord(t: Tingkat) = when (t) {
        Tingkat.MERAH -> "MERAH"
        Tingkat.KUNING -> "KUNING"
        Tingkat.HIJAU -> "HIJAU"
    }
}

/**
 * Skrip berbahasa sehari-hari dengan isi sama seperti SMS, untuk kader yang harus
 * meneruskan rujukan lewat radio, HT, telepon, atau kurir.
 */
object ManualScript {
    fun render(r: Rujukan, pengirim: String? = null, teleponBalik: String? = null): String = buildString {
        appendLine(if (r.fase == FaseRujukan.NIFAS) "Ini rujukan BUNDA SIAGA untuk ibu nifas (sudah melahirkan)." else "Ini rujukan BUNDA SIAGA.")
        pengirim?.takeIf { it.isNotBlank() }?.let { appendLine("Dari: $it.") }
        appendLine("Kode pasien: ${spell(r.pasien)}.")
        appendLine("Tingkat: ${Codebook.levelWord(r.tingkat)}.")
        if (r.fase == FaseRujukan.NIFAS) {
            appendLine("Setelah melahirkan: ${r.hariNifas?.let { "hari ke-$it" } ?: "tidak diketahui"}.")
        } else {
            appendLine("Usia kehamilan: ${r.usiaMinggu?.let { "$it minggu" } ?: "tidak diketahui"}.")
        }
        appendLine(
            "Tekanan darah: " +
                (if (r.tdSistolik != null) "${r.tdSistolik} per ${r.tdDiastolik}." else "tidak diukur."),
        )
        appendLine(
            "Tanda bahaya: " +
                (if (r.tanda.isEmpty()) "tidak ada." else r.tanda.joinToString(", ") { Codebook.signLabel(it) } + "."),
        )
        appendLine("Lokasi: ${spell(r.lokasi)}.")
        appendLine("Waktu: tanggal ${r.waktu.day}, jam ${"%02d.%02d".format(r.waktu.hour, r.waktu.minute)}.")
        appendLine("Nomor pesan: ${spell(r.msgId)}.")
        teleponBalik?.takeIf { it.isNotBlank() }?.let { appendLine("Telepon balik: $it.") }
        append("Mohon konfirmasi apakah siap menerima.")
    }

    /** Huruf dan angka dipisah agar dibaca satu per satu di saluran yang buruk. */
    private fun spell(code: String) = code.toList().joinToString("-")
}
