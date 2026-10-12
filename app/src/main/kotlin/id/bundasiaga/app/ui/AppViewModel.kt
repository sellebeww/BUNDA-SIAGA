package id.bundasiaga.app.ui

import android.app.Application
import androidx.room.withTransaction
import id.bundasiaga.app.data.ExamSessionEntity
import id.bundasiaga.app.data.ExamSnapshot
import id.bundasiaga.rules.Level
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
import id.bundasiaga.outbox.OutMessage
import id.bundasiaga.app.data.InputValidation
import id.bundasiaga.outbox.OutState
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom

sealed interface Screen {
    data object Setup : Screen
    data object History : Screen
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

    fun go(s: Screen) { if (current != s) stack.add(s) }

    fun back(): Boolean {
        if (examBusy) return true
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
    var resultCreatedAt by mutableStateOf<Long?>(null)
        private set
    private var examId: Long? = null
    var examBusy by mutableStateOf(false)
        private set
    var examError by mutableStateOf<String?>(null)
        private set
    private val draftMutex = Mutex()
    var draftBusy by mutableStateOf(false)
        private set
    var draftFailed by mutableStateOf(false)
        private set
    private var draftOutboxId: Long? = null

    fun startExam(fase: Fase) {
        if (draftBusy || examBusy) return
        draftFailed = false
        examError = null
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
        if (examBusy) return
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
        saveProgress(0)
    }

    fun answer(index: Int, code: String, j: Jawaban, sub: SubPerdarahan? = null) {
        if (examBusy || current != Screen.Question(index)) return
        val t = Tanda.valueOf(code)
        exam = exam.copy(
            jawaban = exam.jawaban + (t to j),
            subPerdarahan = if (t == Tanda.PDR) (if (j == Jawaban.YA) sub else null) else exam.subPerdarahan,
        )
        next(index)
    }

    fun answerFetal(index: Int, g: GerakJanin) {
        if (examBusy || current != Screen.Question(index)) return
        exam = exam.copy(gerakJanin = g)
        next(index)
    }

    private fun next(index: Int) {
        if (index + 1 < questions.size) saveProgress(index + 1) else finishExam()
    }

    private fun saveProgress(index: Int) {
        if (examBusy) return
        examBusy = true
        examError = null
        val session = ExamSessionEntity(inputJson = ExamSnapshot.encode(exam),
            initials = ReferralMapper.initials(patientInitials), questionIndex = index,
            rulesVersion = "${examEngine!!.config.version}:${examEngine!!.config.seq}", updatedAt = app.clock.now())
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { app.db.sessions().save(session) }
                go(Screen.Question(index))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: Exception) {
                examError = "Jawaban belum tersimpan. Periksa ruang penyimpanan lalu pilih jawaban kembali."
            } finally { examBusy = false }
        }
    }

    fun resumeExam(session: ExamSessionEntity) {
        if (examBusy || draftBusy) return
        try {
            val input = ExamSnapshot.decode(session.inputJson)
            val engine = app.rules.engine(input.fase)
            check("${engine.config.version}:${engine.config.seq}" == session.rulesVersion) {
                "Aturan telah diperbarui. Mulai pemeriksaan baru agar semua jawaban memakai aturan yang sama."
            }
            exam = input
            examEngine = engine
            patientInitials = session.initials
            patientCode = ""
            hasil = null
            examId = null
            draftOutboxId = null
            questions = engine.questionsFor(input.usiaKehamilanMinggu)
            require(session.questionIndex in questions.indices)
            examError = null
            resetTo(Screen.Home)
            go(Screen.ExamIntro)
            // Rebuild the back stack so previously answered questions remain editable.
            (0..session.questionIndex).forEach { go(Screen.Question(it)) }
        } catch (_: Exception) {
            examError = "Pemeriksaan tersimpan tidak cocok dengan aturan aktif atau tidak dapat dibaca. Mulai pemeriksaan baru."
        }
    }

    private fun finishExam() {
        if (examBusy) return
        val h = (examEngine ?: return).evaluate(exam)
        examBusy = true
        examError = null
        if (patientCode.isBlank()) {
            patientCode = ReferralMapper.patientCode(ReferralMapper.initials(patientInitials), app.settings.nextPatientNumber())
        }
        val record = ExamEntity(createdAt = app.clock.now(), patientCode = patientCode,
            inputJson = ExamSnapshot.encode(exam), level = h.level.name,
            firedRules = h.aturanTerpicu.joinToString(",") { it.id }, rulesVersion = h.versiAturan,
            resultJson = ExamSnapshot.encodeResult(h))
        viewModelScope.launch {
            try {
                examId = withContext(Dispatchers.IO) {
                    app.db.withTransaction {
                        val id = app.db.exams().insert(record)
                        app.db.sessions().clear()
                        id
                    }
                }
                resultCreatedAt = record.createdAt
                hasil = h
                app.metric("result_shown", examId, h.level.name)
                stack.removeAll { it is Screen.Question || it is Screen.ExamIntro }
                go(Screen.Result)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: Exception) {
                examError = "Hasil belum tersimpan. Periksa ruang penyimpanan lalu pilih jawaban terakhir kembali."
            } finally { examBusy = false }
        }
    }

    fun openExam(record: ExamEntity) {
        if (draftBusy || examBusy) return
        try {
            val input = ExamSnapshot.decode(record.inputJson)
            val saved = record.resultJson?.let(ExamSnapshot::decodeResult) ?: Hasil(
                Level.valueOf(record.level), emptyList(), emptyList(),
                listOf("Aturan terpicu" to record.firedRules.ifBlank { "Tidak ada" }), emptyList(),
                listOf("Hasil dari versi aplikasi sebelumnya. Rincian saran tidak tersimpan; lakukan pemeriksaan baru untuk kondisi terkini."),
                record.rulesVersion, "Arsip",
            )
            exam = input
            examEngine = app.rules.engine(input.fase)
            hasil = saved
            resultCreatedAt = record.createdAt
            patientCode = record.patientCode
            examId = record.id
            draftOutboxId = record.outboxId
            draftFailed = false
            examError = null
            go(Screen.Result)
        } catch (_: Exception) { examError = "Arsip pemeriksaan tidak dapat dibaca." }
    }

    /** Membuat (sekali saja) draf SMS untuk hasil saat ini dan mengembalikan id outbox-nya. */
    suspend fun ensureDraft(): Long = draftMutex.withLock {
        withContext(Dispatchers.IO) {
            draftOutboxId?.let { return@withContext it }
            checkNotNull(examId) { "hasil belum tersimpan" }
            val h = hasil ?: error("belum ada hasil")
            val msgId = Encoder.newMsgId { random.nextInt(it) }
            val r = ReferralMapper.build(exam, h.level, msgId, patientCode, app.settings.lokasi, resultCreatedAt ?: app.clock.now())
            val body = Encoder.encode(r)
            val savedId = app.db.withTransaction {
                // Insert new rows directly in this transaction; never acquire the outbox
                // engine mutex while holding a Room transaction (the runner locks in reverse order).
                val id = app.db.outbox().insert(OutMessage(kind = OutKind.REFERRAL, msgId = msgId,
                    destination = app.settings.destinationNumber, body = body, refId = examId,
                    state = OutState.DRAFT, createdAt = app.clock.now()).toEntity())
                app.db.exams().linkOutbox(examId!!, id)
                id
            }
            draftOutboxId = savedId
            savedId
        }
    }

    fun openConfirm() = openDraft { Screen.Confirm(it) }

    fun openScript() = openDraft { Screen.Script(it) }

    private fun openDraft(screen: (Long) -> Screen) {
        if (draftBusy) return
        draftBusy = true
        draftFailed = false
        viewModelScope.launch {
            try {
                val id = ensureDraft()
                if (current == Screen.Result) {
                    val state = withContext(Dispatchers.IO) { app.db.outbox().get(id)?.state }
                    go(if (screen(id) is Screen.Confirm && state != OutState.DRAFT) Screen.Status(id) else screen(id))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                draftFailed = true
            } finally {
                draftBusy = false
            }
        }
    }

    var actionBusy by mutableStateOf(false)
        private set
    var actionError by mutableStateOf<String?>(null)
        private set

    private fun action(block: suspend () -> Unit) {
        if (actionBusy) return
        actionBusy = true
        actionError = null
        viewModelScope.launch {
            try { block() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { actionError = "Perubahan belum berhasil. Periksa riwayat pesan sebelum mencoba kembali." }
            finally { actionBusy = false }
        }
    }

    fun sendQueued(outboxId: Long, activity: android.content.Context) = action {
        withContext(Dispatchers.IO) {
            checkNotNull(app.outbox.queue(outboxId, app.settings.destinationNumber))
        }
        app.metric("send_attempt", outboxId)
        app.runner.startForegroundRetry(activity)
        stack.removeAll { it is Screen.Confirm }
        go(Screen.Status(outboxId))
    }

    fun retryNow(outboxId: Long, activity: android.content.Context) = action {
        withContext(Dispatchers.IO) { app.outbox.retryNow(outboxId) }
        app.runner.startForegroundRetry(activity)
    }

    // ---- sisi penerima ------------------------------------------------------------

    fun sendAck(case: CaseEntity, status: AckStatus, activity: android.content.Context) = action {
        require(case.sender != SIMULATED_SENDER)
        withContext(Dispatchers.IO) {
            app.db.withTransaction {
                val latest = checkNotNull(app.db.cases().get(case.id))
                // Repeated taps must not enqueue another identical reply.
                if (latest.ackStatus == status && latest.ackOutboxId != null) {
                    val existing = app.db.outbox().get(latest.ackOutboxId)
                    if (existing != null && existing.state != OutState.EXPIRED) return@withTransaction
                }
                val now = app.clock.now()
                val body = Encoder.encode(Ack(case.msgId, status, ReferralMapper.waktu(now)))
                val id = app.db.outbox().insert(OutMessage(kind = OutKind.ACK, msgId = case.msgId,
                    destination = case.sender, body = body, refId = case.id, state = OutState.QUEUED,
                    createdAt = now, queuedAt = now, nextAttemptAt = now,
                    expiresAt = now + app.currentPolicy().expireAfterMs).toEntity())
                app.db.cases().recordAck(case.id, status, now, id)
            }
            app.metric("ack_queued", case.id, status.name)
        }
        app.runner.startForegroundRetry(activity)
        if (app.settings.role == Role.PUSKESMAS) SyncWorker.schedule(app)
    }

    // ---- siaran kapasitas (PUSKESMAS) ---------------------------------------------

    fun addRecipient(number: String, label: String) {
        val key = normalizePhone(number)
        if (!InputValidation.phone(number)) return
        action { withContext(Dispatchers.IO) {
            app.db.recipients().insert(RecipientEntity(number = number.trim(), numberKey = key, label = label.trim(), addedAt = app.clock.now()))
        } }
    }

    fun deleteRecipient(id: Long) {
        action { withContext(Dispatchers.IO) { app.db.recipients().delete(id) } }
    }

    /** Menambahkan setiap nomor yang pernah mengirim rujukan terbaca ke HP ini (mereka kader/bidan di wilayah itu). */
    fun importRecipientsFromCases() {
        action { withContext(Dispatchers.IO) {
            val now = app.clock.now()
            app.db.withTransaction {
                app.db.cases().senders().filter { it != SIMULATED_SENDER && InputValidation.phone(it) }.forEach {
                    app.db.recipients().insert(RecipientEntity(number = it, numberKey = normalizePhone(it), label = "", addedAt = now))
                }
            }
        } }
    }

    var capacityBusy by mutableStateOf(false)
        private set
    var capacityFailed by mutableStateOf(false)
        private set

    /** Reports success only after all recipient messages have been persisted. */
    fun sendCapacity(faskesId: String, status: StatusKapasitas, beds: Int?, recipients: List<RecipientEntity>, activity: android.content.Context, onQueued: () -> Unit) {
        if (capacityBusy) return
        capacityBusy = true
        capacityFailed = false
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val now = app.clock.now()
                    val body = Encoder.encode(Kapasitas(faskesId, status, beds, ReferralMapper.waktu(now)))
                    app.db.withTransaction {
                        app.db.capacity().upsert(CapacityUpdate(faskesId, status, beds, now, now, LOCAL_SENDER, true).toEntity())
                        recipients.forEach { recipient ->
                            app.db.outbox().insert(OutMessage(kind = OutKind.CAPACITY, msgId = faskesId,
                                destination = recipient.number, body = body, state = OutState.QUEUED,
                                createdAt = now, queuedAt = now, nextAttemptAt = now,
                                expiresAt = now + app.currentPolicy().expireAfterMs).toEntity())
                        }
                    }
                    app.metric("capacity_broadcast", null, "$faskesId ${status.name} n=${recipients.size}")
                }
                if (recipients.isNotEmpty()) app.runner.startForegroundRetry(activity)
                onQueued()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                capacityFailed = true
            } finally {
                capacityBusy = false
            }
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
            try {
                rulesCheck = withContext(Dispatchers.IO) { RulesUpdater.checkServer(app) }
                settingsVersion++
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { actionError = "Pembaruan aturan belum berhasil. Periksa koneksi dan alamat server." }
            finally { rulesBusy = false }
        }
    }

    /** Paket yang dioper lewat kartu memori, Bluetooth, atau obrolan: diverifikasi sama persis dengan yang dari server. */
    fun importRulesFile(uri: android.net.Uri) {
        if (rulesBusy) return
        rulesBusy = true
        viewModelScope.launch {
            rulesImport = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        while (buffer.size() <= 512 * 1024) {
                            val count = input.read(chunk, 0, minOf(chunk.size, 512 * 1024 + 1 - buffer.size()))
                            if (count < 0) break
                            buffer.write(chunk, 0, count)
                        }
                        buffer.toByteArray()
                    }
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
