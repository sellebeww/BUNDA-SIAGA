package id.bundasiaga.server.satusehat

import id.bundasiaga.codec.Codebook
import id.bundasiaga.server.CaseRow
import id.bundasiaga.server.SatuSehatLink
import id.bundasiaga.server.Store
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Tempat kasus dicatat di SATUSEHAT: lokasi dan nakes milik puskesmas penerima. */
data class FacilityMap(val locationId: String, val locationName: String, val practitionerId: String, val practitionerName: String)

data class SatuSehatConfig(
    val authUrl: String,
    val fhirUrl: String,
    val clientId: String,
    val clientSecret: String,
    val organizationId: String,
    /** Kuncinya nama faskes yang diunggah HP gateway (kolom "Nama" di Pengaturan). */
    val facilities: Map<String, FacilityMap>,
) {
    companion object {
        private val ENVIRONMENTS = mapOf(
            "sandbox" to ("https://api-satusehat-stg.dto.kemkes.go.id/oauth2/v1" to "https://api-satusehat-stg.dto.kemkes.go.id/fhir-r4/v1"),
            "production" to ("https://api-satusehat.kemkes.go.id/oauth2/v1" to "https://api-satusehat.kemkes.go.id/fhir-r4/v1"),
        )

        /** Null (integrasi mati) kecuali BS_SS_ENV dan semua kredensial diisi. */
        fun fromEnv(env: Map<String, String>): SatuSehatConfig? {
            val urls = ENVIRONMENTS[env["BS_SS_ENV"]?.lowercase()] ?: return null
            val id = env["BS_SS_CLIENT_ID"].orEmpty()
            val secret = env["BS_SS_CLIENT_SECRET"].orEmpty()
            val org = env["BS_SS_ORG_ID"].orEmpty()
            if (id.isBlank() || secret.isBlank() || org.isBlank()) return null
            return SatuSehatConfig(
                authUrl = env["BS_SS_AUTH_URL"] ?: urls.first,
                fhirUrl = env["BS_SS_FHIR_URL"] ?: urls.second,
                clientId = id, clientSecret = secret, organizationId = org,
                facilities = env["BS_SS_MAP"]?.let { parseFacilities(File(it).readText()) } ?: emptyMap(),
            )
        }

        fun parseFacilities(text: String): Map<String, FacilityMap> =
            Json.parseToJsonElement(text).jsonObject.mapValues { (_, v) ->
                val o = v.jsonObject
                fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull ?: error("peta faskes: tidak ada '$k'")
                FacilityMap(s("locationId"), s("locationName"), s("practitionerId"), s("practitionerName"))
            }
    }
}

/** Pemetaan kode, dimuat dari JSON agar peninjau terminologi bisa memperbaikinya tanpa build ulang. */
class Terminology(val raw: JsonObject) {
    fun coding(key: String): JsonObject {
        val o = raw[key]?.jsonObject ?: error("terminologi: tidak ada '$key'")
        return buildJsonObject {
            put("system", o["system"]!!.jsonPrimitive.content)
            put("code", o["code"]!!.jsonPrimitive.content)
            put("display", o["display"]!!.jsonPrimitive.content)
        }
    }

    val dangerSignSystem: String get() = raw["dangerSignSystem"]!!.jsonPrimitive.content
    fun extraCodings(sign: String): List<JsonElement> = raw["dangerSignExtraCodings"]?.jsonObject?.get(sign)?.jsonArray ?: emptyList()
    fun priority(tingkat: String?): String = raw["priority"]?.jsonObject?.get(tingkat ?: "")?.jsonPrimitive?.contentOrNull ?: "routine"
    val status: String get() = raw["status"]?.jsonPrimitive?.contentOrNull ?: "?"

    companion object {
        fun load(path: String?): Terminology {
            val text = path?.let { File(it).readText() }
                ?: Terminology::class.java.getResourceAsStream("/satusehat/terminology.json")!!.bufferedReader().use { it.readText() }
            return Terminology(Json.parseToJsonElement(text).jsonObject)
        }
    }
}

/**
 * Membentuk satu Bundle transaksi FHIR per kasus: Encounter + Observation (tekanan darah,
 * usia kehamilan atau hari nifas, satu per tanda bahaya) + ServiceRequest (rujukan).
 * Resource saling merujuk lewat URL penuh urn:uuid yang diturunkan dari kunci kasus,
 * sehingga kasus yang sama selalu menghasilkan paket yang sama.
 */
class FhirMapper(private val t: Terminology, private val organizationId: String) {
    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx").withZone(ZoneOffset.UTC)

    private fun uuid(caseKey: String, part: String) = "urn:uuid:" + UUID.nameUUIDFromBytes("$caseKey/$part".toByteArray())

    fun bundle(row: CaseRow, patientIhs: String, facility: FacilityMap): JsonObject {
        val c = row.c
        val at = time.format(Instant.ofEpochMilli(c.receivedAt))
        val encRef = uuid(c.caseKey, "encounter")
        val patient = buildJsonObject { put("reference", "Patient/$patientIhs") }
        val practitioner = buildJsonObject {
            put("reference", "Practitioner/${facility.practitionerId}")
            put("display", facility.practitionerName)
        }
        val entries = mutableListOf<Pair<String, JsonObject>>()

        entries += encRef to buildJsonObject {
            put("resourceType", "Encounter")
            putJsonArray("identifier") {
                add(buildJsonObject {
                    put("system", "http://sys-ids.kemkes.go.id/encounter/$organizationId")
                    put("value", "BUNDASIAGA-${c.caseKey}")
                })
            }
            put("status", "arrived")
            put("class", t.coding("encounterClass"))
            put("subject", patient)
            putJsonArray("participant") {
                add(buildJsonObject {
                    putJsonArray("type") {
                        add(buildJsonObject {
                            putJsonArray("coding") {
                                add(buildJsonObject {
                                    put("system", "http://terminology.hl7.org/CodeSystem/v3-ParticipationType")
                                    put("code", "ATND")
                                    put("display", "attender")
                                })
                            }
                        })
                    }
                    put("individual", practitioner)
                })
            }
            putJsonObject("period") { put("start", at) }
            putJsonArray("location") {
                add(buildJsonObject {
                    putJsonObject("location") {
                        put("reference", "Location/${facility.locationId}")
                        put("display", facility.locationName)
                    }
                })
            }
            putJsonArray("statusHistory") {
                add(buildJsonObject {
                    put("status", "arrived")
                    putJsonObject("period") { put("start", at) }
                })
            }
            putJsonObject("serviceProvider") { put("reference", "Organization/$organizationId") }
        }

        fun observation(part: String, category: JsonObject, code: JsonObject, value: JsonObjectBuilder.() -> Unit) {
            entries += uuid(c.caseKey, part) to buildJsonObject {
                put("resourceType", "Observation")
                put("status", "final")
                putJsonArray("category") { add(buildJsonObject { putJsonArray("coding") { add(category) } }) }
                putJsonObject("code") { putJsonArray("coding") { add(code) } }
                put("subject", patient)
                putJsonArray("performer") { add(practitioner) }
                putJsonObject("encounter") { put("reference", encRef) }
                put("effectiveDateTime", at)
                value()
            }
        }
        val vital = buildJsonObject {
            put("system", "http://terminology.hl7.org/CodeSystem/observation-category")
            put("code", "vital-signs")
            put("display", "Vital Signs")
        }
        fun mmHg(v: Int): JsonObjectBuilder.() -> Unit = {
            putJsonObject("valueQuantity") {
                put("value", v)
                put("unit", "mm[Hg]")
                put("system", "http://unitsofmeasure.org")
                put("code", "mm[Hg]")
            }
        }
        c.td?.split('/')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }?.let { (sys, dia) ->
            observation("bp-sys", vital, t.coding("bpSystolic"), mmHg(sys))
            observation("bp-dia", vital, t.coding("bpDiastolic"), mmHg(dia))
        }
        val exam = t.coding("dangerSignCategory")
        if (c.fase == "NIFAS") {
            c.hariNifas?.let { d -> observation("hari", exam, t.coding("daysPostpartum")) { putJsonObject("valueQuantity") { put("value", d); put("unit", "d"); put("system", "http://unitsofmeasure.org"); put("code", "d") } } }
        } else {
            c.usiaMinggu?.let { w -> observation("uk", exam, t.coding("gestationalAge")) { putJsonObject("valueQuantity") { put("value", w); put("unit", "wk"); put("system", "http://unitsofmeasure.org"); put("code", "wk") } } }
        }
        val signs = c.tanda.split('.').filter { it.isNotBlank() }
        for (sign in signs) {
            val base = sign.substringBefore(':')
            entries += uuid(c.caseKey, "sign-$sign") to buildJsonObject {
                put("resourceType", "Observation")
                put("status", "final")
                putJsonArray("category") { add(buildJsonObject { putJsonArray("coding") { add(exam) } }) }
                putJsonObject("code") {
                    putJsonArray("coding") {
                        add(buildJsonObject {
                            put("system", t.dangerSignSystem)
                            put("code", base)
                            put("display", Codebook.signLabel(sign))
                        })
                        t.extraCodings(base).forEach { add(it) }
                    }
                    put("text", Codebook.signLabel(sign))
                }
                put("subject", patient)
                putJsonArray("performer") { add(practitioner) }
                putJsonObject("encounter") { put("reference", encRef) }
                put("effectiveDateTime", at)
                put("valueBoolean", true)
            }
        }

        val observationRefs = entries.drop(1).map { it.first }
        entries += uuid(c.caseKey, "referral") to buildJsonObject {
            put("resourceType", "ServiceRequest")
            putJsonArray("identifier") {
                add(buildJsonObject {
                    put("system", "http://sys-ids.kemkes.go.id/servicerequest/$organizationId")
                    put("value", "BUNDASIAGA-${c.caseKey}")
                })
            }
            put("status", "active")
            put("intent", "order")
            putJsonArray("category") { add(buildJsonObject { putJsonArray("coding") { add(t.coding("referralCategory")) } }) }
            put("priority", t.priority(c.tingkat))
            putJsonObject("code") {
                putJsonArray("coding") { add(t.coding("referralCategory")) }
                put("text", "Rujukan BUNDA SIAGA (${if (c.fase == "NIFAS") "ibu nifas" else "ibu hamil"})")
            }
            put("subject", patient)
            putJsonObject("encounter") { put("reference", encRef) }
            put("authoredOn", at)
            put("requester", practitioner)
            putJsonArray("reasonReference") { observationRefs.forEach { add(buildJsonObject { put("reference", it) }) } }
            putJsonArray("note") {
                add(buildJsonObject {
                    put(
                        "text",
                        "Sinyal prioritas rujukan ${c.tingkat ?: "-"} dari aplikasi BUNDA SIAGA (aturan draft, bukan diagnosis). " +
                            "Kode pasien di aplikasi: ${c.pasien ?: "-"}. Lokasi asal: ${c.lokasi ?: "-"}.",
                    )
                })
            }
        }

        return buildJsonObject {
            put("resourceType", "Bundle")
            put("type", "transaction")
            putJsonArray("entry") {
                entries.forEach { (fullUrl, res) ->
                    add(buildJsonObject {
                        put("fullUrl", fullUrl)
                        put("resource", res)
                        putJsonObject("request") {
                            put("method", "POST")
                            put("url", res["resourceType"]!!.jsonPrimitive.content)
                        }
                    })
                }
            }
        }
    }
}

/** Sambungan HTTP minimal agar klien bisa diuji tanpa jaringan. */
fun interface HttpCall {
    fun send(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String>
}

val JdkHttp = HttpCall { method, url, headers, body ->
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
    val b = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(60))
    headers.forEach { (k, v) -> b.header(k, v) }
    b.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
    val r = client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    r.statusCode() to r.body()
}

class SatuSehatException(message: String) : Exception(message)

class SatuSehatClient(private val cfg: SatuSehatConfig, private val http: HttpCall = JdkHttp, private val clock: () -> Long = System::currentTimeMillis) {
    private var token: String? = null
    private var tokenExpiresAt = 0L

    @Synchronized
    fun token(): String {
        token?.let { if (clock() < tokenExpiresAt - 60_000) return it }
        val form = "client_id=${enc(cfg.clientId)}&client_secret=${enc(cfg.clientSecret)}"
        val (code, body) = http.send(
            "POST", "${cfg.authUrl}/accesstoken?grant_type=client_credentials",
            mapOf("Content-Type" to "application/x-www-form-urlencoded"), form,
        )
        if (code !in 200..299) throw SatuSehatException("permintaan token gagal: HTTP $code")
        val o = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrElse { throw SatuSehatException("jawaban token bukan JSON") }
        val t = o["access_token"]?.jsonPrimitive?.contentOrNull ?: throw SatuSehatException("tidak ada access_token pada jawaban")
        val ttl = o["expires_in"]?.jsonPrimitive?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 3600
        token = t
        tokenExpiresAt = clock() + ttl * 1000
        return t
    }

    private fun auth() = mapOf("Authorization" to "Bearer ${token()}", "Content-Type" to "application/json")

    /** Mencari ID IHS pasien lewat NIK. NIK hanya dikirim ke SATUSEHAT, tidak pernah disimpan atau dicatat di sini. */
    fun patientIdByNik(nik: String): String? {
        require(nik.length == 16 && nik.all { it.isDigit() }) { "NIK harus 16 angka" }
        val q = enc("https://fhir.kemkes.go.id/id/nik|$nik")
        val (code, body) = http.send("GET", "${cfg.fhirUrl}/Patient?identifier=$q", auth(), null)
        if (code !in 200..299) throw SatuSehatException("pencarian pasien gagal: HTTP $code")
        val entries = Json.parseToJsonElement(body).jsonObject["entry"] as? JsonArray ?: return null
        return entries.firstOrNull()?.jsonObject?.get("resource")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
    }

    /** Mengirim Bundle transaksi; mengembalikan ID Encounter yang dibuat. */
    fun postBundle(bundle: JsonObject): String? {
        val (code, body) = http.send("POST", cfg.fhirUrl, auth(), bundle.toString())
        val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (code !in 200..299) throw SatuSehatException("HTTP $code: ${json?.let(::outcomeText) ?: body.take(300)}")
        return json?.get("entry")?.jsonArray?.firstNotNullOfOrNull { e ->
            e.jsonObject["response"]?.jsonObject?.get("location")?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.startsWith("Encounter/") }?.split('/')?.getOrNull(1)
        }
    }

    private fun outcomeText(o: JsonObject): String? {
        if (o["resourceType"]?.jsonPrimitive?.contentOrNull != "OperationOutcome") return null
        return o["issue"]?.jsonArray?.joinToString("; ") { i ->
            val io = i.jsonObject
            io["diagnostics"]?.jsonPrimitive?.contentOrNull
                ?: io["details"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                ?: io["code"]?.jsonPrimitive?.contentOrNull ?: "?"
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
}

/** Menautkan dan mengirim, dengan aturan yang membuatnya aman bila tombol ditekan dua kali. */
class SatuSehatService(
    val config: SatuSehatConfig?,
    val terminology: Terminology,
    private val store: Store,
    private val client: SatuSehatClient?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val ihsFormat = Regex("^[A-Za-z0-9-]{4,64}$")

    val enabled get() = config != null && client != null

    /** Nilai pengganti memungkinkan dinkes melihat pratinjau paket persis sebelum punya kredensial. */
    fun preview(row: CaseRow): JsonObject {
        val link = store.links()[row.facility to row.c.caseKey]
        val fac = config?.facilities?.get(row.facility) ?: FacilityMap("<LOCATION-ID>", row.facility, "<PRACTITIONER-ID>", "<NAMA-NAKES>")
        return FhirMapper(terminology, config?.organizationId ?: "<ORGANIZATION-ID>")
            .bundle(row, link?.patientIhs ?: "<IHS-PASIEN>", fac)
    }

    /** Menautkan kasus ke pasien SATUSEHAT, langsung lewat ID IHS atau lewat pencarian NIK (NIK tidak disimpan). */
    fun link(facility: String, caseKey: String, ihs: String?, nik: String?): String {
        val row = store.case(facility, caseKey) ?: return "Kasus tidak ditemukan."
        val existing = store.links()[facility to caseKey]
        if (existing?.status == "SENT") return "Sudah terkirim; tautan tidak bisa diubah."
        val id = when {
            !ihs.isNullOrBlank() -> ihs.trim()
            !nik.isNullOrBlank() -> {
                val c = client ?: return "Integrasi SATUSEHAT belum aktif: cari lewat NIK tidak tersedia."
                runCatching { c.patientIdByNik(nik.trim()) }.getOrElse { return "Pencarian NIK gagal: ${it.message}" }
                    ?: return "Pasien dengan NIK itu tidak ditemukan di SATUSEHAT."
            }
            else -> return "Isi ID pasien SATUSEHAT atau NIK."
        }
        if (!ihsFormat.matches(id)) return "Format ID pasien tidak valid."
        store.saveLink(SatuSehatLink(row.facility, caseKey, id, "LINKED", null, null, clock()))
        return "Ditautkan ke pasien $id."
    }

    fun send(facility: String, caseKey: String): String {
        val cfg = config ?: return "Integrasi SATUSEHAT belum aktif."
        val c = client ?: return "Integrasi SATUSEHAT belum aktif."
        val row = store.case(facility, caseKey) ?: return "Kasus tidak ditemukan."
        if (row.c.unparsed) return "SMS tidak terbaca tidak dikirim ke SATUSEHAT."
        val link = store.links()[facility to caseKey] ?: return "Tautkan pasien dulu."
        // Pengiriman kedua akan membuat Encounter ganda di SATUSEHAT.
        if (link.status == "SENT") return "Sudah terkirim (Encounter ${link.encounterId ?: "?"})."
        val fac = cfg.facilities[facility] ?: return "Faskes '$facility' belum dipetakan di BS_SS_MAP (Location dan Practitioner)."
        return try {
            val enc = c.postBundle(FhirMapper(terminology, cfg.organizationId).bundle(row, link.patientIhs, fac))
            store.saveLink(link.copy(status = "SENT", encounterId = enc, lastError = null, updatedAt = clock()))
            "Terkirim ke SATUSEHAT${enc?.let { " (Encounter $it)" } ?: ""}."
        } catch (e: Exception) {
            val msg = (e.message ?: e.javaClass.simpleName).take(500)
            store.saveLink(link.copy(status = "FAILED", lastError = msg, updatedAt = clock()))
            "Gagal: $msg"
        }
    }
}
