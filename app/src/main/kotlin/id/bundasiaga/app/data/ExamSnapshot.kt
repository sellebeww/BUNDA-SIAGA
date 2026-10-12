package id.bundasiaga.app.data

import id.bundasiaga.rules.*
import kotlinx.serialization.json.*

/** Persisted clinical input and results; reopening a result never reevaluates new rules. */
object ExamSnapshot {
    fun encode(p: Pemeriksaan): String = buildJsonObject {
        put("fase", p.fase.name)
        put("uk", p.usiaKehamilanMinggu?.let(::JsonPrimitive) ?: JsonNull)
        put("hari", p.hariNifas?.let(::JsonPrimitive) ?: JsonNull)
        put("tdStatus", p.tdStatus.name)
        put("sis", p.tdSistolik?.let(::JsonPrimitive) ?: JsonNull)
        put("dia", p.tdDiastolik?.let(::JsonPrimitive) ?: JsonNull)
        put("suhu", p.suhuC?.let(::JsonPrimitive) ?: JsonNull)
        put("sub", p.subPerdarahan?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("gjn", p.gerakJanin.name)
        putJsonObject("jawaban") { p.jawaban.forEach { (key, value) -> put(key.name, value.name) } }
    }.toString()

    fun decode(text: String): Pemeriksaan {
        val o = Json.parseToJsonElement(text).jsonObject
        fun int(key: String) = o[key]?.jsonPrimitive?.intOrNull
        return Pemeriksaan(
            fase = Fase.valueOf(o.getValue("fase").jsonPrimitive.content),
            usiaKehamilanMinggu = int("uk"), hariNifas = int("hari"),
            tdStatus = TdStatus.valueOf(o.getValue("tdStatus").jsonPrimitive.content),
            tdSistolik = int("sis"), tdDiastolik = int("dia"),
            suhuC = o["suhu"]?.jsonPrimitive?.doubleOrNull,
            subPerdarahan = o["sub"]?.jsonPrimitive?.contentOrNull?.let(SubPerdarahan::valueOf),
            gerakJanin = GerakJanin.valueOf(o.getValue("gjn").jsonPrimitive.content),
            jawaban = o.getValue("jawaban").jsonObject.map { (k, v) -> Tanda.valueOf(k) to Jawaban.valueOf(v.jsonPrimitive.content) }.toMap(),
        )
    }

    fun encodeResult(h: Hasil): String = buildJsonObject {
        put("level", h.level.name)
        put("version", h.versiAturan)
        put("status", h.statusAturan)
        put("reasons", strings(h.alasanManusia))
        put("incomplete", strings(h.dataBelumLengkap))
        put("advice", strings(h.saran))
        putJsonArray("data") { h.dataDipakai.forEach { (k, v) -> add(strings(listOf(k, v))) } }
        putJsonArray("rules") {
            h.aturanTerpicu.forEach { r -> add(buildJsonObject {
                put("id", r.id); put("level", r.level.name); put("reason", r.alasan); put("status", r.status)
            }) }
        }
    }.toString()

    fun decodeResult(text: String): Hasil {
        val o = Json.parseToJsonElement(text).jsonObject
        fun str(key: String) = o.getValue(key).jsonPrimitive.content
        fun list(key: String) = o.getValue(key).jsonArray.map { it.jsonPrimitive.content }
        return Hasil(
            level = Level.valueOf(str("level")),
            aturanTerpicu = o.getValue("rules").jsonArray.map { value ->
                val r = value.jsonObject
                AturanTerpicu(r.getValue("id").jsonPrimitive.content, Level.valueOf(r.getValue("level").jsonPrimitive.content),
                    r.getValue("reason").jsonPrimitive.content, r.getValue("status").jsonPrimitive.content)
            },
            alasanManusia = list("reasons"), dataBelumLengkap = list("incomplete"), saran = list("advice"),
            dataDipakai = o.getValue("data").jsonArray.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.content },
            versiAturan = str("version"), statusAturan = str("status"),
        )
    }

    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
}
