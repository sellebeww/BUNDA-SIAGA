package id.bundasiaga.outbox

import id.bundasiaga.codec.Kapasitas
import id.bundasiaga.codec.StatusKapasitas
import id.bundasiaga.codec.Waktu
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Kapasitas terbaru yang diketahui dari satu faskes di HP ini. */
data class CapacityUpdate(
    val faskesId: String,
    val status: StatusKapasitas,
    val tempatTidur: Int?,
    /** Kapan pengirim bilang status itu ditetapkan (diurai dari DDHHMM). */
    val statusAt: Long,
    val receivedAt: Long,
    /** Nomor telepon asalnya, atau "LOKAL" bila diatur di HP ini. */
    val sender: String,
    val checksumVerified: Boolean,
)

interface CapacityStore {
    suspend fun latest(faskesId: String): CapacityUpdate?
    suspend fun save(u: CapacityUpdate)
}

object CapacityPolicy {
    /** Status yang lebih tua dari ini ditampilkan sebagai "mungkin sudah tidak berlaku". */
    const val STALE_AFTER_MS = 24 * 60 * 60_000L

    /** Pengirim boleh beberapa menit lebih maju dari jam HP ini. */
    private const val CLOCK_SKEW_MS = 15 * 60_000L

    /**
     * SMS hanya membawa tanggal, jam, dan menit. Mengembalikan saat terbaru yang cocok
     * dan tidak melewati [receivedAt] (ditambah sedikit selisih jam), mencari mundur
     * sampai dua bulan; null bila tidak ada tanggal kalender yang cocok (mis. tanggal 31 dua kali berturut-turut).
     */
    fun resolve(w: Waktu, receivedAt: Long, zone: ZoneId): Long? {
        val limit = receivedAt + CLOCK_SKEW_MS
        val ref = ZonedDateTime.ofInstant(Instant.ofEpochMilli(receivedAt), zone)
        for (back in 0L..2L) {
            val month = ref.minusMonths(back)
            if (w.day > month.toLocalDate().lengthOfMonth()) continue
            val t = month.withDayOfMonth(w.day).withHour(w.hour).withMinute(w.minute).withSecond(0).withNano(0)
                .toInstant().toEpochMilli()
            if (t <= limit) return t
        }
        return null
    }

    fun isStale(u: CapacityUpdate, now: Long) = now - u.statusAt > STALE_AFTER_MS
}

/**
 * Menerima siaran kapasitas. Siapa pun bisa mengirim SMS yang tampak seperti milik kita, jadi
 * siaran hanya dipercaya bila datang dari nomor yang sudah dipercaya HP ini
 * (gateway puskesmasnya sendiri, atau nomor terdaftar milik faskes), dan status yang lebih
 * lama tidak pernah menimpa yang lebih baru.
 */
class CapacityReceiver(
    private val store: CapacityStore,
    private val trustedSenders: () -> Set<String>,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    suspend fun onCapacity(from: String, k: Kapasitas, checksumVerified: Boolean, now: Long): InboxEvent {
        val key = normalizePhone(from)
        if (key.isEmpty() || trustedSenders().map(::normalizePhone).none { it == key }) {
            return InboxEvent.CapacityRejected(k.faskesId, "UNTRUSTED_SENDER")
        }
        val statusAt = CapacityPolicy.resolve(k.waktu, now, zone()) ?: return InboxEvent.CapacityRejected(k.faskesId, "BAD_TIME")
        val existing = store.latest(k.faskesId)
        if (existing != null && existing.statusAt >= statusAt) return InboxEvent.CapacityRejected(k.faskesId, "OLDER_THAN_KNOWN")
        val u = CapacityUpdate(k.faskesId, k.status, k.tempatTidur, statusAt, now, from, checksumVerified)
        store.save(u)
        return InboxEvent.CapacityUpdated(u)
    }
}
