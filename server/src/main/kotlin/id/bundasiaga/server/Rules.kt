package id.bundasiaga.server

import id.bundasiaga.rules.Fase
import id.bundasiaga.rules.RuleBundle
import id.bundasiaga.rules.RuleConfigLoader
import id.bundasiaga.rules.RuleSelfTest
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.PublicKey
import kotlin.system.exitProcess

@Serializable
data class RulesIndexEntry(val fase: String, val version: String, val seq: Int, val keyId: String, val verified: Boolean)

/**
 * Menyajikan paket aturan bertanda tangan dari sebuah folder (`hamil.bundle.json`, `nifas.bundle.json`).
 * Server tidak pernah memegang kunci privat dan tidak bisa membuat atau mengubah paket: HP
 * memverifikasi tanda tangan sendiri. Dengan berkas kepercayaan, server ikut memverifikasi, sehingga
 * berkas yang rusak atau salah tanda tangan ditolak di sini, bukan ditawarkan ke HP.
 */
class RulesMirror(private val dir: File, private val trust: Map<String, PublicKey>) {

    private fun file(fase: Fase) = File(dir, "${fase.name.lowercase()}.bundle.json")

    /** Teks paket untuk satu fase, atau null bila tidak ada atau (dengan berkas kepercayaan) tidak valid. Dibaca ulang setiap panggilan: operator cukup menyalin berkas. */
    fun bundle(fase: Fase): String? {
        val f = file(fase)
        if (!f.isFile || f.length() > 512 * 1024) return null
        val text = f.readText()
        return runCatching {
            val h = RuleBundle.header(text)
            if (h.fase != fase.name) return null
            if (trust.isNotEmpty()) RuleBundle.verify(text, trust, selfTestIterations = 200)
            text
        }.getOrNull()
    }

    fun index(): List<RulesIndexEntry> = Fase.entries.mapNotNull { fase ->
        val text = bundle(fase) ?: return@mapNotNull null
        val h = RuleBundle.header(text)
        RulesIndexEntry(h.fase, h.version, h.seq, h.keyId, verified = trust.isNotEmpty())
    }

    /** Laporan awal agar operator melihat masalah di log, bukan di HP. */
    fun describe(): String = Fase.entries.joinToString("; ") { fase ->
        val f = file(fase)
        when {
            !f.exists() -> "${fase.name}: tidak ada"
            bundle(fase) == null -> "${fase.name}: TIDAK VALID (tidak disajikan)"
            else -> "${fase.name}: ${RuleBundle.header(f.readText()).version}"
        }
    }
}

/**
 * Alat luring untuk pemilik aturan (dinkes). Jalankan di laptop tepercaya:
 *   ./gradlew -q :server:rulesTool --args="keygen <keyId> <outDir>"
 *   ./gradlew -q :server:rulesTool --args="check <config.json>"
 *   ./gradlew -q :server:rulesTool --args="sign <config.json> <keyId> <private.pem> <out.bundle.json>"
 *   ./gradlew -q :server:rulesTool --args="verify <bundle.json> <trust.txt>"
 * Jalur relatif dihitung dari akar repositori (folder kerja tugas).
 */
object RulesTool {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            when (args.firstOrNull()) {
                "keygen" -> keygen(args.getOrNull(1) ?: usage(), File(args.getOrNull(2) ?: usage()))
                "check" -> check(File(args.getOrNull(1) ?: usage()))
                "sign" -> sign(
                    File(args.getOrNull(1) ?: usage()), args.getOrNull(2) ?: usage(),
                    File(args.getOrNull(3) ?: usage()), File(args.getOrNull(4) ?: usage()),
                )
                "verify" -> verify(File(args.getOrNull(1) ?: usage()), File(args.getOrNull(2) ?: usage()))
                "defaults" -> defaults(File(args.getOrNull(1) ?: usage()))
                else -> usage()
            }
        } catch (e: Exception) {
            System.err.println("GALAT: ${e.message}")
            exitProcess(1)
        }
    }

    private fun usage(): Nothing {
        System.err.println(
            """
            cara pakai:
              keygen <keyId> <folderKeluaran>                      buat pasangan kunci (kunci privat disimpan luring)
              check <config.json>                                  urai + uji keamanan mandiri, tanpa menandatangani
              sign <config.json> <keyId> <private.pem> <out.json>  buat paket bertanda tangan
              verify <bundle.json> <trust.txt>                     periksa paket seperti yang dilakukan HP
              defaults <folderKeluaran>                            salin konfigurasi aturan bawaan aplikasi untuk disunting
            """.trimIndent(),
        )
        exitProcess(2)
    }

    private fun keygen(keyId: String, outDir: File) {
        outDir.mkdirs()
        val priv = File(outDir, "$keyId.private.pem")
        require(!priv.exists()) { "${priv.path} sudah ada; menolak menimpa kunci" }
        val kp = RuleBundle.generateKeyPair()
        priv.writeText(RuleBundle.privateKeyPem(kp.private))
        runCatching { Files.setPosixFilePermissions(priv.toPath(), PosixFilePermissions.fromString("rw-------")) }
        val line = "$keyId ${RuleBundle.publicKeyBase64(kp.public)}"
        File(outDir, "$keyId.trust.txt").writeText("$line\n")
        // Validasi id dengan cara yang sama seperti yang akan dipakai aplikasi.
        RuleBundle.parseTrustFile(line)
        println("Kunci privat   : ${priv.absolutePath}   (simpan luring, buat cadangan, jangan pernah di-commit)")
        println("Baris kepercayaan: ${File(outDir, "$keyId.trust.txt").absolutePath}")
        println()
        println("Tambahkan baris ini ke app/src/main/assets/rules_trust.txt, lalu build ulang aplikasi:")
        println(line)
    }

    private fun check(config: File) {
        val cfg = RuleConfigLoader.parse(config.readText())
        val problems = RuleSelfTest.check(cfg, iterations = 20_000)
        if (problems.isNotEmpty()) error("uji keamanan mandiri gagal:\n" + problems.joinToString("\n"))
        println("OK  ${cfg.fase} ${cfg.version} seq=${cfg.seq} status=${cfg.status} rules=${cfg.rules.size}")
    }

    private fun sign(config: File, keyId: String, privateKey: File, out: File) {
        val bundle = RuleBundle.sign(config.readText(), keyId, RuleBundle.parsePrivateKeyPem(privateKey.readText()))
        out.absoluteFile.parentFile?.mkdirs()
        out.writeText(bundle)
        val h = RuleBundle.header(bundle)
        println("Ditandatangani ${h.fase} ${h.version} seq=${h.seq} dengan '$keyId' -> ${out.absolutePath}")
        println("Salin ke folder aturan server dengan nama ${h.fase.lowercase()}.bundle.json")
    }

    private fun verify(bundle: File, trust: File) {
        val v = RuleBundle.verify(bundle.readText(), RuleBundle.parseTrustFile(trust.readText()), selfTestIterations = 20_000)
        println("SAH  ${v.config.fase} ${v.config.version} seq=${v.config.seq} key=${v.keyId}")
    }

    private fun defaults(outDir: File) {
        outDir.mkdirs()
        Fase.entries.forEach { fase ->
            val f = File(outDir, "${fase.name.lowercase()}.json")
            require(!f.exists()) { "${f.path} sudah ada" }
            f.writeText(RuleConfigLoader.defaultText(fase))
            println("Ditulis ${f.absolutePath}")
        }
    }
}
