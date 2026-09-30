package id.bundasiaga.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bed
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoFood
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PregnantWoman
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Sick
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Woman
import androidx.compose.material.icons.filled.SentimentVeryDissatisfied
import androidx.compose.material.icons.filled.BabyChangingStation
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.Water
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import id.bundasiaga.app.BuildConfig
import id.bundasiaga.app.R
import id.bundasiaga.app.data.ReferralMapper
import id.bundasiaga.app.data.Role
import id.bundasiaga.rules.Fase
import id.bundasiaga.rules.GerakJanin
import id.bundasiaga.rules.Jawaban
import id.bundasiaga.rules.Level
import id.bundasiaga.rules.SubPerdarahan
import id.bundasiaga.rules.TdStatus

@Composable
fun HomeScreen(vm: AppViewModel) {
    val s = vm.app.settings
    val referrals by vm.app.db.outbox().observeReferrals().collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize()) {
        BundaTopBar(stringResource(R.string.app_name), onBack = null, actions = { OverflowMenu(vm) })
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(72.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.home_hello, s.userName), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(
                            when (s.role) {
                                Role.BIDAN -> R.string.role_bidan
                                Role.PUSKESMAS -> R.string.role_puskesmas
                                else -> R.string.role_kader
                            },
                        ) + " · " + s.lokasi,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            BigButton(
                stringResource(R.string.home_start_exam), onClick = { vm.startExam(Fase.HAMIL) },
                icon = Icons.Filled.PregnantWoman, color = Coral, modifier = Modifier.heightIn(min = 96.dp),
            )
            BigButton(
                stringResource(R.string.home_start_nifas), onClick = { vm.startExam(Fase.NIFAS) },
                icon = Icons.Filled.BabyChangingStation, color = Teal, modifier = Modifier.heightIn(min = 80.dp),
            )
            if (s.role == Role.BIDAN && BuildConfig.SMS_DIRECT) {
                SecondaryButton(stringResource(R.string.home_inbox), onClick = { vm.go(Screen.Inbox) }, icon = Icons.Filled.Inbox)
            }
            if (s.destinationNumber.isBlank()) Disclaimer(stringResource(R.string.home_no_dest))

            Text(stringResource(R.string.home_recent), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (referrals.isEmpty()) Text(stringResource(R.string.home_recent_empty), style = MaterialTheme.typography.bodyMedium)
            referrals.forEach { m ->
                val r = decodeReferral(m.body)
                OutlinedCard(onClick = { vm.go(Screen.Status(m.id)) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        r?.let { LevelChip(levelOf(it.tingkat)) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${r?.pasien ?: "-"} · ${formatTime(m.createdAt)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(stateLabel(m.state, m.ackStatus), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Disclaimer(vm.app.rules.engine(Fase.HAMIL).config.disclaimer)
        }
    }
}

@Composable
fun OverflowMenu(vm: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(56.dp)) {
        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        buildList {
            add(R.string.menu_faskes to Screen.Faskes)
            if (vm.app.settings.role == Role.PUSKESMAS && BuildConfig.SMS_DIRECT) add(R.string.menu_capacity to Screen.CapacitySend)
            add(R.string.menu_stabilization to Screen.Stabilization(Fase.HAMIL))
            add(R.string.menu_rules to Screen.Rules)
            add(R.string.menu_settings to Screen.Setup)
            add(R.string.menu_debug to Screen.Debug)
            add(R.string.menu_honesty to Screen.Honesty)
        }.forEach { (label, screen) ->
            DropdownMenuItem(
                text = { Text(stringResource(label), style = MaterialTheme.typography.bodyLarge) },
                onClick = { open = false; vm.go(screen) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
fun ExamIntroScreen(vm: AppViewModel) {
    val e = vm.exam
    val nifas = e.fase == Fase.NIFAS
    var initials by rememberSaveable { mutableStateOf(vm.patientInitials) }
    var usia by rememberSaveable { mutableStateOf((if (nifas) e.hariNifas else e.usiaKehamilanMinggu)?.toString() ?: "") }
    var usiaUnknown by rememberSaveable { mutableStateOf(false) }
    var td by rememberSaveable { mutableStateOf(e.tdStatus) }
    var sys by rememberSaveable { mutableStateOf(e.tdSistolik?.toString() ?: "") }
    var dia by rememberSaveable { mutableStateOf(e.tdDiastolik?.toString() ?: "") }

    val usiaInt = usia.toIntOrNull()
    val usiaOk = usiaUnknown || (usiaInt != null && usiaInt in (if (nifas) 0..ReferralMapper.MAX_HARI_NIFAS else 1..45))
    val bpOk = td != TdStatus.TERUKUR || ReferralMapper.isBpPlausible(sys.toIntOrNull(), dia.toIntOrNull())
    val initialsOk = ReferralMapper.initials(initials).length >= 2

    Column(Modifier.fillMaxSize()) {
        BundaTopBar(stringResource(if (nifas) R.string.intro_title_nifas else R.string.intro_title), onBack = { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = initials, onValueChange = { initials = it.take(30) },
                label = { Text(stringResource(R.string.intro_initials)) },
                supportingText = { Text(stringResource(R.string.intro_initials_hint)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge,
            )
            if (initials.isNotBlank()) {
                Text(stringResource(R.string.intro_initials_preview, ReferralMapper.initials(initials)), style = MaterialTheme.typography.bodyMedium)
            }

            Text(stringResource(if (nifas) R.string.intro_hari else R.string.intro_usia), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = usia, onValueChange = { usia = it.filter(Char::isDigit).take(2); usiaUnknown = false },
                label = { Text(stringResource(if (nifas) R.string.intro_hari_label else R.string.intro_usia_weeks)) },
                supportingText = if (nifas) ({ Text(stringResource(R.string.intro_hari_hint, ReferralMapper.MAX_HARI_NIFAS)) }) else null,
                singleLine = true, enabled = !usiaUnknown,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = !usiaOk && usia.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge,
            )
            if (nifas && !usiaOk && usiaInt != null) {
                Text(stringResource(R.string.intro_hari_too_late, ReferralMapper.MAX_HARI_NIFAS), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            ChoiceRow(stringResource(if (nifas) R.string.intro_hari_unknown else R.string.intro_usia_unknown), usiaUnknown) { usiaUnknown = !usiaUnknown; if (usiaUnknown) usia = "" }

            Text(stringResource(R.string.intro_td), style = MaterialTheme.typography.titleMedium)
            ChoiceRow(stringResource(R.string.intro_td_measured), td == TdStatus.TERUKUR) { td = TdStatus.TERUKUR }
            if (td == TdStatus.TERUKUR) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = sys, onValueChange = { sys = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.intro_sys)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodyLarge,
                    )
                    Text("/", style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(
                        value = dia, onValueChange = { dia = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.intro_dia)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (!bpOk && sys.isNotEmpty() && dia.isNotEmpty()) {
                    Text(stringResource(R.string.intro_td_implausible), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
            ChoiceRow(stringResource(R.string.intro_td_no_device), td == TdStatus.TIDAK_ADA_ALAT) { td = TdStatus.TIDAK_ADA_ALAT }
            ChoiceRow(stringResource(R.string.intro_td_not_yet), td == TdStatus.BELUM_DIPERIKSA) { td = TdStatus.BELUM_DIPERIKSA }

            BigButton(
                stringResource(R.string.intro_next), enabled = usiaOk && bpOk && initialsOk,
                onClick = {
                    vm.setIntro(initials, if (usiaUnknown) null else usiaInt, td, sys.toIntOrNull(), dia.toIntOrNull())
                },
            )
            if (!(usiaOk && bpOk && initialsOk)) {
                Text(stringResource(if (nifas) R.string.intro_fill_all_nifas else R.string.intro_fill_all), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ChoiceRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick)
            .semantics { role = SemanticsRole.RadioButton },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

fun questionIcon(key: String): ImageVector = when (key) {
    "bolt" -> Icons.Filled.Bolt
    "bed" -> Icons.Filled.Bed
    "water_drop" -> Icons.Filled.WaterDrop
    "head" -> Icons.Filled.Face
    "eye" -> Icons.Filled.Visibility
    "lungs" -> Icons.Filled.Air
    "belly" -> Icons.Filled.PregnantWoman
    "water" -> Icons.Filled.Water
    "baby" -> Icons.Filled.ChildCare
    "hand" -> Icons.Filled.PanTool
    "stomach" -> Icons.Filled.Sick
    "thermometer" -> Icons.Filled.Thermostat
    "ac_unit" -> Icons.Filled.AcUnit
    "sick" -> Icons.Filled.Sick
    "no_food" -> Icons.Filled.NoFood
    "breast" -> Icons.Filled.Woman
    "leg" -> Icons.AutoMirrored.Filled.DirectionsWalk
    "mood" -> Icons.Filled.SentimentVeryDissatisfied
    "warning" -> Icons.Filled.Warning
    else -> Icons.AutoMirrored.Filled.Help
}

@Composable
fun QuestionScreen(vm: AppViewModel, index: Int) {
    val q = vm.questions.getOrNull(index) ?: return
    val total = vm.questions.size
    var bleedingSub by remember(index) { mutableStateOf(false) }
    val critical = vm.examEngine?.isCritical(q, vm.exam.usiaKehamilanMinggu) ?: false

    Column(Modifier.fillMaxSize()) {
        BundaTopBar(stringResource(R.string.question_progress, index + 1, total), onBack = { vm.back() })
        LinearProgressIndicator(progress = { (index + 1f) / total }, modifier = Modifier.fillMaxWidth().height(8.dp), color = Coral)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(questionIcon(q.icon), contentDescription = null, tint = Teal, modifier = Modifier.size(88.dp).align(Alignment.CenterHorizontally))
            Text(q.prompt, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            if (q.hint.isNotBlank()) Text(q.hint, style = MaterialTheme.typography.bodyLarge)
            if (critical) Text(stringResource(R.string.question_critical), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))

            if (q.code == id.bundasiaga.rules.GJN) {
                AnswerButton(stringResource(R.string.fetal_normal), Icons.Filled.Check, HijauBg) { vm.answerFetal(index, GerakJanin.NORMAL) }
                AnswerButton(stringResource(R.string.fetal_less), Icons.AutoMirrored.Filled.Help, KuningBg, Ink) { vm.answerFetal(index, GerakJanin.BERKURANG) }
                AnswerButton(stringResource(R.string.fetal_none), Icons.Filled.Close, MerahBg) { vm.answerFetal(index, GerakJanin.TIDAK_ADA) }
                AnswerButton(stringResource(R.string.answer_not_checked), Icons.AutoMirrored.Filled.Help, Color(0xFF546E7A)) { vm.answerFetal(index, GerakJanin.BELUM) }
            } else if (bleedingSub) {
                Text(stringResource(R.string.bleeding_how_much), style = MaterialTheme.typography.titleLarge)
                AnswerButton(stringResource(R.string.bleeding_spot), null, Teal) { vm.answer(index, q.code, Jawaban.YA, SubPerdarahan.BERCAK) }
                AnswerButton(stringResource(R.string.bleeding_medium), null, Teal) { vm.answer(index, q.code, Jawaban.YA, SubPerdarahan.SEDANG) }
                AnswerButton(stringResource(R.string.bleeding_heavy), null, MerahBg) { vm.answer(index, q.code, Jawaban.YA, SubPerdarahan.BANYAK) }
                AnswerButton(stringResource(R.string.bleeding_unknown), null, Color(0xFF546E7A)) { vm.answer(index, q.code, Jawaban.YA, null) }
            } else {
                AnswerButton(stringResource(R.string.answer_yes), Icons.Filled.Check, MerahBg) {
                    if (q.bleedingAmount) bleedingSub = true else vm.answer(index, q.code, Jawaban.YA)
                }
                AnswerButton(stringResource(R.string.answer_no), Icons.Filled.Close, HijauBg) { vm.answer(index, q.code, Jawaban.TIDAK) }
                AnswerButton(stringResource(R.string.answer_not_checked), Icons.AutoMirrored.Filled.Help, Color(0xFF546E7A)) {
                    vm.answer(index, q.code, Jawaban.BELUM_DIPERIKSA)
                }
            }
        }
    }
}

@Composable
private fun AnswerButton(text: String, icon: ImageVector?, color: Color, fg: Color = Color.White, onClick: () -> Unit) {
    BigButton(text, onClick = onClick, icon = icon, color = color, contentColor = fg, modifier = Modifier.heightIn(min = 72.dp))
}

@Composable
fun ResultScreen(vm: AppViewModel) {
    val h = vm.hasil ?: return
    val cfg = vm.examEngine?.config ?: return
    Column(Modifier.fillMaxSize()) {
        BundaTopBar(stringResource(R.string.result_title), onBack = { vm.resetTo(Screen.Home) })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.result_signal_label), style = MaterialTheme.typography.bodyMedium)
            LevelBanner(h.level)
            Text(stringResource(R.string.result_patient, vm.patientCode), style = MaterialTheme.typography.titleMedium)
            if (cfg.fase == Fase.NIFAS) MockLabel(stringResource(R.string.fase_nifas_chip))

            SectionCard(stringResource(R.string.result_why)) {
                if (h.aturanTerpicu.isEmpty()) Text(stringResource(R.string.result_no_rules), style = MaterialTheme.typography.bodyLarge)
                h.aturanTerpicu.forEach { hit ->
                    Column {
                        Text(hit.alasan, style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.result_rule_id, hit.id, hit.level.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (h.dataBelumLengkap.isNotEmpty()) {
                SectionCard(stringResource(R.string.result_incomplete), container = Color(0xFFFFF8E1)) {
                    h.dataBelumLengkap.forEach { Bullet(it) }
                }
            }
            SectionCard(stringResource(R.string.result_advice)) { h.saran.forEach { Bullet(it) } }

            if (h.level != Level.HIJAU) {
                BigButton(stringResource(R.string.result_send), onClick = { vm.openConfirm() }, icon = Icons.AutoMirrored.Filled.Send, color = if (h.level == Level.MERAH) MerahBg else Teal)
            } else {
                SecondaryButton(stringResource(R.string.result_send_anyway), onClick = { vm.openConfirm() }, icon = Icons.AutoMirrored.Filled.Send)
            }
            if (h.level == Level.MERAH) {
                SecondaryButton(stringResource(R.string.menu_stabilization), onClick = { vm.go(Screen.Stabilization(cfg.fase)) }, icon = Icons.Filled.HealthAndSafety)
            }
            SecondaryButton(stringResource(R.string.result_script), onClick = { vm.openScript() }, icon = Icons.Filled.RecordVoiceOver)
            SecondaryButton(stringResource(R.string.menu_faskes), onClick = { vm.go(Screen.Faskes) }, icon = Icons.Filled.LocalHospital)

            SectionCard(stringResource(R.string.result_data_used)) { h.dataDipakai.forEach { (k, v) -> KeyValue(k, v) } }
            Disclaimer(cfg.disclaimer)
            Text(stringResource(R.string.result_version, h.versiAturan, h.statusAturan), style = MaterialTheme.typography.bodySmall)
            SecondaryButton(stringResource(R.string.result_done), onClick = { vm.resetTo(Screen.Home) }, icon = Icons.Filled.Home)
        }
    }
}

@Composable
fun StabilizationScreen(vm: AppViewModel, initialFase: Fase) {
    var fase by rememberSaveable { mutableStateOf(initialFase) }
    val st = vm.app.rules.engine(fase).config.stabilization
    Column(Modifier.fillMaxSize()) {
        BundaTopBar(stringResource(R.string.menu_stabilization), onBack = { vm.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = fase == Fase.HAMIL, onClick = { fase = Fase.HAMIL }, label = { Text(stringResource(R.string.fase_hamil), style = MaterialTheme.typography.bodyLarge) }, modifier = Modifier.heightIn(min = 48.dp))
                FilterChip(selected = fase == Fase.NIFAS, onClick = { fase = Fase.NIFAS }, label = { Text(stringResource(R.string.fase_nifas), style = MaterialTheme.typography.bodyLarge) }, modifier = Modifier.heightIn(min = 48.dp))
            }
            Text(st.title, style = MaterialTheme.typography.headlineSmall)
            st.steps.forEachIndexed { i, step ->
                SectionCard(null) {
                    Row {
                        Text("${i + 1}.", style = MaterialTheme.typography.titleLarge, color = Teal, modifier = Modifier.width(36.dp))
                        Text(step, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (vm.app.settings.role != Role.KADER) Disclaimer(st.bidanReminder)
            Text(stringResource(R.string.needs_review_label), style = MaterialTheme.typography.bodySmall)
        }
    }
}
