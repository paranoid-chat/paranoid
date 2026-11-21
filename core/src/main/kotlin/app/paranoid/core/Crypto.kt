package app.paranoid.core

import org.bouncycastle.math.ec.rfc7748.X25519 as BcX25519
import org.bouncycastle.math.ec.rfc8032.Ed25519 as BcEd25519
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal val rng = SecureRandom()

fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

fun b64(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
fun unb64(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

fun cat(vararg parts: ByteArray): ByteArray {
    val out = ByteArray(parts.sumOf { it.size })
    var o = 0
    for (p in parts) {
        p.copyInto(out, o)
        o += p.size
    }
    return out
}

fun compareBytes(a: ByteArray, b: ByteArray): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
        if (d != 0) return d
    }
    return a.size - b.size
}

fun sha256(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    for (p in parts) md.update(p)
    return md.digest()
}

fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
    return mac.doFinal(data)
}

fun hkdf(ikm: ByteArray, salt: ByteArray?, info: String, length: Int): ByteArray =
    hkdf(ikm, salt, info.toByteArray(Charsets.UTF_8), length)

fun hkdf(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
    val prk = hmacSha256(salt ?: ByteArray(32), ikm)
    val out = ByteArray(length)
    var t = ByteArray(0)
    var o = 0
    var i = 1
    while (o < length) {
        t = hmacSha256(prk, cat(t, info, byteArrayOf(i.toByte())))
        val n = minOf(t.size, length - o)
        t.copyInto(out, o, 0, n)
        o += n
        i++
    }
    return out
}

fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

object X25519 {
    fun generatePrivate(): ByteArray = ByteArray(32).also { BcX25519.generatePrivateKey(rng, it) }

    fun publicOf(priv: ByteArray): ByteArray = ByteArray(32).also { BcX25519.generatePublicKey(priv, 0, it, 0) }

    fun agree(priv: ByteArray, pub: ByteArray): ByteArray {
        require(priv.size == 32 && pub.size == 32) { tr("clé X25519 invalide", "invalid X25519 key") }
        val out = ByteArray(32)
        if (!BcX25519.calculateAgreement(priv, 0, pub, 0, out, 0)) throw CryptoException(tr("échange X25519 invalide", "invalid X25519 exchange"))
        return out
    }
}

object Ed25519 {
    fun newSeed(): ByteArray = randomBytes(32)

    fun publicOf(seed: ByteArray): ByteArray = ByteArray(32).also { BcEd25519.generatePublicKey(seed, 0, it, 0) }

    fun sign(seed: ByteArray, msg: ByteArray): ByteArray =
        ByteArray(64).also { BcEd25519.sign(seed, 0, msg, 0, msg.size, it, 0) }

    fun verify(pub: ByteArray, msg: ByteArray, sig: ByteArray): Boolean =
        sig.size == 64 && pub.size == 32 && BcEd25519.verify(sig, 0, pub, 0, msg, 0, msg.size)
}

object Aead {
    fun seal(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        return c.doFinal(plain)
    }

    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        c.doFinal(sealed)
    } catch (e: javax.crypto.AEADBadTagException) {
        null
    }
}

open class CryptoException(msg: String) : Exception(msg)

fun safetyNumber(ik: ByteArray): String =
    hex(sha256(ik).copyOf(10)).uppercase().chunked(4).joinToString(" ")

fun parseFingerprint(s: String): ByteArray {
    val clean = s.trim().replace(":", "").replace(" ", "")
    require(clean.length == 64 && clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        tr("empreinte invalide : 32 octets attendus (format FB:2B:… ou hexadécimal)", "invalid fingerprint: 32 bytes expected (FB:2B:… or hexadecimal)")
    }
    return ByteArray(32) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun formatFingerprint(fp: ByteArray): String = fp.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
