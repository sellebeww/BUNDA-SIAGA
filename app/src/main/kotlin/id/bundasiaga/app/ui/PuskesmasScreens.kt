package id.bundasiaga.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.automirrored.filled.PhoneCallback
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.bundasiaga.app.R
import id.bundasiaga.app.data.CaseEntity
import id.bundasiaga.app.data.Role
import id.bundasiaga.codec.AckStatus
import id.bundasiaga.rules.Level

@Composable
fun InboxScreen(vm: AppViewModel) {
    val cases by vm.app.db.cases().observeAll().collectAsState(initial = emptyList())
    val isRoot = vm.stack.size == 1
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    val realCases = cases.filter { it.sender != SIMULATED_SENDER }
    val visible = realCases.filter { c ->
        (filter != "pending" || c.ackStatus == null) &&
            (filter != "red" || c.tingkat == id.bundasiaga.codec.Tingkat.MERAH) &&
            (search.isBlank() || listOfNotNull(c.pasien, c.lokasi).any { it.contains(search.trim(), ignoreCase = true) })
    }
    Column(Modifier.fillMaxSize()) {
        BundaTopBar(
            stringResource(R.string.inbox_title), onBack = if (isRoot) null else ({ vm.back() }),
            actions = { if (vm.app.settings.role == Role.PUSKESMAS) OverflowMenu(vm) },
        )
        LazyColumn(
            Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionHeading(stringResource(R.string.inbox_count, realCases.size, realCases.count { it.ackStatus == null }), stringResource(R.string.inbox_sorted))
            }
            item {
                OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.inbox_search)) }, leadingIcon = { Icon(Icons.Filled.Search, null) })
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all" to R.string.inbox_filter_all, "pending" to R.string.inbox_filter_pending, "red" to R.string.inbox_filter_red).forEach { (value, label) ->
                        FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(stringResource(label)) })
                    }
                }
            }
            item { SecondaryButton(stringResource(R.string.summary_title), onClick = { vm.go(Screen.Summary) }, icon = Icons.Filled.BarChart) }
            if (vm.app.settings.role == Role.PUSKESMAS && id.bundasiaga.app.BuildConfig.SMS_DIRECT) {
                item { SecondaryButton(stringResource(R.string.menu_capacity), onClick = { vm.go(Screen.CapacitySend) }, icon = Icons.Filled.LocalHospital) }
            }
            if (visible.isEmpty()) {
                item { EmptyState(stringResource(if (realCases.isEmpty()) R.string.inbox_empty_title else R.string.inbox_no_match),
                    stringResource(if (realCases.isEmpty()) R.string.inbox_empty else R.string.inbox_no_match_hint), Icons.Filled.Inbox) }
            }
            items(visible, key = { it.id }) { c -> CaseRow(c) { vm.go(Screen.CaseDetail(c.id)) } }
        }
    }
}

@Composable
private fun CaseRow(c: CaseEntity, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (c.tingkat != null) LevelChip(levelOf(c.tingkat)) else StatusLabel(stringResource(R.string.inbox_unparsed_chip))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    listOfNotNull(c.pasien, c.lokasi, formatTime(c.receivedAt)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (c.ackStatus != null) stringResource(R.string.inbox_replied, c.ackStatus.label)
                    else stringResource(R.string.inbox_not_replied),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (c.ackStatus == null) MerahBg else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (c.fase == id.bundasiaga.codec.FaseRujukan.NIFAS) StatusLabel(stringResource(R.string.fase_nifas_chip))
            }
        }
    }
}

@Composable
fun CaseDetailScreen(vm: AppViewModel, caseId: Long) {
    val context = LocalContext.current
    val c by vm.app.db.cases().observe(caseId).collectAsState(initial = null)
    val case = c ?: return
    val r = case.referral()
    val ackMsg by (case.ackOutboxId?.let { vm.app.db.outbox().observe(it) } ?: kotlinx.coroutines.flow.flowOf(null))
        .collectAsState(initial = null)

    Column(Modifier.fillMaxSize()) {
        BundaTopBar(stringResource(R.string.case_title), onBack = { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (r != null) {
                LevelBanner(levelOf(r.tingkat))
                Text(stringResource(R.string.case_signal_note), style = MaterialTheme.typography.bodyMedium)
                SectionCard(stringResource(R.string.case_data)) { ReferralDetails(r) }
                if (!case.checksumVerified) Disclaimer(stringResource(R.string.case_unverified))
            } else {
                SectionCard(stringResource(R.string.case_unparsed_title), container = Color(0xFFFFF8E1)) {
                    Text(stringResource(R.string.case_unparsed_body), style = MaterialTheme.typography.bodyLarge)
                    Text(case.parseError ?: "", style = MaterialTheme.typography.bodySmall)
                }
            }
            SectionCard(stringResource(R.string.case_from)) {
                KeyValue(stringResource(R.string.case_sender), case.sender)
                KeyValue(stringResource(R.string.case_received), formatTime(case.receivedAt))
                if (case.duplicates > 0) KeyValue(stringResource(R.string.case_duplicates), case.duplicates.toString())
                RawSms(case.raw)
            }
            if (case.sender != SIMULATED_SENDER) {
                BigButton(stringResource(R.string.case_call), onClick = { dial(context, case.sender) }, icon = Icons.Filled.Call)
            }

            if (r != null) {
                SectionCard(stringResource(R.string.case_reply)) {
                    Text(stringResource(R.string.case_reply_note), style = MaterialTheme.typography.bodyMedium)
                    case.ackStatus?.let {
                        Text(stringResource(R.string.case_replied_with, it.label), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                        ackMsg?.let { m -> Text(stateLabel(m.state, null), style = MaterialTheme.typography.bodyMedium) }
                    }
                    BigButton(stringResource(R.string.ack_ready), enabled = !vm.actionBusy, onClick = { vm.sendAck(case, AckStatus.SIAP, context) }, icon = Icons.Filled.CheckCircle, color = HijauBg)
                    BigButton(stringResource(R.string.ack_full), enabled = !vm.actionBusy, onClick = { vm.sendAck(case, AckStatus.PENUH, context) }, icon = Icons.Filled.DoNotDisturbOn, color = MerahBg)
                    BigButton(stringResource(R.string.ack_callback), enabled = !vm.actionBusy, onClick = { vm.sendAck(case, AckStatus.HUBUNGI, context) }, icon = Icons.AutoMirrored.Filled.PhoneCallback, color = Teal)
                }
            }
        }
    }
}

@Composable
fun SummaryScreen(vm: AppViewModel) {
    val cases by vm.app.db.cases().observeAll().collectAsState(initial = emptyList())
    val real = cases.filter { it.sender != SIMULATED_SENDER }
    val acked = real.filter { it.ackAt != null }
    val ackMinutes = acked.map { (it.ackAt!! - it.receivedAt) / 60_000.0 }.filter { it >= 0 }
    val avgAckMin = ackMinutes.takeIf { it.isNotEmpty() }?.average()
    Column(Modifier.fillMaxSize()) {
        BundaTopBar(stringResource(R.string.summary_title), onBack = { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.summary_note), style = MaterialTheme.typography.bodySmall)
            SectionCard(stringResource(R.string.summary_by_level)) {
                Level.entries.reversed().forEach { lv ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        LevelChip(lv)
                        Spacer(Modifier.width(12.dp))
                        Text(real.count { it.tingkat != null && levelOf(it.tingkat) == lv }.toString(), style = MaterialTheme.typography.headlineSmall)
                    }
                }
                KeyValue(stringResource(R.string.summary_unparsed), real.count { it.tingkat == null }.toString())
            }
            SectionCard(stringResource(R.string.summary_ack)) {
                KeyValue(stringResource(R.string.summary_replied), "${acked.size} / ${real.size}")
                KeyValue(
                    stringResource(R.string.summary_avg_ack),
                    avgAckMin?.let { stringResource(R.string.minutes_fmt, "%.1f".format(it)) } ?: stringResource(R.string.summary_no_data),
                )
            }
            SectionCard(stringResource(R.string.summary_by_location)) {
                val byLok = real.groupBy { it.lokasi ?: "?" }.mapValues { it.value.size }.toList().sortedByDescending { it.second }
                if (byLok.isEmpty()) Text(stringResource(R.string.summary_no_data))
                byLok.forEach { (lok, n) -> KeyValue(lok, n.toString()) }
            }
            if (vm.app.settings.role == Role.PUSKESMAS) {
                val s = vm.app.settings
                SectionCard(stringResource(R.string.summary_sync)) {
                    Text(
                        if (s.serverUrl.isBlank()) stringResource(R.string.summary_sync_off)
                        else stringResource(R.string.summary_sync_on, s.serverUrl, real.count { it.syncedAt == null }),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
