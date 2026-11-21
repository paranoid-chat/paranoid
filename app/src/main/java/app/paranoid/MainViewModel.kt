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
import app.paranoid.core.RelayDirectory
import app.paranoid.core.RelayRef
import app.paranoid.core.TYPE_RELAY_CHANGE
import app.paranoid.core.formatFingerprint
import app.paranoid.core.b64
import app.paranoid.core.randomBytes
import app.paranoid.core.parseFingerprint
import app.paranoid.core.safetyNumber
import app.paranoid.core.tr
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
    data object ChangeRelay : Screen
    data object Learn : Screen
    data object Settings : Screen
    data object MyInfo : Screen
    data object Donation : Screen
    data object Locked : Screen
    data object Contacts : Screen
    data object AddContact : Screen
    data class Chat(val name: String) : Screen
    data class Fatal(val message: String) : Screen
}

data class ContactRow(
    val name: String,
    val ready: Boolean,
    val safety: String?,
    val lastText: String?,
    val lastTs: Long,
    val burned: Boolean,
)

data class MessageRow(val key: String, val outgoing: Boolean, val text: String, val ts: Long, val status: MessageStatus)

const val NO_RELAY_URL = "https://relay.invalid"

data class RelayRow(val name: String, val url: String, val fp: String, val version: String, val lastSeen: Long, val online: Boolean)

data class AddFlow(val name: String = "", val invite: String? = null)

data class Ui(
    val screen: Screen = Screen.Loading,
    val mySafety: String = "",
    val relayUrl: String = "",
    val relayFp: String = "",
    val historySeconds: Long = 0,
    val contacts: List<ContactRow> = emptyList(),
    val chat: List<MessageRow> = emptyList(),
    val add: AddFlow = AddFlow(),
    val busy: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val connectionError: String? = null,
    val directory: List<RelayRow>? = null,
    val probedUrl: String? = null,
    val probedFp: String? = null,
    val noRelay: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = StateStore(app)
    private var messenger: Messenger? = null

    private var pending: AppState? = null
    private var duressJob: Job? = null
    private var autoLockJob: Job? = null

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    val maxText = MAX_TEXT
    val minCode = LockBox.MIN_CODE_LENGTH

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

    private fun rows(c: Contact) = ContactRow(
        name = c.name,
        ready = c.ready,
        safety = c.ik?.let { safetyNumber(it) },
        lastText = c.messages.lastOrNull()?.text,
        lastTs = c.messages.lastOrNull()?.ts ?: 0,
        burned = c.burned,
    )

    private fun refresh(screen: Screen? = null) {
        val m = messenger ?: return
        synchronized(m) {
            if (messenger !== m) return
            val contacts = m.state.contacts.values.map(::rows).sortedByDescending { it.lastTs }
            _ui.update { cur ->
                val target = screen ?: cur.screen
                val chat = (target as? Screen.Chat)?.let { s ->
                    m.state.contacts[s.name]?.messages?.map {
                        MessageRow(b64(it.id) + it.outgoing, it.outgoing, it.text, it.ts, it.status)
                    }
                } ?: emptyList()
                cur.copy(
                    screen = target, mySafety = m.safetyNumber,
                    relayUrl = if (m.state.relay.url == NO_RELAY_URL) "" else m.state.relay.url,
                    relayFp = if (m.state.relay.url == NO_RELAY_URL) "" else formatFingerprint(m.state.relay.fp),
                    noRelay = m.state.relay.url == NO_RELAY_URL, historySeconds = m.state.historySeconds,
                    contacts = contacts, chat = chat,
                )
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

    fun goContacts() = viewModelScope.launch(Dispatchers.IO) {
        _ui.update { it.copy(add = AddFlow(), error = null, info = null) }
        refresh(Screen.Contacts)
    }

    fun goAdd() = _ui.update { it.copy(screen = Screen.AddContact, add = AddFlow(), error = null, info = null) }

    fun openChat(name: String) = viewModelScope.launch(Dispatchers.IO) {
        val c = messenger?.state?.contacts?.get(name)
        if (c != null && !c.ready && !c.burned) {
            _ui.update { it.copy(screen = Screen.AddContact, add = AddFlow(name, null), error = null, info = null) }
            createInvite(name)
        } else {
            refresh(Screen.Chat(name))
        }
    }

    fun dismissMessages() = _ui.update { it.copy(error = null, info = null) }

    fun setup(url: String, fp: String) = action {
        duressJob?.join()
        val relay = RelayRef(url.trim().trimEnd('/'), parseFingerprint(fp))
        require(relay.url.startsWith("https://")) { tr("l'adresse du relais doit commencer par https://", "the relay address must start with https://") }
        pending = Messenger.createIdentity(relay)
        store.beginNew()
        _ui.update { it.copy(screen = Screen.SetCodes(migrating = false)) }
    }

    fun setCodes(unlock: String, unlockAgain: String, duress: String, duressAgain: String) = action {
        require(unlock == unlockAgain) { tr("les deux saisies du code de déverrouillage diffèrent", "the two entries of the unlock code differ") }
        require(duress == duressAgain) { tr("les deux saisies du code de destruction diffèrent", "the two entries of the destruction code differ") }
        LockBox.validateCodes(unlock, duress)
        val s = pending ?: error(tr("aucune identité en attente", "no pending identity"))
        store.setCodes(s, unlock, duress)
        pending = null
        messenger = Messenger(s, ::persist)
        refresh(Screen.Contacts)
    }

    fun unlock(code: String) = action {
        when (val r = store.unlock(code)) {
            StateStore.UnlockResult.Unlocked -> {
                messenger = Messenger(store.load(), ::persist).also { it.purgeHistory() }
                refresh(Screen.Contacts)
            }
            StateStore.UnlockResult.Duress -> redButton(code)
            is StateStore.UnlockResult.Wrong ->
                error(if (r.waitSeconds > 0) tr("Code incorrect. Prochain essai dans ${r.waitSeconds} s.", "Wrong code. Next attempt in ${r.waitSeconds} s.") else tr("Code incorrect.", "Wrong code."))
            is StateStore.UnlockResult.Throttled -> error(tr("Trop d'essais. Nouvel essai possible dans ${r.waitSeconds} s.", "Too many attempts. Try again in ${r.waitSeconds} s."))
        }
    }

    private fun redButton(code: String) {
        val old = runCatching { store.load() }.getOrNull()
        val m = messenger
        if (m != null) synchronized(m) { messenger = null }
        var fresh = AppState.newIdentity(old?.relay ?: RelayRef(NO_RELAY_URL, ByteArray(32)))
        _ui.value = if (old != null) {
            Ui(screen = Screen.Contacts, mySafety = safetyNumber(fresh.ik), relayUrl = fresh.relay.url, relayFp = formatFingerprint(fresh.relay.fp))
        } else {
            Ui(screen = Screen.Contacts, mySafety = safetyNumber(fresh.ik), noRelay = true)
        }
        duressJob = viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            store.wipe()
            if (old == null) {
                runCatching { RelayDirectory.fetch(10_000).firstOrNull { it.online } }.getOrNull()?.let { r ->
                    fresh = AppState(fresh.identitySeed, fresh.x25519Priv, RelayRef(r.url, r.fp))
                    _ui.update { it.copy(relayUrl = r.url, relayFp = formatFingerprint(r.fp), noRelay = false) }
                }
            }
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

    fun changeCodes(current: String, unlock: String, unlockAgain: String, duress: String, duressAgain: String) = action {
        require(unlock == unlockAgain) { tr("les deux saisies du nouveau code de déverrouillage diffèrent", "the two entries of the new unlock code differ") }
        require(duress == duressAgain) { tr("les deux saisies du nouveau code de destruction diffèrent", "the two entries of the new destruction code differ") }
        LockBox.validateCodes(unlock, duress)
        when (val r = store.changeCodes(current, unlock, duress)) {
            StateStore.UnlockResult.Unlocked -> {
                _ui.update { it.copy(info = tr("Codes modifiés.", "Codes changed.")) }
                refresh(Screen.Contacts)
            }
            StateStore.UnlockResult.Duress -> redButton(current)
            is StateStore.UnlockResult.Wrong ->
                error(if (r.waitSeconds > 0) tr("Code actuel incorrect. Prochain essai dans ${r.waitSeconds} s.", "Wrong current code. Next attempt in ${r.waitSeconds} s.") else tr("Code actuel incorrect.", "Wrong current code."))
            is StateStore.UnlockResult.Throttled -> error(tr("Trop d'essais. Nouvel essai possible dans ${r.waitSeconds} s.", "Too many attempts. Try again in ${r.waitSeconds} s."))
        }
    }

    fun goMyInfo() = _ui.update { it.copy(screen = Screen.MyInfo, error = null, info = null) }

    fun goSettings() = _ui.update { it.copy(screen = Screen.Settings, error = null, info = null) }

    fun countOlderThan(seconds: Long): Int = messenger?.countOlderThan(seconds) ?: 0

    fun setHistory(seconds: Long) = action {
        val m = messenger ?: return@action
        val n = m.setHistory(seconds)
        _ui.update { it.copy(info = if (n > 0) tr("$n message(s) effacé(s) de ce téléphone.", "$n message(s) erased from this phone.") else null) }
        refresh()
    }

    fun goChangeRelay() = _ui.update { it.copy(screen = Screen.ChangeRelay, error = null, info = null, directory = null, probedUrl = null, probedFp = null) }

    fun goDonation() = _ui.update { it.copy(screen = Screen.Donation, error = null, info = null) }

    fun loadDirectory() = action {
        val list = RelayDirectory.fetch().map {
            RelayRow(it.name, it.url, formatFingerprint(it.fp), it.version, it.lastSeen, it.online)
        }
        _ui.update { it.copy(directory = list) }
    }

    fun probeRelay(url: String) = action {
        val u = url.trim().trimEnd('/')
        val fp = formatFingerprint(RelayDirectory.probeFingerprint(u))
        _ui.update { it.copy(probedUrl = u, probedFp = fp) }
    }

    fun keyProtection(): String = runCatching { store.keyProtection() }.getOrDefault(tr("Inconnue", "Unknown"))

    fun goLearn() = _ui.update { it.copy(screen = Screen.Learn, error = null, info = null) }

    fun changeRelay(current: String, url: String, fp: String) = action {
        val relay = RelayRef(url.trim().trimEnd('/'), parseFingerprint(fp))
        require(relay.url.startsWith("https://")) { tr("l'adresse du relais doit commencer par https://", "the relay address must start with https://") }
        when (val r = store.unlock(current)) {
            StateStore.UnlockResult.Unlocked -> Unit
            StateStore.UnlockResult.Duress -> return@action redButton(current)
            is StateStore.UnlockResult.Wrong ->
                error(if (r.waitSeconds > 0) tr("Code actuel incorrect. Prochain essai dans ${r.waitSeconds} s.", "Wrong current code. Next attempt in ${r.waitSeconds} s.") else tr("Code actuel incorrect.", "Wrong current code."))
            is StateStore.UnlockResult.Throttled -> error(tr("Trop d'essais. Nouvel essai possible dans ${r.waitSeconds} s.", "Too many attempts. Try again in ${r.waitSeconds} s."))
        }
        val fresh = Messenger.createIdentity(relay)
        val m = messenger ?: error(tr("appli verrouillée", "app locked"))
        synchronized(m) {
            runCatching { m.sendAlerts(5_000, TYPE_RELAY_CHANGE) }
            messenger = null
            store.wipe()
            m.state.identitySeed.fill(0)
            m.state.x25519Priv.fill(0)
        }
        pending = fresh
        store.beginNew()
        _ui.value = Ui(screen = Screen.SetCodes(migrating = false), info = tr("Relais changé. Choisir les codes de la nouvelle identité.", "Relay changed. Choose the codes of the new identity."))
    }

    fun lock() {
        autoLockJob?.cancel()
        val m = messenger ?: return
        synchronized(m) {
            messenger = null
            store.lock()
        }
        _ui.value = Ui(screen = Screen.Locked)
    }

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

    fun createInvite(name: String) = action {
        val m = messenger ?: return@action
        val invite = m.invite(name)
        _ui.update { it.copy(add = AddFlow(name.trim(), invite)) }
        refresh()
    }

    fun addScanned(name: String, invite: String) = action {
        val m = messenger ?: return@action
        val c = m.join(name, invite)
        val sn = safetyNumber(c.ik!!)
        _ui.update {
            it.copy(
                add = AddFlow(),
                info = tr("Appairage envoyé à ${c.name}.\n\nSon empreinte : $sn\n\nComparez-la de vive voix avec celle que ${c.name} voit comme « Mon empreinte ».", "Pairing sent to ${c.name}.\n\nTheir fingerprint: $sn\n\nCompare it out loud with what ${c.name} sees as “My fingerprint”."),
            )
        }
        refresh(Screen.Chat(c.name))
    }

    fun send(name: String, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                messenger?.send(name, text)
            } catch (e: Exception) {
                _ui.update { it.copy(error = tr("Échec de l'envoi : ${e.message}", "Sending failed: ${e.message}")) }
            }
            refresh()
        }
    }

    fun resetSession(name: String) = action {
        messenger?.resetSession(name)
        _ui.update { it.copy(info = tr("Session réinitialisée. $name doit faire de même de son côté.", "Session reset. $name must do the same on their side.")) }
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

    suspend fun pollLoop() {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val ok = withContext(Dispatchers.IO) { pollOnce() }
            delay(if (ok) 5_000 else 15_000)
        }
    }

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
