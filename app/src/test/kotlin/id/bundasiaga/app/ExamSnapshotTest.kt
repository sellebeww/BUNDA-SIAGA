package id.bundasiaga.app

import id.bundasiaga.app.data.ExamSnapshot
import id.bundasiaga.app.data.InputValidation
import id.bundasiaga.rules.*
import kotlin.test.*
import org.junit.Test

class ExamSnapshotTest {
    @Test fun unfinishedExamRetainsUnknownAnswersAndBleedingDetail() {
        val p = Pemeriksaan(fase = Fase.NIFAS, hariNifas = 0, tdStatus = TdStatus.TIDAK_ADA_ALAT,
            jawaban = mapOf(Tanda.PDR to Jawaban.YA, Tanda.KEJ to Jawaban.BELUM_DIPERIKSA, Tanda.DMM to Jawaban.TIDAK),
            subPerdarahan = SubPerdarahan.BANYAK, suhuC = 38.5)
        assertEquals(p, ExamSnapshot.decode(ExamSnapshot.encode(p)))
    }

    @Test fun pregnancyRetainsBloodPressureAndFetalMovement() {
        val p = Pemeriksaan(usiaKehamilanMinggu = 35, tdStatus = TdStatus.TERUKUR, tdSistolik = 165, tdDiastolik = 110,
            gerakJanin = GerakJanin.BERKURANG)
        assertEquals(p, ExamSnapshot.decode(ExamSnapshot.encode(p)))
    }

    @Test fun historicalResultRetainsExactReasonsVersionAndAdvice() {
        for (fase in Fase.entries) {
            val engine = RuleEngine(RuleConfigLoader.loadDefault(fase))
            val h = engine.evaluate(Pemeriksaan(fase = fase, jawaban = mapOf(Tanda.KEJ to Jawaban.YA)))
            assertEquals(h, ExamSnapshot.decodeResult(ExamSnapshot.encodeResult(h)))
        }
    }

    @Test fun readsPreviousInputFormatWithoutTemperature() {
        val text = """{"fase":"HAMIL","uk":null,"hari":null,"tdStatus":"BELUM_DIPERIKSA","sis":null,"dia":null,"sub":null,"gjn":"BELUM","jawaban":{}}"""
        assertEquals(Pemeriksaan(), ExamSnapshot.decode(text))
    }

    @Test fun rejectsCorruptedSnapshotInsteadOfSilentlyDefaulting() {
        assertFails { ExamSnapshot.decode("{}") }
        assertFails { ExamSnapshot.decodeResult("{}") }
        assertFails { ExamSnapshot.decode(ExamSnapshot.encode(Pemeriksaan()).replace("HAMIL", "INVALID")) }
    }

    @Test fun validatesPhoneNumbersWithoutAcceptingServiceCodesOrPlaceholderNumbers() {
        listOf("081234567890", "+62 812-3456-7890", "(021) 5551234").forEach { assertTrue(InputValidation.phone(it), it) }
        listOf("", "000000000001".replace('1', '0'), "1234", "1234567890123456", "*123#", "+62abc812345", "٠٨١٢٣٤٥", "08+1234567").forEach { assertFalse(InputValidation.phone(it), it) }
    }

    @Test fun rejectsInsecureOrCredentialBearingProductionServerUrls() {
        assertTrue(InputValidation.serverUrl("https://server.example/api", false))
        assertTrue(InputValidation.serverUrl("", false))
        assertTrue(InputValidation.serverUrl("http://10.0.2.2:8080", true))
        listOf("http://example.com", "https://token@example.com", "https://example.com?token=x", "https://example.com#x", "https://", "file:///tmp/x").forEach {
            assertFalse(InputValidation.serverUrl(it, false), it)
        }
    }
}
