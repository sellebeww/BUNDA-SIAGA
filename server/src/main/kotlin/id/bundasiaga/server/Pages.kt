package id.bundasiaga.server

import id.bundasiaga.server.Dashboard.esc
import id.bundasiaga.server.satusehat.SatuSehatService
import java.net.URLEncoder
import java.time.Instant

object Pages {

    fun satuSehat(rows: List<CaseRow>, links: Map<Pair<String, String>, SatuSehatLink>, ss: SatuSehatService, csrf: String, message: String?): String =
        buildString {
            append(Dashboard.head("BUNDA SIAGA · SATUSEHAT"))
            append("""<header><h1>BUNDA SIAGA · Kirim ke SATUSEHAT</h1><p><a href="/">Kembali ke dasbor</a></p></header><main>""")
            message?.let { append("<div class=\"msg\">${esc(it)}</div>") }
            if (!ss.enabled) {
                append(
                    """<div class="warn"><b>Integrasi belum aktif.</b> Isi BS_SS_ENV, BS_SS_CLIENT_ID, BS_SS_CLIENT_SECRET, BS_SS_ORG_ID dan BS_SS_MAP
(lihat docs/SATUSEHAT.md). Sementara itu, tombol <i>Lihat FHIR</i> menampilkan data yang akan dikirim, dengan tanda &lt;...&gt; untuk ID yang belum ada.</div>""",
                )
            } else {
                val env = if (ss.config!!.fhirUrl.contains("-stg")) "SANDBOX (uji coba)" else "PRODUKSI"
                append("<div class=\"warn\">Terhubung ke SATUSEHAT <b>$env</b>. Pemetaan kode: <b>${esc(ss.terminology.status)}</b>.</div>")
            }
            append(
                """<div class="warn">Yang dikirim: kunjungan (Encounter), tekanan darah, usia kehamilan atau hari nifas, tanda bahaya yang dijawab YA,
dan permintaan rujukan (ServiceRequest) dengan catatan bahwa tingkat MERAH/KUNING/HIJAU adalah sinyal prioritas, bukan diagnosis.
Untuk menautkan pasien diperlukan ID pasien SATUSEHAT (IHS) atau NIK. <b>NIK tidak disimpan</b> di server ini; hanya ID IHS yang disimpan.</div>""",
            )
            append("<div class=\"scroll\"><table><tr><th>Tingkat</th><th>Pasien (kode)</th><th>Faskes</th><th>Diterima</th><th>Status SATUSEHAT</th><th>Tindakan</th></tr>")
            val visible = rows.filter { !it.c.unparsed }.sortedByDescending { it.c.receivedAt }
            if (visible.isEmpty()) append("<tr><td colspan=6>Belum ada data.</td></tr>")
            for (r in visible) {
                val c = r.c
                val link = links[r.facility to c.caseKey]
                val status = when (link?.status) {
                    null -> "Belum ditautkan"
                    "LINKED" -> "Ditautkan ke ${esc(link.patientIhs)}, belum dikirim"
                    "SENT" -> "<b>Terkirim</b>${link.encounterId?.let { " (Encounter ${esc(it)})" } ?: ""}"
                    "FAILED" -> "<b>Gagal</b>: ${esc(link.lastError)}"
                    else -> esc(link.status)
                }
                val hidden = """<input type="hidden" name="csrf" value="${esc(csrf)}"><input type="hidden" name="facility" value="${esc(r.facility)}"><input type="hidden" name="caseKey" value="${esc(c.caseKey)}">"""
                val q = "facility=${url(r.facility)}&case=${url(c.caseKey)}"
                append("<tr><td><span class=\"lv ${esc(c.tingkat ?: "X")}\">${esc(c.tingkat ?: "?")}</span>${if (c.fase == "NIFAS") " <span class=\"lv NIFAS\">NIFAS</span>" else ""}</td>")
                append("<td>${esc(c.pasien)}</td><td>${esc(r.facility)}</td><td>${Dashboard.fmt.format(Instant.ofEpochMilli(c.receivedAt))}</td><td>$status</td><td>")
                append("<a href=\"/satusehat/preview?$q\">Lihat FHIR</a>")
                if (link?.status != "SENT") {
                    append(
                        """<br><form method="post" action="/satusehat/link">$hidden<input type="text" name="ihs" placeholder="ID pasien (IHS)" autocomplete="off">
 atau <input type="text" name="nik" placeholder="NIK (16 digit)" inputmode="numeric" autocomplete="off"> <button>Tautkan</button></form>""",
                    )
                    if (link != null && ss.enabled) append("""<br><form method="post" action="/satusehat/send">$hidden<button>Kirim ke SATUSEHAT</button></form>""")
                }
                append("</td></tr>")
            }
            append("</table></div></main></body></html>")
        }

    fun privacy(): String = Dashboard.head("BUNDA SIAGA · Kebijakan privasi") +
        (Pages::class.java.getResourceAsStream("/privasi.html")?.bufferedReader()?.use { it.readText() } ?: "<p>Belum tersedia.</p>") +
        "</body></html>"

    private fun url(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
}
