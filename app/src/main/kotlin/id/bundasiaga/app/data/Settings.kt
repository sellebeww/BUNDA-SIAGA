package id.bundasiaga.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class Role { KADER, BIDAN, PUSKESMAS }

/**
 * Konfigurasi perangkat. Tidak ada nomor telepon yang ditulis di kode: semuanya
 * berasal dari layar Pengaturan. Tidak menyimpan data pasien.
 */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var role: Role?
        get() = prefs.getString("role", null)?.let { runCatching { Role.valueOf(it) }.getOrNull() }
        set(v) = prefs.edit().putString("role", v?.name).apply()

    var userName: String by string("userName")
    /** Kode lokasi singkat yang ikut dikirim di SMS, mis. "DSA12" (dusun/desa). */
    var lokasi: String by string("lokasi")
    /** ID dusun di faskes.json untuk perkiraan waktu tempuh. */
    var dusunId: String by string("dusunId")
    /** Tujuan rujukan (HP gateway puskesmas). */
    var destinationNumber: String by string("destinationNumber")
    /** Nomor yang bisa dihubungi balik oleh penerima (biasanya HP ini). */
    var callbackNumber: String by string("callbackNumber")
    /** Khusus PUSKESMAS: server dinas untuk meneruskan kasus (opsional). */
    var serverUrl: String by string("serverUrl")
    var serverToken: String by string("serverToken")

    var simulationMode: Boolean by bool("simulationMode", false)
    /** Khusus simulasi: berpura-pura radio tidak ada sinyal. */
    var simulateNoService: Boolean by bool("simulateNoService", false)
    /** FLAG_SECURE di layar pasien; bisa dimatikan untuk tangkapan layar demo. */
    var secureScreens: Boolean by bool("secureScreens", true)

    val isConfigured: Boolean get() = role != null && userName.isNotBlank()

    fun nextPatientNumber(): Int {
        val n = prefs.getInt("patientSeq", 0) % 999 + 1
        prefs.edit().putInt("patientSeq", n).apply()
        return n
    }

    private fun string(key: String) = object : kotlin.properties.ReadWriteProperty<Any, String> {
        override fun getValue(thisRef: Any, property: kotlin.reflect.KProperty<*>) = prefs.getString(key, "") ?: ""
        override fun setValue(thisRef: Any, property: kotlin.reflect.KProperty<*>, value: String) =
            prefs.edit().putString(key, value.trim()).apply()
    }

    private fun bool(key: String, default: Boolean) = object : kotlin.properties.ReadWriteProperty<Any, Boolean> {
        override fun getValue(thisRef: Any, property: kotlin.reflect.KProperty<*>) = prefs.getBoolean(key, default)
        override fun setValue(thisRef: Any, property: kotlin.reflect.KProperty<*>, value: Boolean) =
            prefs.edit().putBoolean(key, value).apply()
    }
}

/**
 * Sandi database: 32 byte acak, hanya disimpan dalam bentuk terenkripsi. Kunci
 * pembungkusnya ada di Android Keystore dan tidak pernah keluar dari sana.
 */
object DbKey {
    private const val ALIAS = "bunda_db_wrap"
    private const val PREF = "dbkey"

    @android.annotation.SuppressLint("ApplySharedPref")
    fun passphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val stored = prefs.getString("wrapped", null)
        val key = keystoreKey()
        if (stored != null) {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val wrapped = cipher.iv + cipher.doFinal(raw)
        // commit(), bukan apply(): kunci harus sudah tertulis di penyimpanan sebelum database dibuat dengannya.
        check(prefs.edit().putString("wrapped", Base64.encodeToString(wrapped, Base64.NO_WRAP)).commit()) { "kunci database tidak bisa disimpan" }
        return raw
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}
