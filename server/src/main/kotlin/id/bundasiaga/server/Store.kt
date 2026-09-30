package id.bundasiaga.server

import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.DriverManager

/** Satu kasus yang diteruskan HP gateway PUSKESMAS. Berpseudonim: tanpa nama, tanpa nomor telepon. */
@Serializable
data class CaseIn(
    val caseKey: String,
    val tingkat: String? = null,
    val usiaMinggu: Int? = null,
    val td: String? = null,
    val tanda: String = "",
    val lokasi: String? = null,
    val pasien: String? = null,
    val checksumVerified: Boolean = false,
    val unparsed: Boolean = false,
    val receivedAt: Long,
    val ackStatus: String? = null,
    val ackAt: Long? = null,
    val duplicates: Int = 0,
    /** "HAMIL" atau "NIFAS" (gateway lebih lama dari v0.2 tidak mengirimnya: HAMIL). */
    val fase: String = "HAMIL",
    /** Khusus NIFAS: hari sejak melahirkan. */
    val hariNifas: Int? = null,
)

@Serializable
data class Batch(val facility: String, val facilityLokasi: String = "", val cases: List<CaseIn>)

data class CaseRow(val facility: String, val c: CaseIn, val updatedAt: Long)

/** Tautan antara satu kasus dan SATUSEHAT. Menyimpan ID IHS pasien, tidak pernah NIK. */
data class SatuSehatLink(
    val facility: String,
    val caseKey: String,
    val patientIhs: String,
    val status: String,
    val encounterId: String?,
    val lastError: String?,
    val updatedAt: Long,
)

/** Penyimpanan SQLite kecil. Satu koneksi, tersinkronisasi: bebannya hanya beberapa HP. */
class Store(path: String) {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        conn.createStatement().use {
            it.executeUpdate(
                """CREATE TABLE IF NOT EXISTS cases (
                    facility TEXT NOT NULL, case_key TEXT NOT NULL,
                    tingkat TEXT, usia INTEGER, td TEXT, tanda TEXT, lokasi TEXT, pasien TEXT,
                    checksum_ok INTEGER, unparsed INTEGER, received_at INTEGER NOT NULL,
                    ack_status TEXT, ack_at INTEGER, duplicates INTEGER, updated_at INTEGER NOT NULL,
                    PRIMARY KEY (facility, case_key))""",
            )
            it.executeUpdate("CREATE TABLE IF NOT EXISTS sync_log (facility TEXT, at INTEGER, n INTEGER)")
            it.executeUpdate(
                """CREATE TABLE IF NOT EXISTS satusehat_link (
                    facility TEXT NOT NULL, case_key TEXT NOT NULL, patient_ihs TEXT NOT NULL,
                    status TEXT NOT NULL, encounter_id TEXT, last_error TEXT, updated_at INTEGER NOT NULL,
                    PRIMARY KEY (facility, case_key))""",
            )
        }
        // v0.2: rujukan NIFAS. Hanya penambahan, sehingga database dari v0.1 tetap jalan.
        val cols = conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(cases)").use { rs -> buildSet { while (rs.next()) add(rs.getString("name")) } }
        }
        conn.createStatement().use {
            if ("fase" !in cols) it.executeUpdate("ALTER TABLE cases ADD COLUMN fase TEXT")
            if ("hari_nifas" !in cols) it.executeUpdate("ALTER TABLE cases ADD COLUMN hari_nifas INTEGER")
        }
    }

    /** Upsert idempoten: mengirim ulang paket yang sama tidak pernah menggandakan kasus. */
    @Synchronized
    fun upsert(b: Batch, now: Long): Int {
        conn.autoCommit = false
        try {
            conn.prepareStatement(
                """INSERT INTO cases (facility, case_key, tingkat, usia, td, tanda, lokasi, pasien, checksum_ok,
                     unparsed, received_at, ack_status, ack_at, duplicates, updated_at, fase, hari_nifas)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                   ON CONFLICT(facility, case_key) DO UPDATE SET fase=excluded.fase, hari_nifas=excluded.hari_nifas,
                   tingkat=excluded.tingkat, usia=excluded.usia, td=excluded.td, tanda=excluded.tanda,
                   lokasi=excluded.lokasi, pasien=excluded.pasien, checksum_ok=excluded.checksum_ok,
                   unparsed=excluded.unparsed, ack_status=excluded.ack_status, ack_at=excluded.ack_at,
                   duplicates=excluded.duplicates, updated_at=excluded.updated_at""",
            ).use { st ->
                for (c in b.cases) {
                    st.setString(1, b.facility)
                    st.setString(2, c.caseKey)
                    st.setString(3, c.tingkat)
                    st.setObject(4, c.usiaMinggu)
                    st.setString(5, c.td)
                    st.setString(6, c.tanda)
                    st.setString(7, c.lokasi)
                    st.setString(8, c.pasien)
                    st.setInt(9, if (c.checksumVerified) 1 else 0)
                    st.setInt(10, if (c.unparsed) 1 else 0)
                    st.setLong(11, c.receivedAt)
                    st.setString(12, c.ackStatus)
                    st.setObject(13, c.ackAt)
                    st.setInt(14, c.duplicates)
                    st.setLong(15, now)
                    st.setString(16, if (c.fase == "NIFAS") "NIFAS" else "HAMIL")
                    st.setObject(17, c.hariNifas)
                    st.addBatch()
                }
                st.executeBatch()
            }
            conn.prepareStatement("INSERT INTO sync_log VALUES (?,?,?)").use {
                it.setString(1, b.facility); it.setLong(2, now); it.setInt(3, b.cases.size); it.executeUpdate()
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
        return b.cases.size
    }

    @Synchronized
    fun all(): List<CaseRow> = conn.createStatement().use { st ->
        st.executeQuery("SELECT * FROM cases ORDER BY received_at DESC").use { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        CaseRow(
                            rs.getString("facility"),
                            CaseIn(
                                caseKey = rs.getString("case_key"),
                                tingkat = rs.getString("tingkat"),
                                usiaMinggu = rs.getObject("usia") as Int?,
                                td = rs.getString("td"),
                                tanda = rs.getString("tanda") ?: "",
                                lokasi = rs.getString("lokasi"),
                                pasien = rs.getString("pasien"),
                                checksumVerified = rs.getInt("checksum_ok") == 1,
                                unparsed = rs.getInt("unparsed") == 1,
                                receivedAt = rs.getLong("received_at"),
                                ackStatus = rs.getString("ack_status"),
                                ackAt = (rs.getObject("ack_at") as Number?)?.toLong(),
                                duplicates = rs.getInt("duplicates"),
                                fase = rs.getString("fase") ?: "HAMIL",
                                hariNifas = (rs.getObject("hari_nifas") as Number?)?.toInt(),
                            ),
                            rs.getLong("updated_at"),
                        ),
                    )
                }
            }
        }
    }

    @Synchronized
    fun lastSync(): Map<String, Long> = conn.createStatement().use { st ->
        st.executeQuery("SELECT facility, MAX(at) m FROM sync_log GROUP BY facility").use { rs ->
            buildMap { while (rs.next()) put(rs.getString(1), rs.getLong(2)) }
        }
    }

    fun case(facility: String, caseKey: String): CaseRow? = all().firstOrNull { it.facility == facility && it.c.caseKey == caseKey }

    @Synchronized
    fun saveLink(l: SatuSehatLink) {
        conn.prepareStatement(
            """INSERT INTO satusehat_link VALUES (?,?,?,?,?,?,?)
               ON CONFLICT(facility, case_key) DO UPDATE SET patient_ihs=excluded.patient_ihs, status=excluded.status,
               encounter_id=excluded.encounter_id, last_error=excluded.last_error, updated_at=excluded.updated_at""",
        ).use {
            it.setString(1, l.facility); it.setString(2, l.caseKey); it.setString(3, l.patientIhs); it.setString(4, l.status)
            it.setString(5, l.encounterId); it.setString(6, l.lastError); it.setLong(7, l.updatedAt)
            it.executeUpdate()
        }
    }

    @Synchronized
    fun links(): Map<Pair<String, String>, SatuSehatLink> = conn.createStatement().use { st ->
        st.executeQuery("SELECT * FROM satusehat_link").use { rs ->
            buildMap {
                while (rs.next()) {
                    val l = SatuSehatLink(
                        rs.getString("facility"), rs.getString("case_key"), rs.getString("patient_ihs"), rs.getString("status"),
                        rs.getString("encounter_id"), rs.getString("last_error"), rs.getLong("updated_at"),
                    )
                    put(l.facility to l.caseKey, l)
                }
            }
        }
    }
}
