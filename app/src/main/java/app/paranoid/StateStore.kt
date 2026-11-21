package app.paranoid

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.AtomicFile
import app.paranoid.core.Aead
import app.paranoid.core.AppState
import app.paranoid.core.LockBox
import app.paranoid.core.LockThrottle
import app.paranoid.core.randomBytes
import app.paranoid.core.tr
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

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

    @Synchronized
    fun beginNew() {
        dek = randomBytes(32)
    }

    @Synchronized
    fun openLegacy(): AppState {
        val raw = legacyDek.readFully()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keystoreKey(create = false), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        c.updateAAD(AAD_LEGACY_DEK)
        dek = c.doFinal(raw.copyOfRange(12, raw.size))
        val data = legacyState.readFully()
        val plain = Aead.open(dek!!, data.copyOfRange(0, 12), data.copyOfRange(12, data.size), AAD_STATE)
            ?: error(tr("ancien état illisible", "unreadable old state"))
        return AppState.decode(plain)
    }

    @Synchronized
    fun setCodes(state: AppState, unlockCode: String, duressCode: String) {
        val key = dek ?: error(tr("aucune clé de données en mémoire", "no data key in memory"))
        val box = LockBox.create(key, unlockCode, duressCode)
        save(state)
        writeBox(box)
        resetAttempts()
        legacyDek.delete()
        legacyState.delete()
    }

    @Synchronized
    fun changeCodes(current: String, unlockCode: String, duressCode: String): UnlockResult {
        LockBox.validateCodes(unlockCode, duressCode)
        val r = unlock(current)
        if (r is UnlockResult.Unlocked) writeBox(LockBox.create(dek!!, unlockCode, duressCode))
        return r
    }

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
        val key = dek ?: error(tr("coffre verrouillé", "vault locked"))
        val raw = stateFile.readFully()
        val plain = Aead.open(key, raw.copyOfRange(0, 12), raw.copyOfRange(12, raw.size), AAD_STATE)
            ?: error(tr("état illisible (clé incorrecte ou fichier altéré)", "unreadable state (wrong key or altered file)"))
        return AppState.decode(plain)
    }

    @Synchronized
    fun save(state: AppState) {
        val key = dek ?: error(tr("coffre verrouillé", "vault locked"))
        val nonce = randomBytes(12)
        write(stateFile, nonce + Aead.seal(key, nonce, state.encode(), AAD_STATE))
    }

    @Synchronized
    fun lock() {
        dek?.fill(0)
        dek = null
    }

    @Synchronized
    fun wipe() {
        runCatching { keyStore().deleteEntry(ALIAS) }
        lock()
        for (f in listOf(stateFile, lockFile, attemptsFile, legacyDek, legacyState)) runCatching { f.delete() }
    }

    @Synchronized
    fun keyProtection(): String {
        val key = keyStore().getKey(ALIAS, null) as? SecretKey ?: return "clé absente"
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> tr("StrongBox (puce de sécurité dédiée)", "StrongBox (dedicated security chip)")
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> tr("TEE (zone sécurisée du processeur)", "TEE (processor secure area)")
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> tr("Logiciel (pas de matériel sécurisé)", "Software (no secure hardware)")
            else -> tr("Inconnue", "Unknown")
        }
    }

    private fun writeBox(box: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keystoreKey(create = true))
        c.updateAAD(AAD_LOCK)
        write(lockFile, c.iv + c.doFinal(box))
    }

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

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun keystoreKey(create: Boolean): SecretKey {
        (keyStore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { tr("clé de l'appareil absente : données illisibles", "device key missing: unreadable data") }
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
