package app.paranoid.core

// Double Ratchet, format de message v2. Port exact de relay/cmd/relaycli/dr.go : les deux
// implémentations doivent produire et accepter les mêmes blocs (voir MESSAGES.md).

const val BLOB_SIZE = 2048
private const val DR_VERSION: Byte = 2
const val DR_TYPE_RATCHET: Byte = 0
const val DR_TYPE_BOOT: Byte = 1
const val DR_HEADER_LEN = 1 + 1 + 32 + 4 + 4
private const val TAG_LEN = 16
const val DR_INNER_LEN = BLOB_SIZE - DR_HEADER_LEN - TAG_LEN // 1990
private const val INNER_HEAD = 1 + 16 + 8 + 2
const val MAX_TEXT = DR_INNER_LEN - INNER_HEAD // 1963 octets
const val TYPE_TEXT: Byte = 1

/** Alerte « bouton rouge » : l'expéditeur a détruit sa clé privée. Corps vide. */
const val TYPE_ALERT: Byte = 2

/** L'expéditeur a changé de relais : son ancienne identité est effacée. Corps vide. */
const val TYPE_RELAY_CHANGE: Byte = 3

private const val MAX_SKIP = 1000L
private const val MAX_SKIPPED = 2000
private const val INFO_SK = "relay-dr-v1/sk"
private const val INFO_RK = "relay-dr-v1/rk"
private const val INFO_BOOT = "relay-dr-v1/boot"
private const val INFO_MSG = "relay-dr-v1/msg"
private const val AD_CONTEXT = "relay-dr-v1/ad"

class DecryptException(msg: String = tr("message illisible (clé incorrecte ou bloc altéré)", "unreadable message (wrong key or altered block)")) : CryptoException(msg)
class ReplayException : CryptoException(tr("message déjà lu, trop ancien ou non authentique", "message already read, too old or not authentic"))
class SkipException : CryptoException(tr("trop de messages manquants", "too many missing messages"))
class VersionException : CryptoException(tr("version de message non prise en charge", "unsupported message version"))

class ChainState(var dh: ByteArray, var ck: ByteArray, var n: Long) {
    fun copy() = ChainState(dh.copyOf(), ck.copyOf(), n)
}

class SkippedKey(val dh: ByteArray, val n: Long, val mk: ByteArray)

/** État du ratchet pour un contact. */
class Session(
    val initiator: Boolean,
    var rk: ByteArray,
    var dhsPriv: ByteArray,
    var dhr: ByteArray? = null,
    var cks: ByteArray? = null,
    var ckr: ByteArray? = null,
    var ns: Long = 0,
    var nr: Long = 0,
    var pn: Long = 0,
    val skipped: MutableList<SkippedKey> = mutableListOf(),
    var bootSend: ChainState? = null,
    var bootRecv: ChainState? = null,
) {
    fun copy() = Session(
        initiator, rk.copyOf(), dhsPriv.copyOf(), dhr?.copyOf(), cks?.copyOf(), ckr?.copyOf(),
        ns, nr, pn, skipped.toMutableList(), bootSend?.copy(), bootRecv?.copy(),
    )

    private fun assign(o: Session) {
        rk = o.rk; dhsPriv = o.dhsPriv; dhr = o.dhr; cks = o.cks; ckr = o.ckr
        ns = o.ns; nr = o.nr; pn = o.pn; bootSend = o.bootSend; bootRecv = o.bootRecv
        skipped.clear(); skipped.addAll(o.skipped)
    }

    /**
     * Chiffre [inner] ([DR_INNER_LEN] octets) et fait avancer la chaîne d'envoi.
     * L'appelant doit enregistrer la session AVANT de déposer le bloc.
     */
    fun encrypt(env: DrEnv, inner: ByteArray): ByteArray {
        require(inner.size == DR_INNER_LEN) { "taille de contenu incorrecte" }
        val header: DrHeader
        val mk: ByteArray
        val sendChain = cks
        when {
            sendChain != null -> {
                val (next, key) = kdfCK(sendChain)
                cks = next; mk = key
                header = DrHeader(DR_TYPE_RATCHET, X25519.publicOf(dhsPriv), pn, ns)
                ns++
            }
            !initiator -> {
                val boot = bootSend ?: run {
                    val eph = X25519.generatePrivate()
                    val ck = bootCK(env.sk, X25519.agree(eph, env.theirXK))
                    ChainState(X25519.publicOf(eph), ck, 0) // la clé privée éphémère n'est pas conservée
                }.also { bootSend = it }
                val (next, key) = kdfCK(boot.ck)
                boot.ck = next; mk = key
                header = DrHeader(DR_TYPE_BOOT, boot.dh, 0, boot.n)
                boot.n++
            }
            else -> throw CryptoException("état de session incohérent")
        }
        val hb = header.bytes()
        val (key, nonce) = msgKeys(mk)
        val blob = cat(hb, Aead.seal(key, nonce, inner, cat(env.ad, hb)))
        check(blob.size == BLOB_SIZE)
        return blob
    }

    /** Déchiffre un bloc v2. La session n'est modifiée que si le déchiffrement réussit. */
    fun decrypt(env: DrEnv, blob: ByteArray): ByteArray {
        val h = DrHeader.parse(blob)
        val c = copy()
        val mk = c.messageKey(env, h)
        val (key, nonce) = msgKeys(mk)
        val hb = blob.copyOfRange(0, DR_HEADER_LEN)
        val inner = Aead.open(key, nonce, blob.copyOfRange(DR_HEADER_LEN, BLOB_SIZE), cat(env.ad, hb))
        if (inner == null || inner.size != DR_INNER_LEN) throw DecryptException()
        assign(c)
        return inner
    }

    private fun takeSkipped(dh: ByteArray, n: Long): ByteArray? {
        val i = skipped.indexOfFirst { it.n == n && it.dh.contentEquals(dh) }
        return if (i < 0) null else skipped.removeAt(i).mk
    }

    /** Dérive et met de côté les clés des messages [from, until) ; renvoie la nouvelle clé de chaîne. */
    private fun skipTo(dh: ByteArray, ck: ByteArray, from: Long, until: Long): ByteArray {
        if (until < from) throw ReplayException()
        if (until - from > MAX_SKIP) throw SkipException()
        var chain = ck
        for (n in from until until) {
            val (next, mk) = kdfCK(chain)
            skipped.add(SkippedKey(dh.copyOf(), n, mk))
            chain = next
        }
        while (skipped.size > MAX_SKIPPED) skipped.removeAt(0)
        return chain
    }

    private fun messageKey(env: DrEnv, h: DrHeader): ByteArray {
        takeSkipped(h.dh, h.n)?.let { return it }

        if (h.type == DR_TYPE_BOOT) {
            if (!initiator) throw DecryptException()
            // Après la bascule du répondeur sur le ratchet, la chaîne de démarrage (recalculable
            // depuis les clés statiques) n'est plus acceptée : sinon on pourrait rejouer d'anciens messages.
            if (ckr != null) throw ReplayException()
            var boot = bootRecv
            if (boot == null || !boot.dh.contentEquals(h.dh)) {
                val out = try { X25519.agree(env.myX, h.dh) } catch (e: CryptoException) { throw DecryptException() }
                boot = ChainState(h.dh.copyOf(), bootCK(env.sk, out), 0)
                bootRecv = boot
            }
            boot.ck = skipTo(h.dh, boot.ck, boot.n, h.n)
            boot.n = h.n
            val (next, mk) = kdfCK(boot.ck)
            boot.ck = next
            boot.n++
            return mk
        }

        val currentDhr = dhr
        if (currentDhr == null || !h.dh.contentEquals(currentDhr)) {
            val chain = ckr
            if (chain != null && currentDhr != null) {
                ckr = skipTo(currentDhr, chain, nr, h.pn)
                nr = h.pn
            }
            dhRatchet(h.dh)
            bootSend = null
            bootRecv = null
        }
        val chain = ckr ?: throw DecryptException()
        ckr = skipTo(dhr!!, chain, nr, h.n)
        nr = h.n
        val (next, mk) = kdfCK(ckr!!)
        ckr = next
        nr++
        return mk
    }

    private fun dhRatchet(theirDh: ByteArray) {
        pn = ns
        ns = 0; nr = 0
        dhr = theirDh.copyOf()
        val out1 = try { X25519.agree(dhsPriv, theirDh) } catch (e: CryptoException) { throw DecryptException() }
        kdfRK(rk, out1).let { (r, c) -> rk = r; ckr = c }
        dhsPriv = X25519.generatePrivate()
        val out2 = try { X25519.agree(dhsPriv, theirDh) } catch (e: CryptoException) { throw DecryptException() }
        kdfRK(rk, out2).let { (r, c) -> rk = r; cks = c }
    }

    companion object {
        /** État initial à partir des clés statiques (résultat du double scan). */
        fun create(env: DrEnv): Session {
            if (!env.initiator) return Session(initiator = false, rk = env.sk.copyOf(), dhsPriv = env.myX.copyOf())
            val priv = X25519.generatePrivate()
            val (rk, cks) = kdfRK(env.sk, X25519.agree(priv, env.theirXK))
            return Session(initiator = true, rk = rk, dhsPriv = priv, dhr = env.theirXK.copyOf(), cks = cks)
        }
    }
}

/** Éléments dérivés des clés statiques, recalculés à chaque usage. */
class DrEnv(val sk: ByteArray, val ad: ByteArray, val myX: ByteArray, val theirXK: ByteArray, val initiator: Boolean) {
    companion object {
        fun create(myIK: ByteArray, myX: ByteArray, theirIK: ByteArray, theirXK: ByteArray): DrEnv {
            val cmp = compareBytes(myIK, theirIK)
            if (cmp == 0) throw CryptoException("état de session incohérent")
            val init = cmp < 0
            val myXK = X25519.publicOf(myX)
            val (ikLo, ikHi) = if (init) myIK to theirIK else theirIK to myIK
            val (xkLo, xkHi) = if (init) myXK to theirXK else theirXK to myXK
            val dh = X25519.agree(myX, theirXK)
            val sk = hkdf(dh, cat(ikLo, ikHi, xkLo, xkHi), INFO_SK, 32)
            return DrEnv(sk, cat(AD_CONTEXT.toByteArray(), ikLo, ikHi), myX, theirXK, init)
        }
    }
}

class DrHeader(val type: Byte, val dh: ByteArray, val pn: Long, val n: Long) {
    fun bytes(): ByteArray {
        val b = ByteArray(DR_HEADER_LEN)
        b[0] = DR_VERSION
        b[1] = type
        dh.copyInto(b, 2)
        putU32(b, 34, pn)
        putU32(b, 38, n)
        return b
    }

    companion object {
        fun parse(blob: ByteArray): DrHeader {
            if (blob.size != BLOB_SIZE) throw DecryptException()
            if (blob[0] != DR_VERSION) throw VersionException()
            val type = blob[1]
            if (type != DR_TYPE_RATCHET && type != DR_TYPE_BOOT) throw DecryptException()
            return DrHeader(type, blob.copyOfRange(2, 34), getU32(blob, 34), getU32(blob, 38))
        }
    }
}

internal fun putU32(b: ByteArray, o: Int, v: Long) {
    for (i in 0 until 4) b[o + i] = (v ushr (24 - 8 * i)).toByte()
}

internal fun getU32(b: ByteArray, o: Int): Long {
    var v = 0L
    for (i in 0 until 4) v = (v shl 8) or (b[o + i].toLong() and 0xff)
    return v
}

private fun kdfRK(rk: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> {
    val out = hkdf(dhOut, rk, INFO_RK, 64)
    return out.copyOfRange(0, 32) to out.copyOfRange(32, 64)
}

/** Renvoie (nouvelle clé de chaîne, clé de message). */
private fun kdfCK(ck: ByteArray): Pair<ByteArray, ByteArray> =
    hmacSha256(ck, byteArrayOf(0x02)) to hmacSha256(ck, byteArrayOf(0x01))

private fun bootCK(sk: ByteArray, dhOut: ByteArray) = hkdf(dhOut, sk, INFO_BOOT, 32)

private fun msgKeys(mk: ByteArray): Pair<ByteArray, ByteArray> {
    val out = hkdf(mk, null, INFO_MSG, 44)
    return out.copyOfRange(0, 32) to out.copyOfRange(32, 44)
}

// ---------- contenu clair ----------

class Plain(val type: Byte, val id: ByteArray, val ts: Long, val body: ByteArray)

fun encodeInner(p: Plain): ByteArray {
    require(p.body.size <= MAX_TEXT) { tr("message trop long", "message too long") }
    require(p.id.size == 16)
    val inner = ByteArray(DR_INNER_LEN)
    inner[0] = p.type
    p.id.copyInto(inner, 1)
    for (i in 0 until 8) inner[17 + i] = (p.ts ushr (56 - 8 * i)).toByte()
    inner[25] = (p.body.size ushr 8).toByte()
    inner[26] = p.body.size.toByte()
    p.body.copyInto(inner, 27)
    return inner
}

fun decodeInner(inner: ByteArray): Plain {
    if (inner.size != DR_INNER_LEN) throw DecryptException()
    var ts = 0L
    for (i in 0 until 8) ts = (ts shl 8) or (inner[17 + i].toLong() and 0xff)
    val n = ((inner[25].toInt() and 0xff) shl 8) or (inner[26].toInt() and 0xff)
    if (n > MAX_TEXT) throw DecryptException()
    return Plain(inner[0], inner.copyOfRange(1, 17), ts, inner.copyOfRange(27, 27 + n))
}
