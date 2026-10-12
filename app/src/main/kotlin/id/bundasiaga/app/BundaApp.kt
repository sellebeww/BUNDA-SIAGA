package id.bundasiaga.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import id.bundasiaga.app.data.BundaDb
import id.bundasiaga.app.data.DbKey
import id.bundasiaga.app.data.MetricEntity
import id.bundasiaga.app.data.Role
import id.bundasiaga.app.data.RoomCapacityStore
import id.bundasiaga.app.data.RoomInboxStore
import id.bundasiaga.app.data.RulesRepository
import id.bundasiaga.app.data.RoomOutboxStore
import id.bundasiaga.app.data.Settings
import id.bundasiaga.app.sms.SmsTransport
import id.bundasiaga.app.work.OutboxRunner
import id.bundasiaga.app.work.RulesUpdateWorker
import id.bundasiaga.outbox.CapacityReceiver
import id.bundasiaga.outbox.Clock
import id.bundasiaga.outbox.InboxProcessor
import id.bundasiaga.outbox.OutboxEngine
import id.bundasiaga.outbox.RetryPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Dependensi dirangkai manual; aplikasinya cukup kecil sehingga kerangka DI hanya menambah beban. */
class BundaApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val clock = Clock { System.currentTimeMillis() }

    val settings by lazy { Settings(this) }
    val db by lazy { BundaDb.open(this, DbKey.passphrase(this)) }
    /**
     * Mesin aturan HAMIL dan NIFAS (aturan APK, atau pembaruan bertanda tangan yang sudah diverifikasi).
     * Pemakaian pertama memeriksa berkas: panggil dari luar thread utama.
     */
    val rules by lazy { RulesRepository(this) { clock.now() } }
    val transport by lazy { SmsTransport(this) }
    val outbox by lazy {
        OutboxEngine(RoomOutboxStore(db.outbox()), transport, clock, currentPolicy())
    }
    val inbox by lazy {
        InboxProcessor(RoomInboxStore(db.cases()), outbox, CapacityReceiver(RoomCapacityStore(db.capacity()), ::trustedCapacitySenders)) {
            BuildConfig.SMS_DIRECT && (settings.role == Role.PUSKESMAS || settings.role == Role.BIDAN)
        }
    }
    val runner by lazy { OutboxRunner(this) }

    /**
     * Kabar kapasitas hanya dipercaya dari gateway puskesmas HP ini atau dari nomor
     * terdaftar milik faskes, tidak pernah dari pengirim sembarang.
     */
    fun trustedCapacitySenders(): Set<String> = buildSet {
        settings.destinationNumber.takeIf { it.isNotBlank() }?.let(::add)
        db.facilities().phoneNumbers().filter { it.isNotBlank() }.forEach(::add)
    }

    fun currentPolicy() = RetryPolicy()

    fun refreshPolicy() {
        outbox.policy = currentPolicy()
    }

    /** Pencatatan khusus di HP (tidak pernah keluar dari HP kecuali diekspor pengguna). */
    fun metric(event: String, refId: Long? = null, detail: String? = null) {
        scope.launch { db.metrics().insert(MetricEntity(event = event, at = clock.now(), refId = refId, detail = detail)) }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        // Lanjutkan apa pun yang tersisa di antrian (proses mati, aplikasi diperbarui).
        runner.kick()
        RulesUpdateWorker.schedulePeriodic(this)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_INCOMING, getString(R.string.channel_incoming), NotificationManager.IMPORTANCE_HIGH),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ACK, getString(R.string.channel_ack), NotificationManager.IMPORTANCE_HIGH),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SENDING, getString(R.string.channel_sending), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CAPACITY, getString(R.string.channel_capacity), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        const val CH_INCOMING = "incoming_referral"
        const val CH_ACK = "ack"
        const val CH_SENDING = "sending"
        const val CH_CAPACITY = "capacity"
    }
}

val Context.app: BundaApp get() = applicationContext as BundaApp
