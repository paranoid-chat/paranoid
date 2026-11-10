package app.paranoid

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.PersistableBundle
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.paranoid.core.MessageStatus
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
private fun Messages(ui: Ui, vm: MainViewModel) {
    ui.error?.let {
        AlertDialog(
            onDismissRequest = vm::dismissMessages,
            confirmButton = { TextButton(onClick = vm::dismissMessages) { Text("OK") } },
            title = { Text("Erreur") },
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
            Text("Relais injoignable : $err", color = MaterialTheme.colorScheme.onErrorContainer,
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
private fun copySensitive(ctx: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

/** Menu de partage Android : pour transmettre l'invitation à un PC sans caméra. */
private fun shareInvite(ctx: Context, invite: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, invite)
    }
    ctx.startActivity(Intent.createChooser(send, "Partager l'invitation"))
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
            Text("Données illisibles", style = MaterialTheme.typography.headlineSmall)
            Text(message)
            Text("Sans la clé de l'appareil, l'identité et les conversations ne peuvent pas être récupérées.")
            Button(onClick = { confirm = true }) { Text("Tout effacer et recommencer") }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            confirmButton = { TextButton(onClick = { confirm = false; vm.wipeAll() }) { Text("Effacer") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler") } },
            text = { Text("Effacer définitivement l'identité et toutes les conversations ?") },
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
            Spacer(Modifier.height(32.dp))
            Image(painterResource(R.drawable.logo), contentDescription = "Paranoid", modifier = Modifier.widthIn(max = 280.dp))
            Text("Aucun compte : ton identité est une clé créée sur ce téléphone, et elle ne le quitte jamais.",
                style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(url, { url = it }, label = { Text("Relais") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(fp, { fp = it }, label = { Text("Empreinte SHA-256 du relais") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.setup(url, fp) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (ui.busy) "Connexion au relais…" else "Créer mon identité")
            }
        }
    }
    Messages(ui, vm)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(ui: Ui, vm: MainViewModel) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Paranoid") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = vm::goAdd, modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                Icon(Icons.Default.Add, "Ajouter un contact")
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ConnectionBanner(ui)
            Text("Mon empreinte : ${ui.mySafety}", style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            HorizontalDivider()
            if (ui.contacts.isEmpty()) {
                Text("Aucun contact. Appuie sur + et échangez vos QR en face à face.",
                    modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(ui.contacts, key = { it.name }) { c ->
                    val sub = when {
                        c.pendingFromUnknown > 0 -> "${c.pendingFromUnknown} message(s) en attente : scanne son QR"
                        !c.inviteScanned -> "QR pas encore scanné"
                        !c.inviteShown -> "Ton QR n'a pas encore été montré"
                        c.lastText != null -> c.lastText
                        else -> "Prêt"
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
        r.contents?.let { vm.addScanned(ui.add.name, it) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajouter un contact") },
                navigationIcon = { IconButton(onClick = { vm.goContacts() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
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
                Text("1. Donne un nom à ce contact (il reste sur ton téléphone).")
                OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.createInvite(name) }, enabled = name.isNotBlank() && !ui.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (ui.busy) "Création de la boîte…" else "Afficher mon QR")
                }
            } else {
                Text("2. Fais scanner ce QR par ${ui.add.name}, en face à face uniquement.")
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(remember(invite) { qrBitmap(invite) }, "Mon QR pour ${ui.add.name}",
                        filterQuality = FilterQuality.None, modifier = Modifier.size(280.dp))
                }
                Text("Mon empreinte : ${ui.mySafety}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { copySensitive(ctx, "Invitation Paranoid", invite) }, modifier = Modifier.weight(1f)) {
                        Text("Copier")
                    }
                    OutlinedButton(onClick = { shareInvite(ctx, invite) }, modifier = Modifier.weight(1f)) {
                        Text("Partager")
                    }
                }
                Text(
                    "Sans caméra en face (PC), transmets l'invitation en texte, puis comparez vos empreintes.",
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                if (row?.inviteScanned == true) {
                    Text("3. QR de ${ui.add.name} scanné. Empreinte : ${row.safety}", fontFamily = FontFamily.Monospace)
                    Button(onClick = { vm.openChat(ui.add.name) }, modifier = Modifier.fillMaxWidth()) { Text("Ouvrir la discussion") }
                } else {
                    Text("3. Scanne ensuite le QR de ${ui.add.name}.")
                    Button(
                        onClick = {
                            scanner.launch(
                                ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    .setPrompt("QR de ${ui.add.name}").setBeepEnabled(false).setOrientationLocked(false),
                            )
                        },
                        enabled = !ui.busy, modifier = Modifier.fillMaxWidth(),
                    ) { Text("Scanner son QR") }
                    OutlinedTextField(pasted, { pasted = it }, label = { Text("… ou coller son invitation") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { vm.addScanned(ui.add.name, pasted) }, enabled = pasted.isNotBlank() && !ui.busy,
                        modifier = Modifier.fillMaxWidth()) { Text("Ajouter depuis le texte") }
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
                navigationIcon = { IconButton(onClick = { vm.goContacts() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Réinitialiser la session") }, onClick = { menu = false; confirm = "reset" })
                        DropdownMenuItem(text = { Text("Supprimer le contact") }, onClick = { menu = false; confirm = "remove" })
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val bytes = text.toByteArray().size
                    val counter: (@Composable () -> Unit)? =
                        if (bytes > vm.maxText - 300) {
                            { Text("$bytes / ${vm.maxText} octets") }
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
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "Envoyer") }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ConnectionBanner(ui)
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), state = list) {
                items(ui.chat, key = { it.key }) { m -> Bubble(m) }
            }
        }
    }

    when (confirm) {
        "reset" -> AlertDialog(
            onDismissRequest = { confirm = null },
            confirmButton = { TextButton(onClick = { confirm = null; vm.resetSession(name) }) { Text("Réinitialiser") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Annuler") } },
            text = { Text("À utiliser si les messages ne passent plus. $name devra aussi réinitialiser la session de son côté.") },
        )
        "remove" -> AlertDialog(
            onDismissRequest = { confirm = null },
            confirmButton = { TextButton(onClick = { confirm = null; vm.removeContact(name) }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Annuler") } },
            text = { Text("Supprimer $name, l'historique de la conversation et sa boîte sur le relais ?") },
        )
    }
    Messages(ui, vm)
}

@Composable
private fun Bubble(m: MessageRow) {
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
                    MessageStatus.SENDING -> " · envoi…"
                    MessageStatus.FAILED -> " · échec"
                    else -> ""
                }
                Text(fmt(m.ts) + status, style = MaterialTheme.typography.labelSmall,
                    color = if (m.status == MessageStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
