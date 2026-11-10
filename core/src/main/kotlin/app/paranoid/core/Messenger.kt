package app.paranoid.core

/**
 * Opérations de messagerie, indépendantes d'Android (même logique que relaycli).
 * [persist] est appelé à chaque changement d'état à conserver ; il doit écrire de façon atomique.
 * Toutes les méthodes font du réseau : à appeler hors du fil principal.
 */
class Messenger(
    val state: AppState,
    private val persist: (AppState) -> Unit,
    private val clientFor: (RelayRef) -> RelayClient = { RelayClient(it) },
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

    /** Crée (si besoin) ma boîte pour [name] et renvoie mon invitation, à montrer en QR à ce contact uniquement. */
    @Synchronized
    fun invite(name: String): String {
        val n = name.trim()
        require(n.isNotEmpty() && n.length <= 32) { "nom de contact invalide (1 à 32 caractères)" }
        val c = state.contacts[n] ?: Contact(n)
        var box = c.myBox
        if (box == null) {
            val readSeed = Ed25519.newSeed()
            val (boxId, writeId) = clientFor(state.relay).createBox(Ed25519.publicOf(readSeed))
            box = MyBox(state.relay, boxId, writeId, readSeed)
            c.myBox = box
            state.contacts[n] = c
            save()
        }
        return Invite(state.ik, state.xk, listOf(InviteRelay(box.relay.url, box.relay.fp, box.writeId))).encode()
    }

    /** Enregistre l'invitation scannée de [name]. */
    @Synchronized
    fun addContact(name: String, invite: String): Contact {
        val n = name.trim()
        require(n.isNotEmpty() && n.length <= 32) { "nom de contact invalide (1 à 32 caractères)" }
        val inv = Invite.decode(invite)
        if (inv.ik.contentEquals(state.ik)) throw InviteException("c'est ta propre invitation")
        state.contacts.values.firstOrNull { it.name != n && it.ik?.contentEquals(inv.ik) == true }?.let {
            throw InviteException("cette identité est déjà enregistrée sous le nom ${it.name}")
        }
        val c = state.contacts[n] ?: Contact(n)
        val old = c.ik
        if (old != null && !old.contentEquals(inv.ik)) {
            throw InviteException("les clés de $n ont changé : vérifie de vive voix, puis supprime le contact avant de l'ajouter à nouveau")
        }
        val r = inv.relays.first()
        c.ik = inv.ik
        c.xk = inv.xk
        c.their = TheirBox(RelayRef(r.url, r.fp), r.writeId)
        state.contacts[n] = c
        save()
        return c
    }

    /** Envoie un message. Le message est ajouté à l'historique même si l'envoi échoue (statut FAILED). */
    @Synchronized
    fun send(name: String, text: String): ChatMessage {
        val c = contact(name)
        val their = c.their
        if (!c.ready || their == null) throw IllegalStateException("l'échange de QR avec $name n'est pas terminé")
        val body = text.toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_TEXT) { "message trop long (${body.size} octets, max $MAX_TEXT)" }
        val id = randomBytes(16)
        val ts = System.currentTimeMillis() / 1000
        val e = env(c)
        val blob = session(c, e).encrypt(e, encodeInner(Plain(TYPE_TEXT, id, ts, body)))
        val msg = ChatMessage(id, true, ts, text, MessageStatus.SENDING)
        c.messages.add(msg)
        // Enregistré avant le dépôt : une clé de message n'est jamais réutilisée.
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

    /** Relève toutes les boîtes, déchiffre, enregistre puis acquitte. */
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
        return PollResult(received, rejected, errors)
    }

    private fun pollContact(c: Contact, out: MutableList<Pair<Contact, ChatMessage>>): Int {
        val box = c.myBox ?: return 0
        val client = clientFor(box.relay)
        if (c.ik == null) {
            // Sans les clés du contact, on ne peut rien vérifier : on n'acquitte rien.
            val (recs, _) = client.fetch(box.boxId, box.readSeed, box.lastSeq)
            if (recs.size != c.pendingFromUnknown) {
                c.pendingFromUnknown = recs.size
                save()
            }
            return 0
        }
        c.pendingFromUnknown = 0
        var rejected = 0
        while (true) {
            val (recs, more) = client.fetch(box.boxId, box.readSeed, box.lastSeq)
            if (recs.isEmpty()) return rejected
            for (r in recs) {
                try {
                    val e = env(c)
                    val p = decodeInner(session(c, e).decrypt(e, r.blob))
                    val id = b64(p.id)
                    if (p.type == TYPE_TEXT && id !in c.seen) {
                        c.seen.addLast(id)
                        while (c.seen.size > 500) c.seen.removeFirst()
                        val m = ChatMessage(p.id, false, p.ts, p.body.toString(Charsets.UTF_8), MessageStatus.RECEIVED)
                        c.messages.add(m)
                        out.add(c to m)
                    }
                } catch (ex: CryptoException) {
                    rejected++
                }
                box.lastSeq = r.seq
            }
            // Enregistré avant l'acquittement : en cas d'échec de l'acquittement, rien n'est perdu.
            save()
            client.ack(box.boxId, box.readSeed, box.lastSeq)
            if (!more) return rejected
        }
    }

    /** Réinitialise la session chiffrée (à faire des deux côtés). */
    @Synchronized
    fun resetSession(name: String) {
        contact(name).session = null
        save()
    }

    /** Supprime le contact, son historique et sa boîte sur le relais. */
    @Synchronized
    fun removeContact(name: String) {
        val c = contact(name)
        c.myBox?.let { clientFor(it.relay).deleteBox(it.boxId, it.readSeed) }
        state.contacts.remove(name)
        save()
    }

    companion object {
        /** Teste le relais, puis crée une nouvelle identité. */
        fun createIdentity(relay: RelayRef, clientFor: (RelayRef) -> RelayClient = { RelayClient(it) }): AppState {
            clientFor(relay).check()
            return AppState.newIdentity(relay)
        }
    }
}
