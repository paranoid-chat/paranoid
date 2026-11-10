package app.paranoid

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.AtomicFile
import app.paranoid.core.Aead
import app.paranoid.core.AppState
import app.paranoid.core.LockBox
import app.paranoid.core.LockThrottle
import app.paranoid.core.randomBytes
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stockage chiffré de l'état (identité, contacts, sessions, historique).
 *
 *  - state.bin : l'état, en AES-256-GCM avec une clé de données aléatoire ;
 *  - lock.bin  : la clé de données dans un [LockBox] (code de déverrouillage OU code de
 *                destruction, Argon2id), lui-même chiffré par une clé non exportable du Keystore
 *                (StrongBox si disponible) : sans le téléphone, impossible de tenter les codes ;
 *  - attempts  : compteur d'échecs pour la temporisation.
 *
 * Ancien format (v0.1) : dek.bin = clé de données chiffrée par le Keystore seul. Il est migré
 * dès que l'utilisateur choisit ses codes.
 *
 * Détruire l'entrée Keystore rend tout illisible instantanément.
 */
class StateStore(context: Context) {
    enum class Status { EMPTY, LEGACY, LOCKED }

    sealed interface UnlockResult {
        data object Unlocked : UnlockResult
        data object Duress : UnlockResult
        class Wrong(val waitSeconds: Long) : UnlockResult
        class Throttled(val waitSeconds: Long) : UnlockResult
    }

    private val dir = context.noBackupFilesDir
    private val stateFile = AtomicFile(File(dir, "state.bin"))
    private val lockFile = AtomicFile(File(dir, "lock.bin"))
    private val attemptsFile = AtomicFile(File(dir, "attempts"))
    private val legacyDek = AtomicFile(File(context.filesDir, "dek.bin"))
    private val legacyState = AtomicFile(File(context.filesDir, "state.bin"))
    private var dek: ByteArray? = null

    val unlocked: Boolean get() = dek != null

    @Synchronized
    fun status(): Status = when {
        lockFile.baseFile.exists() -> Status.LOCKED
        legacyDek.baseFile.exists() && legacyState.baseFile.exists() -> Status.LEGACY
        else -> Status.EMPTY
    }

    /** Nouvelle identité : la clé de données est créée en mémoire, rien n'est écrit avant [setCodes]. */
    @Synchronized
    fun beginNew() {
        dek = randomBytes(32)
    }

    /** v0.1 : ouvre l'ancien état (Keystore seul) ; les codes doivent ensuite être définis. */
    @Synchronized
    fun openLegacy(): AppState {
        val raw = legacyDek.readFully()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keystoreKey(create = false), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        c.updateAAD(AAD_LEGACY_DEK)
        dek = c.doFinal(raw.copyOfRange(12, raw.size))
        val data = legacyState.readFully()
        val plain = Aead.open(dek!!, data.copyOfRange(0, 12), data.copyOfRange(12, data.size), AAD_STATE)
            ?: error("ancien état illisible")
        return AppState.decode(plain)
    }

    /** Enregistre l'état et protège la clé de données par les deux codes. */
    @Synchronized
    fun setCodes(state: AppState, unlockCode: String, duressCode: String) {
        val key = dek ?: error("aucune clé de données en mémoire")
        val box = LockBox.create(key, unlockCode, duressCode)
        save(state)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keystoreKey(create = true))
        c.updateAAD(AAD_LOCK)
        write(lockFile, c.iv + c.doFinal(box))
        resetAttempts()
        legacyDek.delete()
        legacyState.delete()
    }

    /** Essaie un code. Les deux emplacements du coffre sont toujours essayés (temps constant). */
    @Synchronized
    fun unlock(code: String): UnlockResult {
        val (failures, last) = attempts()
        val wait = LockThrottle.delaySeconds(failures) - (System.currentTimeMillis() / 1000 - last)
        if (wait > 0) return UnlockResult.Throttled(wait)
        val raw = lockFile.readFully()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keystoreKey(create = false), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        c.updateAAD(AAD_LOCK)
        val box = c.doFinal(raw.copyOfRange(12, raw.size))
        return when (val r = LockBox.open(box, code)) {
            is LockBox.Result.Opened -> {
                dek = r.dataKey
                resetAttempts()
                if (r.role == LockBox.Role.DURESS) UnlockResult.Duress else UnlockResult.Unlocked
            }
            LockBox.Result.Wrong -> {
                writeAttempts(failures + 1)
                UnlockResult.Wrong(LockThrottle.delaySeconds(failures + 1))
            }
        }
    }

    @Synchronized
    fun load(): AppState {
        val key = dek ?: error("coffre verrouillé")
        val raw = stateFile.readFully()
        val plain = Aead.open(key, raw.copyOfRange(0, 12), raw.copyOfRange(12, raw.size), AAD_STATE)
            ?: error("état illisible (clé incorrecte ou fichier altéré)")
        return AppState.decode(plain)
    }

    @Synchronized
    fun save(state: AppState) {
        val key = dek ?: error("coffre verrouillé")
        val nonce = randomBytes(12)
        write(stateFile, nonce + Aead.seal(key, nonce, state.encode(), AAD_STATE))
    }

    /** Oublie la clé de données (verrouillage). */
    @Synchronized
    fun lock() {
        dek?.fill(0)
        dek = null
    }

    /** Détruit la clé Keystore puis efface tous les fichiers. Irréversible. */
    @Synchronized
    fun wipe() {
        runCatching { keyStore().deleteEntry(ALIAS) }
        lock()
        for (f in listOf(stateFile, lockFile, attemptsFile, legacyDek, legacyState)) runCatching { f.delete() }
    }

    // ---------- fichiers ----------

    private fun write(f: AtomicFile, data: ByteArray) {
        val out = f.startWrite()
        try {
            out.write(data)
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
    }

    private fun attempts(): Pair<Int, Long> = runCatching {
        val p = attemptsFile.readFully().toString(Charsets.US_ASCII).trim().split(" ")
        p[0].toInt() to p[1].toLong()
    }.getOrDefault(0 to 0L)

    private fun writeAttempts(n: Int) =
        write(attemptsFile, "$n ${System.currentTimeMillis() / 1000}".toByteArray(Charsets.US_ASCII))

    private fun resetAttempts() {
        attemptsFile.delete()
    }

    // ---------- Keystore ----------

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun keystoreKey(create: Boolean): SecretKey {
        (keyStore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "clé de l'appareil absente : données illisibles" }
        return try {
            generate(strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(spec)
            generateKey()
        }
    }

    private companion object {
        const val ALIAS = "paranoid_master"
        val AAD_LEGACY_DEK = "paranoid/dek/v1".toByteArray()
        val AAD_LOCK = "paranoid/lock/v1".toByteArray()
        val AAD_STATE = "paranoid/state/v1".toByteArray()
    }
}
