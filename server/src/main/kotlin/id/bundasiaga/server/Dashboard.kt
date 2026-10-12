package id.bundasiaga.server

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
data class Summary(
    val total: Int,
    val perLevel: Map<String, Int>,
    val merahWithoutAck: Int,
    val acked: Int,
    val medianMinutesToAck: Double?,
    val perFacility: Map<String, Int>,
    val perLokasi: Map<String, Int>,
)

object Dashboard {
    private val SIGNS = mapOf(
        "KEJ" to "kejang", "KSD" to "pingsan/tidak sadar", "PDR" to "perdarahan", "SKH" to "sakit kepala hebat",
        "PDG" to "pandangan kabur", "SSK" to "sesak/nyeri dada", "NPT" to "nyeri perut hebat", "KTB" to "ketuban keluar",
        "BKK" to "bengkak", "NPU" to "nyeri ulu hati", "DMM" to "demam", "MGL" to "menggigil", "CRB" to "cairan berbau",
        "MNT" to "muntah terus", "GJB" to "gerak janin berkurang", "GJT" to "gerak janin tidak ada",
        "PYD" to "payudara bengkak/nyeri", "BTS" to "nyeri/bengkak betis", "SDH" to "sedih/murung",
        "PMD" to "pikiran menyakiti diri/bayi",
    )
    private val ACK = mapOf("SIAP" to "Siap menerima", "PENUH" to "Penuh / tidak siap", "HUBUNGI" to "Minta ditelepon")
    val fmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault())

    private val CSS = """
:root{--teal:#1f6f6b;--teal-d:#0f4c4a;--coral:#e0625a;--cream:#f5f8f7;--ink:#18332f;--muted:#50655f;--card:#fff;--line:#d8e4de;
--merah:#b3261e;--kuning:#ffc928;--hijau:#1b7f3b}
@media (prefers-color-scheme:dark){:root{--cream:#141817;--ink:#ecebe7;--muted:#a9a9a3;--card:#1e2422;--line:#2e3633}}
*{box-sizing:border-box}body{margin:0;font:16px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:var(--cream);color:var(--ink)}
header{background:linear-gradient(120deg,#0f4c4a,#246c61);color:#fff;padding:32px max(20px,calc((100vw - 1200px)/2))}header h1{margin:0 0 8px;font-size:clamp(22px,3vw,32px);letter-spacing:-.6px}header p{margin:2px 0 0;opacity:.85;font-size:14px}
main{max-width:1240px;margin:0 auto;padding:28px 20px 48px}
.warn{background:#fff4e0;color:#5d3a00;border:1px solid #b26a00;border-radius:10px;padding:10px 12px;margin-bottom:14px;font-size:14px}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:14px;margin-bottom:28px}
.tile{background:var(--card);border:1px solid var(--line);border-radius:20px;padding:20px;box-shadow:0 4px 20px #102f2610}
.tile b{display:block;font-size:34px;letter-spacing:-1px;margin-bottom:8px}.tile span{color:var(--muted);font-size:14px}
.lv{display:inline-block;padding:2px 8px;border-radius:6px;font-weight:700;font-size:13px}
.lv.MERAH{background:var(--merah);color:#fff}.lv.KUNING{background:var(--kuning);color:#1b1b1b}.lv.HIJAU{background:var(--hijau);color:#fff}.lv.X{background:#546e7a;color:#fff}
table{width:100%;border-collapse:collapse;background:var(--card);border:1px solid var(--line);border-radius:12px;overflow:hidden}
th,td{text-align:left;padding:14px 16px;border-bottom:1px solid var(--line);font-size:14px;vertical-align:top}
th{background:rgba(31,111,107,.1)}tr.open td:first-child{border-left:4px solid var(--coral)}
.scroll{overflow-x:auto;border:1px solid var(--line);border-radius:16px}h2{font-size:20px;margin:28px 0 12px}tbody tr:hover{background:rgba(31,111,107,.04)}:focus-visible{outline:3px solid var(--coral);outline-offset:3px}.muted{color:var(--muted);font-size:13px}
a{color:inherit}header a{color:#fff}
.lv.NIFAS{background:#6a1b9a;color:#fff}form{display:inline}input[type=text]{min-height:44px;padding:10px;border:1px solid var(--line);border-radius:6px;font:inherit;max-width:180px}
button{min-height:44px;padding:10px 14px;border-radius:6px;border:1px solid var(--teal);background:var(--teal);color:#fff;font:inherit;cursor:pointer}
.msg{background:#e8f5e9;color:#1b5e20;border:1px solid #1b7f3b;border-radius:10px;padding:10px 12px;margin-bottom:14px}
pre{white-space:pre-wrap;background:var(--card);border:1px solid var(--line);border-radius:10px;padding:12px;font-size:13px}
"""

    fun head(title: String, refresh: Boolean = false) = """<!doctype html><html lang="id"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">${if (refresh) "\n<meta http-equiv=\"refresh\" content=\"30\">" else ""}
<title>${esc(title)}</title>
<style>$CSS</style></head><body>"""

    fun summary(rows: List<CaseRow>): Summary {
        val acked = rows.filter { it.c.ackAt != null }
        val mins = acked.map { (it.c.ackAt!! - it.c.receivedAt) / 60_000.0 }.filter { it >= 0 }.sorted()
        return Summary(
            total = rows.size,
            perLevel = listOf("MERAH", "KUNING", "HIJAU").associateWith { lv -> rows.count { it.c.tingkat == lv } } +
                ("TIDAK_TERBACA" to rows.count { it.c.unparsed }),
            merahWithoutAck = rows.count { it.c.tingkat == "MERAH" && it.c.ackAt == null },
            acked = acked.size,
            medianMinutesToAck = median(mins),
            perFacility = rows.groupingBy { it.facility }.eachCount(),
            perLokasi = rows.groupingBy { it.c.lokasi ?: "?" }.eachCount(),
        )
    }

    fun median(sorted: List<Double>): Double? = when {
        sorted.isEmpty() -> null
        sorted.size % 2 == 1 -> sorted[sorted.size / 2]
        else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }

    fun esc(s: String?) = (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun render(rows: List<CaseRow>, lastSync: Map<String, Long>, now: Long): String {
        val s = summary(rows)
        val sorted = rows.sortedWith(
            compareBy<CaseRow>({ if (it.c.ackAt == null) 0 else 1 }, { levelRank(it.c.tingkat) }, { -it.c.receivedAt }),
        )
        return buildString {
            append(
                head("BUNDA SIAGA · Dasbor", refresh = true) +
                    """<header><h1>BUNDA SIAGA · Dasbor rujukan wilayah</h1>
<p>Data dari HP puskesmas (gateway SMS). Diperbarui otomatis tiap 30 detik. <a href="/satusehat">SATUSEHAT</a> · <a href="/privasi">Privasi</a></p></header><main>
<div class="warn">Tingkat MERAH/KUNING/HIJAU adalah <b>sinyal prioritas</b> dari aturan DRAFT yang belum divalidasi klinis, bukan diagnosis. Data berupa kode pasien (tanpa nama, tanpa nomor HP).</div>
""",
            )
            append("""<div class="grid">""")
            tile(s.total.toString(), "Total kasus")
            tile(s.perLevel["MERAH"].toString(), "MERAH")
            tile(s.merahWithoutAck.toString(), "MERAH belum dibalas")
            tile(s.perLevel["KUNING"].toString(), "KUNING")
            tile(s.perLevel["HIJAU"].toString(), "HIJAU")
            tile(s.medianMinutesToAck?.let { "%.1f".format(it) } ?: "–", "Median menit sampai dibalas")
            append("</div>")

            append("<h2>Kasus (belum dibalas dulu, lalu MERAH)</h2><div class=\"scroll\"><table><tr><th>Tingkat</th><th>Pasien</th><th>Lokasi</th><th>UK / nifas</th><th>TD</th><th>Tanda</th><th>Diterima</th><th>Balasan</th><th>Faskes</th></tr>")
            if (sorted.isEmpty()) append("<tr><td colspan=9>Belum ada data.</td></tr>")
            for (r in sorted) {
                val c = r.c
                val lv = c.tingkat?.takeIf { it in setOf("MERAH", "KUNING", "HIJAU") } ?: "X"
                append("<tr class=\"${if (c.ackAt == null) "open" else ""}\">")
                append("<td><span class=\"lv $lv\">${esc(c.tingkat ?: "TAK TERBACA")}</span>${if (!c.checksumVerified && !c.unparsed) "<br><span class=muted>tanpa checksum</span>" else ""}</td>")
                val age = if (c.fase == "NIFAS") "<span class=\"lv NIFAS\">NIFAS</span> hari ke-${c.hariNifas ?: "?"}" else (c.usiaMinggu?.let { "$it mg" } ?: "–")
                append("<td>${esc(c.pasien)}</td><td>${esc(c.lokasi)}</td><td>$age</td><td>${esc(c.td ?: "–")}</td>")
                append("<td>${esc(c.tanda.split('.').filter { it.isNotBlank() }.joinToString(", ") { signLabel(it) }.ifEmpty { "–" })}</td>")
                append("<td>${fmt.format(Instant.ofEpochMilli(c.receivedAt))}</td>")
                append("<td>${if (c.ackAt != null) esc(ACK[c.ackStatus] ?: c.ackStatus) + "<br><span class=muted>" + minutes(c.ackAt - c.receivedAt) + "</span>" else "<b>BELUM</b>"}</td>")
                append("<td>${esc(r.facility)}</td></tr>")
            }
            append("</table></div>")

            append("<h2>Per faskes penerima</h2><div class=\"scroll\"><table><tr><th>Faskes</th><th>Kasus</th><th>Sinkron terakhir</th></tr>")
            for ((f, n) in s.perFacility.toList().sortedByDescending { it.second }) {
                val last = lastSync[f]
                append("<tr><td>${esc(f)}</td><td>$n</td><td>${last?.let { fmt.format(Instant.ofEpochMilli(it)) + " (" + minutes(now - it) + " lalu)" } ?: "–"}</td></tr>")
            }
            append("</table></div>")
            append("<h2>Per lokasi asal</h2><div class=\"scroll\"><table><tr><th>Lokasi</th><th>Kasus</th></tr>")
            for ((l, n) in s.perLokasi.toList().sortedByDescending { it.second }) append("<tr><td>${esc(l)}</td><td>$n</td></tr>")
            append("</table></div>")
            append("<p class=muted>Angka di atas dihitung dari data yang sudah tersinkron. Kasus yang masih di HP tanpa internet belum terlihat di sini.</p>")
            append("</main></body></html>")
        }
    }

    private fun StringBuilder.tile(value: String, label: String) {
        append("<div class=\"tile\"><b>${esc(value)}</b><span>${esc(label)}</span></div>")
    }

    private fun levelRank(t: String?) = when (t) { "MERAH" -> 0; null -> 1; "KUNING" -> 2; else -> 3 }

    private fun signLabel(code: String): String {
        val base = code.substringBefore(':')
        val sub = when (code.substringAfter(':', "")) { "C" -> " (bercak)"; "S" -> " (sedang)"; "B" -> " (banyak)"; else -> "" }
        return (SIGNS[base] ?: base) + sub
    }

    private fun minutes(ms: Long) = if (ms < 3_600_000) "${ms / 60_000} mnt" else "%.1f jam".format(ms / 3_600_000.0)
}
