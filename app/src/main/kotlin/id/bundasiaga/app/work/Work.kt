package id.bundasiaga.app.work

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import id.bundasiaga.app.BundaApp
import id.bundasiaga.app.R
import id.bundasiaga.app.app
import id.bundasiaga.app.data.CaseEntity
import id.bundasiaga.app.data.Role
import id.bundasiaga.app.data.RulesRepository
import id.bundasiaga.codec.FaseRujukan
import id.bundasiaga.rules.Fase
import id.bundasiaga.app.ui.SIMULATED_SENDER
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Menggerakkan outbox. Tiga lapis, karena tidak ada yang dijamin sendirian:
 *   1. [kick] memproses segera setiap ada kejadian (hasil kirim, ACK, aksi UI);
 *   2. [OutboxService], layanan latar depan, terus mencoba ulang sesuai jadwal backoff
 *      selama masih ada yang tertunda (dimulai dari UI, tempat yang diizinkan);
 *   3. [OutboxWorker] sebagai cadangan WorkManager bila layanan tidak bisa jalan atau dimatikan.
 * Doze dan penghemat baterai agresif bawaan pabrikan tetap bisa menunda lapis 2-3; lihat dokumen.
 */
class OutboxRunner(private val app: BundaApp) {
    fun kick() {
        app.scope.launch {
            val next = app.outbox.processDue()
            scheduleBackup(next)
        }
    }

    /** Sinyal kembali (nyata atau simulasi): kirim ulang sekarang apa yang gagal karena tidak ada sinyal. */
    fun serviceRestored() {
        app.scope.launch {
            app.outbox.onServiceRestored()
            scheduleBackup(app.outbox.processDue())
        }
    }

    /** Dipanggil dari UI saat pesan diantrekan: memulai layanan latar depan diizinkan di sini. */
    fun startForegroundRetry(context: Context) {
        kick()
        // Build Play tidak punya OutboxService (dan tidak pernah mengantrekan SMS sendiri).
        if (!id.bundasiaga.app.BuildConfig.SMS_DIRECT) return
        runCatching {
            context.startForegroundService(Intent(context, OutboxService::class.java))
        }
    }

    fun scheduleBackup(next: Long?) {
        val wm = WorkManager.getInstance(app)
        if (next == null) {
            wm.cancelUniqueWork(OutboxWorker.NAME)
            return
        }
        val delay = (next - app.clock.now()).coerceAtLeast(0)
        wm.enqueueUniqueWork(
            OutboxWorker.NAME, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<OutboxWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build(),
        )
    }
}

class OutboxWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        app.runner.scheduleBackup(app.outbox.processDue())
        return Result.success()
    }

    companion object { const val NAME = "outbox" }
}

class OutboxService : Service() {
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = NotificationCompat.Builder(this, BundaApp.CH_SENDING)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.fgs_title))
            .setContentText(getString(R.string.fgs_text))
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            // Tipenya dideklarasikan di manifest utama; manifest Play menghapus layanan ini sepenuhnya.
            @android.annotation.SuppressLint("ForegroundServiceType")
            ServiceCompat.startForeground(this, 42, n, type)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) {
            listenForServiceState()
            loop = app.scope.launch {
                while (true) {
                    val next = app.outbox.processDue() ?: break
                    app.runner.scheduleBackup(next)
                    // Tidur sampai percobaan berikutnya jatuh tempo, atau sampai sinyal kembali.
                    withTimeoutOrNull((next - app.clock.now()).coerceIn(1_000, 60_000)) { wake.receive() }
                }
                stopSelf()
            }
        }
        return START_STICKY
    }

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var serviceListener: Any? = null

    /** Coba ulang begitu HP kembali mendapat layanan, tanpa menunggu backoff selesai. */
    private fun listenForServiceState() {
        if (serviceListener != null) return
        val tm = getSystemService(TelephonyManager::class.java) ?: return
        val onState: (ServiceState) -> Unit = { st ->
            if (st.state == ServiceState.STATE_IN_SERVICE) {
                app.scope.launch {
                    app.outbox.onServiceRestored()
                    wake.trySend(Unit)
                }
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val cb = object : TelephonyCallback(), TelephonyCallback.ServiceStateListener {
                    override fun onServiceStateChanged(serviceState: ServiceState) = onState(serviceState)
                }
                tm.registerTelephonyCallback(mainExecutor, cb)
                serviceListener = cb
            } else {
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onServiceStateChanged(serviceState: ServiceState?) {
                        serviceState?.let(onState)
                    }
                }
                @Suppress("DEPRECATION")
                tm.listen(l, PhoneStateListener.LISTEN_SERVICE_STATE)
                serviceListener = l
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun stopListening(tm: TelephonyManager?, l: Any?) {
        if (Build.VERSION.SDK_INT >= 31 && l is TelephonyCallback) tm?.unregisterTelephonyCallback(l)
        if (l is PhoneStateListener) tm?.listen(l, PhoneStateListener.LISTEN_NONE)
    }

    override fun onDestroy() {
        loop?.cancel()
        val tm = getSystemService(TelephonyManager::class.java)
        runCatching { stopListening(tm, serviceListener) }
        serviceListener = null
        super.onDestroy()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        context.app.runner.kick()
    }
}

/**
 * Gateway PUSKESMAS -> server dinas. Hanya berjalan bila alamat server diisi dan
 * jaringan aktif. Mengirim data minimal dan berpseudonim: tanpa nomor telepon (pengirim
 * berupa hash satu arah), tanpa nama.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        val s = app.settings
        if (s.role != Role.PUSKESMAS || s.serverUrl.isBlank()) return Result.success()
        // Kasus simulasi demo tidak pernah keluar dari HP.
        val pending = app.db.cases().unsynced().filter { it.sender != SIMULATED_SENDER }
        if (pending.isEmpty()) return Result.success()
        val payload = JSONObject()
            .put("facility", s.userName)
            .put("facilityLokasi", s.lokasi)
            .put("cases", JSONArray(pending.map { it.toJson() }))
        return try {
            val url = URL(s.serverUrl.trimEnd('/') + "/api/v1/cases")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer ${s.serverToken}")
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            when (code) {
                in 200..299 -> {
                    val now = app.clock.now()
                    pending.forEach { app.db.cases().markSynced(it.id, now) }
                    app.metric("sync_ok", null, pending.size.toString())
                    Result.success()
                }
                401, 403 -> { app.metric("sync_auth_failed", null, code.toString()); Result.failure() }
                else -> Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun CaseEntity.toJson() = JSONObject()
        .put("caseKey", sha256(senderKey).take(16) + ":" + msgId)
        .put("tingkat", tingkat?.name ?: JSONObject.NULL)
        .put("usiaMinggu", usiaMinggu ?: JSONObject.NULL)
        .put("td", if (tdSistolik != null) "$tdSistolik/$tdDiastolik" else JSONObject.NULL)
        .put("tanda", tanda ?: "")
        .put("lokasi", lokasi ?: JSONObject.NULL)
        .put("pasien", pasien ?: JSONObject.NULL)
        .put("checksumVerified", checksumVerified)
        .put("unparsed", parseError != null)
        .put("receivedAt", receivedAt)
        .put("ackStatus", ackStatus?.name ?: JSONObject.NULL)
        .put("ackAt", ackAt ?: JSONObject.NULL)
        .put("duplicates", duplicates)
        .put("fase", (fase ?: FaseRujukan.HAMIL).name)
        .put("hariNifas", hariNifas ?: JSONObject.NULL)

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "sync", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}

/**
 * Mengambil paket aturan bertanda tangan dari server dinas, bila server diatur.
 * Server hanya cermin: [RulesRepository.install] memverifikasi tanda tangan,
 * sehingga server atau jaringan yang dibobol tidak bisa mengubah aturan.
 */
object RulesUpdater {
    sealed interface Outcome {
        data object NoServer : Outcome
        /** Server tidak punya paket untuk fase ini (HTTP 404). */
        data object NotPublished : Outcome
        data class Failed(val detail: String) : Outcome
        data class Done(val result: RulesRepository.InstallResult) : Outcome
    }

    private const val MAX_BYTES = 512 * 1024

    fun checkServer(app: BundaApp): Map<Fase, Outcome> {
        val base = app.settings.serverUrl.trimEnd('/')
        val outcomes = Fase.entries.associateWith { fase ->
            if (base.isBlank()) return@associateWith Outcome.NoServer
            if (!app.rules.updatesEnabled) return@associateWith Outcome.Done(RulesRepository.InstallResult.Disabled)
            try {
                val conn = (URL("$base/api/v1/rules/${fase.name.lowercase()}").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    when (val code = conn.responseCode) {
                        404 -> Outcome.NotPublished
                        in 200..299 -> {
                            val bytes = conn.inputStream.use { it.readNBytesCompat(MAX_BYTES + 1) }
                            if (bytes.size > MAX_BYTES) Outcome.Failed("terlalu besar")
                            else Outcome.Done(app.rules.install(String(bytes, Charsets.UTF_8), "server"))
                        }
                        else -> Outcome.Failed("HTTP $code")
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Outcome.Failed(e.javaClass.simpleName)
            }
        }
        app.getSharedPreferences("rules_meta", Context.MODE_PRIVATE).edit()
            .putLong("lastCheckAt", app.clock.now())
            .putBoolean("lastCheckOk", outcomes.values.none { it is Outcome.Failed })
            .apply()
        outcomes.forEach { (fase, o) ->
            val detail = when (o) {
                is Outcome.Done -> o.result.javaClass.simpleName
                is Outcome.Failed -> "GAGAL ${o.detail}"
                else -> o.javaClass.simpleName
            }
            app.metric("rules_check", null, "${fase.name} $detail")
        }
        return outcomes
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

class RulesUpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        if (app.settings.serverUrl.isBlank() || !app.rules.updatesEnabled) return Result.success()
        val outcomes = RulesUpdater.checkServer(app)
        return if (outcomes.values.any { it is RulesUpdater.Outcome.Failed }) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "rules_update"

        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RulesUpdateWorker>(12, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                    .build(),
            )
        }
    }
}
