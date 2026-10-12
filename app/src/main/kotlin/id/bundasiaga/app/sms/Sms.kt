package id.bundasiaga.app.sms

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import id.bundasiaga.app.BundaApp
import id.bundasiaga.app.R
import id.bundasiaga.app.app
import id.bundasiaga.app.data.Role
import id.bundasiaga.app.data.Settings
import id.bundasiaga.app.ui.MainActivity
import id.bundasiaga.outbox.InboxEvent
import id.bundasiaga.outbox.OutKind
import id.bundasiaga.outbox.OutState
import id.bundasiaga.outbox.SendError
import id.bundasiaga.outbox.SubmitResult
import id.bundasiaga.outbox.Transport
import kotlinx.coroutines.launch

/**
 * Transport SMS sungguhan. Kembali segera setelah pesan diserahkan ke radio;
 * hasil sebenarnya datang di [SmsResultReceiver].
 */
class SmsTransport(private val context: Context) : Transport {
    override fun submit(localId: Long, attempt: Int, destination: String, body: String): SubmitResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return SubmitResult.Failed(SendError.PERMISSION_DENIED)
        }
        if (destination.isBlank()) return SubmitResult.Failed(SendError.OTHER)
        return try {
            val sms = context.getSystemService(SmsManager::class.java) ?: return SubmitResult.Failed(SendError.GENERIC_FAILURE)
            val code = (localId * 1000 + attempt % 1000).toInt()
            val sent = PendingIntent.getBroadcast(
                context, code,
                resultIntent(SmsResultReceiver.ACTION_SENT, localId, attempt),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            // Dibuat mutable agar platform bisa melampirkan PDU laporan status (intent eksplisit, jadi diizinkan).
            val delivered = PendingIntent.getBroadcast(
                context, code,
                resultIntent(SmsResultReceiver.ACTION_DELIVERED, localId, attempt),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            sms.sendTextMessage(destination, null, body, sent, delivered)
            SubmitResult.Submitted
        } catch (e: SecurityException) {
            SubmitResult.Failed(SendError.PERMISSION_DENIED)
        } catch (e: Exception) {
            SubmitResult.Failed(SendError.GENERIC_FAILURE)
        }
    }

    private fun resultIntent(action: String, localId: Long, attempt: Int) =
        Intent(context, SmsResultReceiver::class.java).setAction(action)
            .putExtra(SmsResultReceiver.EXTRA_ID, localId)
            .putExtra(SmsResultReceiver.EXTRA_ATTEMPT, attempt)
}

class SmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.app
        val id = intent.getLongExtra(EXTRA_ID, -1)
        val attempt = intent.getIntExtra(EXTRA_ATTEMPT, 0)
        if (id < 0) return
        val code = resultCode
        val pending = goAsync()
        app.scope.launch {
            try {
                when (intent.action) {
                    ACTION_SENT -> {
                        val ok = code == Activity.RESULT_OK
                        app.outbox.onSendResult(id, attempt, ok, if (ok) null else mapError(code))
                        app.metric(if (ok) "sent" else "send_failed", id, if (ok) null else mapError(code).name)
                    }
                    ACTION_DELIVERED -> {
                        val ok = deliveredOk(intent, code)
                        app.outbox.onDelivered(id, ok)
                        if (ok) app.metric("delivered", id)
                    }
                }
                app.runner.kick()
            } finally {
                pending.finish()
            }
        }
    }

    private fun mapError(code: Int) = when (code) {
        SmsManager.RESULT_ERROR_NO_SERVICE -> SendError.NO_SERVICE
        SmsManager.RESULT_ERROR_RADIO_OFF -> SendError.RADIO_OFF
        SmsManager.RESULT_ERROR_NULL_PDU -> SendError.NULL_PDU
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> SendError.GENERIC_FAILURE
        else -> SendError.OTHER
    }

    private fun deliveredOk(intent: Intent, code: Int): Boolean {
        val pdu = intent.getByteArrayExtra("pdu") ?: return code == Activity.RESULT_OK
        val format = intent.getStringExtra("format")
        val status = runCatching { SmsMessage.createFromPdu(pdu, format).status }.getOrNull() ?: return false
        // TP-Status GSM 0x00..0x1F berarti pesan sudah sampai di HP tujuan.
        return if (format == "3gpp2") status == 0 else status in 0x00..0x1F
    }

    companion object {
        const val ACTION_SENT = "id.bundasiaga.app.SMS_SENT"
        const val ACTION_DELIVERED = "id.bundasiaga.app.SMS_DELIVERED"
        const val EXTRA_ID = "id"
        const val EXTRA_ATTEMPT = "attempt"
    }
}

/** SMS masuk. Hanya pesan berawalan "BS" yang disentuh; yang lain dibiarkan. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val bySender = parts.groupBy { it.originatingAddress ?: "" }
            .mapValues { (_, msgs) -> msgs.joinToString("") { it.messageBody ?: "" } }
        val app = context.app
        val pending = goAsync()
        app.scope.launch {
            try {
                for ((from, body) in bySender) handle(app, from, body)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        suspend fun handle(app: BundaApp, from: String, body: String) {
            when (val e = app.inbox.onSms(from, body, app.clock.now())) {
                is InboxEvent.NewCase -> {
                    app.metric("case_received", e.id, e.case.referral?.tingkat?.name ?: "UNPARSED")
                    val r = e.case.referral
                    val title = if (r != null) {
                        app.getString(R.string.notif_new_case, r.tingkat.name, r.pasien, r.lokasi)
                    } else app.getString(R.string.notif_new_case_unparsed)
                    notify(app, BundaApp.CH_INCOMING, (1_000_000 + e.id).toInt(), title, app.getString(R.string.notif_open_to_ack))
                    if (app.settings.role == Role.PUSKESMAS) id.bundasiaga.app.work.SyncWorker.schedule(app)
                }
                is InboxEvent.AckHandled -> if (e.matched) {
                    val ref = app.db.outbox().byMsgId(ackMsgId(body))
                        .firstOrNull { it.kind == OutKind.REFERRAL && it.state == OutState.ACKED }
                    app.metric("ack_received", ref?.id, ref?.ackStatus?.name)
                    // Jangan pernah menebak status: tampilkan persis apa kata ACK, atau tidak ada yang spesifik.
                    val text = ref?.ackStatus?.let { app.getString(R.string.notif_ack_body, it.label) }
                        ?: app.getString(R.string.notif_open_to_ack)
                    notify(app, BundaApp.CH_ACK, (2_000_000 + (ref?.id ?: 0)).toInt(), app.getString(R.string.notif_ack_title), text)
                }
                is InboxEvent.CapacityUpdated -> {
                    val u = e.update
                    app.metric("capacity_received", null, "${u.faskesId} ${u.status.name}")
                    val name = app.db.facilities().all().firstOrNull { it.id == u.faskesId }?.name ?: u.faskesId
                    notify(
                        app, BundaApp.CH_CAPACITY, (3_000_000 + u.faskesId.hashCode() % 100_000),
                        app.getString(R.string.notif_capacity_title, name), u.status.label,
                    )
                }
                // Tidak ditampilkan ke pengguna (bisa saja palsu); disimpan sebagai metrik lokal untuk pelacakan masalah.
                is InboxEvent.CapacityRejected -> app.metric("capacity_rejected", null, "${e.faskesId} ${e.reason}")
                else -> Unit
            }
        }

        private fun ackMsgId(body: String) = id.bundasiaga.codec.Bs1.normalize(body).split(';').getOrElse(2) { "" }

        fun notify(context: Context, channel: String, id: Int, title: String, text: String) {
            val granted = android.os.Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setContentIntent(open)
                .setAutoCancel(true)
                // Kode pasien tidak muncul di layar kunci.
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_stat)
                        .setContentTitle(context.getString(R.string.app_name))
                        .setContentText(context.getString(R.string.notif_public)).build(),
                )
                .build()
            context.getSystemService(NotificationManager::class.java).notify(id, n)
        }
    }
}
