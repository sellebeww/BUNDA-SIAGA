package id.bundasiaga.codec

/**
 * Format kawat BS1. Satu SMS, <= 160 karakter GSM-7, hanya himpunan karakter dasar
 * `A-Z 0-9 ; . / - :` (tanpa karakter tabel perluasan yang memakan dua septet,
 * dan tidak ada yang memaksa UCS-2).
 *
 *   BS1;R;<MSGID>;<PASIEN>;<LV>;<UK>;<TD>;<TANDA>;<LOKASI>;<DDHHMM>;<CK>     rujukan, ibu hamil
 *   BS1;N;<MSGID>;<PASIEN>;<LV>;<HARI>;<TD>;<TANDA>;<LOKASI>;<DDHHMM>;<CK>   rujukan, nifas (HARI = hari sejak melahirkan)
 *   BS1;A;<MSGID>;<STATUS>;<DDHHMM>;<CK>                                     ACK
 *   BS1;K;<FASKES>;<ST>;<BED>;<DDHHMM>;<CK>                                  siaran kapasitas faskes
 *
 * Aplikasi lama yang hanya mengenal R dan A menampilkan N dan K sebagai "format tidak dikenal",
 * bukan membuangnya, sehingga jenis pesan baru tidak pernah hilang diam-diam.
 *
 * CK adalah CRC-8 (polinomial 0x07, awal 0) atas teks kanonik sebelum ";CK", dalam dua digit
 * heksadesimal. Orang yang mengetik rujukan dari HP biasa boleh menulis "-" untuk CK:
 * pesan tetap diterima tetapi ditandai belum terverifikasi.
 */
object Bs1 {
    const val PREFIX = "BS1;"
    const val MAX_LEN = 160

    /** Alfabet MSGID: tanpa 0/O dan tanpa 1/I. */
    const val ID_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    /**
     * Buku kode tanda. Urutannya adalah urutan penyandian kanonik; kode baru hanya pernah
     * ditambahkan di akhir, sehingga pesan lama (dan checksum-nya) tersandi persis seperti dulu.
     */
    val SIGN_CODES = listOf(
        "KEJ", "KSD", "PDR", "SKH", "PDG", "SSK", "NPT", "KTB", "BKK", "NPU", "DMM", "MGL", "CRB", "MNT", "GJB", "GJT",
        // Ditambahkan bersama daftar periksa nifas (NIFAS).
        "PYD", "BTS", "SDH", "PMD",
    )

    /** Hari sejak melahirkan yang diterima pada pesan N (daftar periksanya sendiri berhenti di hari ke-42). */
    val HARI_RANGE = 0..60

    /** Akhiran jumlah perdarahan untuk PDR, mis. "PDR:B". */
    val BLEEDING_SUFFIX = mapOf('C' to "BERCAK", 'S' to "SEDANG", 'B' to "BANYAK")

    private val ALLOWED = (('A'..'Z') + ('0'..'9') + listOf(';', '.', '/', '-', ':', ' ')).toSet()

    fun isAllowed(text: String) = text.all { it in ALLOWED }

    fun crc8(text: String): Int {
        var crc = 0
        for (ch in text) {
            crc = crc xor (ch.code and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
            }
        }
        return crc
    }

    fun ck(body: String) = crc8(body).toString(16).uppercase().padStart(2, '0')

    /** Bentuk kanonik untuk penguraian dan checksum: huruf kapital, tanpa spasi. */
    fun normalize(raw: String): String = raw.uppercase().filterNot { it.isWhitespace() }

    /** Benar bila SMS tampak milik kita (sebelum validasi apa pun). */
    fun looksLikeBs(raw: String): Boolean = normalize(raw).startsWith("BS")
}

enum class Tingkat(val code: Char) { HIJAU('H'), KUNING('K'), MERAH('M') }

/** Daftar periksa mana yang menghasilkan rujukan; menentukan apakah kolom 5 berisi minggu hamil atau hari nifas. */
enum class FaseRujukan(val type: String) { HAMIL("R"), NIFAS("N") }

enum class StatusKapasitas(val code: Char, val label: String) {
    SIAP('S', "Siap menerima rujukan"),
    TERBATAS('T', "Terbatas, telepon dulu sebelum merujuk"),
    PENUH('P', "Penuh / tidak bisa menerima"),
}

enum class AckStatus(val code: Char, val label: String) {
    SIAP('S', "Siap menerima"),
    PENUH('P', "Penuh / tidak siap, cari fasilitas lain"),
    HUBUNGI('H', "Hubungi balik puskesmas"),
}

/** Tanggal, jam, menit menurut waktu lokal pengirim. */
data class Waktu(val day: Int, val hour: Int, val minute: Int) {
    init {
        require(day in 1..31 && hour in 0..23 && minute in 0..59) { "waktu tidak valid $day $hour $minute" }
    }
    fun encode() = "%02d%02d%02d".format(day, hour, minute)
}

data class Rujukan(
    val msgId: String,
    val pasien: String,
    val tingkat: Tingkat,
    /** Khusus HAMIL (null untuk NIFAS). */
    val usiaMinggu: Int?,
    val tdSistolik: Int?,
    val tdDiastolik: Int?,
    /** Kode dari [Bs1.SIGN_CODES]; PDR boleh berakhiran seperti "PDR:B". */
    val tanda: List<String>,
    val lokasi: String,
    val waktu: Waktu,
    val fase: FaseRujukan = FaseRujukan.HAMIL,
    /** Khusus NIFAS: hari sejak melahirkan. */
    val hariNifas: Int? = null,
)

/** Kapasitas satu faskes, sebagaimana disiarkan gateway puskesmas. */
data class Kapasitas(
    /** ID faskes seperti di faskes.json, 1-8 karakter A-Z 0-9. */
    val faskesId: String,
    val status: StatusKapasitas,
    /** Tempat tidur persalinan / observasi yang kosong, bila pengirim tahu. */
    val tempatTidur: Int?,
    val waktu: Waktu,
)

data class Ack(val msgId: String, val status: AckStatus, val waktu: Waktu)

sealed interface Decoded {
    data class Referral(val value: Rujukan, val checksumVerified: Boolean) : Decoded
    data class Acknowledgement(val value: Ack, val checksumVerified: Boolean) : Decoded
    data class Capacity(val value: Kapasitas, val checksumVerified: Boolean) : Decoded
    data class Error(val kind: ErrorKind, val detail: String, val raw: String) : Decoded
}

enum class ErrorKind { NOT_BS, UNKNOWN_VERSION, UNKNOWN_TYPE, FIELD_COUNT, BAD_FIELD, CHECKSUM, TOO_LONG }

class EncodeException(message: String) : IllegalArgumentException(message)

object Encoder {
    private val PASIEN = Regex("^[A-Z]{2,3}[0-9]{1,3}$")
    private val LOKASI = Regex("^[A-Z0-9]{1,8}$")

    fun encode(r: Rujukan): String {
        validateId(r.msgId)
        if (!PASIEN.matches(r.pasien)) throw EncodeException("pasien harus 2-3 huruf + 1-3 angka")
        if (!LOKASI.matches(r.lokasi)) throw EncodeException("lokasi harus 1-8 karakter A-Z 0-9")
        val uk = when (r.fase) {
            FaseRujukan.HAMIL -> {
                if (r.hariNifas != null) throw EncodeException("hariNifas pada rujukan HAMIL")
                r.usiaMinggu?.let {
                    if (it !in 1..45) throw EncodeException("usia di luar rentang")
                    "%02d".format(it)
                } ?: "-"
            }
            FaseRujukan.NIFAS -> {
                if (r.usiaMinggu != null) throw EncodeException("usiaMinggu pada rujukan NIFAS")
                r.hariNifas?.let {
                    if (it !in Bs1.HARI_RANGE) throw EncodeException("hari di luar rentang")
                    "%02d".format(it)
                } ?: "-"
            }
        }
        val td = if (r.tdSistolik != null && r.tdDiastolik != null) {
            if (r.tdSistolik !in 40..300 || r.tdDiastolik !in 20..200) throw EncodeException("TD di luar rentang")
            "${r.tdSistolik}/${r.tdDiastolik}"
        } else "-"
        val tanda = if (r.tanda.isEmpty()) "-" else canonicalSigns(r.tanda).joinToString(".")
        val body = listOf("BS1", r.fase.type, r.msgId, r.pasien, r.tingkat.code, uk, td, tanda, r.lokasi, r.waktu.encode())
            .joinToString(";")
        return finish(body)
    }

    fun encode(a: Ack): String {
        validateId(a.msgId)
        return finish(listOf("BS1", "A", a.msgId, a.status.code, a.waktu.encode()).joinToString(";"))
    }

    fun encode(k: Kapasitas): String {
        if (!LOKASI.matches(k.faskesId)) throw EncodeException("faskesId harus 1-8 karakter A-Z 0-9")
        val bed = k.tempatTidur?.let {
            if (it !in 0..99) throw EncodeException("tempatTidur di luar rentang")
            it.toString()
        } ?: "-"
        return finish(listOf("BS1", "K", k.faskesId, k.status.code, bed, k.waktu.encode()).joinToString(";"))
    }

    private fun finish(body: String): String {
        val out = "$body;${Bs1.ck(body)}"
        check(out.length <= Bs1.MAX_LEN) { "pesan hasil sandi terlalu panjang (${out.length})" }
        check(Bs1.isAllowed(out)) { "pesan hasil sandi memuat karakter terlarang" }
        return out
    }

    private fun validateId(id: String) {
        if (id.length != 4 || id.any { it !in Bs1.ID_ALPHABET }) throw EncodeException("msgId salah '$id'")
    }

    private fun canonicalSigns(signs: List<String>): List<String> {
        val unique = signs.distinct()
        unique.forEach { s ->
            val base = s.substringBefore(':')
            if (base !in Bs1.SIGN_CODES) throw EncodeException("tanda tidak dikenal '$s'")
            if (':' in s && (base != "PDR" || s.length != 5 || s[4] !in Bs1.BLEEDING_SUFFIX)) {
                throw EncodeException("akhiran tanda salah '$s'")
            }
        }
        if (unique.map { it.substringBefore(':') }.toSet().size != unique.size) throw EncodeException("tanda ganda")
        return unique.sortedBy { Bs1.SIGN_CODES.indexOf(it.substringBefore(':')) }
    }

    /** ID pesan acak yang aman dibaca manusia. Pemanggil menyediakan angka acaknya agar mudah diuji. */
    fun newMsgId(nextInt: (Int) -> Int): String =
        (1..4).map { Bs1.ID_ALPHABET[nextInt(Bs1.ID_ALPHABET.length)] }.joinToString("")
}

/** Tidak pernah melempar galat: setiap masalah dikembalikan sebagai [Decoded.Error]. */
object Decoder {
    private val ID = Regex("^[${Bs1.ID_ALPHABET}]{4}$")
    private val PASIEN = Regex("^[A-Z]{2,3}[0-9]{1,3}$")
    private val LOKASI = Regex("^[A-Z0-9]{1,8}$")
    private val TD = Regex("^([0-9]{2,3})/([0-9]{2,3})$")
    private val TIME = Regex("^[0-9]{6}$")

    fun decode(raw: String): Decoded = try {
        decodeInner(raw)
    } catch (e: Exception) {
        Decoded.Error(ErrorKind.BAD_FIELD, "galat tak terduga: ${e.javaClass.simpleName}", raw)
    }

    private fun decodeInner(raw: String): Decoded {
        val s = Bs1.normalize(raw)
        if (!s.startsWith("BS")) return Decoded.Error(ErrorKind.NOT_BS, "bukan pesan BUNDA SIAGA", raw)
        if (!s.startsWith(Bs1.PREFIX)) return Decoded.Error(ErrorKind.UNKNOWN_VERSION, s.substringBefore(';'), raw)
        if (s.length > Bs1.MAX_LEN) return Decoded.Error(ErrorKind.TOO_LONG, "${s.length} chars", raw)
        val f = s.split(';')
        val expected = when (f.getOrNull(1)) {
            "R", "N" -> 11
            "A" -> 6
            "K" -> 7
            else -> return Decoded.Error(ErrorKind.UNKNOWN_TYPE, f.getOrNull(1) ?: "", raw)
        }
        if (f.size != expected) return Decoded.Error(ErrorKind.FIELD_COUNT, "ditemukan ${f.size}, seharusnya $expected", raw)

        val ckField = f.last()
        val body = s.substring(0, s.lastIndexOf(';'))
        val verified = when (ckField) {
            "-" -> false
            Bs1.ck(body) -> true
            else -> return Decoded.Error(ErrorKind.CHECKSUM, "checksum tidak cocok", raw)
        }

        fun bad(name: String) = Decoded.Error(ErrorKind.BAD_FIELD, name, raw)
        if (f[1] == "K") {
            if (!LOKASI.matches(f[2])) return bad("FASKES")
            val st = StatusKapasitas.entries.firstOrNull { it.code.toString() == f[3] } ?: return bad("ST")
            val bed = when {
                f[4] == "-" -> null
                f[4].length in 1..2 && f[4].all { it.isDigit() } -> f[4].toInt()
                else -> return bad("BED")
            }
            val waktu = parseTime(f[5]) ?: return bad("WAKTU")
            return Decoded.Capacity(Kapasitas(f[2], st, bed, waktu), verified)
        }
        if (!ID.matches(f[2])) return bad("MSGID")

        return if (f[1] == "A") {
            val status = AckStatus.entries.firstOrNull { it.code.toString() == f[3] } ?: return bad("STATUS")
            val waktu = parseTime(f[4]) ?: return bad("WAKTU")
            Decoded.Acknowledgement(Ack(f[2], status, waktu), verified)
        } else {
            if (!PASIEN.matches(f[3])) return bad("PASIEN")
            val tingkat = Tingkat.entries.firstOrNull { it.code.toString() == f[4] } ?: return bad("LV")
            val fase = if (f[1] == "N") FaseRujukan.NIFAS else FaseRujukan.HAMIL
            val range = if (fase == FaseRujukan.NIFAS) Bs1.HARI_RANGE else 1..45
            val age = when {
                f[5] == "-" -> null
                f[5].length in 1..2 && f[5].all { it.isDigit() } -> f[5].toInt().takeIf { it in range } ?: return bad("UK")
                else -> return bad("UK")
            }
            var sis: Int? = null
            var dia: Int? = null
            if (f[6] != "-") {
                val m = TD.matchEntire(f[6]) ?: return bad("TD")
                sis = m.groupValues[1].toInt()
                dia = m.groupValues[2].toInt()
            }
            val tanda = if (f[7] == "-") emptyList() else f[7].split('.')
            tanda.forEach { t ->
                val base = t.substringBefore(':')
                if (base !in Bs1.SIGN_CODES) return bad("TANDA")
                if (':' in t && (base != "PDR" || t.length != 5 || t[4] !in Bs1.BLEEDING_SUFFIX)) return bad("TANDA")
            }
            if (tanda.map { it.substringBefore(':') }.toSet().size != tanda.size) return bad("TANDA")
            if (!LOKASI.matches(f[8])) return bad("LOKASI")
            val waktu = parseTime(f[9]) ?: return bad("WAKTU")
            Decoded.Referral(
                Rujukan(
                    f[2], f[3], tingkat,
                    usiaMinggu = age.takeIf { fase == FaseRujukan.HAMIL },
                    tdSistolik = sis, tdDiastolik = dia, tanda = tanda, lokasi = f[8], waktu = waktu, fase = fase,
                    hariNifas = age.takeIf { fase == FaseRujukan.NIFAS },
                ),
                verified,
            )
        }
    }

    private fun parseTime(s: String): Waktu? {
        if (!TIME.matches(s)) return null
        val d = s.substring(0, 2).toInt()
        val h = s.substring(2, 4).toInt()
        val m = s.substring(4, 6).toInt()
        return if (d in 1..31 && h in 0..23 && m in 0..59) Waktu(d, h, m) else null
    }
}
