package id.bundasiaga.app

import id.bundasiaga.app.data.ReferralMapper
import id.bundasiaga.codec.Decoded
import id.bundasiaga.codec.Decoder
import id.bundasiaga.codec.Encoder
import id.bundasiaga.codec.FaseRujukan
import id.bundasiaga.codec.Tingkat
import id.bundasiaga.rules.Fase
import id.bundasiaga.rules.GerakJanin
import id.bundasiaga.rules.Jawaban
import id.bundasiaga.rules.Level
import id.bundasiaga.rules.Pemeriksaan
import id.bundasiaga.rules.SubPerdarahan
import id.bundasiaga.rules.Tanda
import id.bundasiaga.rules.TdStatus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReferralMapperTest {

    @Test
    fun initialsNeverKeepTheFullName() {
        assertEquals("YL", ReferralMapper.initials("Yuliana L"))
        assertEquals("YUL", ReferralMapper.initials("Yuliana"))
        assertEquals("SMR", ReferralMapper.initials("Siti Maria Rosa Dewi"))
        assertEquals("YL07", ReferralMapper.patientCode("YL", 7))
        assertEquals("XX03", ReferralMapper.patientCode("", 3))
    }

    @Test
    fun lokasiIsSanitisedForTheSmsAlphabet() {
        assertEquals("DSA12", ReferralMapper.lokasiCode("dsa-12"))
        assertEquals("ABCDEFGH", ReferralMapper.lokasiCode("abcdefghijk"))
        assertEquals("X", ReferralMapper.lokasiCode("é!"))
    }

    @Test
    fun examMapsToAValidSms() {
        val p = Pemeriksaan(
            usiaKehamilanMinggu = 36, tdStatus = TdStatus.TIDAK_ADA_ALAT, tdSistolik = 150, tdDiastolik = 95,
            jawaban = mapOf(Tanda.PDR to Jawaban.YA, Tanda.SKH to Jawaban.BELUM_DIPERIKSA, Tanda.KEJ to Jawaban.TIDAK),
            subPerdarahan = SubPerdarahan.BANYAK, gerakJanin = GerakJanin.BERKURANG,
        )
        val r = ReferralMapper.build(p, Level.MERAH, "A7K2", "YL07", "Dusun A", 0L)
        // Angka tekanan darah hanya dikirim bila benar-benar diukur.
        assertEquals(null, r.tdSistolik)
        assertEquals(listOf("PDR:B", "GJB"), r.tanda)
        assertEquals(Tingkat.MERAH, r.tingkat)
        val sms = Encoder.encode(r)
        assertEquals(r, assertIs<Decoded.Referral>(Decoder.decode(sms)).value)
    }

    @Test
    fun nifasExamMapsToAnNSms() {
        val p = Pemeriksaan(
            fase = Fase.NIFAS, hariNifas = 4, tdStatus = TdStatus.TERUKUR, tdSistolik = 150, tdDiastolik = 96,
            jawaban = mapOf(Tanda.PDR to Jawaban.YA, Tanda.PYD to Jawaban.YA, Tanda.SDH to Jawaban.TIDAK),
        )
        val r = ReferralMapper.build(p, Level.MERAH, "Q3M8", "SR04", "DSB", 0L)
        assertEquals(FaseRujukan.NIFAS, r.fase)
        assertEquals(4, r.hariNifas)
        assertEquals(null, r.usiaMinggu)
        // Tidak ada pertanyaan jumlah pada NIFAS: PDR biasa, tidak pernah kode GJ*.
        assertEquals(listOf("PDR", "PYD"), r.tanda)
        val sms = Encoder.encode(r)
        assertTrue(sms.startsWith("BS1;N;Q3M8;SR04;M;04;150/96;PDR.PYD;DSB;"), sms)
        assertEquals(r, assertIs<Decoded.Referral>(Decoder.decode(sms)).value)
    }

    @Test
    fun bpPlausibility() {
        assertEquals(true, ReferralMapper.isBpPlausible(168, 112))
        assertEquals(false, ReferralMapper.isBpPlausible(82, 83))
        assertEquals(false, ReferralMapper.isBpPlausible(1680, 112))
        assertEquals(false, ReferralMapper.isBpPlausible(null, 80))
    }
}
