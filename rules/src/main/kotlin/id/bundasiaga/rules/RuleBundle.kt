package id.bundasiaga.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class RuleBundleException(message: String) : Exception(message)

/**
 * Pembaruan aturan bertanda tangan. Isinya (payload) adalah byte persis dari berkas JSON
 * konfigurasi aturan (format sama seperti `hamil.json`), sehingga tidak perlu dikanonikkan:
 *
 *   { "format": "bunda-rules-bundle-1", "keyId": "dinkes-2026", "fase": "NIFAS",
 *     "version": "nifas-0.2.0", "seq": 2, "payload": "<base64>", "signature": "<base64>" }
 *
 * ECDSA P-256 / SHA-256: tersedia di setiap versi Android yang didukung aplikasi ini dan
 * di JDK mana pun. Kunci privat dipegang penanda tangan aturan (luring); server hanya
 * menyajikan berkas dan tidak bisa memalsukan pembaruan.
 */
object RuleBundle {
    const val FORMAT = "bunda-rules-bundle-1"
    private const val ALG = "SHA256withECDSA"
    private val KEY_ID = Regex("^[a-z0-9][a-z0-9-]{0,31}$")
    private val json = Json { prettyPrint = true }

    class Verified(val keyId: String, val configText: String, val config: RuleConfig)

    /** Kolom amplop, bisa dibaca tanpa verifikasi (hanya untuk daftar; jangan pernah dipercaya). */
    data class Header(val fase: String, val version: String, val seq: Int, val keyId: String)

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun publicKeyBase64(k: PublicKey): String = Base64.getEncoder().encodeToString(k.encoded)

    fun parsePublicKey(base64: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64.trim())))

    fun privateKeyPem(k: PrivateKey): String =
        "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(k.encoded) +
            "\n-----END PRIVATE KEY-----\n"

    fun parsePrivateKeyPem(pem: String): PrivateKey {
        val body = pem.lines().filterNot { it.startsWith("-----") }.joinToString("").trim()
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)))
    }

    /**
     * Mengurai baris "keyId base64" (baris kosong dan komentar # diabaikan). Ini adalah
     * format berkas kepercayaan yang disertakan di aplikasi.
     */
    fun parseTrustFile(text: String): Map<String, PublicKey> = text.lines()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .associate { line ->
            val parts = line.split(Regex("\\s+"))
            if (parts.size != 2 || !KEY_ID.matches(parts[0])) throw RuleBundleException("baris kepercayaan salah: $line")
            parts[0] to runCatching { parsePublicKey(parts[1]) }.getOrElse { throw RuleBundleException("kunci salah untuk ${parts[0]}") }
        }

    /** Memvalidasi konfigurasi (urai + [RuleSelfTest]) dan menolak menandatangani apa pun yang gagal. */
    fun sign(configText: String, keyId: String, key: PrivateKey): String {
        if (!KEY_ID.matches(keyId)) throw RuleBundleException("keyId harus huruf kecil, angka, dan '-' (maks. 32)")
        val config = RuleConfigLoader.parse(configText)
        val problems = RuleSelfTest.check(config)
        if (problems.isNotEmpty()) throw RuleBundleException("konfigurasi gagal uji keamanan mandiri:\n" + problems.joinToString("\n"))
        val payload = configText.toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance(ALG).run {
            initSign(key)
            update(payload)
            sign()
        }
        val b64 = Base64.getEncoder()
        return json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                put("format", JsonPrimitive(FORMAT))
                put("keyId", JsonPrimitive(keyId))
                put("fase", JsonPrimitive(config.fase.name))
                put("version", JsonPrimitive(config.version))
                put("seq", JsonPrimitive(config.seq))
                put("payload", JsonPrimitive(b64.encodeToString(payload)))
                put("signature", JsonPrimitive(b64.encodeToString(signature)))
            },
        ) + "\n"
    }

    fun header(bundleText: String): Header {
        val o = runCatching { Json.parseToJsonElement(bundleText).jsonObject }.getOrElse { throw RuleBundleException("bukan JSON") }
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull ?: throw RuleBundleException("tidak ada '$k'")
        if (s("format") != FORMAT) throw RuleBundleException("format tidak dikenal '${s("format")}'")
        return Header(s("fase"), s("version"), o["seq"]?.jsonPrimitive?.intOrNull ?: throw RuleBundleException("tidak ada 'seq'"), s("keyId"))
    }

    /**
     * Memverifikasi tanda tangan, mengurai konfigurasi, memeriksa amplop sesuai dengannya, dan
     * menjalankan uji keamanan mandiri. Melempar [RuleBundleException] atau [RuleConfigException]
     * bila ada yang salah; tidak pernah mengembalikan konfigurasi yang tidak boleh dipakai.
     */
    fun verify(bundleText: String, trusted: Map<String, PublicKey>, selfTestIterations: Int = 1_000): Verified {
        val h = header(bundleText)
        val o = Json.parseToJsonElement(bundleText).jsonObject
        val key = trusted[h.keyId] ?: throw RuleBundleException("kunci '${h.keyId}' tidak dipercaya aplikasi ini")
        val dec = Base64.getDecoder()
        val payload = runCatching { dec.decode(o["payload"]!!.jsonPrimitive.content) }.getOrElse { throw RuleBundleException("isi (payload) rusak") }
        val signature = runCatching { dec.decode(o["signature"]!!.jsonPrimitive.content) }.getOrElse { throw RuleBundleException("tanda tangan rusak") }
        val ok = runCatching {
            Signature.getInstance(ALG).run {
                initVerify(key)
                update(payload)
                verify(signature)
            }
        }.getOrDefault(false)
        if (!ok) throw RuleBundleException("tanda tangan tidak cocok")
        val text = String(payload, Charsets.UTF_8)
        val config = RuleConfigLoader.parse(text)
        if (config.fase.name != h.fase || config.version != h.version || config.seq != h.seq) {
            throw RuleBundleException("amplop tidak sesuai dengan konfigurasi yang ditandatangani")
        }
        val problems = RuleSelfTest.check(config, iterations = selfTestIterations)
        if (problems.isNotEmpty()) throw RuleBundleException("konfigurasi gagal uji keamanan mandiri: ${problems.first()}")
        return Verified(h.keyId, text, config)
    }
}
