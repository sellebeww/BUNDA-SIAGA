package id.bundasiaga.codec

import id.bundasiaga.rules.Fase
import id.bundasiaga.rules.RuleConfigLoader
import id.bundasiaga.rules.Tanda
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CodecTest {

    private val yuliana = Rujukan(
        msgId = "A7K2", pasien = "YL07", tingkat = Tingkat.MERAH, usiaMinggu = 34,
        tdSistolik = 168, tdDiastolik = 112, tanda = listOf("SKH", "BKK"), lokasi = "DSA12",
        waktu = Waktu(30, 15, 45),
    )

    @Test
    fun specExampleShape() {
        val s = Encoder.encode(yuliana)
        // Checksum dicocokkan dengan implementasi CRC-8 Python yang independen.
        assertEquals("BS1;R;A7K2;YL07;M;34;168/112;SKH.BKK;DSA12;301545;3F", s)
        assertEquals("BS1;A;A7K2;S;301552;2A", Encoder.encode(Ack("A7K2", AckStatus.SIAP, Waktu(30, 15, 52))))
        assertEquals(s.substringBeforeLast(';').let(Bs1::ck), s.substringAfterLast(';'))
        val d = assertIs<Decoded.Referral>(Decoder.decode(s))
        assertEquals(yuliana, d.value)
        assertTrue(d.checksumVerified)
    }

    @Test
    fun crc8KnownVector() {
        // CRC-8/SMBUS (polinomial 0x07, awal 0, tanpa pembalikan) dari "123456789" adalah 0xF4.
        assertEquals(0xF4, Bs1.crc8("123456789"))
    }

    @Test
    fun tolerantOfSpacesAndLowercase() {
        val s = Encoder.encode(yuliana)
        val messy = "  " + s.lowercase().replace(";", " ; ") + "\n"
        assertEquals(yuliana, assertIs<Decoded.Referral>(Decoder.decode(messy)).value)
    }

    @Test
    fun ackRoundTrip() {
        for (st in AckStatus.entries) {
            val a = Ack("A7K2", st, Waktu(30, 15, 52))
            val s = Encoder.encode(a)
            assertEquals(a, assertIs<Decoded.Acknowledgement>(Decoder.decode(s)).value)
        }
    }

    @Test
    fun unknownVersionAndGarbageDoNotCrash() {
        assertEquals(ErrorKind.UNKNOWN_VERSION, assertIs<Decoded.Error>(Decoder.decode("BS2;R;A7K2;x")).kind)
        assertEquals(ErrorKind.NOT_BS, assertIs<Decoded.Error>(Decoder.decode("Halo bu, apa kabar?")).kind)
        assertEquals(ErrorKind.FIELD_COUNT, assertIs<Decoded.Error>(Decoder.decode("BS1;R;A7K2")).kind)
        assertEquals(ErrorKind.UNKNOWN_TYPE, assertIs<Decoded.Error>(Decoder.decode("BS1;X;A7K2")).kind)
        assertIs<Decoded.Error>(Decoder.decode(""))
        assertIs<Decoded.Error>(Decoder.decode(";;;;;;;;;;"))
    }

    @Test
    fun humanTypedWithoutChecksumIsAcceptedButUnverified() {
        val d = assertIs<Decoded.Referral>(Decoder.decode("bs1;r;A7K2;YL07;M;34;-;SKH;DSA12;301545;-"))
        assertEquals(false, d.checksumVerified)
    }

    @Test
    fun rejectsBadFields() {
        val good = Encoder.encode(yuliana)
        fun withField(i: Int, v: String): String {
            val f = good.split(';').toMutableList()
            f[i] = v
            val body = f.dropLast(1).joinToString(";")
            return "$body;${Bs1.ck(body)}"
        }
        mapOf(2 to "A0K2", 3 to "YULIANA", 4 to "X", 5 to "99", 6 to "168-112", 7 to "SKH.XXX", 7 to "SKH.SKH", 8 to "DUSUN-A", 9 to "321545")
            .forEach { (i, v) -> assertEquals(ErrorKind.BAD_FIELD, assertIs<Decoded.Error>(Decoder.decode(withField(i, v))).kind, "$i=$v") }
    }

    @Test
    fun codebookCoversEveryRuleSign() {
        Tanda.entries.forEach { assertTrue(it.name in Bs1.SIGN_CODES, "codec lacks ${it.name}") }
        Bs1.SIGN_CODES.forEach { assertTrue(it in Codebook.SIGN_LABELS) }
        // Label pertanyaan ada untuk setiap tanda (di salah satu fase) agar UI dan SMS sepakat.
        val cfgs = Fase.entries.map { RuleConfigLoader.loadDefault(it) }
        Tanda.entries.forEach { t -> assertTrue(cfgs.any { it.question(t.name) != null }, "tidak ada pertanyaan untuk $t") }
    }

    @Test
    fun manualScriptContainsTheData() {
        val s = ManualScript.render(yuliana, "Kader Mince", "0812")
        listOf("MERAH", "34 minggu", "168 per 112", "sakit kepala hebat", "Y-L-0-7", "A-7-K-2").forEach {
            assertTrue(it in s, "tidak ada '$it' di\n$s")
        }
        assertTrue(ManualScript.render(yuliana.copy(tdSistolik = null, tdDiastolik = null)).contains("tidak diukur"))
    }

    private val nifas = Rujukan(
        msgId = "Q3M8", pasien = "SR04", tingkat = Tingkat.MERAH, usiaMinggu = null,
        tdSistolik = null, tdDiastolik = null, tanda = listOf("PDR", "PMD"), lokasi = "DSB",
        waktu = Waktu(1, 6, 5), fase = FaseRujukan.NIFAS, hariNifas = 3,
    )

    @Test
    fun nifasReferralRoundTrip() {
        val s = Encoder.encode(nifas)
        assertTrue(s.startsWith("BS1;N;Q3M8;SR04;M;03;-;PDR.PMD;DSB;010605;"), s)
        val d = assertIs<Decoded.Referral>(Decoder.decode(s))
        assertEquals(nifas, d.value)
        assertTrue(d.checksumVerified)
        // Hari ke-0 (hari melahirkan) sah untuk NIFAS, tetapi minggu ke-0 tidak sah untuk HAMIL.
        assertEquals(0, assertIs<Decoded.Referral>(Decoder.decode(Encoder.encode(nifas.copy(hariNifas = 0)))).value.hariNifas)
        assertFailsWith<EncodeException> { Encoder.encode(nifas.copy(usiaMinggu = 30)) }
        assertFailsWith<EncodeException> { Encoder.encode(yuliana.copy(hariNifas = 3)) }
        assertFailsWith<EncodeException> { Encoder.encode(nifas.copy(hariNifas = 61)) }
    }

    @Test
    fun nifasScriptSaysDaysAfterBirth() {
        val s = ManualScript.render(nifas)
        assertTrue("ibu nifas" in s && "hari ke-3" in s && "berpikir menyakiti diri atau bayi" in s, s)
        assertTrue("Usia kehamilan" !in s)
    }

    @Test
    fun existingReferralEncodingIsUnchanged() {
        // Menambah kode tanda baru tidak boleh mengubah cara pesan lama disandi (atau CRC-nya).
        assertEquals(
            "BS1;R;K7M2;SR03;M;30;-;PDR:B.GJT;DSB01;301420;",
            Encoder.encode(yuliana.copy(msgId = "K7M2", pasien = "SR03", usiaMinggu = 30, tdSistolik = null, tdDiastolik = null,
                tanda = listOf("GJT", "PDR:B"), lokasi = "DSB01", waktu = Waktu(30, 14, 20))).substringBeforeLast(';') + ";",
        )
    }

    @Test
    fun capacityRoundTripAndValidation() {
        val k = Kapasitas("PKM1", StatusKapasitas.TERBATAS, 2, Waktu(30, 16, 0))
        val s = Encoder.encode(k)
        assertTrue(s.startsWith("BS1;K;PKM1;T;2;301600;"), s)
        val d = assertIs<Decoded.Capacity>(Decoder.decode(s))
        assertEquals(k, d.value)
        assertTrue(d.checksumVerified)
        assertEquals(null, assertIs<Decoded.Capacity>(Decoder.decode(Encoder.encode(k.copy(tempatTidur = null)))).value.tempatTidur)
        // Diketik manual tanpa checksum: diterima, ditandai belum terverifikasi.
        assertEquals(false, assertIs<Decoded.Capacity>(Decoder.decode("bs1;k;rs1;p;-;301600;-")).checksumVerified)
        fun resign(body: String) = "$body;${Bs1.ck(body)}"
        listOf("BS1;K;RS-1;S;1;301600", "BS1;K;RS1;X;1;301600", "BS1;K;RS1;S;100;301600", "BS1;K;RS1;S;1;302500").forEach {
            assertEquals(ErrorKind.BAD_FIELD, assertIs<Decoded.Error>(Decoder.decode(resign(it))).kind, it)
        }
        assertFailsWith<EncodeException> { Encoder.encode(k.copy(faskesId = "PKM-1")) }
        assertFailsWith<EncodeException> { Encoder.encode(k.copy(tempatTidur = 100)) }
    }

    // ---- tes fuzz / properti ------------------------------------------------

    private fun randomReferral(r: Random): Rujukan {
        val signs = Bs1.SIGN_CODES.filter { r.nextInt(3) == 0 }.map {
            if (it == "PDR" && r.nextBoolean()) "PDR:" + Bs1.BLEEDING_SUFFIX.keys.random(r) else it
        }.shuffled(r)
        val hasTd = r.nextBoolean()
        val letters = ('A'..'Z')
        return Rujukan(
            msgId = Encoder.newMsgId { r.nextInt(it) },
            pasien = (1..r.nextInt(2, 4)).map { letters.random(r) }.joinToString("") + r.nextInt(0, 1000),
            tingkat = Tingkat.entries.random(r),
            usiaMinggu = if (r.nextBoolean()) r.nextInt(1, 46) else null,
            tdSistolik = if (hasTd) r.nextInt(40, 301) else null,
            tdDiastolik = if (hasTd) r.nextInt(20, 201) else null,
            tanda = signs,
            lokasi = (1..r.nextInt(1, 9)).map { (letters + ('0'..'9')).random(r) }.joinToString(""),
            waktu = Waktu(r.nextInt(1, 32), r.nextInt(0, 24), r.nextInt(0, 60)),
        ).let {
            if (r.nextInt(3) == 0) it.copy(fase = FaseRujukan.NIFAS, usiaMinggu = null, hariNifas = if (r.nextBoolean()) r.nextInt(0, 61) else null)
            else it
        }
    }

    @Test
    fun fuzzRoundTripLengthAndCharset() {
        val r = Random(2026)
        repeat(50_000) {
            val ref = randomReferral(r)
            val s = Encoder.encode(ref)
            assertTrue(s.length <= 160, "terlalu panjang: ${s.length} $s")
            assertTrue(Bs1.isAllowed(s), "karakter terlarang: $s")
            val d = assertIs<Decoded.Referral>(Decoder.decode(s), s)
            // Penyandi mengkanonikkan urutan tanda; bandingkan sebagai himpunan.
            assertEquals(ref.copy(tanda = emptyList()), d.value.copy(tanda = emptyList()))
            assertEquals(ref.tanda.toSet(), d.value.tanda.toSet())
        }
    }

    @Test
    fun worstCaseFitsIn160() {
        val worst = Rujukan(
            msgId = "ZZZZ", pasien = "ABC999", tingkat = Tingkat.MERAH, usiaMinggu = 45,
            tdSistolik = 300, tdDiastolik = 200,
            tanda = Bs1.SIGN_CODES.map { if (it == "PDR") "PDR:B" else it },
            lokasi = "ABCDEFGH", waktu = Waktu(31, 23, 59),
        )
        val s = Encoder.encode(worst)
        println("panjang kasus terburuk = ${s.length}: $s")
        assertTrue(s.length <= 160)
    }

    @Test
    fun checksumRejectsEverySingleCharCorruption() {
        val r = Random(99)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789;./-:"
        repeat(3_000) {
            val s = Encoder.encode(randomReferral(r))
            val i = r.nextInt(s.length)
            val c = alphabet.filter { it != s[i] }.random(r)
            val corrupted = s.substring(0, i) + c + s.substring(i + 1)
            val d = Decoder.decode(corrupted)
            // Kerusakan tidak boleh pernah diterima diam-diam sebagai pesan yang *terverifikasi*.
            if (d is Decoded.Referral) assertEquals(false, d.checksumVerified, "kerusakan diterima: $corrupted")
            if (d is Decoded.Acknowledgement) assertEquals(false, d.checksumVerified, "kerusakan diterima: $corrupted")
        }
    }

    @Test
    fun decoderNeverThrowsOnRandomInput() {
        val r = Random(1)
        val chars = "BS1;RA.:/-0123456789ABCXYZ abc\n€|{}^~éあ😀"
        repeat(50_000) {
            val len = r.nextInt(0, 200)
            val s = (if (r.nextBoolean()) "BS1;" else "") + (1..len).map { chars.random(r) }.joinToString("")
            Decoder.decode(s) // tidak boleh melempar galat
        }
    }

    @Test
    fun msgIdAlphabetHasNoAmbiguousChars() {
        listOf('0', 'O', '1', 'I').forEach { assertTrue(it !in Bs1.ID_ALPHABET) }
        val r = Random(4)
        repeat(1000) { assertTrue(Encoder.newMsgId { r.nextInt(it) }.all { it in Bs1.ID_ALPHABET }) }
    }
}
