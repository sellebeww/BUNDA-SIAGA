package id.bundasiaga.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.bundasiaga.app.R
import id.bundasiaga.app.data.MetricEntity
import id.bundasiaga.app.data.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class Faskes(
    val id: String,
    val nama: String,
    val jenis: String,
    val kontak: String,
    val kapasitas: String,
    val kapasitasUpdatedAt: String,
    val waktuTempuh: Map<String, Int>,
)

data class FaskesData(val note: String, val updatedAt: String, val dusun: List<Pair<String, String>>, val faskes: List<Faskes>) {
    companion object {
        /** Data MOCK bawaan. Mengembalikan null (dan UI mengatakannya) bila berkas hilang atau rusak. */
        fun load(context: Context): FaskesData? = runCatching {
            val o = JSONObject(context.assets.open("faskes.json").bufferedReader().use { it.readText() })
            val dusun = o.getJSONArray("dusun").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { d -> d.getString("id") to d.getString("nama") } } }
            val fk = o.getJSONArray("faskes").let { a ->
                (0 until a.length()).map { i ->
                    val f = a.getJSONObject(i)
                    val w = f.getJSONObject("waktu_tempuh_menit")
                    Faskes(
                        f.getString("id"), f.getString("nama"), f.getString("jenis"), f.getString("kontak"), f.getString("kapasitas"),
                        f.getString("kapasitas_updated_at"), w.keys().asSequence().associateWith { w.getInt(it) },
                    )
                }
            }
            FaskesData(o.getString("note"), o.getString("updated_at"), dusun, fk)
        }.getOrNull()
    }
}

private fun daysAgo(date: String): Long? = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(date.take(10)), LocalDate.now()) }.getOrNull()

@Composable
private fun ageText(date: String): String {
    val d = daysAgo(date) ?: return date
    return if (d <= 0) stringResource(R.string.age_today) else stringResource(R.string.age_days, d)
}

@Composable
fun FaskesScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val data = vm.app.faskes
    val dusunId = vm.app.settings.dusunId
    val capacity by vm.app.db.capacity().observeAll().collectAsState(initial = emptyList())
    val now = System.currentTimeMillis()
    Column(Modifier.fillMaxSize()) {
        BundaTopBar(stringResource(R.string.menu_faskes), onBack = { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (data == null) {
                Disclaimer(stringResource(R.string.faskes_missing))
                return@Column
            }
            MockLabel(stringResource(R.string.mock_label))
            Text(data.note, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.faskes_updated, ageText(data.updatedAt)), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.faskes_from, data.dusun.firstOrNull { it.first == dusunId }?.second ?: stringResource(R.string.faskes_no_dusun)),
                style = MaterialTheme.typography.titleMedium,
            )
            val sorted = data.faskes.sortedBy { it.waktuTempuh[dusunId] ?: Int.MAX_VALUE }
            sorted.forEach { f ->
                SectionCard(f.nama) {
                    Text(f.jenis, style = MaterialTheme.typography.bodyMedium)
                    KeyValue(
                        stringResource(R.string.faskes_travel),
                        f.waktuTempuh[dusunId]?.let { stringResource(R.string.faskes_travel_value, it / 60, it % 60) } ?: stringResource(R.string.unknown),
                    )
                    val live = capacity.firstOrNull { it.faskesId == f.id }
                    if (live != null) {
                        Text(stringResource(R.string.faskes_capacity), style = MaterialTheme.typography.bodyMedium)
                        CapacityLine(live, now)
                    } else {
                        KeyValue(stringResource(R.string.faskes_capacity), f.kapasitas)
                        KeyValue(stringResource(R.string.faskes_capacity_updated), ageText(f.kapasitasUpdatedAt))
                        Text(stringResource(R.string.faskes_capacity_mock_note), style = MaterialTheme.typography.bodySmall)
                    }
                    SecondaryButton(stringResource(R.string.faskes_call, f.kontak), onClick = { dial(context, f.kontak) }, icon = Icons.Filled.Call)
                }
            }
            Disclaimer(stringResource(R.string.faskes_estimate_note))
        }
    }
}

@Composable
fun DebugScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val s = vm.app.settings
    val scope = rememberCoroutineScope()
    val events by vm.app.db.metrics().observeRecent().collectAsState(initial = emptyList())
    var sim by remember { mutableStateOf(s.simulationMode) }
    var noService by remember { mutableStateOf(s.simulateNoService) }
    var secure by remember { mutableStateOf(s.secureScreens) }
    var durations by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(events.size) {
        durations = withContext(Dispatchers.IO) { Metrics.durations(vm.app.db.metrics().all()) }
    }

    Column(Modifier.fillMaxSize()) {
        BundaTopBar(stringResource(R.string.menu_debug), onBack = { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(stringResource(R.string.sim_section)) {
                Text(stringResource(R.string.sim_explain), style = MaterialTheme.typography.bodyMedium)
                ToggleRow(stringResource(R.string.sim_mode), sim) {
                    sim = it; s.simulationMode = it
                    if (!it) { noService = false; s.simulateNoService = false }
                    vm.settingsChanged()
                }
                ToggleRow(stringResource(R.string.sim_no_service), noService, enabled = sim) {
                    noService = it; s.simulateNoService = it
                    vm.settingsChanged()
                    // "Memulihkan" sinyal harus langsung mengirim ulang dari antrian.
                    if (!it) vm.app.runner.serviceRestored()
                }
                if (sim && s.role != Role.KADER) {
                    SecondaryButton(stringResource(R.string.sim_inject), onClick = { vm.injectSimulatedReferral() }, icon = Icons.Filled.MoveToInbox)
                }
            }
            SectionCard(stringResource(R.string.privacy_section)) {
                ToggleRow(stringResource(R.string.secure_screens), secure) {
                    secure = it; s.secureScreens = it
                    vm.settingsChanged()
                }
            }
            SectionCard(stringResource(R.string.metrics_section)) {
                Text(stringResource(R.string.metrics_note), style = MaterialTheme.typography.bodySmall)
                if (durations.isEmpty()) Text(stringResource(R.string.summary_no_data))
                durations.forEach { (k, v) -> KeyValue(k, v) }
                SecondaryButton(stringResource(R.string.metrics_export), icon = Icons.Filled.Download, onClick = {
                    scope.launch {
                        val csv = withContext(Dispatchers.IO) { Metrics.csv(vm.app.db.metrics().all()) }
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/csv")
                                    .putExtra(Intent.EXTRA_SUBJECT, "bunda-siaga-metrics.csv")
                                    .putExtra(Intent.EXTRA_TEXT, csv),
                                null,
                            ),
                        )
                    }
                })
            }
            SectionCard(stringResource(R.string.metrics_recent)) {
                events.take(30).forEach { e ->
                    Text("${formatTime(e.at)}  ${e.event}${e.refId?.let { " #$it" } ?: ""}${e.detail?.let { " ($it)" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                }
            }
            id.bundasiaga.rules.Fase.entries.forEach { fase ->
                val cfg = vm.app.rules.engine(fase).config
                Text(stringResource(R.string.rules_version_fmt, cfg.version, cfg.status), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled) { onChange(!checked) }
            .semantics { role = SemanticsRole.Switch },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Selisih waktu antar peristiwa metrik, hanya diukur di HP ini. */
object Metrics {
    private val pairs = listOf(
        Triple("checklist_start", "result_shown", "Mulai periksa → hasil"),
        Triple("send_attempt", "sent", "Tekan kirim → diterima jaringan"),
        Triple("send_attempt", "ack_received", "Tekan kirim → balasan puskesmas"),
        Triple("case_received", "ack_sent", "Rujukan masuk → ACK dikirim"),
    )

    fun durations(all: List<MetricEntity>): List<Pair<String, String>> = pairs.mapNotNull { (from, to, label) ->
        val samples = if (from == "checklist_start") {
            // checklist_start tidak punya ref id: pasangkan setiap awal dengan hasil berikutnya.
            val starts = all.filter { it.event == from }
            val results = all.filter { it.event == to }
            starts.mapNotNull { s -> results.firstOrNull { it.at >= s.at }?.let { it.at - s.at } }
        } else {
            val ends = all.filter { it.event == to }
            all.filter { it.event == from && it.refId != null }.mapNotNull { s ->
                ends.firstOrNull { it.refId == s.refId && it.at >= s.at }?.let { it.at - s.at }
            }
        }
        if (samples.isEmpty()) {
            null
        } else {
            val s = samples.sorted()
            val median = if (s.size % 2 == 1) s[s.size / 2].toDouble() else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
            label to "median %.0f dtk (n=%d)".format(median / 1000.0, samples.size)
        }
    }

    fun csv(all: List<MetricEntity>): String = buildString {
        appendLine("id,event,at_epoch_ms,ref_id,detail")
        all.forEach { appendLine("${it.id},${it.event},${it.at},${it.refId ?: ""},${(it.detail ?: "").replace(",", " ")}") }
    }
}

@Composable
fun HonestyScreen(vm: AppViewModel) {
    val rows = listOf(
        R.string.h_rules to R.string.h_rules_v,
        R.string.h_nifas to R.string.h_nifas_v,
        R.string.h_rules_update to R.string.h_rules_update_v,
        R.string.h_capacity to R.string.h_capacity_v,
        R.string.h_sms to R.string.h_sms_v,
        R.string.h_ack to R.string.h_ack_v,
        R.string.h_delivery to R.string.h_delivery_v,
        R.string.h_encrypt to R.string.h_encrypt_v,
        R.string.h_faskes to R.string.h_faskes_v,
        R.string.h_retry to R.string.h_retry_v,
        R.string.h_sim to R.string.h_sim_v,
        R.string.h_field to R.string.h_field_v,
        R.string.h_data to R.string.h_data_v,
    )
    Column(Modifier.fillMaxSize()) {
        BundaTopBar(stringResource(R.string.menu_honesty), onBack = { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.h_intro), style = MaterialTheme.typography.bodyLarge)
            rows.forEach { (k, v) ->
                SectionCard(null) {
                    Text(stringResource(k), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(v), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
