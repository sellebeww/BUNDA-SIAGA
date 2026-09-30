package id.bundasiaga.rules

import org.junit.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuleBundleTest {
    private val keys = RuleBundle.generateKeyPair()
    private val trusted = mapOf("dinkes-test" to keys.public)
    private val nifasText = RuleConfigLoader.defaultText(Fase.NIFAS)

    @Test
    fun signAndVerifyRoundTrip() {
        val bundle = RuleBundle.sign(nifasText, "dinkes-test", keys.private)
        val v = RuleBundle.verify(bundle, trusted)
        assertEquals(nifasText, v.configText)
        assertEquals(Fase.NIFAS, v.config.fase)
        assertEquals("dinkes-test", v.keyId)
        assertEquals(RuleBundle.Header("NIFAS", "nifas-0.1.0-draft", 1, "dinkes-test"), RuleBundle.header(bundle))
    }

    @Test
    fun keysSurvivePemAndTrustFile() {
        val priv = RuleBundle.parsePrivateKeyPem(RuleBundle.privateKeyPem(keys.private))
        val trust = RuleBundle.parseTrustFile("# comment\n\ndinkes-test ${RuleBundle.publicKeyBase64(keys.public)}  # note\n")
        val bundle = RuleBundle.sign(nifasText, "dinkes-test", priv)
        RuleBundle.verify(bundle, trust)
        assertFailsWith<RuleBundleException> { RuleBundle.parseTrustFile("Bad_Id AAAA") }
    }

    @Test
    fun rejectsUntrustedKeyAndTampering() {
        val bundle = RuleBundle.sign(nifasText, "dinkes-test", keys.private)
        // Key id tidak dikenal, dan kunci lain di bawah id yang dipercaya.
        assertFailsWith<RuleBundleException> { RuleBundle.verify(bundle, mapOf("other" to keys.public)) }
        assertFailsWith<RuleBundleException> { RuleBundle.verify(bundle, mapOf("dinkes-test" to RuleBundle.generateKeyPair().public)) }

        // Isi diubah setelah ditandatangani (mis. ambang diturunkan): tanda tangan tidak lagi cocok.
        val evil = nifasText.replace("\"bpAtLeast\": [160, 110]", "\"bpAtLeast\": [200, 150]")
        val b64 = Base64.getEncoder()
        val tampered = bundle.replace(b64.encodeToString(nifasText.toByteArray()), b64.encodeToString(evil.toByteArray()))
        assertTrue(tampered != bundle)
        assertFailsWith<RuleBundleException> { RuleBundle.verify(tampered, trusted) }

        // Amplop berbohong soal versi.
        assertFailsWith<RuleBundleException> { RuleBundle.verify(bundle.replace("\"seq\": 1", "\"seq\": 9"), trusted) }
        assertFailsWith<RuleBundleException> { RuleBundle.verify("bukan json", trusted) }
    }

    @Test
    fun refusesToSignUnsafeConfig() {
        // Membuat setiap pemeriksaan lengkap menjadi KUNING: melanggar "semua TIDAK menghasilkan HIJAU".
        val unsafe = nifasText.replace(
            "{ \"id\": \"N-K01\", \"level\": \"KUNING\", \"when\": { \"bpAtLeast\": [140, 90] },",
            "{ \"id\": \"N-K01\", \"level\": \"KUNING\", \"when\": { \"bpAtLeast\": [100, 60] },",
        )
        assertTrue(unsafe != nifasText)
        val e = assertFailsWith<RuleBundleException> { RuleBundle.sign(unsafe, "dinkes-test", keys.private) }
        assertTrue("uji keamanan" in (e.message ?: ""))
        assertFailsWith<RuleBundleException> { RuleBundle.sign(nifasText, "Kunci Salah", keys.private) }
    }
}
