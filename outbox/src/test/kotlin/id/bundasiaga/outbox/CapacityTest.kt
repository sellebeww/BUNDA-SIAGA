package id.bundasiaga.outbox

import id.bundasiaga.codec.Encoder
import id.bundasiaga.codec.Kapasitas
import id.bundasiaga.codec.StatusKapasitas
import id.bundasiaga.codec.Waktu
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class MemCapacity : CapacityStore {
    val rows = linkedMapOf<String, CapacityUpdate>()
    override suspend fun latest(faskesId: String) = rows[faskesId]
    override suspend fun save(u: CapacityUpdate) { rows[u.faskesId] = u }
}

class CapacityTest {
    private val zone = ZoneId.of("Asia/Makassar")
    /** 30 Sep 2026, 16:30 WITA. */
    private val now = ZonedDateTime.of(2026, 9, 30, 16, 30, 0, 0, zone).toInstant().toEpochMilli()
    private val store = MemCapacity()
    private var trusted = setOf("+62 812-0000-1111")
    private val receiver = CapacityReceiver(store, { trusted }, { zone })
    private val processor = InboxProcessor(MemInbox(), OutboxEngine(MemOutbox(), FakeTransport(), FakeClock(now)), receiver) { false }

    private fun at(day: Int, h: Int, m: Int) = Waktu(day, h, m)
    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun resolvesDayHourMinuteToTheLatestPastMoment() {
        assertEquals(millis(2026, 9, 30, 16, 0), CapacityPolicy.resolve(at(30, 16, 0), now, zone))
        // Beberapa menit "di masa depan" adalah selisih jam, bukan bulan depan.
        assertEquals(millis(2026, 9, 30, 16, 40), CapacityPolicy.resolve(at(30, 16, 40), now, zone))
        // Lebih siang dari sekarang berarti bulan lalu.
        assertEquals(millis(2026, 8, 30, 20, 0), CapacityPolicy.resolve(at(30, 20, 0), now, zone))
        assertEquals(millis(2026, 9, 29, 23, 59), CapacityPolicy.resolve(at(29, 23, 59), now, zone))
        // 31 September tidak ada: tanggal 31 terakhir jatuh di Agustus.
        assertEquals(millis(2026, 8, 31, 8, 0), CapacityPolicy.resolve(at(31, 8, 0), now, zone))
    }

    @Test
    fun trustedSenderUpdatesAndOlderNeverOverwritesNewer() = runTest {
        val sms = Encoder.encode(Kapasitas("PKM1", StatusKapasitas.PENUH, 0, at(30, 16, 0)))
        val e = assertIs<InboxEvent.CapacityUpdated>(processor.onSms("081200001111", sms, now))
        assertEquals(StatusKapasitas.PENUH, e.update.status)
        assertEquals(millis(2026, 9, 30, 16, 0), e.update.statusAt)

        // SMS tertunda dari pagi hari datang belakangan: diabaikan.
        val older = Encoder.encode(Kapasitas("PKM1", StatusKapasitas.SIAP, 3, at(30, 9, 0)))
        assertEquals(InboxEvent.CapacityRejected("PKM1", "OLDER_THAN_KNOWN"), processor.onSms("081200001111", older, now))
        assertEquals(StatusKapasitas.PENUH, store.rows["PKM1"]!!.status)

        val newer = Encoder.encode(Kapasitas("PKM1", StatusKapasitas.SIAP, 2, at(30, 16, 20)))
        assertIs<InboxEvent.CapacityUpdated>(processor.onSms("+6281200001111", newer, now))
        assertEquals(2, store.rows["PKM1"]!!.tempatTidur)
    }

    @Test
    fun unknownSenderIsRejected() = runTest {
        val sms = Encoder.encode(Kapasitas("RS1", StatusKapasitas.SIAP, 9, at(30, 16, 0)))
        assertEquals(InboxEvent.CapacityRejected("RS1", "UNTRUSTED_SENDER"), processor.onSms("0899999999", sms, now))
        trusted = emptySet()
        assertEquals(InboxEvent.CapacityRejected("RS1", "UNTRUSTED_SENDER"), processor.onSms("081200001111", sms, now))
        assertEquals(InboxEvent.CapacityRejected("RS1", "UNTRUSTED_SENDER"), processor.onSms("", sms, now))
        assertNull(store.rows["RS1"])
    }

    @Test
    fun staleAfterADay() {
        val u = CapacityUpdate("PKM1", StatusKapasitas.SIAP, null, now, now, "x", true)
        assertEquals(false, CapacityPolicy.isStale(u, now + 23 * 3_600_000L))
        assertEquals(true, CapacityPolicy.isStale(u, now + 25 * 3_600_000L))
    }
}
