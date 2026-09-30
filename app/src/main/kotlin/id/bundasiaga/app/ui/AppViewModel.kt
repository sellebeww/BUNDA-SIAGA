package id.bundasiaga.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import id.bundasiaga.app.BundaApp
import id.bundasiaga.app.data.CaseEntity
import id.bundasiaga.app.data.ExamEntity
import id.bundasiaga.app.data.RecipientEntity
import id.bundasiaga.app.data.ReferralMapper
import id.bundasiaga.app.data.Role
import id.bundasiaga.app.data.RulesRepository
import id.bundasiaga.app.data.toEntity
import id.bundasiaga.app.work.RulesUpdater
import id.bundasiaga.app.work.SyncWorker
import id.bundasiaga.codec.Ack
import id.bundasiaga.codec.AckStatus
import id.bundasiaga.codec.Encoder
import id.bundasiaga.codec.Kapasitas
import id.bundasiaga.codec.StatusKapasitas
import id.bundasiaga.outbox.CapacityUpdate
import id.bundasiaga.outbox.OutKind
import id.bundasiaga.outbox.normalizePhone
import id.bundasiaga.rules.Fase
import id.bundasiaga.rules.GerakJanin
import id.bundasiaga.rules.Hasil
import id.bundasiaga.rules.Jawaban
import id.bundasiaga.rules.Pemeriksaan
import id.bundasiaga.rules.Pertanyaan
import id.bundasiaga.rules.RuleEngine
import id.bundasiaga.rules.SubPerdarahan
import id.bundasiaga.rules.Tanda
import id.bundasiaga.rules.TdStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom

sealed interface Screen {
    data object Setup : Screen
    data object Home : Screen
    data object ExamIntro : Screen
    data class Question(val index: Int) : Screen
    data object Result : Screen
    data class Confirm(val outboxId: Long) : Screen
    data class Status(val outboxId: Long) : Screen
    data class Script(val outboxId: Long) : Screen
    data class Stabilization(val fase: Fase) : Screen
    data object Faskes : Screen
    data object Inbox : Screen
    data class CaseDetail(val caseId: Long) : Screen
    data object Summary : Screen
    data object Debug : Screen
    data object Honesty : Screen
    data object Rules : Screen
    data object CapacitySend : Screen
    data object Recipients : Screen
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as BundaApp
    private val random = SecureRandom()

    val stack = mutableStateListOf(homeFor(app))
    val current: Screen get() = stack.last()

    /** Dinaikkan setiap pengaturan berubah agar composable membacanya ulang. */
    var settingsVersion by mutableIntStateOf(0)
        private set

    fun go(s: Screen) { stack.add(s) }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun resetTo(s: Screen) {
        stack.clear()
        stack.add(s)
    }

    fun settingsChanged() {
        app.refreshPolicy()
        settingsVersion++
    }

    // ---- pemeriksaan yang sedang berjalan ------------------------------------------

    var exam by mutableStateOf(Pemeriksaan())
        private set
    var patientInitials by mutableStateOf("")
    var patientCode by mutableStateOf("")
        private set
    var questions by mutableStateOf<List<Pertanyaan>>(emptyList())
        private set
    var hasil by mutableStateOf<Hasil?>(null)
        private set
    /**
     * Mesin yang diambil saat pemeriksaan dimulai, sehingga pembaruan aturan yang datang
     * di tengah pemeriksaan tidak mencampur pertanyaan satu versi dengan aturan versi lain.
     */
    var examEngine by mutableStateOf<RuleEngine?>(null)
        private set
    private var examId: Long? = null
    private var draftOutboxId: Long? = null

    fun startExam(fase: Fase) {
        examEngine = app.rules.engine(fase)
        exam = Pemeriksaan(
            fase = fase,
            tdStatus = if (app.settings.role == Role.KADER) TdStatus.TIDAK_ADA_ALAT else TdStatus.TERUKUR,
        )
        patientInitials = ""
        patientCode = ""
        hasil = null
        examId = null
        draftOutboxId = null
        app.metric("checklist_start", null, fase.name)
        go(Screen.ExamIntro)
    }

    /** [age] adalah usia kehamilan (minggu) untuk HAMIL, hari sejak melahirkan untuk NIFAS. */
    fun setIntro(initials: String, age: Int?, tdStatus: TdStatus, sys: Int?, dia: Int?) {
        val engine = examEngine ?: return
        val nifas = exam.fase == Fase.NIFAS
        patientInitials = initials
        exam = exam.copy(
            usiaKehamilanMinggu = if (nifas) null else age,
            hariNifas = if (nifas) age else null,
            tdStatus = tdStatus,
            tdSistolik = if (tdStatus == TdStatus.TERUKUR) sys else null,
            tdDiastolik = if (tdStatus == TdStatus.TERUKUR) dia else null,
        )
        questions = engine.questionsFor(exam.usiaKehamilanMinggu)
        // Pertanyaan yang tidak berlaku lagi (mis. usia berubah) tidak boleh menyimpan jawaban lama.
        if (questions.none { it.code == "GJN" }) exam = exam.copy(gerakJanin = GerakJanin.BELUM)
        stack.add(Screen.Question(0))
    }

    fun answer(index: Int, code: String, j: Jawaban, sub: SubPerdarahan? = null) {
        val t = Tanda.valueOf(code)
        exam = exam.copy(
            jawaban = exam.jawaban + (t to j),
            subPerdarahan = if (t == Tanda.PDR) (if (j == Jawaban.YA) sub else null) else exam.subPerdarahan,
        )
        next(index)
    }

    fun answerFetal(index: Int, g: GerakJanin) {
        exam = exam.copy(gerakJanin = g)
        next(index)
    }

    private fun next(index: Int) {
        if (index + 1 < questions.size) {
            go(Screen.Question(index + 1))
        } else {
            finishExam()
        }
    }

    private fun finishExam() {
        val h = (examEngine ?: return).evaluate(exam)
        hasil = h
        if (patientCode.isBlank()) {
            patientCode = ReferralMapper.patientCode(ReferralMapper.initials(patientInitials), app.settings.nextPatientNumber())
        }
        val snapshot = exam
        viewModelScope.launch(Dispatchers.IO) {
            examId = app.db.exams().insert(
                ExamEntity(
                    createdAt = app.clock.now(), patientCode = patientCode,
                    inputJson = ReferralMapper.toJson(snapshot), level = h.level.name,
                    firedRules = h.aturanTerpicu.joinToString(",") { it.id }, rulesVersion = h.versiAturan,
                ),
            )
            app.metric("result_shown", examId, h.level.name)
        }
        // Hasil menggantikan layar pertanyaan sehingga "kembali" dari hasil menuju beranda.
        stack.removeAll { it is Screen.Question || it is Screen.ExamIntro }
        go(Screen.Result)
    }

    /** Membuat (sekali saja) draf SMS untuk hasil saat ini dan mengembalikan id outbox-nya. */
    suspend fun ensureDraft(): Long = withContext(Dispatchers.IO) {
        draftOutboxId?.let { return@withContext it }
        val h = hasil ?: error("belum ada hasil")
        val msgId = Encoder.newMsgId { random.nextInt(it) }
        val r = ReferralMapper.build(exam, h.level, msgId, patientCode, app.settings.lokasi, app.clock.now())
        val body = Encoder.encode(r)
        val id = app.outbox.createDraft(OutKind.REFERRAL, msgId, app.settings.destinationNumber, body, examId)
        examId?.let { app.db.exams().linkOutbox(it, id) }
        draftOutboxId = id
        id
    }

    fun openConfirm() = viewModelScope.launch { go(Screen.Confirm(ensureDraft())) }

    fun openScript() = viewModelScope.launch { go(Screen.Script(ensureDraft())) }

    fun sendQueued(outboxId: Long, activity: android.content.Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Tujuan bisa saja baru diisi di Pengaturan setelah draf dibuat.
                val draft = app.db.outbox().get(outboxId)
                val dest = app.settings.destinationNumber
                if (draft != null && draft.destination.isBlank() && dest.isNotBlank()) {
                    app.db.outbox().update(draft.copy(destination = dest))
                }
                app.outbox.queue(outboxId)
            }
            app.metric("send_attempt", outboxId)
            app.runner.startForegroundRetry(activity)
            stack.removeAll { it is Screen.Confirm }
            go(Screen.Status(outboxId))
        }
    }

    fun retryNow(outboxId: Long, activity: android.content.Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { app.outbox.retryNow(outboxId) }
            app.runner.startForegroundRetry(activity)
        }
    }

    // ---- sisi penerima ------------------------------------------------------------

    fun sendAck(case: CaseEntity, status: AckStatus, activity: android.content.Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val now = app.clock.now()
                if (case.sender == SIMULATED_SENDER) {
                    // Tidak ada yang bisa dikirimi SMS balik: catat pilihan hanya di HP.
                    app.db.cases().update(case.copy(ackStatus = status, ackAt = now))
                    return@withContext
                }
                val body = Encoder.encode(Ack(case.msgId, status, ReferralMapper.waktu(now)))
                val id = app.outbox.enqueue(OutKind.ACK, case.msgId, case.sender, body, case.id)
                app.db.cases().update(case.copy(ackStatus = status, ackOutboxId = id, ackAt = now))
                app.metric("ack_sent", case.id, status.name)
            }
            app.runner.startForegroundRetry(activity)
            if (app.settings.role == Role.PUSKESMAS) SyncWorker.schedule(app)
        }
    }

    // ---- siaran kapasitas (PUSKESMAS) ---------------------------------------------

    fun addRecipient(number: String, label: String) {
        val key = normalizePhone(number)
        if (key.length < 5) return
        viewModelScope.launch(Dispatchers.IO) {
            app.db.recipients().insert(RecipientEntity(number = number.trim(), numberKey = key, label = label.trim(), addedAt = app.clock.now()))
        }
    }

    fun deleteRecipient(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { app.db.recipients().delete(id) }
    }

    /** Menambahkan setiap nomor yang pernah mengirim rujukan terbaca ke HP ini (mereka kader/bidan di wilayah itu). */
    fun importRecipientsFromCases() {
        viewModelScope.launch(Dispatchers.IO) {
            val now = app.clock.now()
            app.db.cases().senders().filter { it != SIMULATED_SENDER && normalizePhone(it).length >= 5 }.forEach {
                app.db.recipients().insert(RecipientEntity(number = it, numberKey = normalizePhone(it), label = "", addedAt = now))
            }
        }
    }

    /** Menyimpan status di HP ini dan mengantrekan satu SMS per penerima (masing-masing dicoba ulang seperti rujukan). */
    fun sendCapacity(faskesId: String, status: StatusKapasitas, beds: Int?, recipients: List<RecipientEntity>, activity: android.content.Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val now = app.clock.now()
                val body = Encoder.encode(Kapasitas(faskesId, status, beds, ReferralMapper.waktu(now)))
                app.db.capacity().upsert(CapacityUpdate(faskesId, status, beds, now, now, LOCAL_SENDER, true).toEntity())
                recipients.forEach { app.outbox.enqueue(OutKind.CAPACITY, faskesId, it.number, body) }
                app.metric("capacity_broadcast", null, "$faskesId ${status.name} n=${recipients.size}")
            }
            if (recipients.isNotEmpty()) app.runner.startForegroundRetry(activity)
        }
    }

    // ---- pembaruan aturan ---------------------------------------------------------

    var rulesBusy by mutableStateOf(false)
        private set
    var rulesCheck by mutableStateOf<Map<Fase, RulesUpdater.Outcome>?>(null)
        private set
    var rulesImport by mutableStateOf<RulesRepository.InstallResult?>(null)
        private set

    fun checkRulesNow() {
        if (rulesBusy) return
        rulesBusy = true
        viewModelScope.launch {
            rulesCheck = withContext(Dispatchers.IO) { RulesUpdater.checkServer(app) }
            rulesBusy = false
            settingsVersion++
        }
    }

    /** Paket yang dioper lewat kartu memori, Bluetooth, atau obrolan: diverifikasi sama persis dengan yang dari server. */
    fun importRulesFile(uri: android.net.Uri) {
        if (rulesBusy) return
        rulesBusy = true
        viewModelScope.launch {
            rulesImport = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@runCatching RulesRepository.InstallResult.Rejected("berkas tidak bisa dibaca")
                    if (bytes.size > 512 * 1024) RulesRepository.InstallResult.Rejected("berkas terlalu besar")
                    else app.rules.install(String(bytes, Charsets.UTF_8), "file")
                }.getOrElse { RulesRepository.InstallResult.Rejected(it.javaClass.simpleName) }
            }
            rulesImport?.let { app.metric("rules_import", null, it.javaClass.simpleName) }
            rulesBusy = false
            settingsVersion++
        }
    }

    /** Khusus demo: menyuntikkan rujukan simulasi berlabel jelas ke kotak masuk HP ini. */
    fun injectSimulatedReferral() {
        viewModelScope.launch(Dispatchers.IO) {
            val msgId = Encoder.newMsgId { random.nextInt(it) }
            val r = ReferralMapper.build(
                Pemeriksaan(
                    usiaKehamilanMinggu = 34, tdStatus = TdStatus.TERUKUR, tdSistolik = 168, tdDiastolik = 112,
                    jawaban = mapOf(Tanda.SKH to Jawaban.YA, Tanda.BKK to Jawaban.YA),
                ),
                id.bundasiaga.rules.Level.MERAH, msgId, "YL07", "SIM01", app.clock.now(),
            )
            id.bundasiaga.app.sms.SmsReceiver.handle(app, SIMULATED_SENDER, Encoder.encode(r))
        }
    }
}

const val SIMULATED_SENDER = "SIMULASI"

/** Pengirim yang dicatat untuk status kapasitas yang diatur di HP ini sendiri. */
const val LOCAL_SENDER = "LOKAL"

/** Layar awal per peran: gateway puskesmas berada di kotak masuknya, yang lain di beranda pemeriksaan. */
fun homeFor(app: BundaApp): Screen = when {
    !app.settings.isConfigured -> Screen.Setup
    // Build Play tidak bisa menerima SMS, jadi HP gateway yang dipindah ke sana harus memilih peran lain.
    app.settings.role == Role.PUSKESMAS && !id.bundasiaga.app.BuildConfig.SMS_DIRECT -> Screen.Setup
    app.settings.role == Role.PUSKESMAS -> Screen.Inbox
    else -> Screen.Home
}
