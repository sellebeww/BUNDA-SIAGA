package id.bundasiaga.app.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import id.bundasiaga.app.R
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySecureFlag()
        setContent {
            BundaTheme {
                LaunchedEffect(vm.settingsVersion) { applySecureFlag() }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
                        Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                            // Opening the encrypted database (Keystore + SQLCipher) and verifying any
                            // installed rules update take seconds on cheap phones: do both off the main
                            // thread before any screen touches them.
                            var dbReady by remember { mutableStateOf(false) }
                            var startupFailed by remember { mutableStateOf(false) }
                            var attempt by remember { mutableIntStateOf(0) }
                            LaunchedEffect(attempt) {
                                startupFailed = false
                                try {
                                    withContext(Dispatchers.IO) {
                                        vm.app.db.exams().count()
                                        vm.app.rules
                                    }
                                    dbReady = true
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    startupFailed = true
                                }
                            }
                            if (dbReady) {
                                vm.actionError?.let { Disclaimer(it) }
                                AppRoot(vm)
                            } else {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        if (startupFailed) {
                                            Text(stringResource(R.string.loading_error), style = MaterialTheme.typography.titleLarge)
                                            Text(stringResource(R.string.loading_error_detail))
                                            BigButton(stringResource(R.string.try_again), onClick = { attempt++ })
                                        } else {
                                            CircularProgressIndicator(color = Teal)
                                            Text(stringResource(R.string.loading_title), style = MaterialTheme.typography.titleMedium)
                                            Text(stringResource(R.string.loading_detail), style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Data pasien di layar: blokir tangkapan layar dan gambar mini di daftar aplikasi terbaru, sesuai pengaturan privasi. */
    private fun applySecureFlag() {
        if (vm.app.settings.secureScreens) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onResume() {
        super.onResume()
        vm.app.runner.kick()
    }
}

@Composable
fun AppRoot(vm: AppViewModel) {
    BackHandler(enabled = vm.stack.size > 1) {
        // Setelah mengirim, "kembali" menuju beranda (sama dengan panah di bilah atas), bukan ke layar hasil/konfirmasi.
        if (vm.current is Screen.Status && vm.stack.any { it is Screen.Result }) vm.resetTo(Screen.Home) else vm.back()
    }
    key(vm.current) {
        when (val s = vm.current) {
            Screen.Setup -> SetupScreen(vm)
            Screen.Home -> HomeScreen(vm)
            Screen.History -> HistoryScreen(vm)
            Screen.ExamIntro -> ExamIntroScreen(vm)
            is Screen.Question -> QuestionScreen(vm, s.index)
            Screen.Result -> ResultScreen(vm)
            is Screen.Confirm -> ConfirmScreen(vm, s.outboxId)
            is Screen.Status -> StatusScreen(vm, s.outboxId)
            is Screen.Script -> ScriptScreen(vm, s.outboxId)
            is Screen.Stabilization -> StabilizationScreen(vm, s.fase)
            Screen.Faskes -> FaskesScreen(vm)
            Screen.Inbox -> InboxScreen(vm)
            is Screen.CaseDetail -> CaseDetailScreen(vm, s.caseId)
            Screen.Summary -> SummaryScreen(vm)
            Screen.Debug -> DebugScreen(vm)
            Screen.Honesty -> HonestyScreen(vm)
            Screen.Rules -> RulesScreen(vm)
            Screen.CapacitySend -> CapacitySendScreen(vm)
            Screen.Recipients -> RecipientsScreen(vm)
        }
    }
}
