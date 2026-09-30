package id.bundasiaga.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import id.bundasiaga.app.BuildConfig
import id.bundasiaga.app.R
import id.bundasiaga.app.data.toModel
import id.bundasiaga.codec.AckStatus
import id.bundasiaga.codec.Codebook
import id.bundasiaga.codec.Decoded
import id.bundasiaga.codec.Decoder
import id.bundasiaga.codec.FaseRujukan
import id.bundasiaga.codec.ManualScript
import id.bundasiaga.codec.Rujukan
import id.bundasiaga.codec.Tingkat
import id.bundasiaga.outbox.OutState
import id.bundasiaga.rules.Fase
import id.bundasiaga.rules.Level
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun decodeReferral(body: String): Rujukan? = (Decoder.decode(body) as? Decoded.Referral)?.value

fun faseOf(r: Rujukan?) = if (r?.fase == FaseRujukan.NIFAS) Fase.NIFAS else Fase.HAMIL

fun levelOf(t: Tingkat) = when (t) {
    Tingkat.MERAH -> Level.MERAH
    Tingkat.KUNING -> Level.KUNING
    Tingkat.HIJAU -> Level.HIJAU
}

fun formatTime(ms: Long): String = SimpleDateFormat("d MMM HH:mm", Locale.forLanguageTag("id")).format(Date(ms))

@Composable
fun stateLabel(state: OutState, ack: AckStatus?): String = when (state) {
    OutState.DRAFT -> stringResource(R.string.state_draft)
    OutState.QUEUED -> stringResource(R.string.state_queued)
    OutState.SENDING -> stringResource(R.string.state_sending)
    OutState.SENT -> stringResource(R.string.state_sent)
    OutState.DELIVERED -> stringResource(R.string.state_delivered)
    OutState.ACKED -> stringResource(R.string.state_acked, ack?.label ?: "")
    OutState.FAILED_RETRYING -> stringResource(R.string.state_failed_retrying)
    OutState.EXPIRED -> stringResource(R.string.state_expired)
}

/** Versi mudah dibaca dari isi SMS yang persis. */
@Composable
fun ReferralDetails(r: Rujukan) {
    KeyValue(stringResource(R.string.field_patient), r.pasien)
    KeyValue(stringResource(R.string.field_level), Codebook.levelWord(r.tingkat))
    if (r.fase == FaseRujukan.NIFAS) {
        KeyValue(stringResource(R.string.field_fase), stringResource(R.string.fase_nifas))
        KeyValue(stringResource(R.string.field_hari), r.hariNifas?.let { stringResource(R.string.day_n, it) } ?: stringResource(R.string.unknown))
    } else {
        KeyValue(stringResource(R.string.field_ga), r.usiaMinggu?.let { stringResource(R.string.weeks, it) } ?: stringResource(R.string.unknown))
    }
    KeyValue(stringResource(R.string.field_bp), if (r.tdSistolik != null) "${r.tdSistolik}/${r.tdDiastolik}" else stringResource(R.string.not_measured))
    KeyValue(
        stringResource(R.string.field_signs),
        if (r.tanda.isEmpty()) stringResource(R.string.none) else r.tanda.joinToString(", ") { Codebook.signLabel(it) },
    )
    KeyValue(stringResource(R.string.field_location), r.lokasi)
    KeyValue(stringResource(R.string.field_time), "%02d, %02d:%02d".format(r.waktu.day, r.waktu.hour, r.waktu.minute))
    KeyValue(stringResource(R.string.field_msgid), r.msgId)
}

@Composable
fun RawSms(body: String) {
    SelectionContainer {
        Text(
            body,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().background(Color(0xFFECEFF1), RoundedCornerShape(8.dp)).padding(10.dp),
        )
    }
}

fun hasSmsPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

fun dial(context: Context, number: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number.filter { it.isDigit() || it == '+' }))) }
}

/** Cadangan bila SEND_SMS tidak diizinkan: serahkan teks ke aplikasi SMS HP. Status tidak bisa dilacak. */
fun openSmsApp(context: Context, number: String, body: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + number)).putExtra("sms_body", body))
    }
}

@Composable
fun ConfirmScreen(vm: AppViewModel, outboxId: Long) {
    val context = LocalContext.current
    val m by vm.app.db.outbox().observe(outboxId).collectAsState(initial = null)
    val msg = m ?: return
    val r = decodeReferral(msg.body)
    var permissionAsked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionAsked = true
        if (granted) vm.sendQueued(outboxId, context)
    }
    val noDest = msg.destination.isBlank() && vm.app.settings.destinationNumber.isBlank()
    var handedOff by remember { mutableStateOf(false) }
    val destination = msg.destination.ifBlank { vm.app.settings.destinationNumber }

    Column(Modifier.fillMaxSize()) {
        BundaTopBar(stringResource(R.string.confirm_title), onBack = { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.confirm_check), style = MaterialTheme.typography.titleMedium)
            r?.let { LevelBanner(levelOf(it.tingkat), compact = true) }
            SectionCard(stringResource(R.string.confirm_content)) { r?.let { ReferralDetails(it) } }
            SectionCard(stringResource(R.string.confirm_to)) {
                Text(
                    msg.destination.ifBlank { vm.app.settings.destinationNumber }.ifBlank { stringResource(R.string.confirm_no_dest) },
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            SectionCard(stringResource(R.string.confirm_raw)) {
                RawSms(msg.body)
                Text(stringResource(R.string.confirm_raw_note, msg.body.length), style = MaterialTheme.typography.bodySmall)
            }
            Disclaimer(stringResource(R.string.confirm_unencrypted))

            if (noDest) {
                BigButton(stringResource(R.string.confirm_go_setup), onClick = { vm.go(Screen.Setup) })
            } else if (!BuildConfig.SMS_DIRECT) {
                // Build Play: tanpa izin SMS. Aplikasi SMS di HP yang mengirimnya; kita tidak bisa melihat hasilnya.
                Disclaimer(stringResource(R.string.play_send_note))
                BigButton(
                    stringResource(R.string.perm_use_sms_app), icon = Icons.Filled.Sms, color = MerahBg,
                    onClick = {
                        vm.app.metric("sms_app_handoff", outboxId)
                        openSmsApp(context, destination, msg.body)
                        handedOff = true
                    },
                )
                if (handedOff) {
                    SectionCard(stringResource(R.string.play_handoff_title), container = Color(0xFFFFF8E1)) {
                        Text(stringResource(R.string.play_handoff_body), style = MaterialTheme.typography.bodyLarge)
                        BigButton(stringResource(R.string.status_call), onClick = { dial(context, destination) }, icon = Icons.Filled.Call)
                    }
                }
            } else {
                BigButton(
                    stringResource(R.string.confirm_send), icon = Icons.AutoMirrored.Filled.Send, color = MerahBg,
                    onClick = {
                        if (hasSmsPermission(context)) vm.sendQueued(outboxId, context)
                        else launcher.launch(Manifest.permission.SEND_SMS)
                    },
                )
            }
            if (permissionAsked && !hasSmsPermission(context)) {
                SectionCard(stringResource(R.string.perm_denied_title), container = Color(0xFFFFEBEE)) {
                    Text(stringResource(R.string.perm_denied_body), style = MaterialTheme.typography.bodyLarge)
                    SecondaryButton(stringResource(R.string.perm_open_settings), onClick = {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    })
                    SecondaryButton(stringResource(R.string.perm_use_sms_app), onClick = {
                        vm.app.metric("sms_app_fallback", outboxId)
                        openSmsApp(context, msg.destination, msg.body)
                    }, icon = Icons.Filled.Sms)
                    Text(stringResource(R.string.perm_sms_app_note), style = MaterialTheme.typography.bodySmall)
                }
            }
            SecondaryButton(stringResource(R.string.result_script), onClick = { vm.go(Screen.Script(outboxId)) }, icon = Icons.Filled.RecordVoiceOver)
        }
    }
}

@Composable
fun StatusScreen(vm: AppViewModel, outboxId: Long) {
    val context = LocalContext.current
    val m by vm.app.db.outbox().observe(outboxId).collectAsState(initial = null)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val msg = m ?: return
    val model = msg.toModel()
    val r = decodeReferral(msg.body)
    val escalate = vm.app.outbox.needsEscalation(model, now)

    Column(Modifier.fillMaxSize()) {
        BundaTopBar(stringResource(R.string.status_title), onBack = { vm.resetTo(Screen.Home) })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            r?.let { Text(stringResource(R.string.status_for, it.pasien, Codebook.levelWord(it.tingkat)), style = MaterialTheme.typography.titleMedium) }

            SectionCard(stringResource(R.string.status_now)) {
                Text(stateLabel(msg.state, msg.ackStatus), style = MaterialTheme.typography.headlineSmall)
                when (msg.state) {
                    OutState.FAILED_RETRYING -> {
                        val secs = ((msg.nextAttemptAt ?: now) - now).coerceAtLeast(0) / 1000
                        Text(stringResource(R.string.status_retry_in, errorLabel(msg.lastError), secs), style = MaterialTheme.typography.bodyLarge)
                    }
                    OutState.SENDING -> Text(stringResource(R.string.status_sending_note), style = MaterialTheme.typography.bodyLarge)
                    OutState.SENT -> Text(stringResource(R.string.status_sent_note), style = MaterialTheme.typography.bodyLarge)
                    OutState.DELIVERED -> Text(stringResource(R.string.status_delivered_note), style = MaterialTheme.typography.bodyLarge)
                    OutState.ACKED -> Text(stringResource(R.string.status_acked_note, msg.ackStatus?.label ?: ""), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    OutState.EXPIRED -> Text(stringResource(R.string.status_expired_note), style = MaterialTheme.typography.bodyLarge)
                    else -> Unit
                }
                Text(stringResource(R.string.status_attempts, msg.attempts), style = MaterialTheme.typography.bodySmall)
            }

            SectionCard(stringResource(R.string.status_steps)) {
                Step(stringResource(R.string.step_queued), msg.queuedAt)
                Step(stringResource(R.string.step_sent), msg.sentAt)
                Step(stringResource(R.string.step_delivered), msg.deliveredAt)
                Step(stringResource(R.string.step_acked), msg.ackedAt)
                Text(stringResource(R.string.status_delivery_best_effort), style = MaterialTheme.typography.bodySmall)
            }

            if (msg.state == OutState.ACKED && msg.ackStatus != AckStatus.SIAP) {
                Disclaimer(stringResource(R.string.status_ack_not_ready))
            }
            if (escalate) {
                SectionCard(stringResource(R.string.status_escalate_title), container = Color(0xFFFFEBEE)) {
                    Text(stringResource(R.string.status_escalate_body), style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (msg.state == OutState.FAILED_RETRYING || msg.state == OutState.EXPIRED) {
                BigButton(stringResource(R.string.status_retry_now), onClick = { vm.retryNow(outboxId, context) }, icon = Icons.Filled.Refresh)
            }
            BigButton(
                stringResource(R.string.status_call), onClick = { dial(context, msg.destination) },
                icon = Icons.Filled.Call, color = if (escalate) MerahBg else Teal,
            )
            SecondaryButton(stringResource(R.string.result_script), onClick = { vm.go(Screen.Script(outboxId)) }, icon = Icons.Filled.RecordVoiceOver)
            SecondaryButton(stringResource(R.string.menu_stabilization), onClick = { vm.go(Screen.Stabilization(faseOf(r))) })
            SecondaryButton(stringResource(R.string.menu_faskes), onClick = { vm.go(Screen.Faskes) })
        }
    }
}

@Composable
private fun errorLabel(e: id.bundasiaga.outbox.SendError?): String = when (e) {
    id.bundasiaga.outbox.SendError.NO_SERVICE -> stringResource(R.string.err_no_service)
    id.bundasiaga.outbox.SendError.RADIO_OFF -> stringResource(R.string.err_radio_off)
    id.bundasiaga.outbox.SendError.PERMISSION_DENIED -> stringResource(R.string.err_permission)
    id.bundasiaga.outbox.SendError.TIMEOUT -> stringResource(R.string.err_timeout)
    else -> stringResource(R.string.err_generic)
}

@Composable
private fun Step(label: String, at: Long?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(
            if (at != null) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null, tint = if (at != null) HijauBg else Color.Gray, modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (at != null) FontWeight.Bold else FontWeight.Normal)
            Text(at?.let { formatTime(it) } ?: stringResource(R.string.step_not_yet), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ScriptScreen(vm: AppViewModel, outboxId: Long) {
    val context = LocalContext.current
    val m by vm.app.db.outbox().observe(outboxId).collectAsState(initial = null)
    val msg = m ?: return
    val r = decodeReferral(msg.body) ?: return
    val s = vm.app.settings
    val script = ManualScript.render(r, s.userName, s.callbackNumber)
    Column(Modifier.fillMaxSize()) {
        BundaTopBar(stringResource(R.string.script_title), onBack = { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.script_intro), style = MaterialTheme.typography.bodyLarge)
            SectionCard(null) {
                SelectionContainer { Text(script, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Normal) }
            }
            SecondaryButton(stringResource(R.string.script_copy), icon = Icons.Filled.ContentCopy, onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("BUNDA SIAGA", script))
            })
            SectionCard(stringResource(R.string.confirm_raw)) { RawSms(msg.body) }
        }
    }
}
