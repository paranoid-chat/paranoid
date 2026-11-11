package app.paranoid

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.paranoid.core.AppState
import app.paranoid.core.Contact
import app.paranoid.core.LockBox
import app.paranoid.core.MAX_TEXT
import app.paranoid.core.MessageStatus
import app.paranoid.core.Messenger
import app.paranoid.core.RelayRef
import app.paranoid.core.b64
import app.paranoid.core.randomBytes
import app.paranoid.core.parseFingerprint
import app.paranoid.core.safetyNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Loading : Screen
    data object Setup : Screen
    data class SetCodes(val migrating: Boolean) : Screen
    data object ChangeCodes : Screen
    data object Locked : Screen
    data object Contacts : Screen
    data object AddContact : Screen
    data class Chat(val name: String) : Screen
    data class Fatal(val message: String) : Screen
}

data class ContactRow(
    val name: String,
    val ready: Boolean,
    val inviteShown: Boolean,
    val inviteScanned: Boolean,
    val safety: String?,
    val lastText: String?,
    val lastTs: Long,
    val pendingFromUnknown: Int,
    val burned: Boolean,
)

data class MessageRow(val key: String, val outgoing: Boolean, val text: String, val ts: Long, val status: MessageStatus)

data class AddFlow(val name: String = "", val invite: String? = null)

data class Ui(
    val screen: Screen = Screen.Loading,
    val mySafety: String = "",
    val relayUrl: String = "",
    val contacts: List<ContactRow> = emptyList(),
    val chat: List<MessageRow> = emptyList(),
    val add: AddFlow = AddFlow(),
    val busy: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val connectionError: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = StateStore(app)
    private var messenger: Messenger? = null

    /** État en attente des codes (nouvelle identité ou migration depuis la v0.1). */
    private var pending: AppState? = null
    private var duressJob: Job? = null
    private var autoLockJob: Job? = null

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    val maxText = MAX_TEXT
    val minCode = LockBox.MIN_CODE_LENGTH

    /** Enregistre l'état chiffré puis met l'affichage à jour (appelé par le Messenger, sous son verrou). */
    private fun persist(s: AppState) {
        store.save(s)
        refresh()
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (store.status()) {
                    StateStore.Status.EMPTY -> _ui.update { it.copy(screen = Screen.Setup) }
                    StateStore.Status.LOCKED -> _ui.update { it.copy(screen = Screen.Locked) }
                    StateStore.Status.LEGACY -> {
                        pending = store.openLegacy()
                        _ui.update { it.copy(screen = Screen.SetCodes(migrating = true)) }
                    }
                }
            } catch (e: Exception) {
                _ui.update { it.copy(screen = Screen.Fatal(e.message ?: e.toString())) }
            }
        }
    }

    // ---------- état affiché ----------

    private fun rows(c: Contact) = ContactRow(
        name = c.name,
        ready = c.ready,
        inviteShown = c.myBox != null,
        inviteScanned = c.ik != null,
        safety = c.ik?.let { safetyNumber(it) },
        lastText = c.messages.lastOrNull()?.text,
        lastTs = c.messages.lastOrNull()?.ts ?: 0,
        pendingFromUnknown = c.pendingFromUnknown,
        burned = c.burned,
    )

    /** Recalcule l'affichage à partir de l'état (sous le verrou du Messenger). */
    private fun refresh(screen: Screen? = null) {
        val m = messenger ?: return
        synchronized(m) {
            if (messenger !== m) return
            val contacts = m.state.contacts.values.map(::rows).sortedByDescending { it.lastTs }
            // L'écran courant est lu dans update (atomique) : une relève en cours ne peut pas
            // ramener un écran que l'utilisateur vient de quitter.
            _ui.update { cur ->
                val target = screen ?: cur.screen
                val chat = (target as? Screen.Chat)?.let { s ->
                    m.state.contacts[s.name]?.messages?.map {
                        MessageRow(b64(it.id) + it.outgoing, it.outgoing, it.text, it.ts, it.status)
                    }
                } ?: emptyList()
                cur.copy(screen = target, mySafety = m.safetyNumber, relayUrl = m.state.relay.url, contacts = contacts, chat = chat)
            }
        }
    }

    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: e.toString()) }
                withContext(Dispatchers.IO) { refresh() }
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    // ---------- navigation ----------

    fun goContacts() = viewModelScope.launch(Dispatchers.IO) {
        _ui.update { it.copy(add = AddFlow(), error = null, info = null) }
        refresh(Screen.Contacts)
    }

    fun goAdd() = _ui.update { it.copy(screen = Screen.AddContact, add = AddFlow(), error = null, info = null) }

    fun openChat(name: String) = viewModelScope.launch(Dispatchers.IO) {
        val c = messenger?.state?.contacts?.get(name)
        if (c != null && !c.ready && !c.burned) {
            // Échange de QR inachevé : on reprend là où il en était.
            _ui.update { it.copy(screen = Screen.AddContact, add = AddFlow(name, null), error = null, info = null) }
            if (c.myBox != null) createInvite(name)
        } else {
            refresh(Screen.Chat(name))
        }
    }

    fun dismissMessages() = _ui.update { it.copy(error = null, info = null) }

    // ---------- identité et codes ----------

    fun setup(url: String, fp: String) = action {
        duressJob?.join() // un effacement en cours doit être terminé avant toute nouvelle identité
        val relay = RelayRef(url.trim().trimEnd('/'), parseFingerprint(fp))
        require(relay.url.startsWith("https://")) { "l'adresse du relais doit commencer par https://" }
        pending = Messenger.createIdentity(relay)
        store.beginNew()
        _ui.update { it.copy(screen = Screen.SetCodes(migrating = false)) }
    }

    fun setCodes(unlock: String, unlockAgain: String, duress: String, duressAgain: String) = action {
        require(unlock == unlockAgain) { "les deux saisies du code de déverrouillage diffèrent" }
        require(duress == duressAgain) { "les deux saisies du code de destruction diffèrent" }
        LockBox.validateCodes(unlock, duress)
        val s = pending ?: error("aucune identité en attente")
        store.setCodes(s, unlock, duress)
        pending = null
        messenger = Messenger(s, ::persist)
        refresh(Screen.Contacts)
    }

    fun unlock(code: String) = action {
        when (val r = store.unlock(code)) {
            StateStore.UnlockResult.Unlocked -> {
                messenger = Messenger(store.load(), ::persist)
                refresh(Screen.Contacts)
            }
            StateStore.UnlockResult.Duress -> redButton(code)
            is StateStore.UnlockResult.Wrong ->
                error(if (r.waitSeconds > 0) "Code incorrect. Prochain essai dans ${r.waitSeconds} s." else "Code incorrect.")
            is StateStore.UnlockResult.Throttled -> error("Trop d'essais. Réessaie dans ${r.waitSeconds} s.")
        }
    }

    /**
     * Code de destruction, avec leurre : l'appli fait comme si le code était bon et s'ouvre sur
     * une liste de contacts vide. Pendant ce temps, sans interruption possible :
     *  1. l'ancien état est chargé en mémoire ;
     *  2. la clé Keystore et le coffre sont détruits (plus rien n'est déchiffrable sur le disque) ;
     *  3. une nouvelle identité est créée, dont le code de déverrouillage est le code qui vient
     *     d'être saisi (le code de destruction est aléatoire, à redéfinir dans « Changer mes codes ») ;
     *  4. les alertes partent depuis la mémoire (5 s maximum), puis l'ancien état est oublié.
     * Rien ne distingue cet écran d'une appli dont on aurait supprimé tous les contacts.
     */
    private fun redButton(code: String) {
        val old = runCatching { store.load() }.getOrNull()
        val m = messenger
        if (m != null) synchronized(m) { messenger = null }
        val relay = old?.relay ?: RelayRef(BuildConfig.DEFAULT_RELAY_URL, parseFingerprint(BuildConfig.DEFAULT_RELAY_FP))
        val fresh = AppState.newIdentity(relay)
        _ui.value = Ui(screen = Screen.Contacts, mySafety = safetyNumber(fresh.ik), relayUrl = relay.url)
        duressJob = viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            store.wipe()
            runCatching {
                store.beginNew()
                store.setCodes(fresh, code, b64(randomBytes(24)))
                messenger = Messenger(fresh, ::persist)
            }
            if (old != null) runCatching { Messenger(old, persist = {}).sendAlerts(5_000) }
            old?.identitySeed?.fill(0)
            old?.x25519Priv?.fill(0)
        }
    }

    fun goChangeCodes() = _ui.update { it.copy(screen = Screen.ChangeCodes, error = null, info = null) }

    /** Change les deux codes ; le code actuel est exigé. Le code de destruction y déclenche aussi le bouton rouge. */
    fun changeCodes(current: String, unlock: String, unlockAgain: String, duress: String, duressAgain: String) = action {
        require(unlock == unlockAgain) { "les deux saisies du nouveau code de déverrouillage diffèrent" }
        require(duress == duressAgain) { "les deux saisies du nouveau code de destruction diffèrent" }
        LockBox.validateCodes(unlock, duress)
        when (val r = store.changeCodes(current, unlock, duress)) {
            StateStore.UnlockResult.Unlocked -> {
                _ui.update { it.copy(info = "Codes modifiés.") }
                refresh(Screen.Contacts)
            }
            StateStore.UnlockResult.Duress -> redButton(current)
            is StateStore.UnlockResult.Wrong ->
                error(if (r.waitSeconds > 0) "Code actuel incorrect. Prochain essai dans ${r.waitSeconds} s." else "Code actuel incorrect.")
            is StateStore.UnlockResult.Throttled -> error("Trop d'essais. Réessaie dans ${r.waitSeconds} s.")
        }
    }

    /** Verrouille : la clé de données et l'état déchiffré sont oubliés. */
    fun lock() {
        autoLockJob?.cancel()
        val m = messenger ?: return
        synchronized(m) {
            messenger = null
            store.lock()
        }
        _ui.value = Ui(screen = Screen.Locked)
    }

    /** Verrouillage automatique après 60 s en arrière-plan (scanner QR et partage compris). */
    fun onAppBackground() {
        if (messenger == null) return
        autoLockJob?.cancel()
        autoLockJob = viewModelScope.launch {
            delay(60_000)
            lock()
        }
    }

    fun onAppForeground() {
        autoLockJob?.cancel()
        if (messenger != null) pollNow()
    }

    // ---------- messagerie ----------

    fun createInvite(name: String) = action {
        val m = messenger ?: return@action
        val invite = m.invite(name)
        _ui.update { it.copy(add = AddFlow(name.trim(), invite)) }
        refresh()
    }

    fun addScanned(name: String, invite: String) = action {
        val m = messenger ?: return@action
        val c = m.addContact(name, invite)
        val sn = safetyNumber(c.ik!!)
        _ui.update {
            it.copy(info = "Empreinte de ${c.name} : $sn\nCompare-la avec celle affichée sur son téléphone.")
        }
        refresh()
    }

    fun send(name: String, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Chaque enregistrement rafraîchit l'écran : le message apparaît « envoi… », puis « envoyé ».
                messenger?.send(name, text)
            } catch (e: Exception) {
                _ui.update { it.copy(error = "Échec de l'envoi : ${e.message}") }
            }
            refresh()
        }
    }

    fun resetSession(name: String) = action {
        messenger?.resetSession(name)
        _ui.update { it.copy(info = "Session réinitialisée. $name doit faire de même de son côté.") }
        refresh()
    }

    fun removeContact(name: String) = action {
        messenger?.removeContact(name)
        refresh(Screen.Contacts)
    }

    fun wipeAll() = viewModelScope.launch(Dispatchers.IO) {
        store.wipe()
        messenger = null
        pending = null
        _ui.value = Ui(screen = Screen.Setup)
    }

    // ---------- relève ----------

    /** Boucle de relève, active tant que l'appli est au premier plan. */
    suspend fun pollLoop() {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val ok = withContext(Dispatchers.IO) { pollOnce() }
            delay(if (ok) 5_000 else 15_000)
        }
    }

    /** Relève immédiate (retour au premier plan). */
    fun pollNow() {
        viewModelScope.launch(Dispatchers.IO) { pollOnce() }
    }

    private fun pollOnce(): Boolean {
        val m = messenger ?: return true
        return try {
            val r = m.poll()
            _ui.update { it.copy(connectionError = r.errors.firstOrNull()) }
            if (r.received.isNotEmpty() || r.errors.isNotEmpty() || r.rejected > 0) refresh()
            r.errors.isEmpty()
        } catch (e: Exception) {
            _ui.update { it.copy(connectionError = e.message) }
            false
        }
    }
}
