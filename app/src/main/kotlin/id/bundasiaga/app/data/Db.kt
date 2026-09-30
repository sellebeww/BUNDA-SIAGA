package id.bundasiaga.app.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import id.bundasiaga.codec.AckStatus
import id.bundasiaga.codec.FaseRujukan
import id.bundasiaga.codec.Rujukan
import id.bundasiaga.codec.StatusKapasitas
import id.bundasiaga.codec.Tingkat
import id.bundasiaga.codec.Waktu
import id.bundasiaga.outbox.CapacityStore
import id.bundasiaga.outbox.CapacityUpdate
import id.bundasiaga.outbox.IncomingCase
import id.bundasiaga.outbox.InboxStore
import id.bundasiaga.outbox.OutKind
import id.bundasiaga.outbox.OutMessage
import id.bundasiaga.outbox.OutState
import id.bundasiaga.outbox.OutboxStore
import id.bundasiaga.outbox.SendError
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Entity(tableName = "exam")
data class ExamEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val patientCode: String,
    val inputJson: String,
    val level: String,
    val firedRules: String,
    val rulesVersion: String,
    val outboxId: Long? = null,
)

@Entity(tableName = "outbox", indices = [Index("msgId"), Index("state")])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: OutKind,
    val msgId: String,
    val destination: String,
    val body: String,
    val state: OutState,
    val attempts: Int,
    val createdAt: Long,
    val queuedAt: Long?,
    val expiresAt: Long?,
    val nextAttemptAt: Long?,
    val lastAttemptAt: Long?,
    val sentAt: Long?,
    val deliveredAt: Long?,
    val ackedAt: Long?,
    val ackStatus: AckStatus?,
    val lastError: SendError?,
    val refId: Long?,
)

@Entity(tableName = "incoming_case", indices = [Index(value = ["senderKey", "msgId"], unique = true)])
data class CaseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val senderKey: String,
    val msgId: String,
    val raw: String,
    val parseError: String?,
    val checksumVerified: Boolean,
    val receivedAt: Long,
    val duplicates: Int,
    // Rujukan yang diratakan (null bila SMS tidak bisa diurai).
    val pasien: String?,
    val tingkat: Tingkat?,
    val usiaMinggu: Int?,
    val tdSistolik: Int?,
    val tdDiastolik: Int?,
    val tanda: String?,
    val lokasi: String?,
    val waktu: String?,
    // Balasan dari faskes ini.
    val ackStatus: AckStatus? = null,
    val ackOutboxId: Long? = null,
    val ackAt: Long? = null,
    val syncedAt: Long? = null,
    // Ditambahkan di skema v2 (null pada baris dari v1 = HAMIL).
    val fase: FaseRujukan? = null,
    val hariNifas: Int? = null,
) {
    fun referral(): Rujukan? {
        if (pasien == null || tingkat == null || lokasi == null || waktu == null) return null
        return Rujukan(
            msgId, pasien, tingkat, usiaMinggu, tdSistolik, tdDiastolik,
            tanda?.split('.')?.filter { it.isNotBlank() } ?: emptyList(), lokasi,
            Waktu(waktu.substring(0, 2).toInt(), waktu.substring(2, 4).toInt(), waktu.substring(4, 6).toInt()),
            fase = fase ?: FaseRujukan.HAMIL, hariNifas = hariNifas,
        )
    }
}

/** Kapasitas terbaru tiap faskes di HP ini (dari SMS siaran atau diisi lokal). */
@Entity(tableName = "capacity")
data class CapacityEntity(
    @PrimaryKey val faskesId: String,
    val status: StatusKapasitas,
    val tempatTidur: Int?,
    val statusAt: Long,
    val receivedAt: Long,
    val sender: String,
    val checksumVerified: Boolean,
) {
    fun toModel() = CapacityUpdate(faskesId, status, tempatTidur, statusAt, receivedAt, sender, checksumVerified)
}

fun CapacityUpdate.toEntity() = CapacityEntity(faskesId, status, tempatTidur, statusAt, receivedAt, sender, checksumVerified)

/** Khusus PUSKESMAS: HP yang menerima siaran kapasitas (kader, bidan desa). */
@Entity(tableName = "recipient", indices = [Index(value = ["numberKey"], unique = true)])
data class RecipientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val numberKey: String,
    val label: String,
    val addedAt: Long,
)

@Entity(tableName = "metric_events", indices = [Index("event")])
data class MetricEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val event: String,
    val at: Long,
    val refId: Long?,
    val detail: String?,
)

@Dao
interface ExamDao {
    @Insert suspend fun insert(e: ExamEntity): Long
    @Query("UPDATE exam SET outboxId = :outboxId WHERE id = :id") suspend fun linkOutbox(id: Long, outboxId: Long)
    @Query("SELECT * FROM exam WHERE id = :id") suspend fun get(id: Long): ExamEntity?
    @Query("SELECT COUNT(*) FROM exam") suspend fun count(): Int
}

@Dao
interface OutboxDao {
    @Insert suspend fun insert(e: OutboxEntity): Long
    @Update suspend fun update(e: OutboxEntity)
    @Query("SELECT * FROM outbox WHERE id = :id") suspend fun get(id: Long): OutboxEntity?
    @Query("SELECT * FROM outbox WHERE id = :id") fun observe(id: Long): Flow<OutboxEntity?>
    @Query("SELECT * FROM outbox WHERE msgId = :msgId") suspend fun byMsgId(msgId: String): List<OutboxEntity>
    @Query("SELECT * FROM outbox WHERE state IN ('QUEUED','SENDING','FAILED_RETRYING')") suspend fun active(): List<OutboxEntity>
    @Query("SELECT COUNT(*) FROM outbox WHERE state IN ('QUEUED','SENDING','FAILED_RETRYING')") fun observeActiveCount(): Flow<Int>
    @Query("SELECT * FROM outbox WHERE kind = 'REFERRAL' AND state != 'DRAFT' ORDER BY createdAt DESC LIMIT 50")
    fun observeReferrals(): Flow<List<OutboxEntity>>
    @Query("SELECT * FROM outbox WHERE kind = 'CAPACITY' ORDER BY createdAt DESC LIMIT 60")
    fun observeCapacityBroadcasts(): Flow<List<OutboxEntity>>
}

@Dao
interface CapacityDao {
    @Query("SELECT * FROM capacity WHERE faskesId = :faskesId") suspend fun get(faskesId: String): CapacityEntity?
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun upsert(e: CapacityEntity)
    @Query("SELECT * FROM capacity") fun observeAll(): Flow<List<CapacityEntity>>
}

@Dao
interface RecipientDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE) suspend fun insert(e: RecipientEntity): Long
    @Query("DELETE FROM recipient WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM recipient ORDER BY label COLLATE NOCASE, number") fun observeAll(): Flow<List<RecipientEntity>>
    @Query("SELECT * FROM recipient") suspend fun all(): List<RecipientEntity>
}

@Dao
interface CaseDao {
    @Insert suspend fun insert(e: CaseEntity): Long
    @Update suspend fun update(e: CaseEntity)
    @Query("SELECT * FROM incoming_case WHERE id = :id") suspend fun get(id: Long): CaseEntity?
    @Query("SELECT * FROM incoming_case WHERE id = :id") fun observe(id: Long): Flow<CaseEntity?>
    @Query("SELECT * FROM incoming_case WHERE senderKey = :senderKey AND msgId = :msgId") suspend fun find(senderKey: String, msgId: String): CaseEntity?
    @Query("SELECT * FROM incoming_case WHERE ackOutboxId = :outboxId") suspend fun byAckOutbox(outboxId: Long): CaseEntity?
    @Query(
        """SELECT * FROM incoming_case ORDER BY
           CASE tingkat WHEN 'MERAH' THEN 0 WHEN 'KUNING' THEN 1 WHEN 'HIJAU' THEN 2 ELSE 0 END,
           receivedAt DESC""",
    )
    fun observeAll(): Flow<List<CaseEntity>>
    @Query("SELECT * FROM incoming_case") suspend fun all(): List<CaseEntity>
    @Query("SELECT DISTINCT sender FROM incoming_case WHERE parseError IS NULL") suspend fun senders(): List<String>
    @Query("SELECT * FROM incoming_case WHERE syncedAt IS NULL OR syncedAt < MAX(receivedAt, IFNULL(ackAt, 0))")
    suspend fun unsynced(): List<CaseEntity>
    @Query("UPDATE incoming_case SET syncedAt = :at WHERE id = :id") suspend fun markSynced(id: Long, at: Long)
}

@Dao
interface MetricDao {
    @Insert suspend fun insert(e: MetricEntity): Long
    @Query("SELECT * FROM metric_events ORDER BY at ASC") suspend fun all(): List<MetricEntity>
    @Query("SELECT * FROM metric_events ORDER BY at DESC LIMIT 200") fun observeRecent(): Flow<List<MetricEntity>>
}

@Database(
    entities = [
        ExamEntity::class, OutboxEntity::class, CaseEntity::class, MetricEntity::class,
        CapacityEntity::class, RecipientEntity::class,
    ],
    version = 2,
    exportSchema = true,
    // v2: kolom NIFAS pada incoming_case, tabel kapasitas dan penerima. Hanya penambahan.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class BundaDb : RoomDatabase() {
    abstract fun exams(): ExamDao
    abstract fun outbox(): OutboxDao
    abstract fun cases(): CaseDao
    abstract fun metrics(): MetricDao
    abstract fun capacity(): CapacityDao
    abstract fun recipients(): RecipientDao

    companion object {
        fun open(context: Context, passphrase: ByteArray): BundaDb {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, BundaDb::class.java, "bunda.db")
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
        }
    }
}

// ---- adaptor antara baris Room dan outbox/inbox Kotlin murni ------------------

fun OutboxEntity.toModel() = OutMessage(
    id, kind, msgId, destination, body, state, attempts, createdAt, queuedAt, expiresAt, nextAttemptAt,
    lastAttemptAt, sentAt, deliveredAt, ackedAt, ackStatus, lastError, refId,
)

fun OutMessage.toEntity() = OutboxEntity(
    id, kind, msgId, destination, body, state, attempts, createdAt, queuedAt, expiresAt, nextAttemptAt,
    lastAttemptAt, sentAt, deliveredAt, ackedAt, ackStatus, lastError, refId,
)

class RoomOutboxStore(private val dao: OutboxDao) : OutboxStore {
    override suspend fun insert(m: OutMessage) = dao.insert(m.toEntity().copy(id = 0))
    override suspend fun update(m: OutMessage) = dao.update(m.toEntity())
    override suspend fun get(id: Long) = dao.get(id)?.toModel()
    override suspend fun byMsgId(msgId: String) = dao.byMsgId(msgId).map { it.toModel() }
    override suspend fun active() = dao.active().map { it.toModel() }
}

class RoomInboxStore(private val dao: CaseDao) : InboxStore {
    override suspend fun find(senderKey: String, msgId: String) = dao.find(senderKey, msgId)?.toModel()
    override suspend fun insert(c: IncomingCase) = dao.insert(c.toEntity())
    override suspend fun update(c: IncomingCase) {
        val existing = dao.get(c.id) ?: return
        dao.update(existing.copy(duplicates = c.duplicates))
    }

    private fun CaseEntity.toModel() = IncomingCase(
        id, sender, senderKey, msgId, raw, referral(), checksumVerified, parseError, receivedAt, duplicates,
    )

    private fun IncomingCase.toEntity() = CaseEntity(
        sender = sender, senderKey = senderKey, msgId = msgId, raw = raw, parseError = parseError,
        checksumVerified = checksumVerified, receivedAt = receivedAt, duplicates = duplicates,
        pasien = referral?.pasien, tingkat = referral?.tingkat, usiaMinggu = referral?.usiaMinggu,
        tdSistolik = referral?.tdSistolik, tdDiastolik = referral?.tdDiastolik,
        tanda = referral?.tanda?.joinToString("."), lokasi = referral?.lokasi, waktu = referral?.waktu?.encode(),
        fase = referral?.fase, hariNifas = referral?.hariNifas,
    )
}

class RoomCapacityStore(private val dao: CapacityDao) : CapacityStore {
    override suspend fun latest(faskesId: String) = dao.get(faskesId)?.toModel()
    override suspend fun save(u: CapacityUpdate) = dao.upsert(u.toEntity())
}
