package id.bundasiaga.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.selectable
import id.bundasiaga.app.R
import id.bundasiaga.app.data.CapacityEntity
import id.bundasiaga.app.data.ReferralMapper
import id.bundasiaga.codec.Decoded
import id.bundasiaga.codec.Decoder
import id.bundasiaga.codec.Encoder
import id.bundasiaga.codec.Kapasitas
import id.bundasiaga.codec.StatusKapasitas
import id.bundasiaga.outbox.CapacityPolicy

fun capacityColor(s: StatusKapasitas) = when (s) {
    StatusKapasitas.SIAP -> HijauBg
    StatusKapasitas.TERBATAS -> KuningBg
    StatusKapasitas.PENUH -> MerahBg
}

/** Satu baris per status faskes, dengan sumber dan umurnya, serta peringatan bila sudah lama. */
@Composable
fun CapacityLine(c: CapacityEntity, now: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            c.status.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
            color = if (c.status == StatusKapasitas.TERBATAS) Ink else capacityColor(c.status),
        )
    }
    c.tempatTidur?.let { KeyValue(stringResource(R.string.capacity_beds), it.toString()) }
    KeyValue(
        stringResource(R.string.capacity_source),
        if (c.sender == LOCAL_SENDER) stringResource(R.string.capacity_source_local)
        else stringResource(R.string.capacity_source_sms, c.sender),
    )
    KeyValue(stringResource(R.string.capacity_set_at), formatTime(c.statusAt) + " · " + agoText(now - c.statusAt))
    if (CapacityPolicy.isStale(c.toModel(), now)) Disclaimer(stringResource(R.string.capacity_stale))
    if (!c.checksumVerified) Text(stringResource(R.string.capacity_unverified), style = MaterialTheme.typography.bodySmall)
}

@Composable
fun agoText(ms: Long): String = when {
    ms < 60 * 60_000L -> stringResource(R.string.ago_minutes, (ms / 60_000L).coerceAtLeast(0))
    ms < 48 * 60 * 60_000L -> stringResource(R.string.ago_hours, ms / 3_600_000L)
    else -> stringResource(R.string.ago_days, ms / 86_400_000L)
}

@Composable
fun CapacitySendScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val facilities by vm.app.db.facilities().observeAll().collectAsState(initial = emptyList())
    val recipients by vm.app.db.recipients().observeAll().collectAsState(initial = emptyList())
    val history by vm.app.db.outbox().observeCapacityBroadcasts().collectAsState(initial = emptyList())
    var faskesId by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf<StatusKapasitas?>(null) }
    var beds by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }

    val bedsInt = beds.toIntOrNull()
    val bedsOk = beds.isEmpty() || (bedsInt != null && bedsInt in 0..99)
    val preview = status?.let { st ->
        runCatching { Encoder.encode(Kapasitas(faskesId, st, bedsInt, ReferralMapper.waktu(System.currentTimeMillis()))) }.getOrNull()
    }
    val canSend = facilities.any { it.id == faskesId } && preview != null && bedsOk && recipients.isNotEmpty() && !vm.capacityBusy

    Column(Modifier.fillMaxSize()) {
        BundaTopBar(stringResource(R.string.menu_capacity), onBack = { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.capacity_intro), style = MaterialTheme.typography.bodyLarge)
            if (facilities.isEmpty()) {
                EmptyState("Tambahkan fasilitas terlebih dahulu", "Isi identitas dan kontak fasilitas sebelum mengirim kabar kapasitas.", Icons.Filled.People)
                BigButton("Kelola fasilitas", onClick = { vm.go(Screen.Faskes) })
                return@Column
            }
            SectionCard(stringResource(R.string.capacity_which)) {
                facilities.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = faskesId == f.id, role = SemanticsRole.RadioButton, onClick = { faskesId = f.id; sent = false }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = faskesId == f.id, onClick = null)
                        Text(f.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            SectionCard(stringResource(R.string.capacity_status)) {
                StatusKapasitas.entries.forEach { st ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(selected = status == st, role = SemanticsRole.RadioButton, onClick = { status = st; sent = false }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = status == st, onClick = null)
                        Text(st.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                OutlinedTextField(
                    value = beds, onValueChange = { beds = it.filter(Char::isDigit).take(2); sent = false },
                    label = { Text(stringResource(R.string.capacity_beds_optional)) },
                    singleLine = true, isError = !bedsOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge,
                )
            }
            SectionCard(stringResource(R.string.capacity_recipients)) {
                Text(
                    if (recipients.isEmpty()) stringResource(R.string.capacity_no_recipients)
                    else stringResource(R.string.capacity_recipient_count, recipients.size),
                    style = MaterialTheme.typography.bodyLarge,
                )
                SecondaryButton(stringResource(R.string.recipients_title), onClick = { vm.go(Screen.Recipients) }, icon = Icons.Filled.People)
            }
            preview?.let { SectionCard(stringResource(R.string.confirm_raw)) { RawSms(it) } }
            if (!hasSmsPermission(context)) Disclaimer(stringResource(R.string.capacity_no_permission))
            BigButton(
                if (vm.capacityBusy) stringResource(R.string.capacity_preparing) else stringResource(R.string.capacity_send, recipients.size), enabled = canSend,
                icon = Icons.AutoMirrored.Filled.Send, onClick = { confirm = true },
            )
            if (vm.capacityFailed) Disclaimer(stringResource(R.string.capacity_queue_failed))
            if (sent) Text(stringResource(R.string.capacity_queued), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.capacity_trust_note), style = MaterialTheme.typography.bodySmall)

            SectionCard(stringResource(R.string.capacity_history)) {
                if (history.isEmpty()) Text(stringResource(R.string.summary_no_data))
                history.take(30).forEach { m ->
                    val k = (Decoder.decode(m.body) as? Decoded.Capacity)?.value
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text("${k?.faskesId ?: "?"} · ${k?.status?.label ?: ""}", style = MaterialTheme.typography.bodyLarge)
                        Text("${m.destination} · ${formatTime(m.createdAt)} · ${stateLabel(m.state, null)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.capacity_confirm_title)) },
            text = { Text(stringResource(R.string.capacity_confirm_body, recipients.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    sent = false
                    status?.let { vm.sendCapacity(faskesId, it, bedsInt, recipients, context) { sent = true } }
                }) { Text(stringResource(R.string.capacity_confirm_yes)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun RecipientsScreen(vm: AppViewModel) {
    val recipients by vm.app.db.recipients().observeAll().collectAsState(initial = emptyList())
    var number by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    val numberOk = id.bundasiaga.app.data.InputValidation.phone(number)
    Column(Modifier.fillMaxSize()) {
        BundaTopBar(stringResource(R.string.recipients_title), onBack = { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.recipients_intro), style = MaterialTheme.typography.bodyLarge)
            SectionCard(stringResource(R.string.recipients_add)) {
                OutlinedTextField(
                    value = number, onValueChange = { number = it.take(20) },
                    label = { Text(stringResource(R.string.recipients_number)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge,
                )
                OutlinedTextField(
                    value = label, onValueChange = { label = it.take(40) },
                    label = { Text(stringResource(R.string.recipients_label)) },
                    supportingText = { Text(stringResource(R.string.recipients_label_hint)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge,
                )
                BigButton(stringResource(R.string.recipients_add), enabled = numberOk, icon = Icons.Filled.PersonAdd, onClick = {
                    vm.addRecipient(number, label)
                    number = ""
                    label = ""
                })
            }
            SecondaryButton(stringResource(R.string.recipients_import), onClick = { vm.importRecipientsFromCases() }, icon = Icons.Filled.GroupAdd)
            if (recipients.isEmpty()) Text(stringResource(R.string.capacity_no_recipients), style = MaterialTheme.typography.bodyLarge)
            recipients.forEach { r ->
                SectionCard(null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.label.ifBlank { r.number }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            if (r.label.isNotBlank()) Text(r.number, style = MaterialTheme.typography.bodyMedium)
                        }
                        IconButton(onClick = { vm.deleteRecipient(r.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.recipients_delete), tint = Color(0xFF546E7A))
                        }
                    }
                }
            }
        }
    }
}
