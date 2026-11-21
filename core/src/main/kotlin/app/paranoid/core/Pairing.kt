package app.paranoid.core

const val PAIR_SECRET_LEN = 32
const val PAIR_VERSION: Byte = 3
private const val PAIR_PLAIN_LEN = BLOB_SIZE - 1 - 32 - 16
private const val MAX_URL = 255
private val PAIR_INFO = "relay-pair-v1".toByteArray()
private val PAIR_SIG = "relay-pair-v1/sig".toByteArray()

class PairPayload(val ik: ByteArray, val xk: ByteArray, val writeId: ByteArray, val relay: RelayRef) {
    internal fun body(): ByteArray {
        val url = relay.url.toByteArray(Charsets.UTF_8)
        if (ik.size != 32 || xk.size != 32 || writeId.size != 16 || relay.fp.size != 32 ||
            !relay.url.startsWith("https://") || url.size > MAX_URL
        ) throw CryptoException("bloc d'appairage invalide")
        return cat(ik, xk, writeId, relay.fp, byteArrayOf((url.size shr 8).toByte(), url.size.toByte()), url)
    }
}

object Pairing {
    private fun signed(inviterIk: ByteArray, secret: ByteArray, body: ByteArray) =
        cat(PAIR_SIG, inviterIk, sha256(secret), body)

    private fun keyNonce(shared: ByteArray, secret: ByteArray, inviterIk: ByteArray, ePub: ByteArray): Pair<ByteArray, ByteArray> {
        val okm = hkdf(cat(shared, secret), cat(inviterIk, ePub), PAIR_INFO, 44)
        return okm.copyOfRange(0, 32) to okm.copyOfRange(32, 44)
    }

    fun seal(mySeed: ByteArray, inviterIk: ByteArray, inviterXk: ByteArray, secret: ByteArray, p: PairPayload): ByteArray {
        require(secret.size == PAIR_SECRET_LEN && inviterIk.size == 32)
        val body = p.body()
        val plain = ByteArray(PAIR_PLAIN_LEN)
        body.copyInto(plain)
        Ed25519.sign(mySeed, signed(inviterIk, secret, body)).copyInto(plain, body.size)
        val e = X25519.generatePrivate()
        val ePub = X25519.publicOf(e)
        val shared = X25519.agree(e, inviterXk)
        e.fill(0)
        val (key, nonce) = keyNonce(shared, secret, inviterIk, ePub)
        val blob = cat(byteArrayOf(PAIR_VERSION), ePub, Aead.seal(key, nonce, plain, cat(PAIR_INFO, inviterIk, ePub)))
        check(blob.size == BLOB_SIZE)
        return blob
    }

    fun open(myX: ByteArray, myIk: ByteArray, secret: ByteArray, blob: ByteArray): PairPayload? {
        if (blob.size != BLOB_SIZE || blob[0] != PAIR_VERSION || secret.size != PAIR_SECRET_LEN) return null
        val ePub = blob.copyOfRange(1, 33)
        val shared = try { X25519.agree(myX, ePub) } catch (e: CryptoException) { return null }
        val (key, nonce) = keyNonce(shared, secret, myIk, ePub)
        val plain = Aead.open(key, nonce, blob.copyOfRange(33, BLOB_SIZE), cat(PAIR_INFO, myIk, ePub)) ?: return null
        if (plain.size != PAIR_PLAIN_LEN) return null
        val n = ((plain[112].toInt() and 0xff) shl 8) or (plain[113].toInt() and 0xff)
        if (n > MAX_URL) return null
        val end = 114 + n
        val p = PairPayload(
            plain.copyOfRange(0, 32), plain.copyOfRange(32, 64), plain.copyOfRange(64, 80),
            RelayRef(plain.copyOfRange(114, end).toString(Charsets.UTF_8), plain.copyOfRange(80, 112)),
        )
        val body = try { p.body() } catch (e: CryptoException) { return null }
        return if (Ed25519.verify(p.ik, signed(myIk, secret, body), plain.copyOfRange(end, end + 64))) p else null
    }
}
