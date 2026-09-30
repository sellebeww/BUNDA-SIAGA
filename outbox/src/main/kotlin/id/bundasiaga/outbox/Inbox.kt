package id.bundasiaga.outbox

import id.bundasiaga.codec.Bs1
import id.bundasiaga.codec.Decoded
import id.bundasiaga.codec.Decoder
import id.bundasiaga.codec.Rujukan

data class IncomingCase(
    val id: Long = 0,
    val sender: String,
    val senderKey: String,
    /** MSGID, atau kunci sintetis untuk pesan yang tidak bisa diurai. */
    val msgId: String,
    val raw: String,
    val referral: Rujukan?,
    val checksumVerified: Boolean,
    val parseError: String?,
    val receivedAt: Long,
    val duplicates: Int = 0,
)

interface InboxStore {
    /** Mengembalikan kasus yang sudah ada untuk (senderKey, msgId), bila ada. */
    suspend fun find(senderKey: String, msgId: String): IncomingCase?
    suspend fun insert(c: IncomingCase): Long
    suspend fun update(c: IncomingCase)
}

sealed interface InboxEvent {
    data object Ignored : InboxEvent
    data class NewCase(val id: Long, val case: IncomingCase) : InboxEvent
    data class Duplicate(val id: Long) : InboxEvent
    data class AckHandled(val matched: Boolean) : InboxEvent
    data class CapacityUpdated(val update: CapacityUpdate) : InboxEvent
    data class CapacityRejected(val faskesId: String, val reason: String) : InboxEvent
}

/**
 * Logika sisi penerima yang dipakai semua peran: rujukan menjadi kasus (tanpa duplikat
 * berdasarkan pengirim + MSGID), ACK masuk ke outbox, siaran kapasitas masuk ke [capacity],
 * SMS lain dibiarkan.
 */
class InboxProcessor(
    private val store: InboxStore,
    private val outbox: OutboxEngine,
    private val capacity: CapacityReceiver? = null,
    private val acceptReferrals: () -> Boolean,
) {
    suspend fun onSms(from: String, body: String, now: Long): InboxEvent {
        if (!Bs1.looksLikeBs(body)) return InboxEvent.Ignored
        val key = normalizePhone(from)
        return when (val d = Decoder.decode(body)) {
            is Decoded.Acknowledgement -> InboxEvent.AckHandled(outbox.onAckReceived(from, d.value))
            is Decoded.Capacity -> capacity?.onCapacity(from, d.value, d.checksumVerified, now) ?: InboxEvent.Ignored
            is Decoded.Referral -> {
                if (!acceptReferrals()) return InboxEvent.Ignored
                store(key, d.value.msgId, IncomingCase(
                    sender = from, senderKey = key, msgId = d.value.msgId, raw = body,
                    referral = d.value, checksumVerified = d.checksumVerified, parseError = null, receivedAt = now,
                ))
            }
            is Decoded.Error -> {
                if (!acceptReferrals()) return InboxEvent.Ignored
                // Tetap tampilkan sebagai "format tidak dikenal" daripada membuang kemungkinan keadaan darurat.
                val syntheticId = "?" + Bs1.ck(Bs1.normalize(body)) + body.length
                store(key, syntheticId, IncomingCase(
                    sender = from, senderKey = key, msgId = syntheticId, raw = body, referral = null,
                    checksumVerified = false, parseError = "${d.kind}: ${d.detail}", receivedAt = now,
                ))
            }
        }
    }

    private suspend fun store(key: String, msgId: String, c: IncomingCase): InboxEvent {
        val existing = store.find(key, msgId)
        if (existing != null) {
            store.update(existing.copy(duplicates = existing.duplicates + 1))
            return InboxEvent.Duplicate(existing.id)
        }
        val id = store.insert(c)
        return InboxEvent.NewCase(id, c.copy(id = id))
    }
}
