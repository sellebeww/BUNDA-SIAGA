package id.bundasiaga.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class Pertanyaan(
    val code: String,
    val label: String,
    val prompt: String,
    val hint: String,
    val icon: String,
    /** Kritis pada usia kehamilan berapa pun. */
    val critical: Boolean,
    /** Kritis hanya mulai minggu ini (atau bila usia tidak diketahui). */
    val criticalFromWeek: Int?,
    /** Tanyakan hanya mulai minggu ini (null = selalu). Usia tidak diketahui selalu ditanyakan. */
    val askFromWeek: Int?,
    /** Khusus PDR: setelah YA, tanyakan juga berapa banyak darah (bercak / sedang / banyak). */
    val bleedingAmount: Boolean = false,
)

data class Aturan(
    val id: String,
    val level: Level,
    val condition: Condition,
    val reason: String,
    val suggestions: List<String>,
    val status: String,
    val sourceHint: String,
    val verified: Boolean,
    val note: String,
)

data class MetaRule(
    val id: String,
    val level: Level,
    val reason: String,
    val status: String,
    val note: String,
)

data class BpBounds(val sysMin: Int, val sysMax: Int, val diaMin: Int, val diaMax: Int)

data class Stabilisasi(
    val title: String,
    val steps: List<String>,
    val bidanReminder: String,
    val status: String,
)

data class RuleConfig(
    val version: String,
    /** Monoton per fase. Pembaruan jarak jauh hanya diterima dengan seq yang lebih tinggi (tanpa mundur versi). */
    val seq: Int,
    /** [RuleEngine.ENGINE_VERSION] terendah yang memahami konfigurasi ini. */
    val engine: Int,
    val status: String,
    val fase: Fase,
    val disclaimer: String,
    val bpBounds: BpBounds,
    val questions: List<Pertanyaan>,
    val rules: List<Aturan>,
    val incomplete: MetaRule,
    val unmatchedSign: MetaRule,
    val advice: Map<Level, List<String>>,
    val bpMissingAdvice: Map<TdStatus, String>,
    val stabilization: Stabilisasi,
) {
    fun question(code: String): Pertanyaan? = questions.firstOrNull { it.code == code }
    fun label(code: String): String = question(code)?.label ?: code
}

class RuleConfigException(message: String) : Exception(message)

/** Kode pertanyaan untuk gerakan janin (bukan [Tanda] ya/tidak). */
const val GJN = "GJN"

/**
 * Mengurai konfigurasi aturan JSON. Gagal keras pada apa pun yang tidak dikenal: aturan yang
 * diabaikan diam-diam pada alat keselamatan lebih buruk daripada crash saat dimuat (dan
 * konfigurasi ikut dalam APK, jadi ini tertangkap oleh tes unit).
 */
object RuleConfigLoader {
    private val json = Json { ignoreUnknownKeys = false }

    fun loadDefaultHamil(): RuleConfig = loadDefault(Fase.HAMIL)

    fun loadDefault(fase: Fase): RuleConfig = parse(defaultText(fase)).also {
        if (it.fase != fase) throw RuleConfigException("${resourceName(fase)} menyatakan fase ${it.fase}")
    }

    /** Teks konfigurasi yang disertakan di dalam APK / jar. */
    fun defaultText(fase: Fase): String =
        RuleConfigLoader::class.java.getResourceAsStream("/" + resourceName(fase))
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw RuleConfigException("${resourceName(fase)} tidak ditemukan di classpath")

    fun resourceName(fase: Fase) = "rules/${fase.name.lowercase()}.json"

    fun parse(text: String): RuleConfig {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw RuleConfigException("bukan objek JSON: ${it.message}") }
        val fase = runCatching { Fase.valueOf(root.str("fase")) }
            .getOrElse { throw RuleConfigException("fase tidak dikenal") }
        val questions = root.arr("questions").map { parseQuestion(it.jsonObject) }
        val codes = questions.map { it.code }
        if (codes.toSet().size != codes.size) throw RuleConfigException("kode pertanyaan ganda")
        codes.forEach { code ->
            if (code != GJN && Tanda.entries.none { it.name == code }) throw RuleConfigException("kode pertanyaan tidak dikenal: $code")
            if (code == GJN && fase != Fase.HAMIL) throw RuleConfigException("gerakan janin hanya ditanyakan pada HAMIL")
        }
        questions.filter { it.bleedingAmount && it.code != Tanda.PDR.name }.forEach {
            throw RuleConfigException("bleedingAmount hanya sah pada PDR, bukan ${it.code}")
        }
        val rules = root.arr("rules").map { parseRule(it.jsonObject) }
        val ids = rules.map { it.id }
        if (ids.toSet().size != ids.size) throw RuleConfigException("ID aturan ganda")
        rules.forEach { checkCondition(it.id, it.condition, fase, questions) }
        val engine = root["engine"]?.jsonPrimitive?.intOrNull ?: 1
        if (engine > RuleEngine.ENGINE_VERSION) {
            throw RuleConfigException("konfigurasi butuh mesin $engine, aplikasi ini punya ${RuleEngine.ENGINE_VERSION}")
        }
        val bp = root.obj("bpBounds")
        val stab = root.obj("stabilization")
        return RuleConfig(
            version = root.str("version"),
            seq = root["seq"]?.jsonPrimitive?.intOrNull ?: 0,
            engine = engine,
            status = root.str("status"),
            fase = fase,
            disclaimer = root.str("disclaimer"),
            bpBounds = BpBounds(bp.int("sysMin"), bp.int("sysMax"), bp.int("diaMin"), bp.int("diaMax")),
            questions = questions,
            rules = rules,
            incomplete = parseMeta(root.obj("incomplete")),
            unmatchedSign = parseMeta(root.obj("unmatchedSign")),
            advice = root.obj("advice").entries.associate { (k, v) ->
                Level.valueOf(k) to v.jsonArray.map { it.jsonPrimitive.content }
            },
            bpMissingAdvice = root.obj("bpMissingAdvice").entries.associate { (k, v) ->
                TdStatus.valueOf(k) to v.jsonPrimitive.content
            },
            stabilization = Stabilisasi(
                title = stab.str("title"),
                steps = stab.arr("steps").map { it.jsonPrimitive.content },
                bidanReminder = stab.str("bidanReminder"),
                status = stab.str("status"),
            ),
        )
    }

    private fun parseQuestion(o: JsonObject) = Pertanyaan(
        code = o.str("code"),
        label = o.str("label"),
        prompt = o.str("prompt"),
        hint = o["hint"]?.jsonPrimitive?.content ?: "",
        icon = o["icon"]?.jsonPrimitive?.content ?: "help",
        critical = o["critical"]?.jsonPrimitive?.booleanOrNull ?: false,
        criticalFromWeek = o["criticalFromWeek"]?.jsonPrimitive?.intOrNull,
        askFromWeek = o["askFromWeek"]?.jsonPrimitive?.intOrNull,
        bleedingAmount = o["bleedingAmount"]?.jsonPrimitive?.booleanOrNull ?: false,
    )

    /**
     * Aturan yang tidak pernah bisa terpicu adalah lubang senyap pada alat keselamatan, jadi kondisi hanya
     * boleh melihat pertanyaan yang benar-benar ditanyakan konfigurasi ini, dan NIFAS tidak boleh
     * memakai operator yang khusus kehamilan.
     */
    private fun checkCondition(ruleId: String, c: Condition, fase: Fase, questions: List<Pertanyaan>) {
        fun fail(msg: String): Nothing = throw RuleConfigException("aturan $ruleId: $msg")
        val asked = questions.map { it.code }.toSet()
        when (c) {
            is Condition.All -> c.items.forEach { checkCondition(ruleId, it, fase, questions) }
            is Condition.AnyOf -> c.items.forEach { checkCondition(ruleId, it, fase, questions) }
            is Condition.Yes -> if (c.tanda.name !in asked) fail("memakai ${c.tanda} yang tidak ditanyakan")
            is Condition.Bleeding -> if (questions.none { it.code == Tanda.PDR.name && it.bleedingAmount }) {
                fail("jumlah perdarahan tidak ditanyakan di konfigurasi ini")
            }
            is Condition.Fetal -> if (GJN !in asked) fail("gerakan janin tidak ditanyakan di konfigurasi ini")
            is Condition.GaAtLeast, is Condition.GaBelow -> if (fase != Fase.HAMIL) fail("usia kehamilan tidak dipakai di $fase")
            is Condition.BpAtLeast, is Condition.BpBelow, is Condition.NotBpBelow -> Unit
        }
    }

    private fun parseRule(o: JsonObject) = Aturan(
        id = o.str("id"),
        level = Level.valueOf(o.str("level")),
        condition = parseCondition(o["when"] ?: throw RuleConfigException("aturan ${o["id"]} tidak punya 'when'")),
        reason = o.str("reason"),
        suggestions = o["suggestions"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
        status = o.str("status"),
        sourceHint = o["sourceHint"]?.jsonPrimitive?.content ?: "",
        verified = o["verified"]?.jsonPrimitive?.boolean ?: false,
        note = o["note"]?.jsonPrimitive?.content ?: "",
    ).also {
        if (it.level == Level.HIJAU) throw RuleConfigException("aturan ${it.id}: aturan tidak boleh menghasilkan HIJAU")
    }

    private fun parseMeta(o: JsonObject) = MetaRule(
        id = o.str("id"),
        level = Level.valueOf(o.str("level")),
        reason = o.str("reason"),
        status = o.str("status"),
        note = o["note"]?.jsonPrimitive?.content ?: "",
    ).also {
        // Invarian 4: data tidak lengkap saja tidak pernah menghasilkan MERAH (dan tidak pernah HIJAU).
        if (it.level != Level.KUNING) throw RuleConfigException("aturan meta ${it.id} harus KUNING")
    }

    fun parseCondition(e: JsonElement): Condition {
        val o = e.jsonObject
        if (o.size != 1 && !(o.size == 2 && "gaAtLeast" in o && "orUnknown" in o)) {
            throw RuleConfigException("kondisi harus punya tepat satu operator: $o")
        }
        return when {
            "yes" in o -> Condition.Yes(tanda(o.str("yes")))
            "all" in o -> Condition.All(o.arr("all").map(::parseCondition))
            "any" in o -> Condition.AnyOf(o.arr("any").map(::parseCondition))
            "gaAtLeast" in o -> Condition.GaAtLeast(
                o.int("gaAtLeast"),
                o["orUnknown"]?.jsonPrimitive?.boolean ?: false,
            )
            "gaBelow" in o -> Condition.GaBelow(o.int("gaBelow"))
            "bpAtLeast" in o -> o.arr("bpAtLeast").let { Condition.BpAtLeast(it[0].jsonPrimitive.int, it[1].jsonPrimitive.int) }
            "bpBelow" in o -> o.arr("bpBelow").let { Condition.BpBelow(it[0].jsonPrimitive.int, it[1].jsonPrimitive.int) }
            "notBpBelow" in o -> o.arr("notBpBelow").let { Condition.NotBpBelow(it[0].jsonPrimitive.int, it[1].jsonPrimitive.int) }
            "bleeding" in o -> Condition.Bleeding(SubPerdarahan.valueOf(o.str("bleeding")))
            "fetal" in o -> Condition.Fetal(GerakJanin.valueOf(o.str("fetal")))
            else -> throw RuleConfigException("kondisi tidak dikenal: $o")
        }
    }

    private fun tanda(code: String) = runCatching { Tanda.valueOf(code) }
        .getOrElse { throw RuleConfigException("kode tanda tidak dikenal: $code") }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content ?: throw RuleConfigException("tidak ada '$k'")
    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.int ?: throw RuleConfigException("tidak ada '$k'")
    private fun JsonObject.obj(k: String) = this[k]?.jsonObject ?: throw RuleConfigException("tidak ada '$k'")
    private fun JsonObject.arr(k: String): JsonArray = this[k]?.jsonArray ?: throw RuleConfigException("tidak ada '$k'")
}
