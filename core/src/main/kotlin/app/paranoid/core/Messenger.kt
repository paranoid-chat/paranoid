package app.paranoid.core

class Messenger(
    val state: AppState,
    private val persist: (AppState) -> Unit,
    private val clientFor: (RelayRef) -> RelayClient = { RelayClient(it) },
    private val alertClientFor: (RelayRef, Int) -> RelayClient = { r, t -> RelayClient(r, t) },
) {
    class PollResult(
        val received: List<Pair<Contact, ChatMessage>>,
        val rejected: Int,
        val errors: List<String>,
    )

    val safetyNumber: String get() = safetyNumber(state.ik)

    private fun save() = persist(state)

    private fun contact(name: String) = state.contacts[name] ?: throw IllegalArgumentException("contact $name inconnu")

    private fun env(c: Contact) = DrEnv.create(state.ik, state.x25519Priv, c.ik!!, c.xk!!)

    private fun session(c: Contact, env: DrEnv): Session = c.session ?: Session.create(env).also { c.session = it }

    private fun checkName(name: String): String {
        val n = name.trim()
        require(n.isNotEmpty() && n.length <= 32) { tr("nom de contact invalide (1 à 32 caractères)", "invalid contact name (1 to 32 characters)") }
        return n
    }

    private fun newBox(relay: RelayRef): MyBox {
        val readSeed = Ed25519.newSeed()
        val (boxId, writeId) = clientFor(relay).createBox(Ed25519.publicOf(readSeed))
        return MyBox(relay, boxId, writeId, readSeed)
    }

    @Synchronized
    fun invite(name: String): String {
        val n = checkName(name)
        val c = state.contacts[n] ?: Contact(n)
        if (c.ik != null) throw InviteException(tr("$n est déjà appairé : supprimer le contact pour recommencer", "$n is already paired: delete the contact to start over"))
        val box = c.myBox ?: newBox(state.relay).also { c.myBox = it }
        val secret = c.pairSecret ?: randomBytes(PAIR_SECRET_LEN).also { c.pairSecret = it }
        state.contacts[n] = c
        save()
        return Invite(state.ik, state.xk, listOf(InviteRelay(box.relay.url, box.relay.fp, box.writeId)), secret).encode()
    }

    @Synchronized
    fun join(name: String, invite: String): Contact {
        val n = checkName(name)
        val inv = Invite.decode(invite)
        if (inv.ik.contentEquals(state.ik)) throw InviteException(tr("c'est votre propre invitation", "this is your own invitation"))
        if (state.contacts.containsKey(n)) throw InviteException(tr("le contact $n existe déjà : le supprimer ou choisir un autre nom", "contact $n already exists: delete it or choose another name"))
        state.contacts.values.firstOrNull { it.ik?.contentEquals(inv.ik) == true }?.let {
            throw InviteException(tr("cette identité est déjà enregistrée sous le nom ${it.name}", "this identity is already saved as ${it.name}"))
        }
        val r = inv.relays.first()
        val their = TheirBox(RelayRef(r.url, r.fp), r.writeId)
        val box = newBox(state.relay)
        try {
            val blob = Pairing.seal(state.identitySeed, inv.ik, inv.xk, inv.secret, PairPayload(state.ik, state.xk, box.writeId, box.relay))
            clientFor(their.relay).deposit(their.writeId, blob)
        } catch (ex: Exception) {
            runCatching { clientFor(box.relay).deleteBox(box.boxId, box.readSeed) }
            throw ex
        }
        val c = Contact(n).also { it.ik = inv.ik; it.xk = inv.xk; it.myBox = box; it.their = their }
        state.contacts[n] = c
        save()
        return c
    }

    private fun completePairing(c: Contact, blob: ByteArray): Boolean {
        val secret = c.pairSecret ?: return false
        val p = Pairing.open(state.x25519Priv, state.ik, secret, blob) ?: return false
        if (p.ik.contentEquals(state.ik)) return false
        if (state.contacts.values.any { it !== c && it.ik?.contentEquals(p.ik) == true }) return false
        c.ik = p.ik
        c.xk = p.xk
        c.their = TheirBox(p.relay, p.writeId)
        secret.fill(0)
        c.pairSecret = null
        return true
    }

    @Synchronized
    fun send(name: String, text: String): ChatMessage {
        val c = contact(name)
        val their = c.their
        if (c.burned) throw IllegalStateException(tr("$name a effacé son identité : impossible de lui écrire", "$name erased their identity: they can no longer be reached"))
        if (!c.ready || their == null) throw IllegalStateException(tr("$name n'a pas encore scanné votre QR", "$name has not scanned your QR yet"))
        val body = text.toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_TEXT) { tr("message trop long (${body.size} octets, max $MAX_TEXT)", "message too long (${body.size} bytes, max $MAX_TEXT)") }
        val id = randomBytes(16)
        val ts = System.currentTimeMillis() / 1000
        val e = env(c)
        val blob = session(c, e).encrypt(e, encodeInner(Plain(TYPE_TEXT, id, ts, body)))
        val msg = ChatMessage(id, true, ts, text, MessageStatus.SENDING)
        c.messages.add(msg)
        save()
        try {
            clientFor(their.relay).deposit(their.writeId, blob)
            msg.status = MessageStatus.SENT
        } catch (ex: Exception) {
            msg.status = MessageStatus.FAILED
            save()
            throw ex
        }
        save()
        return msg
    }

    @Synchronized
    fun poll(): PollResult {
        val received = ArrayList<Pair<Contact, ChatMessage>>()
        val errors = ArrayList<String>()
        var rejected = 0
        for (c in state.contacts.values.toList()) {
            try {
                rejected += pollContact(c, received)
            } catch (ex: Exception) {
                errors.add("${c.name} : ${ex.message}")
            }
        }
        purgeHistory()
        return PollResult(received, rejected, errors)
    }

    private fun expired(m: ChatMessage, cutoff: Long) =
        m.ts < cutoff && m.status != MessageStatus.ALERT && m.status != MessageStatus.RELAY_CHANGE

    @Synchronized
    fun countOlderThan(seconds: Long, now: Long = System.currentTimeMillis() / 1000): Int {
        if (seconds <= 0) return 0
        val cutoff = now - seconds
        return state.contacts.values.sumOf { c -> c.messages.count { expired(it, cutoff) } }
    }

    @Synchronized
    fun purgeHistory(now: Long = System.currentTimeMillis() / 1000): Int {
        val seconds = state.historySeconds
        if (seconds <= 0) return 0
        val cutoff = now - seconds
        var n = 0
        for (c in state.contacts.values) {
            val before = c.messages.size
            c.messages.removeAll { expired(it, cutoff) }
            n += before - c.messages.size
        }
        if (n > 0) save()
        return n
    }

    @Synchronized
    fun setHistory(seconds: Long): Int {
        require(seconds >= 0)
        state.historySeconds = seconds
        save()
        return purgeHistory()
    }

    private fun pollContact(c: Contact, out: MutableList<Pair<Contact, ChatMessage>>): Int {
        val box = c.myBox ?: return 0
        val client = clientFor(box.relay)
        if (c.ik == null && c.pairSecret == null) return 0
        var rejected = 0
        while (true) {
            val (recs, more) = client.fetch(box.boxId, box.readSeed, box.lastSeq)
            if (recs.isEmpty()) return rejected
            for (r in recs) {
                if (c.ik == null) {
                    if (!completePairing(c, r.blob)) rejected++
                    box.lastSeq = r.seq
                    continue
                }
                try {
                    val e = env(c)
                    val p = decodeInner(session(c, e).decrypt(e, r.blob))
                    val id = b64(p.id)
                    val ts = minOf(p.ts, System.currentTimeMillis() / 1000)
                    if ((p.type == TYPE_TEXT || p.type == TYPE_ALERT || p.type == TYPE_RELAY_CHANGE) && id !in c.seen) {
                        c.seen.addLast(id)
                        while (c.seen.size > 500) c.seen.removeFirst()
                        val m = when (p.type) {
                            TYPE_ALERT -> {
                                c.burned = true
                                ChatMessage(p.id, false, ts, "", MessageStatus.ALERT)
                            }
                            TYPE_RELAY_CHANGE -> {
                                c.burned = true
                                ChatMessage(p.id, false, ts, "", MessageStatus.RELAY_CHANGE)
                            }
                            else -> ChatMessage(p.id, false, ts, p.body.toString(Charsets.UTF_8), MessageStatus.RECEIVED)
                        }
                        c.messages.add(m)
                        out.add(c to m)
                    }
                } catch (ex: CryptoException) {
                    rejected++
                }
                box.lastSeq = r.seq
            }
            save()
            client.ack(box.boxId, box.readSeed, box.lastSeq)
            if (!more) return rejected
        }
    }

    @Synchronized
    fun sendAlerts(timeoutMs: Long = 5_000, type: Byte = TYPE_ALERT): Int {
        require(type == TYPE_ALERT || type == TYPE_RELAY_CHANGE)
        val jobs = ArrayList<Pair<TheirBox, ByteArray>>()
        for (c in state.contacts.values) {
            val their = c.their ?: continue
            if (!c.ready || c.burned) continue
            try {
                val e = env(c)
                jobs.add(their to session(c, e).encrypt(e, encodeInner(Plain(type, randomBytes(16), System.currentTimeMillis() / 1000, ByteArray(0)))))
            } catch (ex: Exception) {
            }
        }
        if (jobs.isEmpty()) return 0
        runCatching { save() }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(jobs.size, 8)) { r ->
            Thread(r, "alerte").apply { isDaemon = true }
        }
        val ok = java.util.concurrent.atomic.AtomicInteger()
        val perRequest = maxOf(1_000L, timeoutMs - 500).toInt()
        for ((their, blob) in jobs) {
            pool.execute {
                runCatching { alertClientFor(their.relay, perRequest).deposit(their.writeId, blob) }.onSuccess { ok.incrementAndGet() }
            }
        }
        pool.shutdown()
        pool.awaitTermination(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        pool.shutdownNow()
        return ok.get()
    }

    @Synchronized
    fun resetSession(name: String) {
        contact(name).session = null
        save()
    }

    @Synchronized
    fun removeContact(name: String) {
        val c = contact(name)
        c.myBox?.let { clientFor(it.relay).deleteBox(it.boxId, it.readSeed) }
        state.contacts.remove(name)
        save()
    }

    companion object {
        fun createIdentity(relay: RelayRef, clientFor: (RelayRef) -> RelayClient = { RelayClient(it) }): AppState {
            clientFor(relay).check()
            return AppState.newIdentity(relay)
        }
    }
}
