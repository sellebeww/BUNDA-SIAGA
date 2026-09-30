package id.bundasiaga.app.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.bundasiaga.app.R
import id.bundasiaga.app.data.RulesRepository.InstallResult
import id.bundasiaga.app.work.RulesUpdater.Outcome
import id.bundasiaga.rules.Fase

@Composable
private fun installText(r: InstallResult): String = when (r) {
    is InstallResult.Installed -> stringResource(R.string.rules_installed, r.version)
    is InstallResult.NotNewer -> stringResource(R.string.rules_not_newer)
    is InstallResult.Rejected -> stringResource(R.string.rules_rejected, r.reason)
    InstallResult.Disabled -> stringResource(R.string.rules_disabled_short)
}

@Composable
private fun outcomeText(o: Outcome): String = when (o) {
    Outcome.NoServer -> stringResource(R.string.rules_no_server)
    Outcome.NotPublished -> stringResource(R.string.rules_not_published)
    is Outcome.Failed -> stringResource(R.string.rules_check_failed, o.detail)
    is Outcome.Done -> installText(o.result)
}

@Composable
fun RulesScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val repo = vm.app.rules
    val s = vm.app.settings
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importRulesFile(uri)
    }
    val meta = context.getSharedPreferences("rules_meta", Context.MODE_PRIVATE)

    Column(Modifier.fillMaxSize()) {
        BundaTopBar(stringResource(R.string.menu_rules), onBack = { vm.back() })
        key(vm.settingsVersion) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.rules_intro), style = MaterialTheme.typography.bodyLarge)
                for (fase in Fase.entries) {
                    val cfg = repo.engine(fase).config
                    val src = repo.source(fase)
                    SectionCard(stringResource(if (fase == Fase.HAMIL) R.string.fase_hamil else R.string.fase_nifas)) {
                        KeyValue(stringResource(R.string.rules_version), cfg.version)
                        KeyValue(stringResource(R.string.rules_seq), cfg.seq.toString())
                        KeyValue(stringResource(R.string.rules_status), cfg.status)
                        KeyValue(
                            stringResource(R.string.rules_source),
                            if (!src.fromUpdate) stringResource(R.string.rules_source_apk)
                            else stringResource(
                                R.string.rules_source_update, src.origin ?: "?", src.keyId ?: "?",
                                src.installedAt?.let { formatTime(it) } ?: "?",
                            ),
                        )
                        KeyValue(stringResource(R.string.rules_count), cfg.rules.size.toString())
                        if (cfg.status.startsWith("DRAFT")) Disclaimer(stringResource(R.string.rules_draft_warning))
                    }
                }

                if (!repo.updatesEnabled) {
                    Disclaimer(stringResource(R.string.rules_disabled))
                } else {
                    Text(stringResource(R.string.rules_safety_note), style = MaterialTheme.typography.bodyMedium)
                }
                SectionCard(stringResource(R.string.rules_server)) {
                    Text(s.serverUrl.ifBlank { stringResource(R.string.rules_server_none) }, style = MaterialTheme.typography.bodyLarge)
                    meta.getLong("lastCheckAt", 0L).takeIf { it > 0 }?.let {
                        Text(stringResource(R.string.rules_last_check, formatTime(it)), style = MaterialTheme.typography.bodySmall)
                    }
                    BigButton(
                        stringResource(R.string.rules_check_now), icon = Icons.Filled.CloudDownload,
                        enabled = !vm.rulesBusy && repo.updatesEnabled && s.serverUrl.isNotBlank(),
                        onClick = { vm.checkRulesNow() },
                    )
                    vm.rulesCheck?.forEach { (fase, o) ->
                        Text("${fase.name}: ${outcomeText(o)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
                SectionCard(stringResource(R.string.rules_file)) {
                    Text(stringResource(R.string.rules_file_note), style = MaterialTheme.typography.bodyMedium)
                    SecondaryButton(
                        stringResource(R.string.rules_import), icon = Icons.Filled.FileOpen,
                        onClick = { if (!vm.rulesBusy && repo.updatesEnabled) picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    )
                    vm.rulesImport?.let { Text(installText(it), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
                }
                if (vm.rulesBusy) CircularProgressIndicator(color = Teal)
            }
        }
    }
}
