package app.paranoid

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.AtomicFile
import app.paranoid.core.Aead
import app.paranoid.core.AppState
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
 * Chiffrement en enveloppe :
 *  - une clé AES-256 non exportable dans l'Android Keystore (StrongBox si disponible) ;
 *  - elle chiffre une clé de données aléatoire (dek.bin) ;
 *  - la clé de données chiffre l'état (state.bin) en AES-256-GCM.
 * Détruire l'entrée Keystore rend tout illisible instantanément (base du futur code de destruction).
 */
class StateStore(context: Context) {
    private val dir = context.filesDir
    private val dekFile = AtomicFile(File(dir, "dek.bin"))
    private val stateFile = AtomicFile(File(dir, "state.bin"))
    private var dek: ByteArray? = null

    val exists: Boolean get() = stateFile.baseFile.exists()

    @Synchronized
    fun load(): AppState? {
        if (!exists) return null
        val raw = stateFile.readFully()
        check(raw.size > 12) { "état corrompu" }
        val key = dataKey(create = false) ?: error("clé de chiffrement absente : état illisible")
        val plain = Aead.open(key, raw.copyOfRange(0, 12), raw.copyOfRange(12, raw.size), AAD_STATE)
            ?: error("état illisible (clé incorrecte ou fichier altéré)")
        return AppState.decode(plain)
    }

    @Synchronized
    fun save(state: AppState) {
        val key = dataKey(create = true)!!
        val nonce = randomBytes(12)
        write(stateFile, nonce + Aead.seal(key, nonce, state.encode(), AAD_STATE))
    }

    /** Efface l'état et détruit la clé Keystore. Irréversible. */
    @Synchronized
    fun wipe() {
        runCatching { keyStore().deleteEntry(ALIAS) }
        dek?.fill(0)
        dek = null
        stateFile.delete()
        dekFile.delete()
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

    private fun dataKey(create: Boolean): ByteArray? {
        dek?.let { return it }
        if (dekFile.baseFile.exists()) {
            val raw = dekFile.readFully()
            val ks = keyStore().getKey(ALIAS, null) as? SecretKey ?: return null
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, ks, GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            c.updateAAD(AAD_DEK)
            return c.doFinal(raw.copyOfRange(12, raw.size)).also { dek = it }
        }
        if (!create) return null
        val fresh = randomBytes(32)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keystoreKey())
        c.updateAAD(AAD_DEK)
        val wrapped = c.doFinal(fresh)
        write(dekFile, c.iv + wrapped)
        dek = fresh
        return fresh
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun keystoreKey(): SecretKey {
        (keyStore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
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
        val AAD_DEK = "paranoid/dek/v1".toByteArray()
        val AAD_STATE = "paranoid/state/v1".toByteArray()
    }
}
