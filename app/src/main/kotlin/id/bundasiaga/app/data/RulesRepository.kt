package id.bundasiaga.app.data

import android.content.Context
import android.util.Log
import id.bundasiaga.rules.Fase
import id.bundasiaga.rules.RuleBundle
import id.bundasiaga.rules.RuleBundleException
import id.bundasiaga.rules.RuleConfigException
import id.bundasiaga.rules.RuleConfigLoader
import id.bundasiaga.rules.RuleEngine
import java.io.File
import java.security.PublicKey

/**
 * Mesin aturan HAMIL dan NIFAS. Mulai dari konfigurasi bawaan APK; pembaruan
 * bertanda tangan (dari server dinas atau berkas) menggantinya hanya bila:
 *   - tanda tangan cocok dengan kunci di `assets/rules_trust.txt`,
 *   - konfigurasi lolos diurai dan lolos [id.bundasiaga.rules.RuleSelfTest],
 *   - nomor urutnya lebih tinggi dari konfigurasi bawaan maupun yang sedang dipakai (tanpa mundur versi).
 * Kegagalan apa pun mempertahankan aturan yang sedang dipakai. Paket yang diterima disimpan apa adanya
 * dan diverifikasi ulang tiap aplikasi mulai, sehingga berkas rusak kembali ke aturan APK.
 */
class RulesRepository(private val context: Context, private val now: () -> Long) {

    sealed interface InstallResult {
        data class Installed(val version: String, val seq: Int) : InstallResult
        data class NotNewer(val offeredSeq: Int, val currentSeq: Int) : InstallResult
        data class Rejected(val reason: String) : InstallResult
        data object Disabled : InstallResult
    }

    data class Source(val fromUpdate: Boolean, val keyId: String?, val installedAt: Long?, val origin: String?)

    private val trust: Map<String, PublicKey> = runCatching {
        context.assets.open(TRUST_FILE).bufferedReader().use { RuleBundle.parseTrustFile(it.readText()) }
    }.getOrElse {
        Log.w(TAG, "berkas kunci tepercaya aturan tidak terbaca: ${it.javaClass.simpleName}")
        emptyMap()
    }

    val updatesEnabled: Boolean get() = trust.isNotEmpty()

    private val bundled = Fase.entries.associateWith { RuleConfigLoader.loadDefault(it) }
    private val prefs = context.getSharedPreferences("rules_meta", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "rules")

    @Volatile private var engines: Map<Fase, RuleEngine> = Fase.entries.associateWith { loadCurrent(it) }
    @Volatile private var sources: Map<Fase, Source> = Fase.entries.associateWith { sourceFor(it) }

    fun engine(fase: Fase): RuleEngine = engines.getValue(fase)
    fun source(fase: Fase): Source = sources.getValue(fase)

    private fun file(fase: Fase) = File(dir, "${fase.name.lowercase()}.bundle.json")

    private fun loadCurrent(fase: Fase): RuleEngine {
        val base = bundled.getValue(fase)
        val f = file(fase)
        if (!f.exists() || trust.isEmpty()) return RuleEngine(base)
        return try {
            val v = RuleBundle.verify(f.readText(), trust)
            if (v.config.fase != fase || v.config.seq <= base.seq) RuleEngine(base) else RuleEngine(v.config)
        } catch (e: Exception) {
            // Jangan sampai crash karena berkas rusak: kembali ke aturan bawaan APK.
            Log.w(TAG, "aturan $fase yang tersimpan diabaikan: ${e.javaClass.simpleName}")
            RuleEngine(base)
        }
    }

    private fun sourceFor(fase: Fase): Source {
        val active = engines.getValue(fase).config
        if (active === bundled.getValue(fase)) return Source(false, null, null, null)
        return Source(
            fromUpdate = true,
            keyId = prefs.getString("${fase.name}.keyId", null),
            installedAt = prefs.getLong("${fase.name}.installedAt", 0L).takeIf { it > 0 },
            origin = prefs.getString("${fase.name}.origin", null),
        )
    }

    /** Memverifikasi dan, bila lebih baru, mengaktifkan paket. [origin] ditampilkan ke pengguna ("server", "file"). */
    @Synchronized
    fun install(bundleText: String, origin: String): InstallResult {
        if (trust.isEmpty()) return InstallResult.Disabled
        val verified = try {
            RuleBundle.verify(bundleText, trust)
        } catch (e: RuleBundleException) {
            return InstallResult.Rejected(e.message ?: "paket tidak valid")
        } catch (e: RuleConfigException) {
            return InstallResult.Rejected("config: ${e.message}")
        } catch (e: Exception) {
            return InstallResult.Rejected(e.javaClass.simpleName)
        }
        val fase = verified.config.fase
        val current = engine(fase).config.seq
        if (verified.config.seq <= current) return InstallResult.NotNewer(verified.config.seq, current)

        dir.mkdirs()
        val tmp = File(dir, "${fase.name.lowercase()}.tmp")
        tmp.writeText(bundleText)
        if (!tmp.renameTo(file(fase))) {
            tmp.delete()
            return InstallResult.Rejected("berkas aturan tidak bisa disimpan")
        }
        prefs.edit()
            .putString("${fase.name}.keyId", verified.keyId)
            .putLong("${fase.name}.installedAt", now())
            .putString("${fase.name}.origin", origin)
            .apply()
        engines = engines + (fase to RuleEngine(verified.config))
        sources = sources + (fase to sourceFor(fase))
        return InstallResult.Installed(verified.config.version, verified.config.seq)
    }

    companion object {
        const val TRUST_FILE = "rules_trust.txt"
        private const val TAG = "BundaRules"
    }
}
