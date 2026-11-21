package app.paranoid.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class MyBox(val relay: RelayRef, val boxId: ByteArray, val writeId: ByteArray, val readSeed: ByteArray, var lastSeq: Long = 0)

class TheirBox(val relay: RelayRef, val writeId: ByteArray)

enum class MessageStatus { SENDING, SENT, FAILED, RECEIVED, ALERT, RELAY_CHANGE }

class ChatMessage(val id: ByteArray, val outgoing: Boolean, val ts: Long, val text: String, var status: MessageStatus)

class Contact(val name: String) {
    var ik: ByteArray? = null
    var xk: ByteArray? = null
    var myBox: MyBox? = null
    var their: TheirBox? = null
    var session: Session? = null
    val seen: ArrayDeque<String> = ArrayDeque()
    val messages: MutableList<ChatMessage> = mutableListOf()

    var burned: Boolean = false

    var pairSecret: ByteArray? = null

    val ready: Boolean get() = ik != null && myBox != null && their != null
}

class AppState(
    val identitySeed: ByteArray,
    val x25519Priv: ByteArray,
    val relay: RelayRef,
    val contacts: MutableMap<String, Contact> = LinkedHashMap(),
) {
    var historySeconds: Long = 0

    val ik: ByteArray get() = Ed25519.publicOf(identitySeed)
    val xk: ByteArray get() = X25519.publicOf(x25519Priv)

    fun encode(): ByteArray = StateCodec.encode(this)

    companion object {
        fun newIdentity(relay: RelayRef) = AppState(Ed25519.newSeed(), X25519.generatePrivate(), relay)
        fun decode(data: ByteArray): AppState = StateCodec.decode(data)
    }
}

internal object StateCodec {
    private const val MAGIC = 0x50524E44
    private const val VERSION = 6

    fun encode(s: AppState): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { o ->
            o.writeInt(MAGIC); o.writeInt(VERSION)
            o.bytes(s.identitySeed); o.bytes(s.x25519Priv); o.relay(s.relay)
            o.writeInt(s.contacts.size)
            for (c in s.contacts.values) o.contact(c)
            o.writeLong(s.historySeconds)
        }
        return bos.toByteArray()
    }

    fun decode(data: ByteArray): AppState {
        DataInputStream(ByteArrayInputStream(data)).use { i ->
            require(i.readInt() == MAGIC) { tr("état illisible", "unreadable state") }
            val version = i.readInt()
            require(version in 1..VERSION) { tr("version d'état inconnue", "unknown state version") }
            val s = AppState(i.bytes(), i.bytes(), i.relay())
            repeat(i.readInt()) { val c = i.contact(version); s.contacts[c.name] = c }
            if (version >= 6) s.historySeconds = i.readLong().coerceAtLeast(0)
            return s
        }
    }

    private fun DataOutputStream.bytes(b: ByteArray) { writeInt(b.size); write(b) }
    private fun DataInputStream.bytes(): ByteArray {
        val n = readInt(); require(n in 0..(1 shl 20)); return ByteArray(n).also { readFully(it) }
    }
    private fun DataOutputStream.opt(b: ByteArray?) { writeBoolean(b != null); if (b != null) bytes(b) }
    private fun DataInputStream.opt(): ByteArray? = if (readBoolean()) bytes() else null

    private fun DataOutputStream.relay(r: RelayRef) { writeUTF(r.url); bytes(r.fp) }
    private fun DataInputStream.relay() = RelayRef(readUTF(), bytes())

    private fun DataOutputStream.chain(c: ChainState?) {
        writeBoolean(c != null); if (c != null) { bytes(c.dh); bytes(c.ck); writeLong(c.n) }
    }
    private fun DataInputStream.chain(): ChainState? = if (readBoolean()) ChainState(bytes(), bytes(), readLong()) else null

    private fun DataOutputStream.session(s: Session?) {
        writeBoolean(s != null)
        if (s == null) return
        writeBoolean(s.initiator); bytes(s.rk); bytes(s.dhsPriv); opt(s.dhr); opt(s.cks); opt(s.ckr)
        writeLong(s.ns); writeLong(s.nr); writeLong(s.pn)
        writeInt(s.skipped.size)
        for (k in s.skipped) { bytes(k.dh); writeLong(k.n); bytes(k.mk) }
        chain(s.bootSend); chain(s.bootRecv)
    }

    private fun DataInputStream.session(): Session? {
        if (!readBoolean()) return null
        val s = Session(readBoolean(), bytes(), bytes(), opt(), opt(), opt(), readLong(), readLong(), readLong())
        repeat(readInt()) { s.skipped.add(SkippedKey(bytes(), readLong(), bytes())) }
        s.bootSend = chain(); s.bootRecv = chain()
        return s
    }

    private fun DataOutputStream.contact(c: Contact) {
        writeUTF(c.name); opt(c.ik); opt(c.xk)
        val b = c.myBox
        writeBoolean(b != null)
        if (b != null) { relay(b.relay); bytes(b.boxId); bytes(b.writeId); bytes(b.readSeed); writeLong(b.lastSeq) }
        val t = c.their
        writeBoolean(t != null)
        if (t != null) { relay(t.relay); bytes(t.writeId) }
        session(c.session)
        writeInt(c.seen.size); for (id in c.seen) writeUTF(id)
        writeInt(c.messages.size)
        for (m in c.messages) {
            bytes(m.id); writeBoolean(m.outgoing); writeLong(m.ts); bytes(m.text.toByteArray()); writeInt(m.status.ordinal)
        }
        writeBoolean(c.burned)
        opt(c.pairSecret)
    }

    private fun DataInputStream.contact(version: Int): Contact {
        val c = Contact(readUTF())
        c.ik = opt(); c.xk = opt()
        if (readBoolean()) c.myBox = MyBox(relay(), bytes(), bytes(), bytes(), readLong())
        if (readBoolean()) c.their = TheirBox(relay(), bytes())
        c.session = session()
        repeat(readInt()) { c.seen.addLast(readUTF()) }
        repeat(readInt()) {
            c.messages.add(
                ChatMessage(bytes(), readBoolean(), readLong(), bytes().toString(Charsets.UTF_8), MessageStatus.entries[readInt()]),
            )
        }
        if (version < 5) readInt()
        if (version >= 2) c.burned = readBoolean()
        if (version == 3 && readBoolean()) readUTF()
        if (version >= 5) c.pairSecret = opt()
        return c
    }
}
