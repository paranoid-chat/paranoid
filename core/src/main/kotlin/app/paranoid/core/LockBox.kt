package app.paranoid.core

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Coffre de la clé de données, ouvrable par deux codes :
 *  - le code de déverrouillage ;
 *  - le code de destruction, qui ouvre aussi la clé (il faut l'état pour envoyer les alertes)
 *    mais signale à l'appli qu'elle doit tout effacer.
 *
 * Chaque emplacement = Argon2id(code, sel) → AES-256-GCM(rôle ‖ clé de données). Le rôle est
 * chiffré et l'ordre des emplacements est aléatoire : rien ne distingue l'emplacement de
 * destruction de l'autre. Les deux dérivations sont toujours calculées, pour un temps constant.
 *
 * Sur Android, ce coffre est lui-même chiffré par une clé non exportable du Keystore : une copie
 * des fichiers ne suffit pas pour tenter les codes hors de l'appareil.
 */
object LockBox {
    enum class Role { UNLOCK, DURESS }

    sealed interface Result {
        class Opened(val role: Role, val dataKey: ByteArray) : Result
        data object Wrong : Result
    }

    class Params(val memoryKiB: Int = 65_536, val iterations: Int = 3, val parallelism: Int = 1)

    const val MIN_CODE_LENGTH = 6
    private const val MAGIC = 0x504C434B // "PLCK"
    private const val VERSION = 1
    private val AAD = "paranoid/lockbox/v1".toByteArray()

    fun validateCodes(unlock: String, duress: String) {
        require(unlock.length >= MIN_CODE_LENGTH) { tr("le code de déverrouillage doit faire au moins $MIN_CODE_LENGTH caractères", "the unlock code must be at least $MIN_CODE_LENGTH characters long") }
        require(duress.length >= MIN_CODE_LENGTH) { tr("le code de destruction doit faire au moins $MIN_CODE_LENGTH caractères", "the destruction code must be at least $MIN_CODE_LENGTH characters long") }
        require(unlock != duress) { tr("les deux codes doivent être différents", "the two codes must be different") }
    }

    fun create(dataKey: ByteArray, unlock: String, duress: String, params: Params = Params()): ByteArray {
        validateCodes(unlock, duress)
        require(dataKey.size == 32)
        val slots = mutableListOf(slot(Role.UNLOCK, unlock, dataKey, params), slot(Role.DURESS, duress, dataKey, params))
        if (rng.nextBoolean()) slots.reverse()
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { o ->
            o.writeInt(MAGIC); o.writeInt(VERSION)
            o.writeInt(params.memoryKiB); o.writeInt(params.iterations); o.writeInt(params.parallelism)
            for ((salt, nonce, ct) in slots) { o.write(salt); o.write(nonce); o.writeInt(ct.size); o.write(ct) }
        }
        return bos.toByteArray()
    }

    fun open(box: ByteArray, code: String): Result {
        val slots = ArrayList<Triple<ByteArray, ByteArray, ByteArray>>()
        val params: Params
        DataInputStream(ByteArrayInputStream(box)).use { i ->
            require(i.readInt() == MAGIC) { "coffre illisible" }
            require(i.readInt() == VERSION) { "version de coffre inconnue" }
            params = Params(i.readInt(), i.readInt(), i.readInt())
            repeat(2) {
                val salt = ByteArray(16).also { i.readFully(it) }
                val nonce = ByteArray(12).also { i.readFully(it) }
                val n = i.readInt(); require(n in 1..256)
                slots.add(Triple(salt, nonce, ByteArray(n).also { i.readFully(it) }))
            }
        }
        // Les deux emplacements sont toujours essayés : même durée quel que soit le code.
        var found: Result = Result.Wrong
        for ((salt, nonce, ct) in slots) {
            val key = derive(code, salt, params)
            val plain = Aead.open(key, nonce, ct, AAD)
            key.fill(0)
            if (plain != null && plain.size == 33 && found is Result.Wrong) {
                val role = if (plain[0].toInt() == 1) Role.DURESS else Role.UNLOCK
                found = Result.Opened(role, plain.copyOfRange(1, 33))
            }
            plain?.fill(0)
        }
        return found
    }

    private fun slot(role: Role, code: String, dataKey: ByteArray, params: Params): Triple<ByteArray, ByteArray, ByteArray> {
        val salt = randomBytes(16)
        val nonce = randomBytes(12)
        val key = derive(code, salt, params)
        val plain = cat(byteArrayOf(if (role == Role.DURESS) 1 else 0), dataKey)
        val ct = Aead.seal(key, nonce, plain, AAD)
        key.fill(0); plain.fill(0)
        return Triple(salt, nonce, ct)
    }

    private fun derive(code: String, salt: ByteArray, p: Params): ByteArray {
        val gen = Argon2BytesGenerator()
        gen.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(p.memoryKiB)
                .withIterations(p.iterations)
                .withParallelism(p.parallelism)
                .build(),
        )
        val out = ByteArray(32)
        val pw = code.toByteArray(Charsets.UTF_8)
        gen.generateBytes(pw, out)
        pw.fill(0)
        return out
    }
}

/** Temporisation des essais de code : rien jusqu'à 4 échecs, puis 30 s doublées à chaque échec (1 h max). */
object LockThrottle {
    fun delaySeconds(failures: Int): Long = if (failures < 5) 0 else minOf(3600L, 30L shl minOf(failures - 5, 7))
}
