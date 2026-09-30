package id.bundasiaga.app.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
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
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        key(vm.settingsVersion) {
                            val s = vm.app.settings
                            SimulationBanner(s.simulationMode, s.simulateNoService)
                        }
                        // Membuka database terenkripsi (Keystore + SQLCipher) dan memeriksa pembaruan aturan
                        // yang terpasang memakan waktu beberapa detik di HP murah: lakukan keduanya di luar
                        // thread utama sebelum layar mana pun menyentuhnya.
                        var dbReady by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) {
                            withContext(Dispatchers.IO) {
                                vm.app.db
                                vm.app.rules
                            }
                            dbReady = true
                        }
                        if (dbReady) {
                            AppRoot(vm)
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Teal)
                            }
                        }
                    }
                }
            }
        }
    }

    /** Data pasien di layar: blokir tangkapan layar dan gambar mini di daftar aplikasi terbaru, kecuali dimatikan untuk demo. */
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
    when (val s = vm.current) {
        Screen.Setup -> SetupScreen(vm)
        Screen.Home -> HomeScreen(vm)
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
