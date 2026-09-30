package id.bundasiaga.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.auth.bearer
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import id.bundasiaga.rules.Fase
import id.bundasiaga.rules.RuleBundle
import id.bundasiaga.server.satusehat.SatuSehatClient
import id.bundasiaga.server.satusehat.SatuSehatConfig
import id.bundasiaga.server.satusehat.SatuSehatService
import id.bundasiaga.server.satusehat.Terminology
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

data class ServerConfig(
    /** Token unggah, satu per gateway puskesmas. Kosong = unggahan ditolak. */
    val tokens: Set<String>,
    val dashUser: String,
    /** Kosong = dasbor tanpa sandi, hanya diizinkan bila terikat ke localhost. */
    val dashPass: String,
    val maxBatch: Int = 500,
)

fun main() {
    val env = System.getenv()
    val host = env["BS_HOST"] ?: "127.0.0.1"
    val port = env["BS_PORT"]?.toIntOrNull() ?: 8080
    val dbPath = env["BS_DB"] ?: "data/bunda.db"
    val cfg = ServerConfig(
        tokens = (env["BS_TOKENS"] ?: "").split(',').map { it.trim() }.filter { it.length >= 16 }.toSet(),
        dashUser = env["BS_DASH_USER"] ?: "dinkes",
        dashPass = env["BS_DASH_PASS"] ?: "",
    )
    if (cfg.dashPass.isEmpty() && host != "127.0.0.1" && host != "localhost") {
        System.err.println("Menolak membuka dasbor tanpa BS_DASH_PASS di $host. Isi BS_DASH_PASS atau ikat ke 127.0.0.1.")
        return
    }
    if (cfg.tokens.isEmpty()) {
        System.err.println("PERINGATAN: BS_TOKENS kosong (atau token < 16 karakter): unggahan dari gateway akan ditolak.")
    }
    File(dbPath).absoluteFile.parentFile?.mkdirs()
    val store = Store(dbPath)
    val trust = env["BS_RULES_TRUST"]?.let { RuleBundle.parseTrustFile(File(it).readText()) } ?: emptyMap()
    val rules = RulesMirror(File(env["BS_RULES_DIR"] ?: "data/rules"), trust)
    val ssConfig = SatuSehatConfig.fromEnv(env)
    val ss = SatuSehatService(ssConfig, Terminology.load(env["BS_SS_TERMINOLOGY"]), store, ssConfig?.let { SatuSehatClient(it) })
    println("Server BUNDA SIAGA berjalan di http://$host:$port  (database: $dbPath)")
    println("Aturan: ${rules.describe()}${if (trust.isEmpty()) "  (tanpa BS_RULES_TRUST: tanda tangan hanya diperiksa HP)" else ""}")
    println("SATUSEHAT: ${ssConfig?.let { "AKTIF ${it.fhirUrl}, ${it.facilities.size} pemetaan faskes" } ?: "mati"}")
    embeddedServer(CIO, port = port, host = host) { module(store, cfg, rules = rules, satusehat = ss) }.start(wait = true)
}

private val prettyJson = Json { prettyPrint = true }

private fun constantTimeEquals(a: String, b: String) =
    MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

fun Application.module(
    store: Store,
    cfg: ServerConfig,
    clock: () -> Long = System::currentTimeMillis,
    rules: RulesMirror = RulesMirror(File("data/rules"), emptyMap()),
    satusehat: SatuSehatService = SatuSehatService(null, Terminology.load(null), store, null),
) {
    // Token per proses untuk formulir dasbor, agar situs lain tidak bisa mengirimnya memakai login tersimpan di browser.
    val csrf = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            // Jangan pernah mengembalikan isi permintaan (bisa berisi kode pasien berpseudonim).
            call.application.environment.log.warn("permintaan gagal: ${cause.javaClass.simpleName}")
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "permintaan tidak valid"))
        }
    }
    install(Authentication) {
        bearer("gateway") {
            authenticate { cred -> if (cfg.tokens.any { constantTimeEquals(it, cred.token) }) UserIdPrincipal("gateway") else null }
        }
        basic("dashboard") {
            realm = "BUNDA SIAGA"
            validate { cred ->
                if (cfg.dashPass.isEmpty() ||
                    (constantTimeEquals(cred.name, cfg.dashUser) && constantTimeEquals(cred.password, cfg.dashPass))
                ) UserIdPrincipal(cred.name) else null
            }
        }
    }

    routing {
        get("/health") { call.respondText("ok") }
        get("/privasi") { call.respondText(Pages.privacy(), ContentType.Text.Html) }

        // Publik: paket bertanda tangan, dan HP memverifikasinya sebelum dipakai.
        get("/api/v1/rules") { call.respond(rules.index()) }
        get("/api/v1/rules/{fase}") {
            val fase = Fase.entries.firstOrNull { it.name.equals(call.parameters["fase"], ignoreCase = true) }
            val text = fase?.let { rules.bundle(it) }
            if (text == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "aturan tidak tersedia"))
            else call.respondText(text, ContentType.Application.Json)
        }

        authenticate("gateway") {
            post("/api/v1/cases") {
                val batch = call.receive<Batch>()
                if (batch.facility.isBlank() || batch.cases.size > cfg.maxBatch) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "paket tidak valid"))
                    return@post
                }
                val n = store.upsert(batch, clock())
                call.respond(mapOf("stored" to n))
            }
        }

        val dash: suspend io.ktor.server.routing.RoutingContext.() -> Unit = {
            call.respondText(Dashboard.render(store.all(), store.lastSync(), clock()), ContentType.Text.Html)
        }
        val summary: suspend io.ktor.server.routing.RoutingContext.() -> Unit = {
            call.respond(Dashboard.summary(store.all()))
        }
        val ssPage: suspend io.ktor.server.routing.RoutingContext.() -> Unit = {
            call.respondText(
                Pages.satuSehat(store.all(), store.links(), satusehat, csrf, call.request.queryParameters["msg"]),
                ContentType.Text.Html,
            )
        }
        val ssPreview: suspend io.ktor.server.routing.RoutingContext.() -> Unit = {
            val row = store.case(call.request.queryParameters["facility"] ?: "", call.request.queryParameters["case"] ?: "")
            if (row == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "tidak ditemukan"))
            else call.respondText(prettyJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), satusehat.preview(row)), ContentType.Application.Json)
        }
        fun ssAction(action: (String, String, io.ktor.http.Parameters) -> String): suspend io.ktor.server.routing.RoutingContext.() -> Unit = {
            val origin = call.request.header("Origin")
            val host = call.request.header("Host")
            val p = call.receiveParameters()
            if (!constantTimeEquals(p["csrf"] ?: "", csrf) || (origin != null && host != null && origin.substringAfter("://") != host)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "ditolak"))
            } else {
                val msg = action(p["facility"] ?: "", p["caseKey"] ?: "", p)
                call.respondRedirect("/satusehat?msg=" + URLEncoder.encode(msg, Charsets.UTF_8))
            }
        }
        val ssLink = ssAction { f, k, p -> satusehat.link(f, k, p["ihs"], p["nik"]) }
        val ssSend = ssAction { f, k, _ -> satusehat.send(f, k) }

        val dashboardRoutes: io.ktor.server.routing.Route.() -> Unit = {
            get("/") { dash() }
            get("/api/v1/summary") { summary() }
            get("/satusehat") { ssPage() }
            get("/satusehat/preview") { ssPreview() }
            post("/satusehat/link") { ssLink() }
            post("/satusehat/send") { ssSend() }
        }
        if (cfg.dashPass.isEmpty()) dashboardRoutes() else authenticate("dashboard") { dashboardRoutes() }
    }
}
