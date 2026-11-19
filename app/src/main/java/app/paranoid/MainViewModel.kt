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

/** Relais de l'annuaire public, tel qu'affiché dans « Changer de relais ». */
/**
 * Relais de repli de l'identité leurre (code de destruction, ancien état illisible, annuaire vide ou
 * injoignable) : adresse réservée (.invalid), jamais affichée ; l'appli demande alors de choisir un relais.
 */
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
    /** Annuaire public : null = pas encore chargé (aucune requête vers getparanoid.fr avant le bouton). */
    val directory: List<RelayRow>? = null,
    /** Empreinte lue sur un relais privé, pour l'adresse [probedUrl]. */
    val probedUrl: String? = null,
    val probedFp: String? = null,
    /** Identité sans relais utilisable (voir [NO_RELAY_URL]). */
    val noRelay: Boolean = false,
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
        safety = c.ik?.let { safetyNumber(it) },
        lastText = c.messages.lastOrNull()?.text,
        lastTs = c.messages.lastOrNull()?.ts ?: 0,
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

    // ---------- navigation ----------

    fun goContacts() = viewModelScope.launch(Dispatchers.IO) {
        _ui.update { it.copy(add = AddFlow(), error = null, info = null) }
        refresh(Screen.Contacts)
    }

    fun goAdd() = _ui.update { it.copy(screen = Screen.AddContact, add = AddFlow(), error = null, info = null) }

    fun openChat(name: String) = viewModelScope.launch(Dispatchers.IO) {
        val c = messenger?.state?.contacts?.get(name)
        if (c != null && !c.ready && !c.burned) {
            // Invitation pas encore scannée : on réaffiche le même QR.
            _ui.update { it.copy(screen = Screen.AddContact, add = AddFlow(name, null), error = null, info = null) }
            createInvite(name)
        } else {
            refresh(Screen.Chat(name))
        }
    }

    fun dismissMessages() = _ui.update { it.copy(error = null, info = null) }

    // ---------- identité et codes ----------

    fun setup(url: String, fp: String) = action {
        duressJob?.join() // un effacement en cours doit être terminé avant toute nouvelle identité
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
        // Nouvelle identité sur le même relais. Ancien état illisible : premier relais en ligne de
        // l'annuaire public, cherché en arrière-plan ; à défaut, NO_RELAY_URL (rien n'est affiché).
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

    /** Change les deux codes ; le code actuel est exigé. Le code de destruction y déclenche aussi le bouton rouge. */
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

    /** Nombre de messages qu'effacerait une conservation de [seconds] secondes. */
    fun countOlderThan(seconds: Long): Int = messenger?.countOlderThan(seconds) ?: 0

    /** Change la durée de conservation de l'historique ; la purge est immédiate. */
    fun setHistory(seconds: Long) = action {
        val m = messenger ?: return@action
        val n = m.setHistory(seconds)
        _ui.update { it.copy(info = if (n > 0) tr("$n message(s) effacé(s) de ce téléphone.", "$n message(s) erased from this phone.") else null) }
        refresh()
    }

    fun goChangeRelay() = _ui.update { it.copy(screen = Screen.ChangeRelay, error = null, info = null, directory = null, probedUrl = null, probedFp = null) }

    fun goDonation() = _ui.update { it.copy(screen = Screen.Donation, error = null, info = null) }

    /** Charge l'annuaire public de getparanoid.fr (à la demande seulement). */
    fun loadDirectory() = action {
        val list = RelayDirectory.fetch().map {
            RelayRow(it.name, it.url, formatFingerprint(it.fp), it.version, it.lastSeen, it.online)
        }
        _ui.update { it.copy(directory = list) }
    }

    /** Lit l'empreinte présentée par un relais privé ; elle s'affiche pour être vérifiée avant l'épinglage. */
    fun probeRelay(url: String) = action {
        val u = url.trim().trimEnd('/')
        val fp = formatFingerprint(RelayDirectory.probeFingerprint(u))
        _ui.update { it.copy(probedUrl = u, probedFp = fp) }
    }

    fun keyProtection(): String = runCatching { store.keyProtection() }.getOrDefault(tr("Inconnue", "Unknown"))

    fun goLearn() = _ui.update { it.copy(screen = Screen.Learn, error = null, info = null) }

    /**
     * Changement de relais, volontairement sans migration : tout est perdu.
     *  1. le code actuel est exigé (le code de destruction y déclenche le bouton rouge) ;
     *  2. le nouveau relais est testé (TLS et empreinte) avant de toucher à quoi que ce soit ;
     *  3. chaque contact reçoit « X a changé de relais » sur l'ancien relais (5 s maximum) ;
     *  4. clé Keystore et fichiers sont détruits, puis une nouvelle identité est créée sur le
     *     nouveau relais ; ses codes sont à choisir à nouveau.
     */
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
        val fresh = Messenger.createIdentity(relay) // échoue si le relais est injoignable ou l'empreinte fausse
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

    /** J'ai scanné (ou collé) l'invitation de [name] : l'appairage lui est envoyé, la discussion s'ouvre. */
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
                // Chaque enregistrement rafraîchit l'écran : le message apparaît « envoi… », puis « envoyé ».
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
