package app.paranoid.core

import java.net.URI
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

const val DIRECTORY_URL = "https://getparanoid.fr/relais.php?format=json"

class PublicRelay(
    val name: String,
    val url: String,
    val fp: ByteArray,
    val version: String,
    val lastSeen: Long,
    val online: Boolean,
)

object RelayDirectory {
    private val NAME = Regex("^[A-Za-z0-9._-]{1,32}$")
    private val VERSION = Regex("^[0-9A-Za-z.+~-]{1,20}$")
    private val URL = Regex("^https://[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?$")

    fun parse(json: String): List<PublicRelay> {
        val root = MiniJson.parse(json) as? Map<*, *> ?: throw RelayException(tr("annuaire illisible", "unreadable directory"))
        require((root["v"] as? Number)?.toInt() == 1) { tr("version d'annuaire inconnue", "unknown directory version") }
        val list = root["relays"] as? List<*> ?: return emptyList()
        return list.mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val name = m["name"] as? String ?: return@mapNotNull null
            val url = m["url"] as? String ?: return@mapNotNull null
            val version = m["version"] as? String ?: return@mapNotNull null
            val fp = runCatching { parseFingerprint(m["fp"] as? String ?: "") }.getOrNull() ?: return@mapNotNull null
            if (!NAME.matches(name) || !URL.matches(url) || !VERSION.matches(version)) return@mapNotNull null
            PublicRelay(name, url, fp, version, (m["last_seen"] as? Number)?.toLong() ?: 0, m["online"] == true)
        }.take(1000).sortedWith(compareByDescending<PublicRelay> { it.online }.thenByDescending { it.lastSeen })
    }

    fun fetch(timeoutMs: Int = 15_000): List<PublicRelay> {
        val conn = URI(DIRECTORY_URL).toURL().openConnection() as HttpsURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code != 200) throw RelayException(tr("annuaire indisponible (HTTP $code)", "directory unavailable (HTTP $code)"), code)
            val body = conn.inputStream.use { readLimited(it, 512 * 1024) }
            return parse(String(body, Charsets.UTF_8))
        } finally {
            conn.disconnect()
        }
    }

    fun probeFingerprint(url: String, timeoutMs: Int = 15_000): ByteArray {
        val u = URI(url.trim().trimEnd('/'))
        require(u.scheme == "https" && !u.host.isNullOrEmpty()) { tr("l'adresse du relais doit commencer par https://", "the relay address must start with https://") }
        var leaf: ByteArray? = null
        val capture = object : X509TrustManager {
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                leaf = chain?.firstOrNull()?.encoded ?: throw CertificateException("aucun certificat")
            }
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("non utilisé")
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLSv1.3").apply { init(null, arrayOf(capture), SecureRandom()) }
        val sock = ctx.socketFactory.createSocket() as SSLSocket
        try {
            sock.soTimeout = timeoutMs
            sock.connect(java.net.InetSocketAddress(u.host, if (u.port > 0) u.port else 443), timeoutMs)
            sock.startHandshake()
        } finally {
            sock.close()
        }
        return sha256(leaf ?: throw RelayException(tr("le relais n'a présenté aucun certificat", "the relay presented no certificate")))
    }
}
