package id.bundasiaga.outbox

import id.bundasiaga.codec.Ack
import id.bundasiaga.codec.AckStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Status pesan keluar. UI tidak boleh menampilkan sesuatu yang lebih optimis dari
 * status saat ini:
 *   - SENT      = radio menerimanya (PendingIntent kirim mengembalikan OK);
 *   - DELIVERED = laporan pengiriman datang (sebisanya, bisa saja tidak pernah datang);
 *   - ACKED     = seseorang di sisi penerima menekan tombol ACK.
 */
enum class OutState { DRAFT, QUEUED, SENDING, SENT, DELIVERED, ACKED, FAILED_RETRYING, EXPIRED }

enum class OutKind { REFERRAL, ACK, CAPACITY }

enum class SendError { NO_SERVICE, RADIO_OFF, GENERIC_FAILURE, NULL_PDU, PERMISSION_DENIED, TIMEOUT, OTHER }

data class OutMessage(
    val id: Long = 0,
    val kind: OutKind,
    val msgId: String,
    val destination: String,
    val body: String,
    val state: OutState,
    val attempts: Int = 0,
    val createdAt: Long,
    val queuedAt: Long? = null,
    val expiresAt: Long? = null,
    val nextAttemptAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val sentAt: Long? = null,
    val deliveredAt: Long? = null,
    val ackedAt: Long? = null,
    val ackStatus: AckStatus? = null,
    val lastError: SendError? = null,
    /** Catatan lokal yang memiliki pesan ini (mis. pemeriksaan), untuk UI. */
    val refId: Long? = null,
)

interface OutboxStore {
    suspend fun insert(m: OutMessage): Long
    suspend fun update(m: OutMessage)
    suspend fun get(id: Long): OutMessage?
    suspend fun byMsgId(msgId: String): List<OutMessage>
    /** Pesan berstatus QUEUED, SENDING, dan FAILED_RETRYING. */
    suspend fun active(): List<OutMessage>
}

sealed interface SubmitResult {
    /** Diserahkan ke radio; hasil sebenarnya datang lewat [OutboxEngine.onSendResult]. */
    data object Submitted : SubmitResult
    data class Failed(val error: SendError) : SubmitResult
}

interface Transport {
    fun submit(localId: Long, attempt: Int, destination: String, body: String): SubmitResult
}

fun interface Clock { fun now(): Long }

data class RetryPolicy(
    val initialDelayMs: Long = 10_000,
    val maxDelayMs: Long = 120_000,
    /** Berapa lama pesan SENDING boleh menunggu callback kirimnya sebelum dicoba ulang. */
    val sendingTimeoutMs: Long = 180_000,
    val expireAfterMs: Long = 24 * 60 * 60_000L,
    /** Rujukan tanpa ACK setelah selama ini seharusnya memicu telepon. */
    val escalateAfterMs: Long = 10 * 60_000L,
) {
    fun backoff(attempts: Int): Long {
        val shift = (attempts - 1).coerceIn(0, 20)
        return (initialDelayMs shl shift).coerceAtMost(maxDelayMs)
    }

    companion object {
        /** Khusus demo: sengaja cepat terlihat, hanya dipakai saat mode simulasi aktif. */
        val SIMULATION = RetryPolicy(
            initialDelayMs = 2_000, maxDelayMs = 5_000, sendingTimeoutMs = 15_000,
            expireAfterMs = 30 * 60_000L, escalateAfterMs = 60_000,
        )
    }
}

/** Nomor telepon dibandingkan pada bagian nasionalnya ("+62 812-3" == "08123"). */
fun normalizePhone(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    return when {
        digits.startsWith("62") -> digits.substring(2)
        digits.startsWith("0") -> digits.substring(1)
        else -> digits
    }
}

/**
 * Antrian + mesin status. Kotlin murni sehingga bisa diuji dengan transport palsu
 * dan jam palsu. Semua perubahan lewat satu mutex.
 */
class OutboxEngine(
    private val store: OutboxStore,
    private val transport: Transport,
    private val clock: Clock,
    @Volatile var policy: RetryPolicy = RetryPolicy(),
) {
    private val lock = Mutex()

    suspend fun createDraft(kind: OutKind, msgId: String, destination: String, body: String, refId: Long? = null): Long =
        lock.withLock {
            store.insert(
                OutMessage(
                    kind = kind, msgId = msgId, destination = destination, body = body,
                    state = OutState.DRAFT, createdAt = clock.now(), refId = refId,
                ),
            )
        }

    /** DRAFT -> QUEUED. Idempoten untuk apa pun yang sudah diantrekan atau lebih jauh. */
    suspend fun queue(id: Long): OutMessage? = lock.withLock {
        val m = store.get(id) ?: return@withLock null
        if (m.state != OutState.DRAFT) return@withLock m
        val now = clock.now()
        m.copy(state = OutState.QUEUED, queuedAt = now, expiresAt = now + policy.expireAfterMs, nextAttemptAt = now)
            .also { store.update(it) }
    }

    suspend fun enqueue(kind: OutKind, msgId: String, destination: String, body: String, refId: Long? = null): Long {
        val id = createDraft(kind, msgId, destination, body, refId)
        queue(id)
        return id
    }

    /**
     * Mencoba setiap pesan yang jatuh tempo, menghentikan kirim yang macet, dan menandai kedaluwarsa yang lama.
     * Mengembalikan waktu paling awal sesuatu perlu diperhatikan lagi, atau null bila tidak ada.
     */
    suspend fun processDue(): Long? = lock.withLock {
        val now = clock.now()
        var next: Long? = null
        fun wake(at: Long) { next = next?.let { minOf(it, at) } ?: at }

        for (m in store.active()) {
            var cur = m
            if (cur.state == OutState.SENDING) {
                val started = cur.lastAttemptAt ?: now
                if (now - started >= policy.sendingTimeoutMs) {
                    cur = failed(cur, SendError.TIMEOUT, now)
                    store.update(cur)
                } else {
                    wake(started + policy.sendingTimeoutMs)
                    continue
                }
            }
            if (cur.state == OutState.FAILED_RETRYING && cur.expiresAt != null && now >= cur.expiresAt) {
                store.update(cur.copy(state = OutState.EXPIRED, nextAttemptAt = null))
                continue
            }
            val due = cur.nextAttemptAt ?: now
            if (due > now) {
                wake(due)
                continue
            }
            val attempt = cur.attempts + 1
            val sending = cur.copy(state = OutState.SENDING, attempts = attempt, lastAttemptAt = now, nextAttemptAt = null)
            store.update(sending)
            when (val r = transport.submit(sending.id, attempt, sending.destination, sending.body)) {
                SubmitResult.Submitted -> wake(now + policy.sendingTimeoutMs)
                is SubmitResult.Failed -> {
                    val f = failed(sending, r.error, now)
                    store.update(f)
                    f.nextAttemptAt?.let(::wake)
                }
            }
        }
        next
    }

    private fun failed(m: OutMessage, error: SendError, now: Long): OutMessage =
        m.copy(state = OutState.FAILED_RETRYING, lastError = error, nextAttemptAt = now + policy.backoff(m.attempts))

    /** Hasil PendingIntent kirim. */
    suspend fun onSendResult(localId: Long, attempt: Int, ok: Boolean, error: SendError?) = lock.withLock {
        val m = store.get(localId) ?: return@withLock
        val now = clock.now()
        if (ok) {
            // Keberhasilan terlambat dari percobaan sebelumnya tetap keberhasilan sungguhan.
            if (m.state in setOf(OutState.QUEUED, OutState.SENDING, OutState.FAILED_RETRYING, OutState.EXPIRED)) {
                store.update(m.copy(state = OutState.SENT, sentAt = now, nextAttemptAt = null, lastError = null))
            }
        } else if (m.state == OutState.SENDING && attempt == m.attempts) {
            store.update(failed(m, error ?: SendError.OTHER, now))
        }
    }

    /** Laporan pengiriman. Sebisanya: bisa datang terlambat, dua kali, atau tidak pernah. */
    suspend fun onDelivered(localId: Long, ok: Boolean) = lock.withLock {
        val m = store.get(localId) ?: return@withLock
        if (!ok) return@withLock
        if (m.state in setOf(OutState.SENDING, OutState.SENT, OutState.FAILED_RETRYING, OutState.EXPIRED)) {
            store.update(
                m.copy(
                    state = OutState.DELIVERED, deliveredAt = clock.now(), sentAt = m.sentAt ?: clock.now(),
                    nextAttemptAt = null,
                ),
            )
        }
    }

    /**
     * SMS ACK datang. Dicocokkan berdasarkan (nomor pengirim, MSGID) sehingga ACK nyasar
     * atau palsu dari nomor lain tidak bisa menandai rujukan sebagai diterima.
     */
    suspend fun onAckReceived(fromNumber: String, ack: Ack): Boolean = lock.withLock {
        val from = normalizePhone(fromNumber)
        val m = store.byMsgId(ack.msgId).firstOrNull {
            it.kind == OutKind.REFERRAL && normalizePhone(it.destination) == from && it.state != OutState.DRAFT
        } ?: return@withLock false
        if (m.state == OutState.ACKED && m.ackStatus == ack.status) return@withLock true
        val now = clock.now()
        store.update(
            m.copy(
                state = OutState.ACKED, ackStatus = ack.status, ackedAt = now,
                // Mendapat ACK membuktikan rujukan sudah keluar dari HP ini.
                sentAt = m.sentAt ?: now, nextAttemptAt = null,
            ),
        )
        true
    }

    /** Pengguna menekan "coba lagi": coba ulang segera dan mulai ulang jendela kedaluwarsa. */
    suspend fun retryNow(id: Long) = lock.withLock {
        val m = store.get(id) ?: return@withLock
        if (m.state == OutState.FAILED_RETRYING || m.state == OutState.EXPIRED || m.state == OutState.QUEUED) {
            val now = clock.now()
            store.update(m.copy(state = OutState.QUEUED, nextAttemptAt = now, expiresAt = now + policy.expireAfterMs))
        }
    }

    /**
     * HP melaporkan layanan seluler kembali: pesan yang gagal hanya karena tidak ada
     * sinyal dijadikan jatuh tempo sekarang, tanpa menunggu backoff selesai.
     */
    suspend fun onServiceRestored() = lock.withLock {
        val now = clock.now()
        store.active()
            .filter { it.state == OutState.FAILED_RETRYING && it.lastError in setOf(SendError.NO_SERVICE, SendError.RADIO_OFF) }
            .forEach { store.update(it.copy(nextAttemptAt = now)) }
    }

    fun needsEscalation(m: OutMessage, now: Long = clock.now()): Boolean {
        if (m.kind != OutKind.REFERRAL) return false
        if (m.state == OutState.ACKED || m.state == OutState.DRAFT) return false
        if (m.state == OutState.EXPIRED) return true
        val since = m.queuedAt ?: return false
        return now - since >= policy.escalateAfterMs
    }
}
