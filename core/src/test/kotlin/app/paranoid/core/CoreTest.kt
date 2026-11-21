package app.paranoid.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private inline fun <reified T : Throwable> expect(block: () -> Unit): T {
    try {
        block()
    } catch (t: Throwable) {
        if (t is T) return t
        fail("exception ${T::class.simpleName} attendue, obtenu $t")
    }
    fail("exception ${T::class.simpleName} attendue")
    throw IllegalStateException()
}

private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

class CryptoTest {
    @Test
    fun hkdfRfc5869Case1() {
        val okm = hkdf(
            unhex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            unhex("000102030405060708090a0b0c"),
            unhex("f0f1f2f3f4f5f6f7f8f9"),
            42,
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            hex(okm),
        )
    }

    @Test
    fun x25519Rfc7748() {
        val a = unhex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bPub = unhex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", hex(X25519.publicOf(a)))
        assertEquals("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742", hex(X25519.agree(a, bPub)))
        expect<CryptoException> { X25519.agree(a, ByteArray(32)) }
    }

    @Test
    fun ed25519Rfc8032Test1() {
        val seed = unhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", hex(Ed25519.publicOf(seed)))
        val sig = Ed25519.sign(seed, ByteArray(0))
        assertEquals(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            hex(sig),
        )
        assertTrue(Ed25519.verify(Ed25519.publicOf(seed), ByteArray(0), sig))
        assertFalse(Ed25519.verify(Ed25519.publicOf(seed), byteArrayOf(1), sig))
    }

    @Test
    fun fingerprintAndSafetyNumber() {
        val fp = "FB:2B:01:81:C4:A7:E4:EA:3C:C9:DE:31:EA:A4:96:5A:D4:7E:C0:D6:F6:B7:4B:91:4F:DD:FB:FC:89:C6:B0:EA"
        assertEquals(fp, formatFingerprint(parseFingerprint(fp)))
        expect<IllegalArgumentException> { parseFingerprint("FB:2B") }
        assertEquals(24, safetyNumber(ByteArray(32)).length)
    }
}

class InviteTest {
    @Test
    fun roundTrip() {
        val inv = Invite(randomBytes(32), randomBytes(32), listOf(InviteRelay("https://192.168.1.252:8443", randomBytes(32), randomBytes(16))), randomBytes(32))
        val back = Invite.decode(inv.encode())
        assertArrayEquals(inv.ik, back.ik)
        assertArrayEquals(inv.xk, back.xk)
        assertEquals(inv.relays[0].url, back.relays[0].url)
        assertArrayEquals(inv.relays[0].writeId, back.relays[0].writeId)
        assertArrayEquals(inv.secret, back.secret)
    }

    @Test
    fun rejectsInvalid() {
        val good = Invite(randomBytes(32), randomBytes(32), listOf(InviteRelay("https://x:1", randomBytes(32), randomBytes(16))), randomBytes(32))
        for (bad in listOf(
            "", "!!!", b64("{".toByteArray()),
            b64("""{"v":1,"ik":"${b64(good.ik)}","xk":"${b64(good.xk)}","r":[{"u":"https://x:1","fp":"${b64(randomBytes(32))}","w":"${b64(randomBytes(16))}"}]}""".toByteArray()),
            Invite(good.ik, good.xk, emptyList(), good.secret).encode(),
            Invite(good.ik, good.xk, listOf(InviteRelay("http://x:1", randomBytes(32), randomBytes(16))), good.secret).encode(),
            Invite(good.ik.copyOf(31), good.xk, good.relays, good.secret).encode(),
            Invite(good.ik, good.xk, good.relays, randomBytes(16)).encode(),
        )) {
            expect<InviteException> { Invite.decode(bad) }
        }
    }
}

class PairingTest {
    private val inviterSeed = Ed25519.newSeed()
    private val inviterIk = Ed25519.publicOf(inviterSeed)
    private val inviterX = X25519.generatePrivate()
    private val scannerSeed = Ed25519.newSeed()
    private val scannerX = X25519.generatePrivate()
    private val secret = randomBytes(PAIR_SECRET_LEN)
    private val payload = PairPayload(Ed25519.publicOf(scannerSeed), X25519.publicOf(scannerX), randomBytes(16), RelayRef("https://192.168.1.252:8443", randomBytes(32)))

    @Test
    fun roundTrip() {
        val blob = Pairing.seal(scannerSeed, inviterIk, X25519.publicOf(inviterX), secret, payload)
        assertEquals(BLOB_SIZE, blob.size)
        assertEquals(PAIR_VERSION, blob[0])
        val p = Pairing.open(inviterX, inviterIk, secret, blob)!!
        assertArrayEquals(payload.ik, p.ik)
        assertArrayEquals(payload.xk, p.xk)
        assertArrayEquals(payload.writeId, p.writeId)
        assertEquals(payload.relay.url, p.relay.url)
        assertArrayEquals(payload.relay.fp, p.relay.fp)
    }

    @Test
    fun rejections() {
        val blob = Pairing.seal(scannerSeed, inviterIk, X25519.publicOf(inviterX), secret, payload)
        assertTrue(Pairing.open(inviterX, inviterIk, randomBytes(PAIR_SECRET_LEN), blob) == null)
        assertTrue(Pairing.open(X25519.generatePrivate(), inviterIk, secret, blob) == null)
        for (i in listOf(0, 1, 40, 500, BLOB_SIZE - 1)) {
            val bad = blob.copyOf(); bad[i] = (bad[i].toInt() xor 1).toByte()
            assertTrue(Pairing.open(inviterX, inviterIk, secret, bad) == null)
        }
        val mallory = Ed25519.newSeed()
        val forged = PairPayload(payload.ik, X25519.publicOf(X25519.generatePrivate()), payload.writeId, payload.relay)
        assertTrue(Pairing.open(inviterX, inviterIk, secret, Pairing.seal(mallory, inviterIk, X25519.publicOf(inviterX), secret, forged)) == null)
    }
}

private class Peer(val name: String) {
    val seed = Ed25519.newSeed()
    val ik = Ed25519.publicOf(seed)
    val x = X25519.generatePrivate()
    lateinit var env: DrEnv
    lateinit var s: Session

    fun send(text: String): ByteArray {
        val blob = s.encrypt(env, encodeInner(Plain(TYPE_TEXT, randomBytes(16), 0, text.toByteArray())))
        assertEquals(BLOB_SIZE, blob.size)
        return blob
    }

    fun recv(blob: ByteArray, want: String) {
        assertEquals("$name reçoit", want, decodeInner(s.decrypt(env, blob)).body.toString(Charsets.UTF_8))
    }

    fun recvFails(blob: ByteArray): CryptoException {
        val before = StateCodec.encode(AppState(seed, x, RelayRef("https://x", ByteArray(32))).also {
            it.contacts["p"] = Contact("p").also { c -> c.session = s }
        })
        val e = expect<CryptoException> { s.decrypt(env, blob) }
        val after = StateCodec.encode(AppState(seed, x, RelayRef("https://x", ByteArray(32))).also {
            it.contacts["p"] = Contact("p").also { c -> c.session = s }
        })
        assertArrayEquals("$name : état modifié après un échec", before, after)
        return e
    }
}

private fun peers(): Pair<Peer, Peer> {
    val a = Peer("a")
    val b = Peer("b")
    a.env = DrEnv.create(a.ik, a.x, b.ik, X25519.publicOf(b.x))
    b.env = DrEnv.create(b.ik, b.x, a.ik, X25519.publicOf(a.x))
    assertArrayEquals(a.env.sk, b.env.sk)
    assertArrayEquals(a.env.ad, b.env.ad)
    a.s = Session.create(a.env)
    b.s = Session.create(b.env)
    assertTrue(a.s.initiator != b.s.initiator)
    return if (a.s.initiator) a to b else b to a
}

class DoubleRatchetTest {
    @Test
    fun initiatorFirst() {
        val (a, b) = peers()
        repeat(4) { r ->
            repeat(3) { b.recv(a.send("a$r.$it"), "a$r.$it") }
            repeat(2) { a.recv(b.send("b$r.$it"), "b$r.$it") }
        }
    }

    @Test
    fun responderFirstThenSwitch() {
        val (a, b) = peers()
        val m1 = b.send("boot 1")
        assertEquals(DR_TYPE_BOOT, m1[1])
        a.recv(m1, "boot 1")
        b.recv(a.send("réponse"), "réponse")
        val m2 = b.send("ratchet")
        assertEquals(DR_TYPE_RATCHET, m2[1])
        a.recv(m2, "ratchet")
    }

    @Test
    fun simultaneousFirst() {
        val (a, b) = peers()
        val ma = a.send("de a")
        val mb = b.send("de b")
        a.recv(mb, "de b")
        b.recv(ma, "de a")
        a.recv(b.send("b2"), "b2")
        b.recv(a.send("a2"), "a2")
    }

    @Test
    fun outOfOrderAndLost() {
        val (a, b) = peers()
        val m = (0 until 5).map { a.send("m$it") }
        b.recv(m[3], "m3")
        b.recv(m[0], "m0")
        a.recv(b.send("r"), "r")
        b.recv(a.send("n0"), "n0")
        b.recv(m[4], "m4")
        b.recv(m[1], "m1")
        b.recv(m[2], "m2")
        assertEquals(0, b.s.skipped.size)
    }

    @Test
    fun replayAndTampering() {
        val (a, b) = peers()
        val m = a.send("une fois")
        for (i in listOf(1, 2, 20, 34, 38, DR_HEADER_LEN, BLOB_SIZE - 1)) {
            val bad = m.copyOf(); bad[i] = (bad[i].toInt() xor 0x40).toByte()
            b.recvFails(bad)
        }
        b.recv(m, "une fois")
        assertTrue(b.recvFails(m) is ReplayException)
    }

    @Test
    fun bootReplayAfterSwitch() {
        val (a, b) = peers()
        val m0 = b.send("b0"); val m1 = b.send("b1"); val m2 = b.send("b2")
        a.recv(m0, "b0")
        a.recv(m2, "b2")
        b.recv(a.send("réponse"), "réponse")
        a.recv(b.send("ratchet"), "ratchet")
        assertTrue(a.recvFails(m0) is ReplayException)
        a.recv(m1, "b1")
    }

    @Test
    fun postCompromise() {
        val (a, b) = peers()
        b.recv(a.send("avant"), "avant")
        val stolen = b.s.copy()
        b.recv(a.send("m1"), "m1")
        a.recv(b.send("r1"), "r1")
        b.recv(a.send("m2"), "m2")
        a.recv(b.send("r2"), "r2")
        val m3 = a.send("m3")
        b.recv(m3, "m3")
        expect<CryptoException> { stolen.decrypt(b.env, m3) }
    }

    @Test
    fun skipLimit() {
        val (a, b) = peers()
        repeat(1001) { a.send("x") }
        assertTrue(b.recvFails(a.send("trop loin")) is SkipException)
    }

    @Test
    fun stateCodecRoundTrip() {
        val (a, b) = peers()
        val m = a.send("1")
        val st = AppState(b.seed, b.x, RelayRef("https://x:1", randomBytes(32)))
        st.contacts["a"] = Contact("a").also { it.session = b.s; it.ik = a.ik; it.messages.add(ChatMessage(randomBytes(16), true, 5, "é€", MessageStatus.SENT)) }
        val back = AppState.decode(st.encode())
        assertArrayEquals(st.encode(), back.encode())
        b.s = back.contacts["a"]!!.session!!
        b.recv(m, "1")
        assertEquals("é€", back.contacts["a"]!!.messages[0].text)
    }

    @Test
    fun alertAndBurnedSurviveCodec() {
        val (a, b) = peers()
        val blob = a.s.encrypt(a.env, encodeInner(Plain(TYPE_ALERT, randomBytes(16), 7, ByteArray(0))))
        val p = decodeInner(b.s.decrypt(b.env, blob))
        assertEquals(TYPE_ALERT, p.type)
        assertEquals(0, p.body.size)
        val st = AppState(b.seed, b.x, RelayRef("https://x:1", randomBytes(32)))
        st.contacts["a"] = Contact("a").also { it.burned = true; it.messages.add(ChatMessage(p.id, false, 7, "", MessageStatus.ALERT)) }
        val back = AppState.decode(st.encode())
        assertTrue(back.contacts["a"]!!.burned)
        val blob3 = a.s.encrypt(a.env, encodeInner(Plain(TYPE_RELAY_CHANGE, randomBytes(16), 8, ByteArray(0))))
        assertEquals(TYPE_RELAY_CHANGE, decodeInner(b.s.decrypt(b.env, blob3)).type)
        st.contacts["a"]!!.messages.add(ChatMessage(randomBytes(16), false, 8, "", MessageStatus.RELAY_CHANGE))
        assertEquals(MessageStatus.RELAY_CHANGE, AppState.decode(st.encode()).contacts["a"]!!.messages[1].status)
        assertEquals(MessageStatus.ALERT, back.contacts["a"]!!.messages[0].status)
    }

    @Test
    fun maxText() {
        val (a, b) = peers()
        val long = "z".repeat(MAX_TEXT)
        b.recv(a.send(long), long)
        expect<IllegalArgumentException> { encodeInner(Plain(TYPE_TEXT, randomBytes(16), 0, ByteArray(MAX_TEXT + 1))) }
    }
}

class LockBoxTest {
    private val fast = LockBox.Params(memoryKiB = 1024, iterations = 1)

    @Test
    fun opensWithBothCodesAndTellsThemApart() {
        val dek = randomBytes(32)
        repeat(4) {
            val box = LockBox.create(dek, "123456", "654321", fast)
            val u = LockBox.open(box, "123456") as LockBox.Result.Opened
            assertEquals(LockBox.Role.UNLOCK, u.role)
            assertArrayEquals(dek, u.dataKey)
            val d = LockBox.open(box, "654321") as LockBox.Result.Opened
            assertEquals(LockBox.Role.DURESS, d.role)
            assertArrayEquals(dek, d.dataKey)
            assertTrue(LockBox.open(box, "000000") is LockBox.Result.Wrong)
            assertTrue(LockBox.open(box, "") is LockBox.Result.Wrong)
        }
    }

    @Test
    fun rejectsBadCodes() {
        expect<IllegalArgumentException> { LockBox.create(randomBytes(32), "12345", "654321", fast) }
        expect<IllegalArgumentException> { LockBox.create(randomBytes(32), "123456", "123456", fast) }
    }

    @Test
    fun tamperedBoxDoesNotOpen() {
        val box = LockBox.create(randomBytes(32), "123456", "654321", fast)
        val bad = box.copyOf(); bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 1).toByte()
        assertTrue(LockBox.open(bad, "123456") is LockBox.Result.Wrong || LockBox.open(bad, "654321") is LockBox.Result.Wrong)
    }

    @Test
    fun throttle() {
        assertEquals(0L, LockThrottle.delaySeconds(4))
        assertEquals(30L, LockThrottle.delaySeconds(5))
        assertEquals(60L, LockThrottle.delaySeconds(6))
        assertEquals(3600L, LockThrottle.delaySeconds(40))
    }

    @Test
    fun defaultParamsTiming() {
        val t0 = System.nanoTime()
        val box = LockBox.create(randomBytes(32), "123456", "654321")
        val t1 = System.nanoTime()
        LockBox.open(box, "123456")
        val t2 = System.nanoTime()
        println("Argon2id 64 Mio x3 : création ${(t1 - t0) / 1_000_000} ms, ouverture ${(t2 - t1) / 1_000_000} ms (JVM)")
    }
}

class HistoryTest {
    private fun stateWith(now: Long): AppState {
        val st = AppState.newIdentity(RelayRef("https://192.168.1.252:8443", randomBytes(32)))
        val c = Contact("a")
        val day = 86_400L
        c.messages.add(ChatMessage(randomBytes(16), true, now - 10 * day, "vieux", MessageStatus.SENT))
        c.messages.add(ChatMessage(randomBytes(16), false, now - 10 * day, "", MessageStatus.ALERT))
        c.messages.add(ChatMessage(randomBytes(16), false, now - 2 * day, "avant-hier", MessageStatus.RECEIVED))
        c.messages.add(ChatMessage(randomBytes(16), false, now - 3600, "récent", MessageStatus.RECEIVED))
        st.contacts["a"] = c
        return st
    }

    @Test
    fun purgeKeepsAlertsAndRecent() {
        val now = 1_800_000_000L
        val st = stateWith(now)
        var saves = 0
        val m = Messenger(st, persist = { saves++ })
        assertEquals(0, m.purgeHistory(now))
        assertEquals(2, m.countOlderThan(86_400, now))
        st.historySeconds = 7 * 86_400L
        assertEquals(1, m.purgeHistory(now))
        assertEquals(listOf("", "avant-hier", "récent"), st.contacts["a"]!!.messages.map { it.text })
        st.historySeconds = 86_400L
        assertEquals(1, m.purgeHistory(now))
        assertEquals(listOf(MessageStatus.ALERT, MessageStatus.RECEIVED), st.contacts["a"]!!.messages.map { it.status })
        assertEquals(2, saves)
    }

    @Test
    fun retentionIsSaved() {
        val st = stateWith(1_800_000_000L)
        st.historySeconds = 3 * 86_400L
        assertEquals(3 * 86_400L, AppState.decode(st.encode()).historySeconds)
    }
}

class DirectoryTest {
    private val fpA = "FB:2B:01:81:C4:A7:E4:EA:3C:C9:DE:31:EA:A4:96:5A:D4:7E:C0:D6:F6:B7:4B:91:4F:DD:FB:FC:89:C6:B0:EA"

    @Test
    fun parseKeepsValidEntriesOnlineFirst() {
        val json = """{"v":1,"relays":[
            {"name":"vieux","url":"https://203.0.113.5","fp":"$fpA","version":"1.1.2","last_seen":100,"online":false},
            {"name":"nom invalide","url":"https://x.example","fp":"$fpA","version":"1","last_seen":1,"online":true},
            {"name":"http","url":"http://x.example","fp":"$fpA","version":"1","last_seen":1,"online":true},
            {"name":"court","url":"https://x.example","fp":"FB:2B","version":"1","last_seen":1,"online":true},
            {"name":"chemin","url":"https://x.example/api","fp":"$fpA","version":"1","last_seen":1,"online":true},
            {"name":"neuf","url":"https://relay.example.org:8443","fp":"${fpA.replace(":", "").lowercase()}","version":"1.1.2","last_seen":200,"online":true}]}"""
        val l = RelayDirectory.parse(json)
        assertEquals(listOf("neuf", "vieux"), l.map { it.name })
        assertTrue(l[0].online)
        assertEquals(fpA, formatFingerprint(l[0].fp))
        assertEquals("https://relay.example.org:8443", l[0].url)
    }

    @Test
    fun parseRejectsUnknownVersion() {
        try {
            RelayDirectory.parse("""{"v":2,"relays":[]}""")
            fail("version 2 acceptée")
        } catch (_: IllegalArgumentException) {
        }
    }
}
