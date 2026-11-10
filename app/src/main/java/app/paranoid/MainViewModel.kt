package app.paranoid

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.paranoid.core.Contact
import app.paranoid.core.MAX_TEXT
import app.paranoid.core.MessageStatus
import app.paranoid.core.Messenger
import app.paranoid.core.RelayRef
import app.paranoid.core.b64
import app.paranoid.core.parseFingerprint
import app.paranoid.core.safetyNumber
import kotlinx.coroutines.Dispatchers
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

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    val maxText = MAX_TEXT

    /** Enregistre l'état chiffré puis met l'affichage à jour (appelé par le Messenger, sous son verrou). */
    private fun persist(s: app.paranoid.core.AppState) {
        store.save(s)
        refresh()
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val s = store.load()
                if (s == null) {
                    _ui.update { it.copy(screen = Screen.Setup) }
                } else {
                    messenger = Messenger(s, ::persist)
                    refresh(Screen.Contacts)
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
    )

    /** Recalcule l'affichage à partir de l'état (sous le verrou du Messenger). */
    private fun refresh(screen: Screen? = null) {
        val m = messenger ?: return
        synchronized(m) {
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
        if (c != null && !c.ready) {
            // Échange de QR inachevé : on reprend là où il en était.
            _ui.update { it.copy(screen = Screen.AddContact, add = AddFlow(name, null), error = null, info = null) }
            if (c.myBox != null) createInvite(name)
        } else {
            refresh(Screen.Chat(name))
        }
    }

    fun dismissMessages() = _ui.update { it.copy(error = null, info = null) }

    // ---------- actions ----------

    fun setup(url: String, fp: String) = action {
        val relay = RelayRef(url.trim().trimEnd('/'), parseFingerprint(fp))
        require(relay.url.startsWith("https://")) { "l'adresse du relais doit commencer par https://" }
        val s = Messenger.createIdentity(relay)
        store.save(s)
        messenger = Messenger(s, ::persist)
        refresh(Screen.Contacts)
    }

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
