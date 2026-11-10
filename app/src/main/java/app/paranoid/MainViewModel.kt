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
import app.paranoid.core.parseFingerprint
import app.paranoid.core.safetyNumber
import app.paranoid.core.unb64
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
    val pushStatus: String? = null,
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

    /** Clé VAPID du relais : quand elle est connue, l'activité s'enregistre auprès du distributeur. */
    private val _pushVapid = MutableStateFlow<String?>(null)
    val pushVapid: StateFlow<String?> = _pushVapid

    val maxText = MAX_TEXT
    val minCode = LockBox.MIN_CODE_LENGTH

    /** Enregistre l'état chiffré puis met l'affichage à jour (appelé par le Messenger, sous son verrou). */
    private fun persist(s: AppState) {
        store.save(s)
        refresh()
    }

    init {
        PushBus.endpoint.value = PushPrefs.load(app)
        viewModelScope.launch { PushBus.wake.collect { pollNow() } }
        viewModelScope.launch(Dispatchers.IO) { PushBus.endpoint.collect { if (it != null) syncPush() } }
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
        onUnlocked()
    }

    fun unlock(code: String) = action {
        when (val r = store.unlock(code)) {
            StateStore.UnlockResult.Unlocked -> {
                messenger = Messenger(store.load(), ::persist)
                refresh(Screen.Contacts)
                onUnlocked()
            }
            StateStore.UnlockResult.Duress -> redButton()
            is StateStore.UnlockResult.Wrong ->
                error(if (r.waitSeconds > 0) "Code incorrect. Prochain essai dans ${r.waitSeconds} s." else "Code incorrect.")
            is StateStore.UnlockResult.Throttled -> error("Trop d'essais. Réessaie dans ${r.waitSeconds} s.")
        }
    }

    /**
     * Code de destruction. L'écran ressemble aussitôt à une appli vierge. Puis, sans interruption
     * possible : état chargé en mémoire, clé Keystore et coffre détruits (plus rien n'est
     * déchiffrable sur le disque), alertes envoyées depuis la mémoire (5 s maximum), effacement
     * du reste.
     */
    private fun redButton() {
        _ui.value = Ui(screen = Screen.Setup)
        PushBus.foregroundUnlocked = false
        Push.forget(getApplication())
        duressJob = viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            val state = runCatching { store.load() }.getOrNull()
            store.wipe()
            if (state != null) runCatching { Messenger(state, persist = {}).sendAlerts(5_000) }
            state?.identitySeed?.fill(0)
            state?.x25519Priv?.fill(0)
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
        PushBus.foregroundUnlocked = false
        _ui.value = Ui(screen = Screen.Locked)
    }

    // ---------- notifications ----------

    private fun onUnlocked() {
        PushBus.foregroundUnlocked = true
        Notifier.cancel(getApplication())
        viewModelScope.launch(Dispatchers.IO) {
            val m = messenger ?: return@launch
            val key = runCatching { m.vapidKey() }
            key.onSuccess { k ->
                if (k != null) {
                    // Sans distributeur UnifiedPush sur le téléphone, on ne demande rien : les
                    // messages attendent sur le relais et sont relevés au déverrouillage.
                    if (PushBus.endpoint.value != null || Push.distributorAvailable(getApplication())) {
                        _pushVapid.value = b64(k)
                    }
                }
            }
            syncPush()
        }
    }

    /** Réponse de l'activité après le choix du distributeur. */
    fun onPushDistributor(ok: Boolean) {
        if (!ok) _ui.update { it.copy(pushStatus = null) }
    }

    /** Enregistre le point de notification courant sur toutes les boîtes (si déverrouillé). */
    private fun syncPush() {
        val m = messenger ?: return
        val t = PushBus.endpoint.value ?: return
        try {
            m.syncPush(t.url, unb64(t.pubKey), unb64(t.auth))
            _ui.update { it.copy(pushStatus = "Notifications actives") }
        } catch (e: Exception) {
            _ui.update { it.copy(pushStatus = "Notifications : enregistrement impossible (${e.message})") }
        }
    }

    /** Verrouillage automatique après 60 s en arrière-plan (scanner QR et partage compris). */
    fun onAppBackground() {
        PushBus.foregroundUnlocked = false
        if (messenger == null) return
        autoLockJob?.cancel()
        autoLockJob = viewModelScope.launch {
            delay(60_000)
            lock()
        }
    }

    fun onAppForeground() {
        autoLockJob?.cancel()
        if (messenger != null) {
            PushBus.foregroundUnlocked = true
            Notifier.cancel(getApplication())
            pollNow()
        }
    }

    // ---------- messagerie ----------

    fun createInvite(name: String) = action {
        val m = messenger ?: return@action
        val invite = m.invite(name)
        _ui.update { it.copy(add = AddFlow(name.trim(), invite)) }
        refresh()
        syncPush() // la nouvelle boîte doit aussi réveiller le téléphone
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
        Push.forget(getApplication())
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

    /** Relève immédiate (réveil par une notification UnifiedPush). */
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
