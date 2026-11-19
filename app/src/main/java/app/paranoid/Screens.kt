package app.paranoid

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.paranoid.core.MessageStatus
import app.paranoid.core.Lang
import app.paranoid.core.tr
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- éléments communs ----------

@Composable
internal fun Messages(ui: Ui, vm: MainViewModel) {
    ui.error?.let {
        AlertDialog(
            onDismissRequest = vm::dismissMessages,
            confirmButton = { TextButton(onClick = vm::dismissMessages) { Text("OK") } },
            title = { Text(tr("Erreur", "Error")) },
            text = { Text(it) },
        )
    }
    if (ui.error == null) ui.info?.let {
        AlertDialog(
            onDismissRequest = vm::dismissMessages,
            confirmButton = { TextButton(onClick = vm::dismissMessages) { Text("OK") } },
            text = { Text(it) },
        )
    }
}

@Composable
private fun ConnectionBanner(ui: Ui) {
    val err = ui.connectionError ?: return
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.size(8.dp))
            Text(tr("Relais injoignable : $err", "Relay unreachable: $err"), color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun qrBitmap(text: String, size: Int = 720): ImageBitmap {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Copie marquée « sensible » : Android masque l'aperçu du presse-papiers. */
/** Menu de partage Android : pour transmettre l'invitation à un PC sans caméra. */
private fun shareInvite(ctx: Context, invite: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, invite)
    }
    ctx.startActivity(Intent.createChooser(send, tr("Partager l'invitation", "Share the invitation")))
}

private val timeFmt = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)
private fun fmt(ts: Long) = if (ts == 0L) "" else timeFmt.format(Date(ts * 1000))

// ---------- écrans ----------

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun FatalScreen(message: String, vm: MainViewModel) {
    var confirm by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(24.dp).windowInsetsPadding(WindowInsets.navigationBars), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(48.dp))
            Text(tr("Données illisibles", "Unreadable data"), style = MaterialTheme.typography.headlineSmall)
            Text(message)
            Text(tr("Sans la clé de l'appareil, l'identité et les conversations ne peuvent pas être récupérées.", "Without the device key, the identity and conversations cannot be recovered."))
            Button(onClick = { confirm = true }) { Text(tr("Tout effacer et recommencer", "Erase everything and start over")) }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            confirmButton = { TextButton(onClick = { confirm = false; vm.wipeAll() }) { Text(tr("Effacer", "Erase")) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("Annuler", "Cancel")) } },
            text = { Text(tr("Effacer définitivement l'identité et toutes les conversations ?", "Permanently erase the identity and all conversations?")) },
        )
    }
}

@Composable
fun SetupScreen(ui: Ui, vm: MainViewModel) {
    var url by rememberSaveable { mutableStateOf(BuildConfig.DEFAULT_RELAY_URL) }
    var fp by rememberSaveable { mutableStateOf(BuildConfig.DEFAULT_RELAY_FP) }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val ctx = LocalContext.current
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { setAppLanguage(ctx, !Lang.english) }) { Text(if (Lang.english) "Français" else "English") }
            }
            Image(painterResource(R.drawable.logo), contentDescription = "Paranoid", modifier = Modifier.widthIn(max = 220.dp))
            Text(
                "Paranoid - The Chat app for the truly paranoids",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            OutlinedTextField(url, { url = it }, label = { Text(tr("Relais", "Relay")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(fp, { fp = it }, label = { Text(tr("Empreinte SHA-256 du relais", "Relay SHA-256 fingerprint")) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.setup(url, fp) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (ui.busy) tr("Connexion au relais…", "Connecting to the relay…") else tr("Créer mon identité", "Create my identity"))
            }
        }
    }
    Messages(ui, vm)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(ui: Ui, vm: MainViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paranoid") },
                actions = {
                    IconButton(onClick = vm::lock) { Icon(Icons.Default.Lock, tr("Verrouiller", "Lock")) }
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(tr("Modification des codes", "Change codes")) }, onClick = { menu = false; vm.goChangeCodes() })
                        DropdownMenuItem(text = { Text(tr("Changer de relais", "Change relay")) }, onClick = { menu = false; vm.goChangeRelay() })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(tr("Paramètres", "Settings")) }, onClick = { menu = false; vm.goSettings() })
                        DropdownMenuItem(text = { Text(tr("Comprendre Paranoid", "Understanding Paranoid")) }, onClick = { menu = false; vm.goLearn() })
                        DropdownMenuItem(text = { Text(tr("Mes infos en direct", "My live info")) }, onClick = { menu = false; vm.goMyInfo() })
                        DropdownMenuItem(text = { Text("Donation") }, onClick = { menu = false; vm.goDonation() })
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = vm::goAdd, modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                Icon(Icons.Default.Add, tr("Ajouter un contact", "Add a contact"))
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ConnectionBanner(ui)
            Text(tr("Mon empreinte : ${ui.mySafety}", "My fingerprint: ${ui.mySafety}"), style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            if (ui.contacts.isEmpty()) {
                Text(tr("Aucun contact. Appuyer sur + : l'un affiche son QR, l'autre le scanne, en face à face.", "No contacts. Tap +: one shows their QR, the other scans it, face to face."),
                    modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(ui.contacts, key = { it.name }) { c ->
                    val sub = when {
                        c.burned -> tr("Conversation fermée", "Conversation closed")
                        !c.ready -> tr("En attente de son scan", "Waiting for their scan")
                        c.lastText != null -> c.lastText
                        else -> tr("Prêt", "Ready")
                    }
                    ListItem(
                        headlineContent = { Text(c.name) },
                        supportingContent = { Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { Text(fmt(c.lastTs), style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.clickable { vm.openChat(c.name) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    Messages(ui, vm)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(ui: Ui, vm: MainViewModel) {
    val ctx = LocalContext.current
    var name by rememberSaveable(ui.add.name) { mutableStateOf(ui.add.name) }
    var pasted by rememberSaveable { mutableStateOf("") }
    val row = ui.contacts.firstOrNull { it.name == ui.add.name }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { r ->
        r.contents?.let { vm.addScanned(name, it) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Ajouter un contact", "Add a contact")) },
                navigationIcon = { IconButton(onClick = { vm.goContacts() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Retour", "Back")) } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val invite = ui.add.invite
            if (invite == null) {
                Text(tr("L'un affiche son QR, l'autre le scanne.", "One shows their QR, the other scans it."))
                OutlinedTextField(name, { name = it }, label = { Text(tr("Nom du contact (reste sur ce téléphone)", "Contact name (stays on this phone)")) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.createInvite(name) }, enabled = name.isNotBlank() && !ui.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (ui.busy) tr("Création de la boîte…", "Creating the box…") else tr("Afficher mon QR", "Show my QR"))
                }
                Button(
                    onClick = {
                        scanner.launch(
                            ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt(tr("QR de ${name.trim()}", "QR of ${name.trim()}")).setBeepEnabled(false).setOrientationLocked(false),
                        )
                    },
                    enabled = name.isNotBlank() && !ui.busy, modifier = Modifier.fillMaxWidth(),
                ) { Text(if (ui.busy) tr("Envoi de l'appairage…", "Sending the pairing…") else tr("Scanner son QR", "Scan their QR")) }
                HorizontalDivider()
                Text(tr("Si vous avez reçu le contenu du QR par texte : le coller ici.", "If you received the QR content as text: paste it here."), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(pasted, { pasted = it }, label = { Text(tr("Contenu du QR", "QR content")) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { vm.addScanned(name, pasted) }, enabled = name.isNotBlank() && pasted.isNotBlank() && !ui.busy,
                    modifier = Modifier.fillMaxWidth()) { Text(tr("Ajouter depuis le texte", "Add from text")) }
            } else if (row?.ready == true) {
                Text(tr("Appairage terminé avec ${ui.add.name}.", "Pairing complete with ${ui.add.name}."), style = MaterialTheme.typography.titleMedium)
                Text(tr("Son empreinte : ${row.safety}", "Their fingerprint: ${row.safety}"), fontFamily = FontFamily.Monospace)
                Text(tr("Comparez-la de vive voix avec celle que ${ui.add.name} voit comme « Mon empreinte ». Si elles diffèrent, supprimez le contact.", "Compare it out loud with what ${ui.add.name} sees as “My fingerprint”. If they differ, delete the contact."))
                Button(onClick = { vm.openChat(ui.add.name) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Ouvrir la discussion", "Open the conversation")) }
            } else {
                Text(tr("Faire scanner ce QR par ${ui.add.name}.", "Have ${ui.add.name} scan this QR."))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(remember(invite) { qrBitmap(invite) }, tr("Mon QR pour ${ui.add.name}", "My QR for ${ui.add.name}"),
                        filterQuality = FilterQuality.None, modifier = Modifier.size(280.dp))
                }
                Text(tr("Mon empreinte : ${ui.mySafety}", "My fingerprint: ${ui.mySafety}"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(tr("  En attente du scan de ${ui.add.name}…", "  Waiting for ${ui.add.name} to scan…"))
                }
                Text(tr("Partager le contenu du QR code si votre destinataire est distant.", "Share the QR code content if the recipient is not nearby."), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { shareInvite(ctx, invite) }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Partager", "Share"))
                }
            }
        }
    }
    Messages(ui, vm)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(name: String, ui: Ui, vm: MainViewModel) {
    var text by rememberSaveable(name) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val row = ui.contacts.firstOrNull { it.name == name }
    LaunchedEffect(ui.chat.size) { if (ui.chat.isNotEmpty()) list.animateScrollToItem(ui.chat.size - 1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(name)
                        row?.safety?.let { Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
                    }
                },
                navigationIcon = { IconButton(onClick = { vm.goContacts() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Retour", "Back")) } },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(tr("Réinitialiser la session", "Reset the session")) }, onClick = { menu = false; confirm = "reset" })
                        DropdownMenuItem(text = { Text(tr("Supprimer le contact", "Delete the contact")) }, onClick = { menu = false; confirm = "remove" })
                    }
                },
            )
        },
        bottomBar = {
            if (row?.burned == true) {
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Column(
                        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            tr("$name a effacé son identité : cette conversation ne lui parvient plus. Elle peut être supprimée.", "$name erased their identity: this conversation no longer reaches them. It can be deleted."),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Button(onClick = { confirm = "remove" }, modifier = Modifier.fillMaxWidth()) { Text(tr("Supprimer la conversation", "Delete the conversation")) }
                    }
                }
            } else {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val bytes = text.toByteArray().size
                    val counter: (@Composable () -> Unit)? =
                        if (bytes > vm.maxText - 300) {
                            { Text(tr("$bytes / ${vm.maxText} octets", "$bytes / ${vm.maxText} bytes")) }
                        } else {
                            null
                        }
                    OutlinedTextField(
                        text, { text = it }, modifier = Modifier.weight(1f), maxLines = 5,
                        placeholder = { Text("Message") },
                        isError = bytes > vm.maxText,
                        supportingText = counter,
                    )
                    IconButton(
                        onClick = { vm.send(name, text); text = "" },
                        enabled = text.isNotBlank() && bytes <= vm.maxText,
                    ) { Icon(Icons.AutoMirrored.Filled.Send, tr("Envoyer", "Send")) }
                }
            }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ConnectionBanner(ui)
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), state = list) {
                items(ui.chat, key = { it.key }) { m -> Bubble(m, name) }
            }
        }
    }

    when (confirm) {
        "reset" -> AlertDialog(
            onDismissRequest = { confirm = null },
            confirmButton = { TextButton(onClick = { confirm = null; vm.resetSession(name) }) { Text(tr("Réinitialiser", "Reset")) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr("Annuler", "Cancel")) } },
            text = { Text(tr("À utiliser si les messages ne passent plus. $name devra aussi réinitialiser la session de son côté.", "Use this if messages no longer get through. $name will also have to reset the session on their side.")) },
        )
        "remove" -> AlertDialog(
            onDismissRequest = { confirm = null },
            confirmButton = { TextButton(onClick = { confirm = null; vm.removeContact(name) }) { Text(tr("Supprimer", "Delete")) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr("Annuler", "Cancel")) } },
            text = { Text(tr("Supprimer $name, l'historique de la conversation et sa boîte sur le relais ?", "Delete $name, the conversation history and their box on the relay?")) },
        )
    }
    Messages(ui, vm)
}

@Composable
private fun Bubble(m: MessageRow, name: String) {
    if (m.status == MessageStatus.ALERT || m.status == MessageStatus.RELAY_CHANGE) {
        val (title, body) = if (m.status == MessageStatus.ALERT) {
            tr("$name a utilisé son code de destruction", "$name used their destruction code") to
                tr("Sa clé privée est détruite : cette conversation ne lui parvient plus. Pensez à supprimer vos conversations avec $name.", "Their private key is destroyed: this conversation no longer reaches them. Consider deleting your conversations with $name.")
        } else {
            tr("$name a changé de relais", "$name changed relay") to
                tr("Son ancienne identité est effacée : cette conversation ne lui parvient plus. Pour continuer, supprimer le contact et refaire l'appairage.", "Their old identity is erased: this conversation no longer reaches them. To continue, delete the contact and pair again.")
        }
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(body, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(fmt(m.ts), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        return
    }
    val mine = m.outgoing
    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(m.text)
                val status = when (m.status) {
                    MessageStatus.SENDING -> tr(" · envoi…", " · sending…")
                    MessageStatus.FAILED -> tr(" · échec", " · failed")
                    else -> ""
                }
                Text(fmt(m.ts) + status, style = MaterialTheme.typography.labelSmall,
                    color = if (m.status == MessageStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}


// ---------- codes et verrouillage ----------

@Composable
private fun CodeField(value: String, onChange: (String) -> Unit, label: String, onDone: (() -> Unit)? = null) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        // Type « mot de passe » : le clavier n'apprend pas et ne suggère rien.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun SetCodesScreen(migrating: Boolean, ui: Ui, vm: MainViewModel) {
    var u1 by remember { mutableStateOf("") }
    var u2 by remember { mutableStateOf("") }
    var d1 by remember { mutableStateOf("") }
    var d2 by remember { mutableStateOf("") }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Text(tr("Les deux codes", "The two codes"), style = MaterialTheme.typography.headlineSmall)
            if (migrating) Text(tr("Nouvelle version : Paranoid est désormais protégé par un code.", "New version: Paranoid is now protected by a code."))
            Text(tr("Code de déverrouillage : ouvre l'appli. Au moins ${vm.minCode} caractères.", "Unlock code: opens the app. At least ${vm.minCode} characters."))
            CodeField(u1, { u1 = it }, tr("Code de déverrouillage", "Unlock code"))
            CodeField(u2, { u2 = it }, tr("Confirmer le code de déverrouillage", "Confirm the unlock code"))
            HorizontalDivider()
            Text(tr("Code de destruction : saisi à la place du code normal, il alerte tous les contacts, " +
                "détruit la clé privée et efface tout.", "Destruction code: entered instead of the normal code, it alerts all contacts, " +
                "destroys the private key and erases everything."),
                color = MaterialTheme.colorScheme.error)
            CodeField(d1, { d1 = it }, tr("Code de destruction", "Destruction code"))
            CodeField(d2, { d2 = it }, tr("Confirmer le code de destruction", "Confirm the destruction code"))
            Button(onClick = { vm.setCodes(u1, u2, d1, d2) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (ui.busy) tr("Chiffrement…", "Encrypting…") else tr("Enregistrer", "Save"))
            }
            Text(tr("Aucun moyen de récupérer un code oublié.", "A forgotten code cannot be recovered."), style = MaterialTheme.typography.bodySmall)
        }
    }
    Messages(ui, vm)
}

@Composable
fun LockScreen(ui: Ui, vm: MainViewModel) {
    var code by remember { mutableStateOf("") }
    val submit = { if (code.isNotEmpty() && !ui.busy) { vm.unlock(code); code = "" } }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Image(painterResource(R.drawable.logo), contentDescription = "Paranoid", modifier = Modifier.widthIn(max = 220.dp))
            CodeField(code, { code = it }, "Code", onDone = submit)
            Button(onClick = submit, enabled = code.isNotEmpty() && !ui.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (ui.busy) tr("Vérification…", "Checking…") else tr("Déverrouiller", "Unlock"))
            }
        }
    }
    Messages(ui, vm)
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeCodesScreen(ui: Ui, vm: MainViewModel) {
    var cur by remember { mutableStateOf("") }
    var u1 by remember { mutableStateOf("") }
    var u2 by remember { mutableStateOf("") }
    var d1 by remember { mutableStateOf("") }
    var d2 by remember { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Modification des codes", "Change codes")) },
                navigationIcon = { IconButton(onClick = { vm.goContacts() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Retour", "Back")) } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CodeField(cur, { cur = it }, tr("Code actuel", "Current code"))
            HorizontalDivider()
            Text(tr("Nouveau code de déverrouillage (au moins ${vm.minCode} caractères).", "New unlock code (at least ${vm.minCode} characters)."))
            CodeField(u1, { u1 = it }, tr("Nouveau code de déverrouillage", "New unlock code"))
            CodeField(u2, { u2 = it }, tr("Confirmer le nouveau code de déverrouillage", "Confirm the new unlock code"))
            HorizontalDivider()
            Text(tr("Nouveau code de destruction : saisi à la place du code normal, il alerte tous les contacts, " +
                "détruit la clé privée et efface tout.", "New destruction code: entered instead of the normal code, it alerts all contacts, " +
                "destroys the private key and erases everything."), color = MaterialTheme.colorScheme.error)
            CodeField(d1, { d1 = it }, tr("Nouveau code de destruction", "New destruction code"))
            CodeField(d2, { d2 = it }, tr("Confirmer le nouveau code de destruction", "Confirm the new destruction code"))
            Button(
                onClick = { vm.changeCodes(cur, u1, u2, d1, d2); cur = ""; u1 = ""; u2 = ""; d1 = ""; d2 = "" },
                enabled = cur.isNotEmpty() && !ui.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (ui.busy) tr("Chiffrement…", "Encrypting…") else tr("Enregistrer", "Save")) }
        }
    }
    Messages(ui, vm)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeRelayScreen(ui: Ui, vm: MainViewModel) {
    var pick by remember { mutableStateOf<RelayRow?>(null) }
    var url by rememberSaveable { mutableStateOf("") }
    var fp by rememberSaveable { mutableStateOf("") }
    var cur by remember { mutableStateOf("") }
    // Empreinte lue sur le relais privé : reportée dans le champ si l'adresse n'a pas changé entre-temps.
    LaunchedEffect(ui.probedFp, ui.probedUrl) {
        if (ui.probedFp != null && ui.probedUrl == url.trim().trimEnd('/')) fp = ui.probedFp
    }
    val targetUrl = pick?.url ?: url
    val targetFp = pick?.fp ?: fp
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Changer de relais", "Change relay")) },
                navigationIcon = { IconButton(onClick = { vm.goContacts() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Retour", "Back")) } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Text(tr("  Tout sera effacé", "  Everything will be erased"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    Text(
                        tr("L'identité, les contacts et les conversations sont détruits, puis une nouvelle identité est " +
                            "créée sur le nouveau relais.\n\n" +
                            "Chaque contact reçoit l'alerte « X a changé de relais » et sa conversation est fermée.",
                            "The identity, contacts and conversations are destroyed, then a new identity is " +
                            "created on the new relay.\n\n" +
                            "Each contact receives the alert “X changed relay” and their conversation is closed."),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Text(tr("Relais actuel : ${ui.relayUrl}", "Current relay: ${ui.relayUrl}"), style = MaterialTheme.typography.bodySmall)

            // ---------- relais publics ----------
            Text(tr("Relais publics", "Public relays"), style = MaterialTheme.typography.titleMedium)
            Text(tr("Relais publiés dans l'annuaire de getparanoid.fr par leur opérateur. Afficher la liste contacte getparanoid.fr, qui voit alors l'adresse IP du téléphone.",
                "Relays published by their operator in the getparanoid.fr directory. Showing the list contacts getparanoid.fr, which then sees the phone's IP address."),
                style = MaterialTheme.typography.bodySmall)
            val dir = ui.directory
            if (dir == null) {
                OutlinedButton(onClick = { vm.loadDirectory() }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Afficher les relais publics", "Show public relays"))
                }
            } else {
                if (dir.isEmpty()) Text(tr("Aucun relais publié pour le moment.", "No relay published yet."))
                dir.forEach { r ->
                    val current = r.url == ui.relayUrl.trimEnd('/') && r.fp == ui.relayFp
                    RelayCard(r, selected = pick?.url == r.url && pick?.fp == r.fp, current = current) {
                        pick = r; url = ""; fp = ""
                    }
                }
                TextButton(onClick = { pick = null; vm.loadDirectory() }, enabled = !ui.busy) { Text(tr("Actualiser la liste", "Refresh the list")) }
            }
            HorizontalDivider()

            // ---------- relais privé ----------
            Text(tr("Relais privé", "Private relay"), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(url, { url = it; pick = null; fp = "" }, label = { Text(tr("Adresse du relais (https://…)", "Relay address (https://…)")) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(fp, { fp = it; pick = null }, label = { Text(tr("Empreinte SHA-256 (facultative)", "SHA-256 fingerprint (optional)")) },
                modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            if (fp.isBlank()) {
                OutlinedButton(onClick = { vm.probeRelay(url) }, enabled = url.trim().startsWith("https://") && !ui.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Lire l'empreinte présentée par le relais", "Read the fingerprint presented by the relay"))
                }
                Text(tr("Sans empreinte saisie, l'appli lit celle que présente le relais et l'affiche ici. Si possible, comparez-la avec celle que donne son opérateur (paranoid-relay info) : c'est elle qui sera épinglée.",
                    "Without a fingerprint, the app reads the one the relay presents and shows it here. If possible, compare it with the one its operator gives (paranoid-relay info): it is the one that will be pinned."),
                    style = MaterialTheme.typography.bodySmall)
            } else if (ui.probedFp != null && fp == ui.probedFp) {
                Text(tr("Empreinte lue sur le relais : à vérifier avec son opérateur si possible.", "Fingerprint read from the relay: check it with its operator if possible."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            HorizontalDivider()

            // ---------- validation ----------
            Text(
                when {
                    pick != null -> tr("Nouveau relais : ${pick!!.name} (${pick!!.url})", "New relay: ${pick!!.name} (${pick!!.url})")
                    url.isNotBlank() -> tr("Nouveau relais : ${url.trim()}", "New relay: ${url.trim()}")
                    else -> tr("Choisir un relais public ou saisir un relais privé.", "Pick a public relay or enter a private one.")
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(tr("Le nouveau relais est testé (connexion et empreinte) avant que quoi que ce soit ne soit effacé.", "The new relay is tested (connection and fingerprint) before anything is erased."),
                style = MaterialTheme.typography.bodySmall)
            CodeField(cur, { cur = it }, tr("Code actuel", "Current code"))
            Button(
                onClick = { vm.changeRelay(cur, targetUrl, targetFp); cur = "" },
                enabled = cur.isNotEmpty() && targetUrl.isNotBlank() && targetFp.isNotBlank() && !ui.busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (ui.busy) tr("Patienter…", "Please wait…") else tr("Tout effacer et changer de relais", "Erase everything and change relay")) }
        }
    }
    Messages(ui, vm)
}

@Composable
private fun RelayCard(r: RelayRow, selected: Boolean, current: Boolean, onPick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (selected) colors.primaryContainer else colors.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(enabled = !current, onClick = onPick),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    when {
                        current -> tr("Relais actuel", "Current relay")
                        r.online -> tr("En ligne", "Online")
                        else -> tr("Hors ligne", "Offline")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (r.online && !current) colors.primary else colors.onSurfaceVariant,
                )
            }
            Text(r.url, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            Text(tr("Version ${r.version} · dernier signal ${ago(r.lastSeen)}", "Version ${r.version} · last signal ${ago(r.lastSeen)}"),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (selected) {
                Text(tr("Empreinte : ${r.fp}", "Fingerprint: ${r.fp}"), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        }
    }
}

private fun ago(lastSeen: Long): String {
    val s = (System.currentTimeMillis() / 1000 - lastSeen).coerceAtLeast(0)
    return when {
        s < 120 -> tr("à l'instant", "just now")
        s < 7200 -> tr("il y a ${s / 60} min", "${s / 60} min ago")
        s < 172800 -> tr("il y a ${s / 3600} h", "${s / 3600} h ago")
        else -> tr("il y a ${s / 86400} jours", "${s / 86400} days ago")
    }
}
