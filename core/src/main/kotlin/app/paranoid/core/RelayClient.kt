package app.paranoid.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

// Client HTTP du relais (PROTOCOL.md §3 et §4), avec épinglage du certificat par empreinte SHA-256.

class RelayRef(val url: String, val fp: ByteArray)

open class RelayException(msg: String, val status: Int = 0) : Exception(msg)
class QuotaFullException : RelayException("la boîte du destinataire est pleine : réessaie plus tard", 507)
class RelayFullException : RelayException("le relais est plein : réessaie plus tard, ou préviens son administrateur", 507)
class PinningException(msg: String) : CertificateException(msg)

class Record(val seq: Long, val ts: Long, val blob: ByteArray)

class RelayClient(private val ref: RelayRef, private val timeoutMs: Int = 20_000) {

    private class Response(val code: Int, val body: ByteArray, val more: Boolean, val retryAfter: String?, val full: Boolean)

    private class PinningTrustManager(private val fp: ByteArray) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw PinningException("aucun certificat présenté par le relais")
            val got = sha256(leaf.encoded)
            if (!constantTimeEquals(got, fp)) {
                throw PinningException("empreinte du relais inattendue : ${formatFingerprint(got)}")
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("non utilisé")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val socketFactory: SSLSocketFactory by lazy {
        // Pas d'autorité de certification : seule l'empreinte du certificat compte.
        SSLContext.getInstance("TLSv1.3").apply {
            init(null, arrayOf(PinningTrustManager(ref.fp.copyOf())), SecureRandom())
        }.socketFactory
    }

    private val base = ref.url.trimEnd('/')

    private fun request(method: String, pathQuery: String, body: ByteArray?, signer: ByteArray?): Response {
        val conn = URI(base + pathQuery).toURL().openConnection() as HttpsURLConnection
        try {
            conn.sslSocketFactory = socketFactory
            // L'identité du serveur est garantie par l'empreinte ; le nom d'hôte n'apporte rien de plus.
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            conn.requestMethod = method
            if (signer != null) {
                val ts = (System.currentTimeMillis() / 1000).toString()
                val nonce = b64(randomBytes(16))
                val msg = "$method\n$pathQuery\n$ts\n$nonce\n${hex(sha256(body ?: ByteArray(0)))}"
                conn.setRequestProperty("X-Timestamp", ts)
                conn.setRequestProperty("X-Nonce", nonce)
                conn.setRequestProperty("X-Signature", b64(Ed25519.sign(signer, msg.toByteArray())))
            }
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val data = stream?.use { readLimited(it, 1 shl 20) } ?: ByteArray(0)
            return Response(code, data, conn.getHeaderField("X-More") == "1", conn.getHeaderField("Retry-After"), conn.getHeaderField("X-Relay-Full") == "1")
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw RelayException("réponse du relais trop grande")
        }
        return out.toByteArray()
    }

    private fun fail(r: Response): Nothing {
        if (r.full) throw RelayFullException()
        if (r.code == 429) throw RelayException("relais : trop de requêtes, réessaie dans ${r.retryAfter} s", 429)
        throw RelayException("relais : ${r.code} (${r.body.toString(Charsets.UTF_8).trim()})", r.code)
    }

    /** Vérifie que le relais répond et que l'empreinte correspond (une boîte inconnue renvoie 404). */
    fun check() {
        val r = request("GET", "/v1/box/${b64(ByteArray(16))}/msgs", null, null)
        if (r.code != 404) fail(r)
    }

    /** Crée une boîte ; renvoie (box_id, write_id). */
    fun createBox(readPub: ByteArray): Pair<ByteArray, ByteArray> {
        val r = request("POST", "/v1/box", readPub, null)
        if (r.code != 201 || r.body.size != 32) fail(r)
        return r.body.copyOfRange(0, 16) to r.body.copyOfRange(16, 32)
    }

    fun deposit(writeId: ByteArray, blob: ByteArray): Long {
        val r = request("PUT", "/v1/box/${b64(writeId)}/msg", blob, null)
        if (r.code == 507 && !r.full) throw QuotaFullException()
        if (r.code != 201 || r.body.size != 8) fail(r)
        var seq = 0L
        for (b in r.body) seq = (seq shl 8) or (b.toLong() and 0xff)
        return seq
    }

    /** Relève jusqu'à [limit] messages après [after] ; le booléen indique s'il en reste. */
    fun fetch(boxId: ByteArray, readSeed: ByteArray, after: Long, limit: Int = 50): Pair<List<Record>, Boolean> {
        val r = request("GET", "/v1/box/${b64(boxId)}/msgs?after=$after&limit=$limit", null, readSeed)
        val size = 16 + BLOB_SIZE
        if (r.code != 200 || r.body.size % size != 0) fail(r)
        val out = ArrayList<Record>()
        var o = 0
        while (o < r.body.size) {
            var seq = 0L
            var ts = 0L
            for (i in 0 until 8) seq = (seq shl 8) or (r.body[o + i].toLong() and 0xff)
            for (i in 8 until 16) ts = (ts shl 8) or (r.body[o + i].toLong() and 0xff)
            out.add(Record(seq, ts, r.body.copyOfRange(o + 16, o + size)))
            o += size
        }
        return out to r.more
    }

    fun ack(boxId: ByteArray, readSeed: ByteArray, upto: Long) {
        val r = request("DELETE", "/v1/box/${b64(boxId)}/msgs?upto=$upto", null, readSeed)
        if (r.code != 204) fail(r)
    }

    fun deleteBox(boxId: ByteArray, readSeed: ByteArray) {
        val r = request("DELETE", "/v1/box/${b64(boxId)}", null, readSeed)
        if (r.code != 204 && r.code != 404) fail(r)
    }
}
