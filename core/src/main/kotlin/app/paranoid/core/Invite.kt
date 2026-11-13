package app.paranoid.core

// Invitation = contenu du QR (PROTOCOL.md §6), propre à un seul contact. Même format que le client Go :
// base64url( {"v":2,"ik":"…","xk":"…","r":[{"u":"…","fp":"…","w":"…"}],"s":"…"} )
// s = secret à usage unique de l'appairage (32 octets, voir Pairing.kt).

class InviteRelay(val url: String, val fp: ByteArray, val writeId: ByteArray)

class Invite(val ik: ByteArray, val xk: ByteArray, val relays: List<InviteRelay>, val secret: ByteArray) {
    fun encode(): String {
        val r = relays.joinToString(",") {
            """{"u":${jsonString(it.url)},"fp":"${b64(it.fp)}","w":"${b64(it.writeId)}"}"""
        }
        val json = """{"v":2,"ik":"${b64(ik)}","xk":"${b64(xk)}","r":[$r],"s":"${b64(secret)}"}"""
        return b64(json.toByteArray(Charsets.UTF_8))
    }

    companion object {
        fun decode(s: String): Invite {
            val raw = try {
                unb64(s.trim())
            } catch (e: IllegalArgumentException) {
                throw InviteException(tr("invitation illisible (base64url attendu)", "unreadable invitation (base64url expected)"))
            }
            val obj = try {
                MiniJson.parse(raw.toString(Charsets.UTF_8)) as? Map<*, *>
            } catch (e: Exception) {
                null
            } ?: throw InviteException(tr("invitation illisible (JSON)", "unreadable invitation (JSON)"))
            val v = (obj["v"] as? Number)?.toInt()
            if (v != 2) throw InviteException(tr("version d'invitation $v non prise en charge : mettre l'appli à jour des deux côtés", "unsupported invitation version $v: update the app on both sides"))
            val ik = obj.bytes("ik")
            val xk = obj.bytes("xk")
            val secret = obj.bytes("s")
            if (ik?.size != 32 || xk?.size != 32 || secret?.size != PAIR_SECRET_LEN) throw InviteException(tr("invitation : clés invalides", "invitation: invalid keys"))
            val list = obj["r"] as? List<*> ?: emptyList<Any>()
            if (list.isEmpty() || list.size > 3) throw InviteException(tr("invitation : 1 à 3 relais attendus", "invitation: 1 to 3 relays expected"))
            val relays = list.map { e ->
                val m = e as? Map<*, *> ?: throw InviteException(tr("invitation : relais invalide", "invitation: invalid relay"))
                val u = m["u"] as? String
                val fp = m.bytes("fp")
                val w = m.bytes("w")
                if (u == null || !u.startsWith("https://") || fp?.size != 32 || w?.size != 16) {
                    throw InviteException(tr("invitation : relais invalide", "invitation: invalid relay"))
                }
                InviteRelay(u, fp, w)
            }
            return Invite(ik, xk, relays, secret)
        }

        private fun Map<*, *>.bytes(k: String): ByteArray? = (this[k] as? String)?.let {
            try {
                unb64(it)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

class InviteException(msg: String) : Exception(msg)

internal fun jsonString(s: String): String {
    val sb = StringBuilder("\"")
    for (ch in s) {
        when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch < ' ' -> sb.append("\\u%04x".format(ch.code))
            else -> sb.append(ch)
        }
    }
    return sb.append('"').toString()
}

/** Analyseur JSON minimal (objets, tableaux, chaînes, nombres, booléens, null), suffisant pour l'invitation. */
internal object MiniJson {
    fun parse(s: String): Any? {
        val p = P(s)
        val v = p.value()
        p.ws()
        require(p.i == s.length) { "données après le JSON" }
        return v
    }

    private class P(val s: String) {
        var i = 0
        var depth = 0

        fun ws() {
            while (i < s.length && s[i] in " \t\r\n") i++
        }

        fun value(): Any? {
            ws()
            require(i < s.length)
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        fun lit(w: String, v: Any?): Any? {
            require(s.startsWith(w, i)); i += w.length; return v
        }

        fun num(): Number {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDouble()
        }

        fun str(): String {
            require(s[i] == '"'); i++
            val sb = StringBuilder()
            while (true) {
                require(i < s.length)
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> throw IllegalArgumentException("échappement invalide")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun obj(): Map<String, Any?> {
            require(++depth < 16); i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (s[i] == '}') { i++; depth--; return m }
            while (true) {
                ws()
                val k = str()
                ws(); require(s[i] == ':'); i++
                m[k] = value()
                ws()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> { depth--; return m }
                    else -> throw IllegalArgumentException("objet invalide")
                }
            }
        }

        fun arr(): List<Any?> {
            require(++depth < 16); i++
            val l = ArrayList<Any?>()
            ws()
            if (s[i] == ']') { i++; depth--; return l }
            while (true) {
                l.add(value())
                ws()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> { depth--; return l }
                    else -> throw IllegalArgumentException("tableau invalide")
                }
            }
        }
    }
}
