package id.bundasiaga.server

import id.bundasiaga.rules.Fase
import id.bundasiaga.rules.RuleBundle
import id.bundasiaga.rules.RuleConfigLoader
import id.bundasiaga.server.satusehat.FacilityMap
import id.bundasiaga.server.satusehat.FhirMapper
import id.bundasiaga.server.satusehat.HttpCall
import id.bundasiaga.server.satusehat.SatuSehatClient
import id.bundasiaga.server.satusehat.SatuSehatConfig
import id.bundasiaga.server.satusehat.SatuSehatService
import id.bundasiaga.server.satusehat.Terminology
import io.ktor.client.request.basicAuth
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeaturesTest {
    private val token = "test-token-0123456789"
    private fun tmpDb() = File.createTempFile("bunda", ".db").also { it.deleteOnExit() }.absolutePath

    private val nifasBatch = """{"facility":"Puskesmas Contoh","cases":[
        {"caseKey":"abc:Q3M8","tingkat":"MERAH","td":"150/96","tanda":"PDR.PMD","lokasi":"DSB","pasien":"SR04",
         "checksumVerified":true,"receivedAt":1000,"fase":"NIFAS","hariNifas":3},
        {"caseKey":"abc:A7K2","tingkat":"KUNING","usiaMinggu":34,"td":"146/94","tanda":"SKH","lokasi":"DSA12","pasien":"YL07",
         "checksumVerified":true,"receivedAt":2000}]}"""

    @Test
    fun nifasCasesAreStoredAndShown() = testApplication {
        val st = Store(tmpDb())
        application { module(st, ServerConfig(setOf(token), "dinkes", "")) }
        client.post("/api/v1/cases") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(nifasBatch) }
        val byKey = st.all().associateBy { it.c.caseKey }
        assertEquals("NIFAS", byKey["abc:Q3M8"]!!.c.fase)
        assertEquals(3, byKey["abc:Q3M8"]!!.c.hariNifas)
        // Gateway lama yang tidak mengirim fase dianggap HAMIL.
        assertEquals("HAMIL", byKey["abc:A7K2"]!!.c.fase)
        val html = client.get("/").bodyAsText()
        assertTrue("hari ke-3" in html && "pikiran menyakiti diri/bayi" in html, html)
    }

    @Test
    fun opensADatabaseFromVersion01() {
        val path = tmpDb()
        DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.createStatement().use {
                it.executeUpdate(
                    """CREATE TABLE cases (facility TEXT NOT NULL, case_key TEXT NOT NULL, tingkat TEXT, usia INTEGER, td TEXT,
                       tanda TEXT, lokasi TEXT, pasien TEXT, checksum_ok INTEGER, unparsed INTEGER, received_at INTEGER NOT NULL,
                       ack_status TEXT, ack_at INTEGER, duplicates INTEGER, updated_at INTEGER NOT NULL, PRIMARY KEY (facility, case_key))""",
                )
                it.executeUpdate("INSERT INTO cases VALUES ('P','k1','MERAH',30,NULL,'KEJ','L','AB01',1,0,5,NULL,NULL,0,5)")
            }
        }
        val st = Store(path)
        assertEquals("HAMIL", st.all().single().c.fase)
        st.upsert(Batch("P", cases = listOf(CaseIn("k1", "MERAH", receivedAt = 5, fase = "NIFAS", hariNifas = 2))), 10)
        assertEquals(2, st.all().single().c.hariNifas)
    }

    @Test
    fun rulesMirrorServesOnlyValidBundles() = testApplication {
        val dir = Files.createTempDirectory("rules").toFile()
        val keys = RuleBundle.generateKeyPair()
        val bundle = RuleBundle.sign(RuleConfigLoader.defaultText(Fase.NIFAS), "dinkes-test", keys.private)
        File(dir, "nifas.bundle.json").writeText(bundle)
        File(dir, "hamil.bundle.json").writeText(bundle) // fase salah pada nama berkas: tidak boleh disajikan
        val mirror = RulesMirror(dir, mapOf("dinkes-test" to keys.public))
        application { module(Store(tmpDb()), ServerConfig(setOf(token), "dinkes", "pw-dashboard"), rules = mirror) }

        assertEquals(bundle, client.get("/api/v1/rules/nifas").bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/rules/hamil").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/rules/xyz").status)
        val index = client.get("/api/v1/rules").bodyAsText()
        assertTrue("\"fase\":\"NIFAS\"" in index && "\"verified\":true" in index, index)

        // Ditandatangani kunci yang tidak dipercaya operator: tidak ditawarkan ke HP.
        val other = RulesMirror(dir, mapOf("dinkes-test" to RuleBundle.generateKeyPair().public))
        assertNull(other.bundle(Fase.NIFAS))
        assertTrue("TIDAK VALID" in other.describe())
    }

    @Test
    fun privacyPageIsPublic() = testApplication {
        application { module(Store(tmpDb()), ServerConfig(setOf(token), "dinkes", "pw-dashboard")) }
        val r = client.get("/privasi")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue("Kebijakan Privasi" in r.bodyAsText())
    }

    // ---- SATUSEHAT -----------------------------------------------------------------

    private val facility = FacilityMap("LOC-1", "Poli KIA", "N10000001", "Bidan Contoh")
    private val ssConfig = SatuSehatConfig(
        "https://auth.test/oauth2/v1", "https://fhir.test/fhir-r4/v1", "cid", "secret", "ORG-1",
        mapOf("Puskesmas Contoh" to facility),
    )

    /** SATUSEHAT palsu: mencatat permintaan, menjawab seperti API sungguhan. */
    private class FakeSatuSehat : HttpCall {
        val calls = mutableListOf<Triple<String, String, String?>>()
        var bundleStatus = 200
        override fun send(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
            calls += Triple(method, url, body)
            return when {
                url.contains("/accesstoken") -> 200 to """{"access_token":"tok-1","expires_in":"3599"}"""
                url.contains("/Patient?identifier=") -> {
                    assertEquals("Bearer tok-1", headers["Authorization"])
                    200 to """{"resourceType":"Bundle","entry":[{"resource":{"resourceType":"Patient","id":"P02478375538"}}]}"""
                }
                method == "POST" && bundleStatus == 200 -> 200 to """{"resourceType":"Bundle","type":"transaction-response","entry":[
                    {"response":{"status":"201 Created","location":"Encounter/enc-123/_history/1"}},
                    {"response":{"status":"201 Created","location":"Observation/obs-1/_history/1"}}]}"""
                else -> 400 to """{"resourceType":"OperationOutcome","issue":[{"severity":"error","code":"invalid","diagnostics":"pasien tidak ditemukan"}]}"""
            }
        }
    }

    @Test
    fun fhirBundleHasTheExpectedShape() {
        val row = CaseRow("Puskesmas Contoh", CaseIn("abc:Q3M8", "MERAH", td = "150/96", tanda = "PDR.PMD", pasien = "SR04", receivedAt = 0, fase = "NIFAS", hariNifas = 3), 0)
        val b = FhirMapper(Terminology.load(null), "ORG-1").bundle(row, "P1", facility)
        assertEquals("transaction", b["type"]!!.jsonPrimitive.content)
        val resources = b["entry"]!!.jsonArray.map { it.jsonObject["resource"]!!.jsonObject }
        assertEquals(
            listOf("Encounter", "Observation", "Observation", "Observation", "Observation", "Observation", "ServiceRequest"),
            resources.map { it["resourceType"]!!.jsonPrimitive.content },
        )
        val enc = resources.first()
        assertEquals("Patient/P1", enc["subject"]!!.jsonObject["reference"]!!.jsonPrimitive.content)
        assertEquals("1970-01-01T00:00:00+00:00", enc["period"]!!.jsonObject["start"]!!.jsonPrimitive.content)
        assertEquals("http://sys-ids.kemkes.go.id/encounter/ORG-1", enc["identifier"]!!.jsonArray[0].jsonObject["system"]!!.jsonPrimitive.content)
        val text = b.toString()
        assertTrue("8480-6" in text && "8462-4" in text && "hari-nifas" in text && "PMD" in text)
        assertTrue("49051-6" !in text, "usia kehamilan tidak boleh dikirim untuk NIFAS")
        val sr = resources.last()
        assertEquals("stat", sr["priority"]!!.jsonPrimitive.content)
        assertEquals(5, sr["reasonReference"]!!.jsonArray.size)
        assertTrue("bukan diagnosis" in sr.toString())
        // Deterministik: kasus yang sama selalu menghasilkan paket yang sama.
        assertEquals(b, FhirMapper(Terminology.load(null), "ORG-1").bundle(row, "P1", facility))
    }

    @Test
    fun clientCachesTokenAndReadsErrors() {
        val http = FakeSatuSehat()
        var now = 0L
        val c = SatuSehatClient(ssConfig, http) { now }
        assertEquals("tok-1", c.token())
        c.token()
        assertEquals(1, http.calls.count { "/accesstoken" in it.second })
        assertEquals("POST", http.calls.first().first)
        assertTrue(http.calls.first().third!!.contains("client_id=cid"))
        now += 3_600_000
        c.token()
        assertEquals(2, http.calls.count { "/accesstoken" in it.second })

        assertEquals("P02478375538", c.patientIdByNik("3201234567890001"))
        assertTrue(http.calls.last().second.contains("identifier=https%3A%2F%2Ffhir.kemkes.go.id%2Fid%2Fnik%7C3201234567890001"))
        assertEquals("enc-123", c.postBundle(JsonObject(emptyMap())))
        http.bundleStatus = 400
        val e = runCatching { c.postBundle(JsonObject(emptyMap())) }.exceptionOrNull()
        assertTrue(e?.message?.contains("pasien tidak ditemukan") == true, e?.message)
    }

    @Test
    fun linkSendAndNeverSendTwice() = testApplication {
        val st = Store(tmpDb())
        val http = FakeSatuSehat()
        val ss = SatuSehatService(ssConfig, Terminology.load(null), st, SatuSehatClient(ssConfig, http))
        application { module(st, ServerConfig(setOf(token), "dinkes", "pw-dashboard"), satusehat = ss) }
        client.post("/api/v1/cases") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(nifasBatch) }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/satusehat").status)
        val page = client.get("/satusehat") { basicAuth("dinkes", "pw-dashboard") }.bodyAsText()
        val csrf = Regex("name=\"csrf\" value=\"([0-9a-f]+)\"").find(page)!!.groupValues[1]

        // Tanpa token CSRF: ditolak, tidak ada yang ditautkan.
        val forged = client.submitForm("/satusehat/link", parameters { append("facility", "Puskesmas Contoh"); append("caseKey", "abc:Q3M8"); append("ihs", "P1") }) {
            basicAuth("dinkes", "pw-dashboard")
        }
        assertEquals(HttpStatusCode.Forbidden, forged.status)
        assertTrue(st.links().isEmpty())

        fun form(extra: io.ktor.http.ParametersBuilder.() -> Unit) = parameters {
            append("csrf", csrf); append("facility", "Puskesmas Contoh"); append("caseKey", "abc:Q3M8"); extra()
        }
        client.submitForm("/satusehat/link", form { append("nik", "3201234567890001") }) { basicAuth("dinkes", "pw-dashboard") }
        val link = st.links()["Puskesmas Contoh" to "abc:Q3M8"]
        assertNotNull(link)
        assertEquals("P02478375538", link.patientIhs)
        assertTrue(st.all().none { "3201234567890001" in it.toString() } && "3201234567890001" !in link.toString())

        client.submitForm("/satusehat/send", form {}) { basicAuth("dinkes", "pw-dashboard") }
        assertEquals("SENT", st.links()["Puskesmas Contoh" to "abc:Q3M8"]!!.status)
        assertEquals("enc-123", st.links()["Puskesmas Contoh" to "abc:Q3M8"]!!.encounterId)
        val posts = http.calls.count { it.first == "POST" && it.second == ssConfig.fhirUrl }
        assertEquals(posts, 1)
        assertTrue(ss.send("Puskesmas Contoh", "abc:Q3M8").startsWith("Sudah terkirim"))
        assertEquals(1, http.calls.count { it.first == "POST" && it.second == ssConfig.fhirUrl })

        val preview = client.get("/satusehat/preview?facility=Puskesmas%20Contoh&case=abc%3AQ3M8") { basicAuth("dinkes", "pw-dashboard") }.bodyAsText()
        assertEquals("Bundle", Json.parseToJsonElement(preview).jsonObject["resourceType"]!!.jsonPrimitive.content)
    }

    @Test
    fun disabledIntegrationStillPreviews() {
        val st = Store(tmpDb())
        st.upsert(Batch("Puskesmas X", cases = listOf(CaseIn("k", "KUNING", usiaMinggu = 30, receivedAt = 1))), 1)
        val ss = SatuSehatService(null, Terminology.load(null), st, null)
        val text = ss.preview(st.all().single()).toString()
        assertTrue("<IHS-PASIEN>" in text && "<ORGANIZATION-ID>" in text && "49051-6" in text)
        assertTrue(ss.send("Puskesmas X", "k").contains("belum aktif"))
        assertTrue(ss.link("Puskesmas X", "k", null, "3201234567890001").contains("belum aktif"))
        assertEquals(null, SatuSehatConfig.fromEnv(mapOf("BS_SS_ENV" to "sandbox")))
        assertEquals(
            "https://api-satusehat-stg.dto.kemkes.go.id/fhir-r4/v1",
            SatuSehatConfig.fromEnv(mapOf("BS_SS_ENV" to "sandbox", "BS_SS_CLIENT_ID" to "a", "BS_SS_CLIENT_SECRET" to "b", "BS_SS_ORG_ID" to "c"))!!.fhirUrl,
        )
    }
}
