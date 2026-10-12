package id.bundasiaga.server

import io.ktor.client.request.basicAuth
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerTest {
    private val token = "test-token-0123456789"
    private val batch = """{"facility":"Puskesmas Contoh","cases":[
        {"caseKey":"abc:A7K2","tingkat":"MERAH","usiaMinggu":34,"td":"168/112","tanda":"SKH.BKK","lokasi":"DSA12","pasien":"YL07",
         "checksumVerified":true,"unparsed":false,"receivedAt":1000,"ackStatus":null,"ackAt":null,"duplicates":0}]}"""

    private fun store() = Store(File.createTempFile("bunda", ".db").also { it.deleteOnExit() }.absolutePath)

    @Test
    fun uploadRequiresTokenAndIsIdempotent() = testApplication {
        val st = store()
        application { module(st, ServerConfig(setOf(token), "dinkes", "rahasia-dashboard")) }

        val noAuth = client.post("/api/v1/cases") { contentType(ContentType.Application.Json); setBody(batch) }
        assertEquals(HttpStatusCode.Unauthorized, noAuth.status)
        val wrong = client.post("/api/v1/cases") { bearerAuth("nope-nope-nope-nope"); contentType(ContentType.Application.Json); setBody(batch) }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)

        repeat(3) {
            val ok = client.post("/api/v1/cases") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(batch) }
            assertEquals(HttpStatusCode.OK, ok.status)
        }
        assertEquals(1, st.all().size)

        // ACK datang belakangan: kasus yang sama diperbarui, tidak digandakan.
        val acked = batch.replace("\"ackStatus\":null,\"ackAt\":null", "\"ackStatus\":\"SIAP\",\"ackAt\":181000")
        client.post("/api/v1/cases") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(acked) }
        assertEquals(1, st.all().size)
        assertEquals("SIAP", st.all().first().c.ackStatus)
    }

    @Test
    fun dashboardNeedsPasswordAndEscapesHtml() = testApplication {
        val st = store()
        application { module(st, ServerConfig(setOf(token), "dinkes", "rahasia-dashboard")) }
        client.post("/api/v1/cases") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(batch.replace("Puskesmas Contoh", "<script>x</script>"))
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/").status)
        val page = client.get("/") { basicAuth("dinkes", "rahasia-dashboard") }
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        assertTrue("YL07" in html && "sakit kepala hebat" in html)
        assertTrue("<script>x</script>" !in html, "nama faskes harus di-escape")
        assertTrue("sinyal prioritas" in html)
    }

    @Test
    fun dashboardDoesNotInterpolateUntrustedLevelIntoHtmlAttributes() {
        val payload = "\"><img src=x onerror=alert(1)>"
        val html = Dashboard.render(
            listOf(CaseRow("PKM", CaseIn(caseKey = "one", tingkat = payload, receivedAt = 1000), 1000)),
            emptyMap(), 1000,
        )
        assertTrue(payload !in html)
        assertTrue("class=\"lv X\"" in html)
        assertTrue("&lt;img" in html, "unknown level remains readable as escaped text")
    }

    @Test
    fun negativeAckTimesDoNotDistortMedian() {
        val rows = listOf(
            CaseRow("PKM", CaseIn(caseKey = "one", receivedAt = 120_000, ackAt = 60_000), 0),
            CaseRow("PKM", CaseIn(caseKey = "two", receivedAt = 120_000, ackAt = 300_000), 0),
        )
        assertEquals(3.0, Dashboard.summary(rows).medianMinutesToAck)
        assertEquals(2, Dashboard.summary(rows).acked)
    }

    @Test
    fun summaryCounts() = testApplication {
        val st = store()
        application { module(st, ServerConfig(setOf(token), "dinkes", "")) }
        client.post("/api/v1/cases") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(batch) }
        val s = client.get("/api/v1/summary").bodyAsText()
        assertTrue("\"merahWithoutAck\":1" in s, s)
    }

    @Test
    fun malformedBodyIsRejectedWithoutEcho() = testApplication {
        application { module(store(), ServerConfig(setOf(token), "dinkes", "")) }
        val r = client.post("/api/v1/cases") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("{\"pasien\":\"YL07\"") }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue("YL07" !in r.bodyAsText())
    }
}
