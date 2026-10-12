package id.bundasiaga.outbox

import id.bundasiaga.codec.Ack
import id.bundasiaga.codec.AckStatus
import id.bundasiaga.codec.Encoder
import id.bundasiaga.codec.Rujukan
import id.bundasiaga.codec.Tingkat
import id.bundasiaga.codec.Waktu
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeClock(var t: Long = 1_000_000) : Clock {
    override fun now() = t
    fun advance(ms: Long) { t += ms }
}

/** Mencatat setiap pengiriman; tes yang menentukan apa yang "dikatakan" radio. */
class FakeTransport : Transport {
    var immediate: SubmitResult = SubmitResult.Submitted
    val submissions = mutableListOf<Triple<Long, Int, String>>()
    override fun submit(localId: Long, attempt: Int, destination: String, body: String): SubmitResult {
        submissions += Triple(localId, attempt, body)
        return immediate
    }
}

class MemOutbox : OutboxStore {
    val rows = linkedMapOf<Long, OutMessage>()
    private var seq = 0L
    override suspend fun insert(m: OutMessage): Long { val id = ++seq; rows[id] = m.copy(id = id); return id }
    override suspend fun update(m: OutMessage) { rows[m.id] = m }
    override suspend fun get(id: Long) = rows[id]
    override suspend fun byMsgId(msgId: String) = rows.values.filter { it.msgId == msgId }
    override suspend fun active() = rows.values.filter {
        it.state in setOf(OutState.QUEUED, OutState.SENDING, OutState.FAILED_RETRYING)
    }
}

class MemInbox : InboxStore {
    val rows = linkedMapOf<Long, IncomingCase>()
    private var seq = 0L
    override suspend fun find(senderKey: String, msgId: String) =
        rows.values.firstOrNull { it.senderKey == senderKey && it.msgId == msgId }
    override suspend fun insert(c: IncomingCase): Long { val id = ++seq; rows[id] = c.copy(id = id); return id }
    override suspend fun update(c: IncomingCase) { rows[c.id] = c }
}

class OutboxTest {
    private val clock = FakeClock()
    private val transport = FakeTransport()
    private val store = MemOutbox()
    private val engine = OutboxEngine(store, transport, clock, RetryPolicy())
    private val body = Encoder.encode(
        Rujukan("A7K2", "YL07", Tingkat.MERAH, 34, 168, 112, listOf("SKH", "BKK"), "DSA12", Waktu(30, 15, 45)),
    )
    private val puskesmas = "+62 812-0000-1111"

    private suspend fun state(id: Long) = store.get(id)!!.state

    @Test
    fun missingDestinationIsResolvedWhenQueuedAndNeverChangesAfterSending() = runTest {
        val id = engine.createDraft(OutKind.REFERRAL, "A7K2", "", body)
        engine.queue(id, puskesmas)
        assertEquals(puskesmas, store.get(id)!!.destination)
        engine.processDue()
        engine.onSendResult(id, 1, true, null)
        engine.queue(id, "081234567899")
        assertEquals(puskesmas, store.get(id)!!.destination)
        assertEquals(OutState.SENT, state(id))
        assertEquals(1, transport.submissions.size)
    }

    @Test
    fun emptyDestinationCannotBeQueued() = runTest {
        val id = engine.createDraft(OutKind.REFERRAL, "A7K2", "", body)
        kotlin.test.assertFailsWith<IllegalArgumentException> { engine.queue(id) }
        assertEquals(OutState.DRAFT, state(id))
        engine.processDue()
        assertTrue(transport.submissions.isEmpty())
    }

    @Test
    fun queuingPreservesTheDestinationAlreadyConfirmedByUser() = runTest {
        val id = engine.createDraft(OutKind.REFERRAL, "A7K2", puskesmas, body)
        engine.queue(id, "081234567899")
        assertEquals(puskesmas, store.get(id)!!.destination)
    }

    @Test
    fun happyPath_draftQueuedSendingSentDeliveredAcked() = runTest {
        val id = engine.createDraft(OutKind.REFERRAL, "A7K2", puskesmas, body)
        assertEquals(OutState.DRAFT, state(id))
        engine.processDue()
        assertTrue(transport.submissions.isEmpty(), "draf tidak boleh dikirim")

        engine.queue(id)
        assertEquals(OutState.QUEUED, state(id))
        engine.processDue()
        assertEquals(OutState.SENDING, state(id))

        engine.onSendResult(id, 1, ok = true, error = null)
        assertEquals(OutState.SENT, state(id))
        engine.onDelivered(id, ok = true)
        assertEquals(OutState.DELIVERED, state(id))
        assertTrue(engine.onAckReceived("081200001111", Ack("A7K2", AckStatus.SIAP, Waktu(30, 15, 52))))
        assertEquals(OutState.ACKED, state(id))
        assertEquals(AckStatus.SIAP, store.get(id)!!.ackStatus)
        // Laporan pengiriman yang terlambat tidak boleh memundurkan statusnya.
        engine.onDelivered(id, ok = true)
        assertEquals(OutState.ACKED, state(id))
    }

    @Test
    fun noService_retriesWithBackoff_thenSucceedsWithoutUserAction() = runTest {
        val id = engine.enqueue(OutKind.REFERRAL, "A7K2", puskesmas, body)

        // Radio melaporkan tidak ada layanan tiga kali.
        engine.processDue()
        engine.onSendResult(id, 1, ok = false, error = SendError.NO_SERVICE)
        assertEquals(OutState.FAILED_RETRYING, state(id))
        assertEquals(SendError.NO_SERVICE, store.get(id)!!.lastError)

        clock.advance(9_999)
        engine.processDue()
        assertEquals(1, transport.submissions.size, "harus menunggu backoff 10 dtk")
        clock.advance(1)
        engine.processDue()
        assertEquals(2, transport.submissions.size)
        engine.onSendResult(id, 2, ok = false, error = SendError.NO_SERVICE)

        clock.advance(19_999)
        engine.processDue()
        assertEquals(2, transport.submissions.size, "backoff kedua 20 dtk")
        clock.advance(1)
        engine.processDue()
        assertEquals(3, transport.submissions.size)

        // Layanan kembali: radio menerimanya.
        engine.onSendResult(id, 3, ok = true, error = null)
        assertEquals(OutState.SENT, state(id))
        // MSGID dan isi sama di setiap percobaan (idempoten bagi penerima).
        assertTrue(transport.submissions.all { it.third == body })
        assertNull(engine.processDue())
    }

    @Test
    fun serviceRestored_retriesNoServiceFailuresImmediately() = runTest {
        val id = engine.enqueue(OutKind.REFERRAL, "A7K2", puskesmas, body)
        engine.processDue()
        engine.onSendResult(id, 1, ok = false, error = SendError.NO_SERVICE)
        clock.advance(1_000)
        engine.onServiceRestored()
        engine.processDue()
        assertEquals(2, transport.submissions.size, "tidak boleh menunggu sisa backoff")

        // Kegagalan bukan karena sinyal tetap memakai backoff-nya.
        engine.onSendResult(id, 2, ok = false, error = SendError.GENERIC_FAILURE)
        engine.onServiceRestored()
        engine.processDue()
        assertEquals(2, transport.submissions.size)
    }

    @Test
    fun immediateSubmitFailure_isScheduled() = runTest {
        transport.immediate = SubmitResult.Failed(SendError.PERMISSION_DENIED)
        val id = engine.enqueue(OutKind.REFERRAL, "A7K2", puskesmas, body)
        val next = engine.processDue()
        assertEquals(OutState.FAILED_RETRYING, state(id))
        assertEquals(clock.now() + 10_000, next)
    }

    @Test
    fun backoffIsCapped() {
        val p = RetryPolicy()
        assertEquals(10_000, p.backoff(1))
        assertEquals(20_000, p.backoff(2))
        assertEquals(120_000, p.backoff(10))
        assertEquals(120_000, p.backoff(1000))
    }

    @Test
    fun stuckSending_timesOutAndRetries_lateSuccessStillCounts() = runTest {
        val id = engine.enqueue(OutKind.REFERRAL, "A7K2", puskesmas, body)
        engine.processDue()
        clock.advance(RetryPolicy().sendingTimeoutMs)
        engine.processDue()
        assertEquals(OutState.FAILED_RETRYING, state(id))
        assertEquals(SendError.TIMEOUT, store.get(id)!!.lastError)
        // Keberhasilan percobaan pertama datang terlambat: memang sudah terkirim.
        engine.onSendResult(id, 1, ok = true, error = null)
        assertEquals(OutState.SENT, state(id))
    }

    @Test
    fun staleFailureFromOldAttemptIsIgnored() = runTest {
        val id = engine.enqueue(OutKind.REFERRAL, "A7K2", puskesmas, body)
        engine.processDue()
        engine.onSendResult(id, 1, ok = false, error = SendError.NO_SERVICE)
        clock.advance(10_000)
        engine.processDue() // percobaan ke-2 sedang berjalan
        engine.onSendResult(id, 1, ok = false, error = SendError.GENERIC_FAILURE)
        assertEquals(OutState.SENDING, state(id))
    }

    @Test
    fun ackWhileStillRetrying_stopsRetries() = runTest {
        val id = engine.enqueue(OutKind.REFERRAL, "A7K2", puskesmas, body)
        engine.processDue()
        engine.onSendResult(id, 1, ok = false, error = SendError.GENERIC_FAILURE)
        assertTrue(engine.onAckReceived(puskesmas, Ack("A7K2", AckStatus.PENUH, Waktu(1, 1, 1))))
        assertEquals(OutState.ACKED, state(id))
        clock.advance(3_600_000)
        engine.processDue()
        assertEquals(1, transport.submissions.size)
    }

    @Test
    fun ackFromOtherNumberOrUnknownIdIsRejected() = runTest {
        val id = engine.enqueue(OutKind.REFERRAL, "A7K2", puskesmas, body)
        engine.processDue()
        engine.onSendResult(id, 1, ok = true, error = null)
        assertEquals(false, engine.onAckReceived("0813999999", Ack("A7K2", AckStatus.SIAP, Waktu(1, 1, 1))))
        assertEquals(false, engine.onAckReceived(puskesmas, Ack("ZZZZ", AckStatus.SIAP, Waktu(1, 1, 1))))
        assertEquals(OutState.SENT, state(id))
    }

    @Test
    fun expiresAfterWindow_andUserCanRetry() = runTest {
        transport.immediate = SubmitResult.Failed(SendError.NO_SERVICE)
        val id = engine.enqueue(OutKind.REFERRAL, "A7K2", puskesmas, body)
        var guard = 0
        while (state(id) != OutState.EXPIRED && guard++ < 10_000) {
            clock.advance(60_000)
            engine.processDue()
        }
        assertEquals(OutState.EXPIRED, state(id))
        assertTrue(engine.needsEscalation(store.get(id)!!))
        val before = transport.submissions.size
        transport.immediate = SubmitResult.Submitted
        engine.retryNow(id)
        engine.processDue()
        assertEquals(before + 1, transport.submissions.size)
        assertEquals(OutState.SENDING, state(id))
    }

    @Test
    fun escalationAfterTenMinutesWithoutAck() = runTest {
        val id = engine.enqueue(OutKind.REFERRAL, "A7K2", puskesmas, body)
        engine.processDue()
        engine.onSendResult(id, 1, ok = true, error = null)
        clock.advance(9 * 60_000)
        assertEquals(false, engine.needsEscalation(store.get(id)!!))
        clock.advance(60_000)
        assertEquals(true, engine.needsEscalation(store.get(id)!!))
        engine.onAckReceived(puskesmas, Ack("A7K2", AckStatus.SIAP, Waktu(1, 1, 1)))
        assertEquals(false, engine.needsEscalation(store.get(id)!!))
    }

    @Test
    fun queueIsIdempotent() = runTest {
        val id = engine.enqueue(OutKind.REFERRAL, "A7K2", puskesmas, body)
        engine.processDue()
        engine.queue(id)
        engine.queue(id)
        assertEquals(OutState.SENDING, state(id))
        assertEquals(1, transport.submissions.size)
    }

    @Test
    fun queuedMessageExpiresBeforeFirstSendAfterLongOfflinePeriod() = runTest {
        val id = engine.enqueue(OutKind.REFERRAL, "A7K2", puskesmas, body)
        clock.advance(RetryPolicy().expireAfterMs)
        assertNull(engine.processDue())
        assertEquals(OutState.EXPIRED, state(id))
        assertTrue(transport.submissions.isEmpty())
    }

    @Test
    fun expiryWakesBeforeSendingTimeoutAndLateSuccessStillCounts() = runTest {
        engine.policy = RetryPolicy(expireAfterMs = 1_000)
        val id = engine.enqueue(OutKind.REFERRAL, "A7K2", puskesmas, body)
        assertEquals(clock.now() + 1_000, engine.processDue())
        clock.advance(1_000)
        assertNull(engine.processDue())
        assertEquals(OutState.EXPIRED, state(id))
        engine.onSendResult(id, 1, true, null)
        assertEquals(OutState.SENT, state(id))
    }

    @Test
    fun lateDeliveryAfterManualRetryPreventsDuplicateSend() = runTest {
        transport.immediate = SubmitResult.Failed(SendError.TIMEOUT)
        val id = engine.enqueue(OutKind.REFERRAL, "A7K2", puskesmas, body)
        engine.processDue()
        engine.retryNow(id)
        engine.onDelivered(id, true)
        assertEquals(OutState.DELIVERED, state(id))
        assertNull(store.get(id)!!.lastError)
        assertNull(engine.processDue())
        assertEquals(1, transport.submissions.size)
    }

    @Test
    fun phoneNormalization() {
        assertEquals("81200001111", normalizePhone("+62 812-0000-1111"))
        assertEquals("81200001111", normalizePhone("081200001111"))
        assertEquals("81200001111", normalizePhone("6281200001111"))
    }
}

class InboxTest {
    private val clock = FakeClock()
    private val outStore = MemOutbox()
    private val outbox = OutboxEngine(outStore, FakeTransport(), clock)
    private val inbox = MemInbox()
    private var puskesmasMode = true
    private val processor = InboxProcessor(inbox, outbox) { puskesmasMode }
    private val sms = Encoder.encode(
        Rujukan("A7K2", "YL07", Tingkat.MERAH, 34, 168, 112, listOf("SKH", "BKK"), "DSA12", Waktu(30, 15, 45)),
    )

    @Test
    fun duplicateReferralDoesNotCreateSecondCase() = runTest {
        assertIs<InboxEvent.NewCase>(processor.onSms("+6281233334444", sms, clock.now()))
        assertIs<InboxEvent.Duplicate>(processor.onSms("081233334444", sms, clock.now()))
        assertIs<InboxEvent.Duplicate>(processor.onSms("081233334444", sms.lowercase(), clock.now()))
        assertEquals(1, inbox.rows.size)
        assertEquals(2, inbox.rows.values.first().duplicates)
        // MSGID sama dari kader lain adalah kasus yang berbeda.
        assertIs<InboxEvent.NewCase>(processor.onSms("081299998888", sms, clock.now()))
        assertEquals(2, inbox.rows.size)
    }

    @Test
    fun unrelatedSmsIgnored_malformedKept() = runTest {
        assertEquals(InboxEvent.Ignored, processor.onSms("0811", "Promo pulsa murah!", clock.now()))
        val e = assertIs<InboxEvent.NewCase>(processor.onSms("0811", "BS1;R;rusak", clock.now()))
        assertEquals(null, e.case.referral)
        assertTrue(e.case.parseError!!.startsWith("FIELD_COUNT"))
        val v = assertIs<InboxEvent.NewCase>(processor.onSms("0811", "BS9;R;A7K2", clock.now()))
        assertTrue(v.case.parseError!!.startsWith("UNKNOWN_VERSION"))
    }

    @Test
    fun kaderModeIgnoresReferralsButHandlesAck() = runTest {
        puskesmasMode = false
        assertEquals(InboxEvent.Ignored, processor.onSms("0811", sms, clock.now()))
        val id = outbox.enqueue(OutKind.REFERRAL, "A7K2", "0822", sms)
        outbox.processDue()
        val ack = Encoder.encode(Ack("A7K2", AckStatus.SIAP, Waktu(30, 15, 50)))
        assertEquals(InboxEvent.AckHandled(true), processor.onSms("+62822", ack, clock.now()))
        assertEquals(OutState.ACKED, outStore.get(id)!!.state)
    }
}
