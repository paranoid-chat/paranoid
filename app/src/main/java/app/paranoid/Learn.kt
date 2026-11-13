package app.paranoid

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import app.paranoid.core.formatFingerprint
import app.paranoid.core.tr
import app.paranoid.core.sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * « Comprendre Paranoid » : explications hors ligne, à deux niveaux (simple, puis « En savoir
 * plus » technique), avec des schémas dessinés dans l'appli. Aucun accès réseau ici.
 */

private class Topic(
    val title: String,
    val summary: String,
    val simple: String,
    val tech: String? = null,
    val diagram: (@Composable () -> Unit)? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearnScreen(ui: Ui, vm: MainViewModel) {
    var open by rememberSaveable { mutableIntStateOf(-1) }
    BackHandler(enabled = open != -1) { open = -1 }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (open == -1) tr("Comprendre Paranoid", "Understanding Paranoid") else TOPICS[open].title) },
                navigationIcon = {
                    IconButton(onClick = { if (open == -1) vm.goContacts() else open = -1 }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Retour", "Back"))
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (open == -1) {
                TOPICS.forEachIndexed { i, t ->
                    ListItem(
                        headlineContent = { Text(t.title) },
                        supportingContent = { Text(t.summary) },
                        modifier = Modifier.clickable { open = i },
                    )
                    HorizontalDivider()
                }
            } else {
                TopicPage(TOPICS[open])
            }
        }
    }
}

/** « Mes infos en direct », accessible depuis le menu de la liste des contacts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyInfoScreen(ui: Ui, vm: MainViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Mes infos en direct", "My live info")) },
                navigationIcon = { IconButton(onClick = { vm.goContacts() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Retour", "Back")) } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { LiveInfo(ui, vm) }
    }
}

@Composable
private fun TopicPage(t: Topic) {
    var more by rememberSaveable(t.title) { mutableStateOf(false) }
    t.diagram?.let { Diagram(it) }
    Text(t.simple, style = MaterialTheme.typography.bodyLarge)
    val tech = t.tech ?: return
    OutlinedButton(onClick = { more = !more }, modifier = Modifier.fillMaxWidth()) {
        Text(if (more) tr("Masquer le détail technique", "Hide the technical details") else tr("En savoir plus (technique)", "Learn more (technical)"))
    }
    if (more) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
            Text(tech, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---------- Mes infos en direct ----------

@Composable
private fun LiveInfo(ui: Ui, vm: MainViewModel) {
    val ctx = LocalContext.current
    val protection by produceState("…") { value = withContext(Dispatchers.IO) { vm.keyProtection() } }
    val signer = androidx.compose.runtime.remember { apkSigner(ctx) }
    Text(tr("Ces valeurs sont lues sur ce téléphone à l'instant. Elles ne sont pas secrètes, mais à ne montrer qu'à qui en a besoin.", "These values are read on this phone right now. They are not secret, but should only be shown to whoever needs them."),
        style = MaterialTheme.typography.bodyMedium)
    Info(tr("Mon empreinte de sécurité", "My safety fingerprint"), ui.mySafety,
        tr("Le contact doit voir exactement cette suite pour cette identité. 10 premiers octets de SHA-256 de la clé d'identité.", "The contact must see exactly this sequence for this identity. First 10 bytes of the SHA-256 of the identity key."))
    Info(tr("Mon relais", "My relay"), ui.relayUrl, null)
    Info(tr("Empreinte du certificat du relais", "Relay certificate fingerprint"), ui.relayFp,
        tr("SHA-256 du certificat TLS. L'appli refuse tout relais dont le certificat a une autre empreinte.", "SHA-256 of the TLS certificate. The app refuses any relay whose certificate has another fingerprint."))
    Info("Contacts", "${ui.contacts.size}", null)
    Info(tr("Protection de la clé du coffre", "Vault key protection"), protection,
        tr("Où vit la clé Android Keystore qui chiffre le coffre. StrongBox est le meilleur niveau.", "Where the Android Keystore key that encrypts the vault lives. StrongBox is the best level."))
    Info(tr("Signature de l'appli (SHA-256 du certificat)", "App signature (certificate SHA-256)"), signer,
        tr("À comparer avec « apksigner verify --print-certs » sur l'APK installé. Android refuse toute mise à jour signée par une autre clé.", "Compare with “apksigner verify --print-certs” on the installed APK. Android refuses any update signed with another key."))
    Info("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", null)
}

@Composable
private fun Info(label: String, value: String, help: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        help?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    HorizontalDivider()
}

private fun apkSigner(ctx: Context): String = runCatching {
    val info = ctx.packageManager.getPackageInfo(
        ctx.packageName,
        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
    )
    val signers = info.signingInfo?.apkContentsSigners
    if (signers.isNullOrEmpty()) tr("inconnue", "unknown") else signers.joinToString("\n") { formatFingerprint(sha256(it.toByteArray())) }
}.getOrDefault(tr("inconnue", "unknown"))

// ---------- schémas ----------

private enum class Kind { ME, PEER, RELAY, SECRET, PUBLIC, NEUTRAL }

@Composable
private fun Kind.color(): Color = when (this) {
    Kind.ME -> MaterialTheme.colorScheme.primary
    Kind.PEER -> Color(0xFF1565C0)
    Kind.RELAY -> Color(0xFF6D4C41)
    Kind.SECRET -> MaterialTheme.colorScheme.error
    Kind.PUBLIC -> Color(0xFF2E7D32)
    Kind.NEUTRAL -> MaterialTheme.colorScheme.outline
}

@Composable
private fun Diagram(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            content()
        }
    }
}

@Composable
private fun Node(text: String, kind: Kind, modifier: Modifier = Modifier) {
    val c = kind.color()
    Surface(
        border = BorderStroke(1.5.dp, c),
        color = c.copy(alpha = 0.10f),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
    ) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 8.dp), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun RowScope.N(text: String, kind: Kind, weight: Float = 1.3f) = Node(text, kind, Modifier.weight(weight))

/** Flèche horizontale, avec une étiquette au-dessus ; [both] pour un échange dans les deux sens. */
@Composable
private fun RowScope.Arrow(label: String = "", both: Boolean = false, weight: Float = 1f) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.weight(weight).padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, color = color)
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            val y = size.height / 2
            val h = 4.dp.toPx()
            drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
            fun head(x: Float, dir: Float) = drawPath(
                Path().apply { moveTo(x, y); lineTo(x - dir * h * 1.8f, y - h); lineTo(x - dir * h * 1.8f, y + h); close() },
                color,
            )
            head(size.width, 1f)
            if (both) head(0f, -1f)
        }
    }
}

/** Flèche vers le bas, avec une étiquette à droite. */
@Composable
private fun DownArrow(label: String = "") {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(14.dp).height(26.dp)) {
            val x = size.width / 2
            val h = 4.dp.toPx()
            drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            drawPath(Path().apply { moveTo(x, size.height); lineTo(x - h, size.height - h * 1.8f); lineTo(x + h, size.height - h * 1.8f); close() }, color)
        }
        if (label.isNotEmpty()) Text("  $label", style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** Couche de chiffrement, pour les schémas « poupées russes ». */
@Composable
private fun Layer(title: String, kind: Kind, content: @Composable () -> Unit = {}) {
    val c = kind.color()
    Surface(border = BorderStroke(1.5.dp, c), color = c.copy(alpha = 0.06f), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = c)
            content()
        }
    }
}

@Composable
private fun Caption(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Line(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = content)

// ---------- contenu ----------

private val TOPICS: List<Topic> get() = listOf(
    Topic(
        title = tr("L'identité : les clés", "Identity: the keys"),
        summary = tr("Pas de compte : une paire de clés créée sur le téléphone", "No account: a key pair created on the phone"),
        diagram = {
            Line {
                N(tr("Clé privée\n(secrète, reste\ndans le téléphone)", "Private key\n(secret, stays\non the phone)"), Kind.SECRET)
                Arrow(tr("calcul\nà sens unique", "one-way\ncomputation"))
                N(tr("Clé publique\n(partageable)", "Public key\n(shareable)"), Kind.PUBLIC)
            }
            DownArrow(tr("dans le QR", "in the QR"))
            Node(tr("Le contact", "The contact"), Kind.PEER)
            Caption(tr("Impossible de retrouver la clé privée à partir de la clé publique.", "The private key cannot be derived from the public key."))
        },
        simple = tr(
            "Au premier lancement, Paranoid tire au hasard une paire de clés. La clé privée reste dans le téléphone ; " +
                "la clé publique est donnée aux contacts, dans le QR.\n\n" +
                "Avec la clé publique de A et sa propre clé privée, B calcule un secret. A obtient le même secret avec sa clé privée " +
                "et la clé publique de B. Personne d'autre ne peut le calculer, même en ayant vu passer les deux clés publiques : " +
                "c'est ce secret qui protège les messages.\n\n" +
                "Il n'y a ni compte, ni numéro de téléphone, ni adresse mail : l'identité, c'est cette paire de clés. " +
                "Si elle est effacée, personne ne peut la recréer.",
            "On first launch, Paranoid randomly generates a key pair. The private key stays on the phone; " +
                "the public key is given to contacts, in the QR.\n\n" +
                "With A's public key and its own private key, B computes a secret. A gets the same secret with its private key " +
                "and B's public key. Nobody else can compute it, even after seeing both public keys go by: " +
                "this secret is what protects the messages.\n\n" +
                "There is no account, no phone number, no email address: the identity is this key pair. " +
                "If it is erased, nobody can recreate it.",
        ),
        tech = tr(
            "Deux paires sont tirées par SecureRandom (implémentation BouncyCastle 1.78.1) :\n" +
                "• Ed25519 (RFC 8032), la clé d'identité « ik » : elle sert à l'empreinte de sécurité, à fixer qui est " +
                "l'initiateur (le plus petit ik, octet par octet) et aux données associées de chaque message ;\n" +
                "• X25519 (RFC 7748), la clé d'échange « xk » : elle sert au calcul Diffie-Hellman du secret initial.\n\n" +
                "Les clés privées ne sont jamais envoyées au relais. Elles ne vivent que dans l'état chiffré (voir « Stockage ») " +
                "et en mémoire tant que l'appli est déverrouillée.",
            "Two pairs are generated by SecureRandom (BouncyCastle 1.78.1 implementation):\n" +
                "• Ed25519 (RFC 8032), the identity key “ik”: it is used for the safety fingerprint, to decide who is " +
                "the initiator (the smallest ik, byte by byte) and in the associated data of every message;\n" +
                "• X25519 (RFC 7748), the exchange key “xk”: it is used for the Diffie-Hellman computation of the initial secret.\n\n" +
                "Private keys are never sent to the relay. They only live in the encrypted state (see “Storage”) " +
                "and in memory while the app is unlocked.",
        ),
    ),
    Topic(
        title = tr("Appairage et empreinte", "Pairing and fingerprint"),
        summary = tr("Un seul scan, en face à face, puis une vérification à voix haute", "A single scan, face to face, then an out-loud check"),
        diagram = {
            Line {
                N(tr("A affiche\nson QR", "A shows\nits QR"), Kind.ME)
                Arrow(tr("B le scanne,\nface à face", "B scans it,\nface to face"), weight = 1.2f)
                N("B", Kind.PEER)
            }
            DownArrow(tr("le téléphone de B envoie ses clés à A, chiffrées par le secret du QR", "B's phone sends its keys to A, encrypted with the QR secret"))
            Line {
                N(tr("Chez B,\nl'empreinte de A :\n1A2B 3C4D …", "On B's phone,\nA's fingerprint:\n1A2B 3C4D …"), Kind.NEUTRAL)
                Text(" = ", style = MaterialTheme.typography.titleLarge)
                N(tr("Chez A,\n« Mon empreinte » :\n1A2B 3C4D …", "On A's phone,\n“My fingerprint”:\n1A2B 3C4D …"), Kind.NEUTRAL)
            }
        },
        simple = tr(
            "Pour ajouter quelqu'un, l'un affiche son QR et l'autre le scanne, en face à face. Le QR contient la clé publique, " +
                "l'adresse de la boîte réservée à ce contact et un secret qui ne sert qu'une fois. En le scannant, le téléphone de l'autre " +
                "renvoie ses propres clés par le relais, protégées par ce secret : l'appairage se termine tout seul, en quelques secondes.\n\n" +
                "L'empreinte de sécurité est un résumé de la clé publique, en 20 caractères. Une fois l'appairage terminé, elle se compare " +
                "à voix haute : l'empreinte affichée chez le contact doit être « Mon empreinte » sur ce téléphone, et inversement. " +
                "Si elles diffèrent, supprimer le contact et recommencer.\n\n" +
                "Le QR ne doit être montré qu'à la personne concernée : quelqu'un qui le photographierait pourrait tenter de se faire " +
                "passer pour elle. La comparaison des empreintes le révélerait.",
            "To add someone, one shows their QR and the other scans it, face to face. The QR contains the public key, " +
                "the address of the box reserved for this contact and a secret that works only once. When scanning it, the other phone " +
                "sends back its own keys through the relay, protected by this secret: pairing completes by itself, in a few seconds.\n\n" +
                "The safety fingerprint is a summary of the public key, in 20 characters. Once pairing is complete, compare it " +
                "out loud: the fingerprint shown on the contact's phone must be “My fingerprint” on this phone, and vice versa. " +
                "If they differ, delete the contact and start over.\n\n" +
                "The QR should only be shown to the person concerned: someone who photographed it could try to impersonate " +
                "them. Comparing fingerprints would reveal it.",
        ),
        tech = tr(
            "Le QR encode en base64url un JSON {v:2, ik, xk, r:[{u, fp, w}], s} : les deux clés publiques, l'URL du relais, " +
                "l'empreinte SHA-256 de son certificat, le write_id de la boîte créée pour ce contact seulement, et un secret de 32 octets.\n\n" +
                "Celui qui scanne crée sa boîte pour l'invitant, puis dépose dans la boîte de l'invitant un bloc d'appairage de 2048 octets " +
                "(version 3) : X25519 éphémère, puis AES-256-GCM avec une clé HKDF-SHA256(X25519(éphémère, xk) ‖ secret). Le contenu " +
                "(ses ik, xk, write_id et relais) est signé par sa clé d'identité Ed25519, ce qui lie sa clé xk à l'ik que couvre l'empreinte.\n\n" +
                "Le secret est effacé dès le premier bloc valide : l'invitation ne sert qu'une fois.\n\n" +
                "Empreinte de sécurité : les 10 premiers octets de SHA-256(ik), soit 80 bits, affichés en hexadécimal.",
            "The QR encodes in base64url a JSON {v:2, ik, xk, r:[{u, fp, w}], s}: both public keys, the relay URL, " +
                "the SHA-256 fingerprint of its certificate, the write_id of the box created for this contact only, and a 32-byte secret.\n\n" +
                "The scanner creates its own box for the inviter, then deposits a 2048-byte pairing block in the inviter's box " +
                "(version 3): ephemeral X25519, then AES-256-GCM with a key HKDF-SHA256(X25519(ephemeral, xk) ‖ secret). The content " +
                "(its ik, xk, write_id and relay) is signed with its Ed25519 identity key, which binds its xk key to the ik covered by the fingerprint.\n\n" +
                "The secret is erased on the first valid block: the invitation works only once.\n\n" +
                "Safety fingerprint: the first 10 bytes of SHA-256(ik), i.e. 80 bits, shown in hexadecimal.",
        ),
    ),
    Topic(
        title = tr("Le relais", "The relay"),
        summary = tr("Une boîte aux lettres qui ne peut rien lire", "A mailbox that cannot read anything"),
        diagram = {
            Line {
                N("A", Kind.ME, 1f)
                Arrow(tr("bloc chiffré\n2048 octets", "encrypted block\n2048 bytes"))
                N(tr("Relais\n(boîte)", "Relay\n(box)"), Kind.RELAY, 1f)
                Arrow(tr("relève", "fetch"))
                N("B", Kind.PEER, 1f)
            }
            Line {
                N(tr("Il voit : adresses IP, heures, numéros de boîte, nombre de blocs", "It sees: IP addresses, times, box numbers, number of blocks"), Kind.NEUTRAL, 1f)
                Text("  ")
                N(tr("Il ne voit pas : le contenu, les noms, les clés d'identité, la longueur des messages", "It does not see: content, names, identity keys, message length"), Kind.PUBLIC, 1f)
            }
        },
        simple = tr(
            "Le relais est une boîte aux lettres : les messages y attendent, chiffrés, que le destinataire vienne les chercher. " +
                "Il ne peut pas les lire, et tous les blocs ont la même taille : il ne sait même pas si le message fait « ok » ou une page entière.\n\n" +
                "Le relais est interchangeable : c'est un petit programme que n'importe qui peut héberger, chez soi par exemple. " +
                "L'appli vérifie qu'elle parle au bon relais grâce à l'empreinte de son certificat.\n\n" +
                "Un message non relevé est supprimé au bout de 7 jours, et une boîte accepte 100 messages en attente au maximum.",
            "The relay is a mailbox: messages wait there, encrypted, until the recipient comes to fetch them. " +
                "It cannot read them, and all blocks have the same size: it does not even know whether the message is “ok” or a whole page.\n\n" +
                "The relay is interchangeable: it is a small program anyone can host, at home for instance. " +
                "The app checks it is talking to the right relay thanks to its certificate fingerprint.\n\n" +
                "A message that is not fetched is deleted after 7 days, and a box accepts at most 100 pending messages.",
        ),
        tech = tr(
            "HTTPS en TLS 1.3, avec un certificat auto-signé épinglé : l'appli compare l'empreinte SHA-256 du certificat, " +
                "sans autorité de certification (le nom d'hôte n'est pas utilisé).\n\n" +
                "Chaque boîte a deux identifiants aléatoires : box_id pour lire, write_id pour déposer (celui du QR, ou celui envoyé à l'appairage). " +
                "La relève et la suppression sont signées par une clé Ed25519 propre à la boîte, avec horodatage (±60 s) et nonce " +
                "contre le rejeu. Aucune clé commune ne relie les boîtes d'une même personne.\n\n" +
                "Limites : blocs de 2048 octets exactement, 100 en attente par boîte, 7 jours de conservation, boîte supprimée après " +
                "90 jours sans relève, 10 créations de boîtes par heure et par IP, 60 dépôts par minute et par boîte. " +
                "Plafond global : au-delà de 90 % de la taille maximale de la base (1 Gio par défaut), plus de nouvelle boîte ; à 100 %, " +
                "ou s'il reste moins de 2 Gio libres sur le disque, plus de dépôt. L'appli affiche alors « le relais est plein ». " +
                "Le relais n'écrit aucun journal. Le protocole complet est dans PROTOCOL.md.",
            "HTTPS over TLS 1.3, with a pinned self-signed certificate: the app compares the certificate's SHA-256 fingerprint, " +
                "without a certificate authority (the host name is not used).\n\n" +
                "Each box has two random identifiers: box_id to read, write_id to deposit (the one in the QR, or the one sent at pairing). " +
                "Fetching and deletion are signed with an Ed25519 key specific to the box, with a timestamp (±60 s) and a nonce " +
                "against replay. No common key links the boxes of the same person.\n\n" +
                "Limits: blocks of exactly 2048 bytes, 100 pending per box, 7 days of retention, box deleted after " +
                "90 days without fetching, 10 box creations per hour and per IP, 60 deposits per minute and per box. " +
                "Global cap: beyond 90% of the maximum database size (1 GiB by default), no new box; at 100%, " +
                "or if less than 2 GiB remain free on the disk, no deposit. The app then shows “the relay is full”. " +
                "The relay writes no log. The full protocol is in PROTOCOL.md.",
        ),
    ),
    Topic(
        title = tr("Le chiffrement des messages", "Message encryption"),
        summary = tr("Une clé neuve pour chaque message", "A new key for every message"),
        diagram = {
            Line {
                N(tr("clé 1", "key 1"), Kind.SECRET, 1f); Arrow(); N(tr("clé 2", "key 2"), Kind.SECRET, 1f); Arrow()
                N(tr("clé 3", "key 3"), Kind.SECRET, 1f); Arrow(); N("…", Kind.NEUTRAL, 0.6f)
            }
            Caption(tr("Chaque clé sert à un seul message, puis elle est effacée. On ne peut pas revenir en arrière.", "Each key is used for a single message, then erased. There is no going back."))
            Line {
                N(tr("A écrit", "A writes"), Kind.ME, 1f)
                Arrow(tr("B répond", "B replies"), both = true, weight = 1.4f)
                N(tr("nouveau secret\npour les deux", "new secret\nfor both"), Kind.PUBLIC)
            }
            Caption(tr("À chaque changement de sens, un nouveau secret est calculé.", "Every time the direction changes, a new secret is computed."))
        },
        simple = tr(
            "Chaque message est chiffré avec une clé à usage unique, effacée juste après. C'est le « Double Ratchet » " +
                "(double cliquet), le mécanisme de Signal : les clés avancent comme un cliquet, jamais en arrière.\n\n" +
                "Conséquences : si quelqu'un volait un jour les clés d'un téléphone, il ne pourrait pas déchiffrer les anciens messages " +
                "qu'il aurait enregistrés. Et dès que la conversation reprend, il perd aussi l'accès aux suivants.\n\n" +
                "Un message modifié en route, ou copié depuis une autre conversation, est rejeté.",
            "Each message is encrypted with a single-use key, erased right after. This is the “Double Ratchet”, " +
                "Signal's mechanism: keys move forward like a ratchet, never backwards.\n\n" +
                "Consequences: if someone ever stole a phone's keys, they could not decrypt old messages " +
                "they had recorded. And as soon as the conversation resumes, they also lose access to the next ones.\n\n" +
                "A message altered on the way, or copied from another conversation, is rejected.",
        ),
        tech = tr(
            "Secret initial : X25519 entre les clés statiques xk, puis HKDF-SHA256 (info « relay-dr-v1/sk »).\n" +
                "Cliquet Diffie-Hellman : nouvelle paire X25519 à chaque changement de sens ; KDF_RK = HKDF-SHA256.\n" +
                "Cliquet symétrique : KDF_CK = HMAC-SHA256 (constantes 0x01 et 0x02).\n" +
                "Clé de message → HKDF → clé AES-256-GCM et nonce.\n" +
                "Données associées : les deux ik et l'en-tête de 42 octets.\n\n" +
                "Le contenu est complété (bourrage) à 1990 octets, pour un bloc final de 2048 octets ; texte de 1963 octets au maximum. " +
                "L'état n'avance qu'après un déchiffrement réussi. Pour les messages arrivés dans le désordre, au plus 1000 clés " +
                "sautées par message et 2000 au total. Détails dans MESSAGES.md.",
            "Initial secret: X25519 between the static xk keys, then HKDF-SHA256 (info “relay-dr-v1/sk”).\n" +
                "Diffie-Hellman ratchet: new X25519 pair at every change of direction; KDF_RK = HKDF-SHA256.\n" +
                "Symmetric ratchet: KDF_CK = HMAC-SHA256 (constants 0x01 and 0x02).\n" +
                "Message key → HKDF → AES-256-GCM key and nonce.\n" +
                "Associated data: both ik and the 42-byte header.\n\n" +
                "The content is padded to 1990 bytes, for a final 2048-byte block; text of at most 1963 bytes. " +
                "The state only moves forward after a successful decryption. For messages arriving out of order, at most 1000 skipped keys " +
                "per message and 2000 in total. Details in MESSAGES.md.",
        ),
    ),
    Topic(
        title = tr("Les codes et le code de destruction", "The codes and the destruction code"),
        summary = tr("Le code de destruction, et ce qu'il fait", "The destruction code, and what it does"),
        diagram = {
            Line {
                N(tr("Code de\ndéverrouillage", "Unlock\ncode"), Kind.ME)
                Arrow("Argon2id")
                N(tr("Appli ouverte", "App opened"), Kind.PUBLIC)
            }
            Line {
                N(tr("Code de\ndestruction", "Destruction\ncode"), Kind.SECRET)
                Arrow("Argon2id")
                N(tr("Alerte aux contacts,\ntout est effacé,\nappli vide", "Contacts alerted,\neverything erased,\nempty app"), Kind.SECRET)
            }
            Caption(tr("Vu de l'écran, les deux codes font la même chose : l'appli s'ouvre.", "On screen, both codes do the same thing: the app opens."))
        },
        simple = tr(
            "Le code de déverrouillage ouvre l'appli. Le code de destruction se saisit au même endroit : il envoie une alerte " +
                "à tous les contacts, détruit la clé privée et efface tout. Pour qui regarde l'écran, l'appli semble simplement s'ouvrir, " +
                "sur une liste de contacts vide, comme si tout avait été supprimé.\n\n" +
                "Après 5 essais ratés, l'appli impose une attente : 30 s, puis le double à chaque échec, jusqu'à 1 h. " +
                "Un code oublié ne peut pas être récupéré.\n\n" +
                "Le code de destruction fonctionne aussi dans « Modification des codes » et « Changer de relais ».",
            "The unlock code opens the app. The destruction code is entered in the same place: it sends an alert " +
                "to all contacts, destroys the private key and erases everything. To anyone watching the screen, the app simply seems to open, " +
                "on an empty contact list, as if everything had been deleted.\n\n" +
                "After 5 failed attempts, the app imposes a wait: 30 s, then doubled at every failure, up to 1 h. " +
                "A forgotten code cannot be recovered.\n\n" +
                "The destruction code also works in “Change codes” and “Change relay”.",
        ),
        tech = tr(
            "Le coffre contient deux emplacements, dans un ordre aléatoire. Pour chacun, Argon2id (64 Mio, 3 passes, 1 fil, " +
                "sel de 16 octets) dérive une clé AES-256-GCM qui chiffre (rôle ‖ clé de données). Les deux emplacements sont toujours " +
                "essayés : la durée est la même quel que soit le code.\n\n" +
                "Le code de destruction ouvre aussi la clé de données, car il faut l'état pour chiffrer les alertes. Ensuite, sans interruption possible :\n" +
                "1. destruction de la clé Keystore et des fichiers ;\n" +
                "2. nouvelle identité, dont le code de déverrouillage est le code saisi (son code de destruction est aléatoire) ;\n" +
                "3. envoi des alertes depuis la mémoire, 5 s au maximum ;\n" +
                "4. effacement de l'ancien état en mémoire.",
            "The vault holds two slots, in random order. For each, Argon2id (64 MiB, 3 passes, 1 lane, " +
                "16-byte salt) derives an AES-256-GCM key that encrypts (role ‖ data key). Both slots are always " +
                "tried: the duration is the same whatever the code.\n\n" +
                "The destruction code also opens the data key, because the state is needed to encrypt the alerts. Then, with no possible interruption:\n" +
                "1. destruction of the Keystore key and the files;\n" +
                "2. new identity, whose unlock code is the code entered (its destruction code is random);\n" +
                "3. alerts sent from memory, 5 s at most;\n" +
                "4. the old state is erased from memory.",
        ),
    ),
    Topic(
        title = tr("Stockage sur le téléphone", "Storage on the phone"),
        summary = tr("Trois couches de chiffrement, aucune sauvegarde", "Three layers of encryption, no backup"),
        diagram = {
            Layer(tr("Puce de sécurité du téléphone (Keystore) : la clé ne peut pas en sortir", "Phone security chip (Keystore): the key cannot leave it"), Kind.RELAY) {
                Layer(tr("Coffre à deux codes (Argon2id)", "Two-code vault (Argon2id)"), Kind.ME) {
                    Layer(tr("Clé de données (AES-256)", "Data key (AES-256)"), Kind.SECRET) {
                        Node(tr("Les données : identité, contacts, sessions, messages", "The data: identity, contacts, sessions, messages"), Kind.PUBLIC, Modifier.fillMaxWidth())
                    }
                }
            }
        },
        simple = tr(
            "Tout ce que Paranoid garde est chiffré, en trois couches emboîtées comme des poupées russes. La couche extérieure " +
                "est une clé enfermée dans la puce de sécurité du téléphone : elle ne peut pas en sortir, donc une copie des fichiers " +
                "est inutilisable sur un autre appareil.\n\n" +
                "Aucune sauvegarde (Google, Samsung, PC) ne contient Paranoid. Les captures d'écran sont bloquées, et l'aperçu dans " +
                "les applis récentes est masqué. L'appli se verrouille avec le cadenas, ou après 60 s en arrière-plan.\n\n" +
                "Chaque champ de saisie demande au clavier de ne rien apprendre ni mémoriser de ce qui est tapé (Gboard passe alors en mode incognito).\n\n" +
                "Dans « Paramètres », un curseur règle la durée de conservation des messages, de « pour toujours » à 24 heures. " +
                "Les messages plus anciens sont effacés de ce téléphone (pas de celui des contacts).",
            "Everything Paranoid keeps is encrypted, in three layers nested like Russian dolls. The outer layer " +
                "is a key locked in the phone's security chip: it cannot leave it, so a copy of the files " +
                "is useless on another device.\n\n" +
                "No backup (Google, Samsung, PC) contains Paranoid. Screenshots are blocked, and the preview in " +
                "recent apps is hidden. The app locks with the padlock, or after 60 s in the background.\n\n" +
                "Every input field asks the keyboard not to learn or remember anything that is typed (Gboard then switches to incognito mode).\n\n" +
                "In “Settings”, a slider sets how long messages are kept, from “forever” to 24 hours. " +
                "Older messages are erased from this phone (not from the contacts' phones).",
        ),
        tech = tr(
            "state.bin : l'état, en AES-256-GCM avec une clé de données aléatoire de 256 bits.\n" +
                "lock.bin : le coffre à deux codes, chiffré en AES-256-GCM par une clé non exportable de l'Android Keystore " +
                "(StrongBox si le téléphone en a une, sinon l'environnement d'exécution sécurisé, TEE).\n\n" +
                "Fichiers dans noBackupFilesDir, allowBackup=false, règles d'extraction excluant tout, FLAG_SECURE sur la fenêtre, " +
                "IME_FLAG_NO_PERSONALIZED_LEARNING sur tous les champs de saisie. Purge de l'historique au déverrouillage, à chaque " +
                "relève et au changement du réglage ; l'âge d'un message reçu est l'heure annoncée par l'expéditeur, jamais dans le futur. " +
                "Au verrouillage, la clé de données est effacée de la mémoire.\n\n" +
                "Détruire l'entrée Keystore rend tous les fichiers illisibles instantanément, même s'il en reste des traces dans la mémoire flash.",
            "state.bin: the state, in AES-256-GCM with a random 256-bit data key.\n" +
                "lock.bin: the two-code vault, encrypted in AES-256-GCM with a non-exportable Android Keystore key " +
                "(StrongBox if the phone has one, otherwise the trusted execution environment, TEE).\n\n" +
                "Files in noBackupFilesDir, allowBackup=false, extraction rules excluding everything, FLAG_SECURE on the window, " +
                "IME_FLAG_NO_PERSONALIZED_LEARNING on every input field. History purge when unlocking, at every " +
                "fetch and when the setting changes; a received message's age is the time announced by the sender, never in the future. " +
                "When locking, the data key is erased from memory.\n\n" +
                "Destroying the Keystore entry makes all files unreadable instantly, even if traces remain in flash memory.",
        ),
    ),
    Topic(
        title = tr("Pas de notifications", "No notifications"),
        summary = tr("Les messages arrivent quand l'appli est ouverte", "Messages arrive when the app is open"),
        diagram = {
            Line {
                N(tr("Appli ouverte\net déverrouillée", "App open\nand unlocked"), Kind.ME)
                Arrow(tr("relève\ntoutes les 5 s", "fetch\nevery 5 s"))
                N(tr("Relais", "Relay"), Kind.RELAY, 1f)
            }
            Line {
                N(tr("Google, Apple,\nautre service", "Google, Apple,\nother service"), Kind.NEUTRAL)
                Arrow(tr("rien", "nothing"))
                N(tr("Téléphone", "Phone"), Kind.ME, 1f)
            }
        },
        simple = tr(
            "Paranoid n'utilise aucun service de notification. Les messages attendent sur le relais, jusqu'à 7 jours, " +
                "et arrivent à l'ouverture de l'appli.\n\n" +
                "C'est moins pratique, mais personne d'autre (Google, Apple, un opérateur de notifications) ne sait quand un message arrive, " +
                "et aucun programme de Paranoid ne tourne en arrière-plan.",
            "Paranoid uses no notification service. Messages wait on the relay, up to 7 days, " +
                "and arrive when the app is opened.\n\n" +
                "It is less convenient, but nobody else (Google, Apple, a notification provider) knows when a message arrives, " +
                "and no Paranoid program runs in the background.",
        ),
        tech = tr(
            "Relève toutes les 5 s quand l'appli est au premier plan et déverrouillée (15 s après une erreur), et immédiatement " +
                "au retour au premier plan. Rien en arrière-plan : pas de service, pas de tâche planifiée, pas de jeton Firebase.\n\n" +
                "Permissions demandées : INTERNET, et CAMERA pour scanner les QR. Rien d'autre.",
            "Fetch every 5 s while the app is in the foreground and unlocked (15 s after an error), and immediately " +
                "when coming back to the foreground. Nothing in the background: no service, no scheduled task, no Firebase token.\n\n" +
                "Permissions requested: INTERNET, and CAMERA to scan QR codes. Nothing else.",
        ),
    ),
    Topic(
        title = tr("Changer de relais", "Changing relay"),
        summary = tr("Volontairement simple : on repart de zéro", "Deliberately simple: start from scratch"),
        diagram = {
            Line {
                N(tr("Ancien relais", "Old relay"), Kind.RELAY)
                Arrow(tr("« X a changé\nde relais »", "“X changed\nrelay”"))
                N(tr("Les contacts", "The contacts"), Kind.PEER)
            }
            DownArrow(tr("puis tout est effacé", "then everything is erased"))
            Node(tr("Nouvelle identité sur le nouveau relais", "New identity on the new relay"), Kind.ME)
            DownArrow(tr("nouveaux codes, puis nouvel appairage", "new codes, then new pairing"))
            Node(tr("Les contacts", "The contacts"), Kind.PEER)
        },
        simple = tr(
            "Il n'y a pas de migration : moins de code, c'est moins de failles possibles. Changer de relais efface donc tout.\n\n" +
                "Avant d'effacer, l'appli teste le nouveau relais, puis prévient chaque contact. Ensuite, il faut choisir de nouveaux codes " +
                "et refaire l'appairage avec chacun.",
            "There is no migration: less code means fewer possible flaws. Changing relay therefore erases everything.\n\n" +
                "Before erasing, the app tests the new relay, then notifies every contact. Then new codes must be chosen " +
                "and pairing redone with each contact.",
        ),
        tech = tr(
            "Le code actuel est exigé ; le code de destruction y a le même effet qu'au déverrouillage. Le test vérifie la connexion TLS et " +
                "l'empreinte du certificat du nouveau relais : en cas d'échec, rien n'est effacé.\n\n" +
                "L'alerte est un message chiffré de type 3, envoyé par la session en cours, 5 s au maximum. Chez le contact, la conversation " +
                "se ferme. Les anciennes boîtes ne sont plus relevées, et l'ancien relais les supprime au bout de 90 jours.",
            "The current code is required; the destruction code has the same effect there as when unlocking. The test checks the TLS connection and " +
                "the certificate fingerprint of the new relay: if it fails, nothing is erased.\n\n" +
                "The alert is an encrypted type 3 message, sent through the current session, 5 s at most. On the contact's phone, the conversation " +
                "closes. The old boxes are no longer fetched, and the old relay deletes them after 90 days.",
        ),
    ),
    Topic(
        title = tr("Limites et menaces", "Limits and threats"),
        summary = tr("Ce contre quoi Paranoid protège, et ce contre quoi il ne peut rien", "What Paranoid protects against, and what it cannot help with"),
        simple = tr(
            "Paranoid protège contre :\n" +
                "• un relais curieux ou piraté : il ne lit rien ;\n" +
                "• l'écoute du réseau (Wi-Fi public, opérateur) : double chiffrement, TLS et bout en bout ;\n" +
                "• le vol du téléphone verrouillé : coffre protégé par le code et par la puce de sécurité ;\n" +
                "• la contrainte à déverrouiller : code de destruction ;\n" +
                "• le vol des clés après coup : les anciens messages restent illisibles.\n\n" +
                "Paranoid ne peut rien contre :\n" +
                "• un téléphone compromis (logiciel espion, téléphone rooté) : il voit l'écran et le clavier ;\n" +
                "• un clavier qui ignore la demande « sans apprentissage » de l'appli, ou qui envoie ce qui est tapé : un clavier hors ligne est préférable ;\n" +
                "• les métadonnées : le relais voit les adresses IP et les heures, et peut deviner qui parle à qui ;\n" +
                "• le contact lui-même, qui peut montrer ou photographier ses messages ;\n" +
                "• une photo de l'écran prise avec un autre appareil.\n\n" +
                "Limites connues :\n" +
                "• quelqu'un qui analyse les fichiers voit qu'il existe deux codes, sans savoir lequel détruit ;\n" +
                "• le compteur d'essais est un fichier, qu'un attaquant ayant les droits root pourrait restaurer ;\n" +
                "• sans réseau, l'alerte du code de destruction ne part pas, mais l'effacement a lieu quand même ;\n" +
                "• un message non relevé en 7 jours est perdu ;\n" +
                "• effacer l'historique ne l'efface que sur ce téléphone ;\n" +
                "• le code n'a pas encore été relu par un expert indépendant.",
            "Paranoid protects against:\n" +
                "• a curious or hacked relay: it reads nothing;\n" +
                "• network eavesdropping (public Wi-Fi, carrier): double encryption, TLS and end to end;\n" +
                "• theft of the locked phone: vault protected by the code and by the security chip;\n" +
                "• being forced to unlock: destruction code;\n" +
                "• keys stolen later: old messages stay unreadable.\n\n" +
                "Paranoid cannot help against:\n" +
                "• a compromised phone (spyware, rooted phone): it sees the screen and the keyboard;\n" +
                "• a keyboard that ignores the app's “no learning” request, or sends what is typed: an offline keyboard is preferable;\n" +
                "• metadata: the relay sees IP addresses and times, and can guess who talks to whom;\n" +
                "• the contact themselves, who can show or photograph their messages;\n" +
                "• a photo of the screen taken with another device.\n\n" +
                "Known limits:\n" +
                "• someone analysing the files sees that two codes exist, without knowing which one destroys;\n" +
                "• the attempt counter is a file, which an attacker with root rights could restore;\n" +
                "• without network, the destruction code alert is not sent, but erasure happens anyway;\n" +
                "• a message not fetched within 7 days is lost;\n" +
                "• erasing history only erases it on this phone;\n" +
                "• the code has not yet been reviewed by an independent expert.",
        ),
    ),
    Topic(
        title = tr("Vérifier soi-même", "Check for yourself"),
        summary = tr("Ne pas croire sur parole : comment contrôler", "Don't take it on trust: how to check"),
        simple = tr(
            "1. Les empreintes des contacts : les comparer à voix haute, en face à face (voir « Appairage et empreinte »).\n\n" +
                "2. Le relais : depuis un PC, cette commande affiche l'empreinte de son certificat, à comparer avec « Mes infos en direct » :\n" +
                "openssl s_client -connect ADRESSE:PORT </dev/null 2>/dev/null | openssl x509 -noout -fingerprint -sha256\n\n" +
                "3. L'appli installée : « Mes infos en direct » affiche l'empreinte du certificat qui a signé l'APK. La comparer avec " +
                "« apksigner verify --print-certs paranoid.apk » sur le fichier téléchargé. Android refuse toute mise à jour signée par une autre clé.\n\n" +
                "4. Les autorisations : Paramètres Android → Applications → Paranoid → Autorisations. Seul l'appareil photo doit apparaître.\n\n" +
                "5. Le code : le dépôt du projet contient le code de l'appli et du relais, et la description exacte des échanges " +
                "(PROTOCOL.md pour le relais, MESSAGES.md pour le chiffrement). L'APK est compilé par GitHub Actions, avec ses empreintes SHA-256.\n\n" +
                "6. Le relais lui-même : relaycli, le client en ligne de commande, parle le même protocole que l'appli. Sa commande debug " +
                "affiche l'état d'une session, sans rien de secret.",
            "1. Contacts' fingerprints: compare them out loud, face to face (see “Pairing and fingerprint”).\n\n" +
                "2. The relay: from a PC, this command shows its certificate fingerprint, to compare with “My live info”:\n" +
                "openssl s_client -connect ADDRESS:PORT </dev/null 2>/dev/null | openssl x509 -noout -fingerprint -sha256\n\n" +
                "3. The installed app: “My live info” shows the fingerprint of the certificate that signed the APK. Compare it with " +
                "“apksigner verify --print-certs paranoid.apk” on the downloaded file. Android refuses any update signed with another key.\n\n" +
                "4. Permissions: Android Settings → Apps → Paranoid → Permissions. Only the camera should appear.\n\n" +
                "5. The code: the project repository holds the app and relay code, and the exact description of the exchanges " +
                "(PROTOCOL.md for the relay, MESSAGES.md for encryption). The APK is built by GitHub Actions, with its SHA-256 fingerprints.\n\n" +
                "6. The relay itself: relaycli, the command-line client, speaks the same protocol as the app. Its debug command " +
                "shows the state of a session, without anything secret.",
        ),
    ),
    Topic(
        title = tr("Glossaire", "Glossary"),
        summary = tr("Les mots techniques, en une phrase", "Technical words, in one sentence"),
        simple = tr(
            "AES-256-GCM : chiffrement symétrique (même clé pour chiffrer et déchiffrer) qui détecte aussi toute modification.\n\n" +
                "Argon2id : transforme un code en clé, en exigeant beaucoup de mémoire et de calcul, pour ralentir qui essaie tous les codes.\n\n" +
                "Boîte : emplacement sur le relais où un contact dépose les messages. Une boîte par contact et par sens.\n\n" +
                "Bourrage : octets ajoutés pour que tous les messages aient la même taille.\n\n" +
                "Clé privée / clé publique : la privée reste secrète sur le téléphone ; la publique se partage.\n\n" +
                "Code de destruction : saisi à la place du code de déverrouillage, il alerte les contacts et efface tout.\n\n" +
                "Confidentialité persistante : voler les clés d'aujourd'hui ne permet pas de lire les messages d'hier.\n\n" +
                "Diffie-Hellman : méthode qui permet à deux personnes d'obtenir un secret commun en n'échangeant que des clés publiques.\n\n" +
                "Double Ratchet : le mécanisme qui renouvelle la clé à chaque message.\n\n" +
                "Ed25519 : algorithme de signature, utilisé ici pour l'identité et pour s'authentifier auprès des boîtes.\n\n" +
                "Empreinte de sécurité : résumé court de la clé publique d'une personne, à comparer de vive voix.\n\n" +
                "Empreinte du relais : résumé SHA-256 du certificat du relais, pour être sûr de parler au bon serveur.\n\n" +
                "Épinglage : n'accepter qu'un certificat précis, plutôt que n'importe quel certificat validé par une autorité.\n\n" +
                "HKDF, HMAC : fonctions qui dérivent de nouvelles clés à partir d'un secret.\n\n" +
                "Keystore, StrongBox : coffre matériel du téléphone ; une clé qui y est créée ne peut pas en sortir.\n\n" +
                "Métadonnées : tout ce qui entoure un message sans être son contenu (qui, quand, d'où).\n\n" +
                "Nonce : valeur à usage unique, qui empêche de rejouer une requête ou de réutiliser une clé.\n\n" +
                "Relais : serveur qui garde les messages chiffrés en attendant qu'ils soient relevés.\n\n" +
                "SHA-256 : fonction de hachage ; elle produit un résumé de 32 octets, différent pour chaque donnée.\n\n" +
                "TLS 1.3 : le chiffrement de la connexion entre le téléphone et le relais.\n\n" +
                "X25519 : algorithme Diffie-Hellman sur courbe elliptique, utilisé pour calculer les secrets partagés.",
            "AES-256-GCM: symmetric encryption (same key to encrypt and decrypt) that also detects any change.\n\n" +
                "Argon2id: turns a code into a key, requiring a lot of memory and computation, to slow down anyone trying every code.\n\n" +
                "Box: a slot on the relay where a contact deposits messages. One box per contact and per direction.\n\n" +
                "Destruction code: entered instead of the unlock code, it alerts contacts and erases everything.\n\n" +
                "Diffie-Hellman: a method that lets two people obtain a shared secret by exchanging only public keys.\n\n" +
                "Double Ratchet: the mechanism that renews the key for every message.\n\n" +
                "Ed25519: a signature algorithm, used here for identity and to authenticate with boxes.\n\n" +
                "Forward secrecy: stealing today's keys does not allow reading yesterday's messages.\n\n" +
                "HKDF, HMAC: functions that derive new keys from a secret.\n\n" +
                "Keystore, StrongBox: the phone's hardware vault; a key created there cannot leave it.\n\n" +
                "Metadata: everything around a message without being its content (who, when, from where).\n\n" +
                "Nonce: a single-use value that prevents replaying a request or reusing a key.\n\n" +
                "Padding: bytes added so that all messages have the same size.\n\n" +
                "Pinning: accepting only one specific certificate, rather than any certificate validated by an authority.\n\n" +
                "Private key / public key: the private one stays secret on the phone; the public one is shared.\n\n" +
                "Relay: a server that keeps encrypted messages until they are fetched.\n\n" +
                "Relay fingerprint: the SHA-256 summary of the relay's certificate, to be sure to talk to the right server.\n\n" +
                "Safety fingerprint: a short summary of a person's public key, to compare out loud.\n\n" +
                "SHA-256: a hash function; it produces a 32-byte summary, different for every piece of data.\n\n" +
                "TLS 1.3: the encryption of the connection between the phone and the relay.\n\n" +
                "X25519: an elliptic-curve Diffie-Hellman algorithm, used to compute shared secrets.",
        ),
    ),
)
