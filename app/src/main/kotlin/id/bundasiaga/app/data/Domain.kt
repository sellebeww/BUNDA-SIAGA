package id.bundasiaga.app.data

import id.bundasiaga.codec.FaseRujukan
import id.bundasiaga.codec.Rujukan
import id.bundasiaga.codec.Tingkat
import id.bundasiaga.codec.Waktu
import id.bundasiaga.rules.Fase
import id.bundasiaga.rules.GerakJanin
import id.bundasiaga.rules.Jawaban
import id.bundasiaga.rules.Level
import id.bundasiaga.rules.Pemeriksaan
import id.bundasiaga.rules.SubPerdarahan
import id.bundasiaga.rules.Tanda
import id.bundasiaga.rules.TdStatus
import id.bundasiaga.rules.measuredBp
import org.json.JSONObject
import java.util.Calendar

/** Pemetaan murni antara model aturan dan model SMS. Tanpa tipe Android. */
object ReferralMapper {

    fun signCodes(p: Pemeriksaan): List<String> = buildList {
        val sub = p.subPerdarahan
        Tanda.entries.filter { p.jawab(it) == Jawaban.YA }.forEach { t ->
            if (t == Tanda.PDR && sub != null) {
                add(
                    "PDR:" + when (sub) {
                        SubPerdarahan.BERCAK -> 'C'
                        SubPerdarahan.SEDANG -> 'S'
                        SubPerdarahan.BANYAK -> 'B'
                    },
                )
            } else add(t.name)
        }
        when (p.gerakJanin) {
            GerakJanin.BERKURANG -> add("GJB")
            GerakJanin.TIDAK_ADA -> add("GJT")
            else -> Unit
        }
    }

    fun tingkat(level: Level) = when (level) {
        Level.HIJAU -> Tingkat.HIJAU
        Level.KUNING -> Tingkat.KUNING
        Level.MERAH -> Tingkat.MERAH
    }

    /** "Yuliana L" -> "YL"; hanya 2-3 huruf, tidak pernah nama lengkap. */
    fun initials(text: String): String {
        val letters = text.uppercase().filter { it in 'A'..'Z' || it == ' ' }.trim()
        val words = letters.split(' ').filter { it.isNotBlank() }
        val raw = if (words.size >= 2) words.take(3).joinToString("") { it.take(1) } else letters.replace(" ", "").take(3)
        return raw.take(3)
    }

    fun patientCode(initials: String, number: Int): String {
        val i = initials.uppercase().filter { it in 'A'..'Z' }.take(3).padEnd(2, 'X')
        return i + number.coerceIn(0, 999).toString().padStart(2, '0')
    }

    fun lokasiCode(text: String): String =
        text.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(8).ifEmpty { "X" }

    fun waktu(millis: Long): Waktu {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return Waktu(c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    fun build(p: Pemeriksaan, level: Level, msgId: String, pasien: String, lokasi: String, now: Long): Rujukan {
        val bp = p.measuredBp()
        val nifas = p.fase == Fase.NIFAS
        return Rujukan(
            msgId = msgId,
            pasien = pasien,
            tingkat = tingkat(level),
            usiaMinggu = if (nifas) null else p.usiaKehamilanMinggu?.takeIf { it in 1..45 },
            tdSistolik = bp?.first,
            tdDiastolik = bp?.second,
            tanda = signCodes(p),
            lokasi = lokasiCode(lokasi),
            waktu = waktu(now),
            fase = if (nifas) FaseRujukan.NIFAS else FaseRujukan.HAMIL,
            hariNifas = if (nifas) p.hariNifas?.takeIf { it in id.bundasiaga.codec.Bs1.HARI_RANGE } else null,
        )
    }

    fun toJson(p: Pemeriksaan): String = JSONObject().apply {
        put("fase", p.fase.name)
        put("uk", p.usiaKehamilanMinggu ?: JSONObject.NULL)
        put("hari", p.hariNifas ?: JSONObject.NULL)
        put("tdStatus", p.tdStatus.name)
        put("sis", p.tdSistolik ?: JSONObject.NULL)
        put("dia", p.tdDiastolik ?: JSONObject.NULL)
        put("sub", p.subPerdarahan?.name ?: JSONObject.NULL)
        put("gjn", p.gerakJanin.name)
        put("jawaban", JSONObject(p.jawaban.mapKeys { it.key.name }.mapValues { it.value.name }))
    }.toString()

    fun isBpPlausible(sys: Int?, dia: Int?): Boolean =
        sys != null && dia != null && sys in 60..260 && dia in 30..160 && sys > dia

    fun tdLabel(status: TdStatus) = status.name

    /** Hari terakhir masa nifas yang dicakup daftar periksa NIFAS. */
    const val MAX_HARI_NIFAS = 42
}
