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

private const val LIVE = -2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearnScreen(ui: Ui, vm: MainViewModel) {
    var open by rememberSaveable { mutableIntStateOf(-1) }
    BackHandler(enabled = open != -1) { open = -1 }
    val title = when (open) {
        -1 -> "Comprendre Paranoid"
        LIVE -> "Mes infos en direct"
        else -> TOPICS[open].title
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { if (open == -1) vm.goContacts() else open = -1 }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
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
            when (open) {
                -1 -> {
                    Text("Tout ce que fait l'appli, sans rien cacher. Chaque fiche a une explication simple, " +
                        "puis le détail technique pour qui veut vérifier.", style = MaterialTheme.typography.bodyMedium)
                    TOPICS.forEachIndexed { i, t ->
                        ListItem(
                            headlineContent = { Text(t.title) },
                            supportingContent = { Text(t.summary) },
                            modifier = Modifier.clickable { open = i },
                        )
                        HorizontalDivider()
                    }
                    ListItem(
                        headlineContent = { Text("Mes infos en direct") },
                        supportingContent = { Text("Mon empreinte, mon relais, la signature de l'appli, la protection de la clé") },
                        modifier = Modifier.clickable { open = LIVE },
                    )
                }
                LIVE -> LiveInfo(ui, vm)
                else -> TopicPage(TOPICS[open])
            }
        }
    }
}

@Composable
private fun TopicPage(t: Topic) {
    var more by rememberSaveable(t.title) { mutableStateOf(false) }
    t.diagram?.let { Diagram(it) }
    Text(t.simple, style = MaterialTheme.typography.bodyLarge)
    val tech = t.tech ?: return
    OutlinedButton(onClick = { more = !more }, modifier = Modifier.fillMaxWidth()) {
        Text(if (more) "Masquer le détail technique" else "En savoir plus (technique)")
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
    Text("Ces valeurs sont lues sur ce téléphone à l'instant. Elles ne sont pas secrètes, mais ne les montre qu'à qui en a besoin.",
        style = MaterialTheme.typography.bodyMedium)
    Info("Mon empreinte de sécurité", ui.mySafety,
        "Ton contact doit voir exactement cette suite pour toi. 10 premiers octets de SHA-256 de ta clé d'identité.")
    Info("Mon relais", ui.relayUrl, null)
    Info("Empreinte du certificat du relais", ui.relayFp,
        "SHA-256 du certificat TLS. L'appli refuse tout relais dont le certificat a une autre empreinte.")
    Info("Contacts", "${ui.contacts.size}", null)
    Info("Protection de la clé du coffre", protection,
        "Où vit la clé Android Keystore qui chiffre le coffre. StrongBox est le meilleur niveau.")
    Info("Signature de l'appli (SHA-256 du certificat)", signer,
        "À comparer avec « apksigner verify --print-certs » sur l'APK que tu as installé. Android refuse toute mise à jour signée par une autre clé.")
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
    if (signers.isNullOrEmpty()) "inconnue" else signers.joinToString("\n") { formatFingerprint(sha256(it.toByteArray())) }
}.getOrDefault("inconnue")

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

private val TOPICS = listOf(
    Topic(
        title = "Ton identité : les clés",
        summary = "Pas de compte : une paire de clés créée sur le téléphone",
        diagram = {
            Line { N("Clé privée\n(secrète, reste\ndans le téléphone)", Kind.SECRET); Arrow("calcul\nà sens unique"); N("Clé publique\n(partageable)", Kind.PUBLIC) }
            DownArrow("dans ton QR")
            Node("Ton contact", Kind.PEER)
            Caption("Impossible de retrouver la clé privée à partir de la clé publique.")
        },
        simple = "Au premier lancement, Paranoid tire au hasard une paire de clés. La clé privée reste dans le téléphone ; " +
            "la clé publique, tu la donnes à tes contacts dans ton QR.\n\n" +
            "Avec ta clé publique et sa propre clé privée, ton contact calcule un secret. Toi, tu obtiens le même secret " +
            "avec ta clé privée et sa clé publique. Personne d'autre ne peut le calculer, même en ayant vu passer les deux " +
            "clés publiques : c'est ce secret qui protège vos messages.\n\n" +
            "Il n'y a ni compte, ni numéro de téléphone, ni adresse mail : ton identité, c'est cette paire de clés. " +
            "Si elle est effacée, personne, pas même toi, ne peut la recréer.",
        tech = "Deux paires sont tirées par SecureRandom (implémentation BouncyCastle 1.78.1) :\n" +
            "• Ed25519 (RFC 8032), la clé d'identité « ik » : elle sert à l'empreinte de sécurité, à fixer qui est " +
            "l'initiateur (le plus petit ik, octet par octet) et aux données associées de chaque message ;\n" +
            "• X25519 (RFC 7748), la clé d'échange « xk » : elle sert au calcul Diffie-Hellman du secret initial.\n\n" +
            "Les clés privées ne sont jamais envoyées au relais. Elles ne vivent que dans l'état chiffré (voir « Stockage ») " +
            "et en mémoire tant que l'appli est déverrouillée.",
    ),
    Topic(
        title = "Appairage et empreinte",
        summary = "Un seul scan, en face à face, puis une vérification à voix haute",
        diagram = {
            Line { N("Toi : tu affiches\nton QR", Kind.ME); Arrow("il le scanne,\nface à face", weight = 1.2f); N("Ton contact", Kind.PEER) }
            DownArrow("son téléphone t'envoie ses clés, chiffrées par le secret du QR")
            Line { N("Chez lui, ton\nempreinte :\n1A2B 3C4D …", Kind.NEUTRAL); Text(" = ", style = MaterialTheme.typography.titleLarge); N("Chez toi,\n« Mon empreinte » :\n1A2B 3C4D …", Kind.NEUTRAL) }
        },
        simple = "Pour ajouter quelqu'un, l'un affiche son QR et l'autre le scanne, en face à face. Le QR contient ta clé publique, " +
            "l'adresse de la boîte que tu lui réserves et un secret qui ne sert qu'une fois. En le scannant, son téléphone t'envoie " +
            "ses propres clés par le relais, protégées par ce secret : l'appairage se termine tout seul, en quelques secondes.\n\n" +
            "L'empreinte de sécurité est un résumé de ta clé publique, en 20 caractères. Une fois l'appairage terminé, comparez-la " +
            "à voix haute : l'empreinte que ton contact voit pour toi doit être « Mon empreinte » sur ton téléphone, et inversement. " +
            "Si elles diffèrent, supprimez le contact et recommencez.\n\n" +
            "Ne montre ton QR qu'à la personne concernée : quelqu'un qui le photographierait pourrait tenter de se faire passer pour elle. " +
            "La comparaison des empreintes le révélerait.",
        tech = "Le QR encode en base64url un JSON {v:2, ik, xk, r:[{u, fp, w}], s} : les deux clés publiques, l'URL du relais, " +
            "l'empreinte SHA-256 de son certificat, le write_id de la boîte créée pour ce contact seulement, et un secret de 32 octets.\n\n" +
            "Celui qui scanne crée sa boîte pour l'invitant, puis dépose dans la boîte de l'invitant un bloc d'appairage de 2048 octets " +
            "(version 3) : X25519 éphémère, puis AES-256-GCM avec une clé HKDF-SHA256(X25519(éphémère, xk) ‖ secret). Le contenu " +
            "(ses ik, xk, write_id et relais) est signé par sa clé d'identité Ed25519, ce qui lie sa clé xk à l'ik que couvre l'empreinte.\n\n" +
            "Le secret est effacé dès le premier bloc valide : l'invitation ne sert qu'une fois.\n\n" +
            "Empreinte de sécurité : les 10 premiers octets de SHA-256(ik), soit 80 bits, affichés en hexadécimal.",
    ),
    Topic(
        title = "Le relais",
        summary = "Une boîte aux lettres qui ne peut rien lire",
        diagram = {
            Line { N("Toi", Kind.ME, 1f); Arrow("bloc chiffré\n2048 octets"); N("Relais\n(boîte)", Kind.RELAY, 1f); Arrow("relève"); N("Ton\ncontact", Kind.PEER, 1f) }
            Line {
                N("Il voit : adresses IP, heures, numéros de boîte, nombre de blocs", Kind.NEUTRAL, 1f)
                Text("  ")
                N("Il ne voit pas : le contenu, les noms, tes clés d'identité, la longueur des messages", Kind.PUBLIC, 1f)
            }
        },
        simple = "Le relais est une boîte aux lettres : tes messages y attendent, chiffrés, que ton contact vienne les chercher. " +
            "Il ne peut pas les lire, et tous les blocs ont la même taille : il ne sait même pas si tu as écrit « ok » ou une page entière.\n\n" +
            "Le relais est interchangeable : c'est un petit programme que n'importe qui peut héberger, chez soi par exemple. " +
            "L'appli vérifie qu'elle parle au bon relais grâce à l'empreinte de son certificat.\n\n" +
            "Un message non relevé est supprimé au bout de 7 jours, et une boîte accepte 100 messages en attente au maximum.",
        tech = "HTTPS en TLS 1.3, avec un certificat auto-signé épinglé : l'appli compare l'empreinte SHA-256 du certificat, " +
            "sans autorité de certification (le nom d'hôte n'est pas utilisé).\n\n" +
            "Chaque boîte a deux identifiants aléatoires : box_id pour lire, write_id pour déposer (celui du QR, ou celui envoyé à l'appairage). " +
            "La relève et la suppression sont signées par une clé Ed25519 propre à la boîte, avec horodatage (±60 s) et nonce " +
            "contre le rejeu. Aucune clé commune ne relie les boîtes d'une même personne.\n\n" +
            "Limites : blocs de 2048 octets exactement, 100 en attente par boîte, 7 jours de conservation, boîte supprimée après " +
            "90 jours sans relève, 10 créations de boîtes par heure et par IP, 60 dépôts par minute et par boîte. " +
            "Le relais n'écrit aucun journal. Le protocole complet est dans PROTOCOL.md.",
    ),
    Topic(
        title = "Le chiffrement des messages",
        summary = "Une clé neuve pour chaque message",
        diagram = {
            Line { N("clé 1", Kind.SECRET, 1f); Arrow(); N("clé 2", Kind.SECRET, 1f); Arrow(); N("clé 3", Kind.SECRET, 1f); Arrow(); N("…", Kind.NEUTRAL, 0.6f) }
            Caption("Chaque clé sert à un seul message, puis elle est effacée. On ne peut pas revenir en arrière.")
            Line { N("Tu écris", Kind.ME, 1f); Arrow("il répond", both = true, weight = 1.4f); N("nouveau secret\npour tous les deux", Kind.PUBLIC) }
            Caption("À chaque changement de sens, un nouveau secret est calculé.")
        },
        simple = "Chaque message est chiffré avec une clé à usage unique, effacée juste après. C'est le « Double Ratchet » " +
            "(double cliquet), le mécanisme de Signal : les clés avancent comme un cliquet, jamais en arrière.\n\n" +
            "Conséquences : si quelqu'un volait un jour les clés de ton téléphone, il ne pourrait pas déchiffrer les anciens messages " +
            "qu'il aurait enregistrés. Et dès que vous vous écrivez à nouveau, il perd aussi l'accès aux suivants.\n\n" +
            "Un message modifié en route, ou copié depuis une autre conversation, est rejeté.",
        tech = "Secret initial : X25519 entre les clés statiques xk, puis HKDF-SHA256 (info « relay-dr-v1/sk »).\n" +
            "Cliquet Diffie-Hellman : nouvelle paire X25519 à chaque changement de sens ; KDF_RK = HKDF-SHA256.\n" +
            "Cliquet symétrique : KDF_CK = HMAC-SHA256 (constantes 0x01 et 0x02).\n" +
            "Clé de message → HKDF → clé AES-256-GCM et nonce.\n" +
            "Données associées : les deux ik et l'en-tête de 42 octets.\n\n" +
            "Le contenu est complété (bourrage) à 1990 octets, pour un bloc final de 2048 octets ; texte de 1963 octets au maximum. " +
            "L'état n'avance qu'après un déchiffrement réussi. Pour les messages arrivés dans le désordre, au plus 1000 clés " +
            "sautées par message et 2000 au total. Détails dans MESSAGES.md.",
    ),
    Topic(
        title = "Tes codes et le bouton rouge",
        summary = "Le code de destruction, et ce qu'il fait",
        diagram = {
            Line { N("Code de\ndéverrouillage", Kind.ME); Arrow("Argon2id"); N("Appli ouverte", Kind.PUBLIC) }
            Line { N("Code de\ndestruction", Kind.SECRET); Arrow("Argon2id"); N("Alerte aux contacts,\ntout est effacé,\nappli vide", Kind.SECRET) }
            Caption("Vu de l'écran, les deux codes font la même chose : l'appli s'ouvre.")
        },
        simple = "Le code de déverrouillage ouvre l'appli. Le code de destruction se tape au même endroit : il envoie une alerte " +
            "à tous tes contacts, détruit ta clé privée et efface tout. Pour qui regarde l'écran, l'appli semble simplement s'ouvrir, " +
            "sur une liste de contacts vide, comme si tu avais tout supprimé.\n\n" +
            "Après 5 essais ratés, l'appli impose une attente : 30 s, puis le double à chaque échec, jusqu'à 1 h. " +
            "Un code oublié ne peut pas être récupéré.\n\n" +
            "Le code de destruction marche aussi dans « Changer mes codes » et « Changer de relais ».",
        tech = "Le coffre contient deux emplacements, dans un ordre aléatoire. Pour chacun, Argon2id (64 Mio, 3 passes, 1 fil, " +
            "sel de 16 octets) dérive une clé AES-256-GCM qui chiffre (rôle ‖ clé de données). Les deux emplacements sont toujours " +
            "essayés : la durée est la même quel que soit le code.\n\n" +
            "Le code de destruction ouvre aussi la clé de données, car il faut l'état pour chiffrer les alertes. Ensuite, sans interruption possible :\n" +
            "1. destruction de la clé Keystore et des fichiers ;\n" +
            "2. nouvelle identité, dont le code de déverrouillage est le code saisi (son code de destruction est aléatoire) ;\n" +
            "3. envoi des alertes depuis la mémoire, 5 s au maximum ;\n" +
            "4. effacement de l'ancien état en mémoire.",
    ),
    Topic(
        title = "Stockage sur le téléphone",
        summary = "Trois couches de chiffrement, aucune sauvegarde",
        diagram = {
            Layer("Puce de sécurité du téléphone (Keystore) : la clé ne peut pas en sortir", Kind.RELAY) {
                Layer("Coffre à deux codes (Argon2id)", Kind.ME) {
                    Layer("Clé de données (AES-256)", Kind.SECRET) {
                        Node("Tes données : identité, contacts, sessions, messages", Kind.PUBLIC, Modifier.fillMaxWidth())
                    }
                }
            }
        },
        simple = "Tout ce que Paranoid garde est chiffré, en trois couches emboîtées comme des poupées russes. La couche extérieure " +
            "est une clé enfermée dans la puce de sécurité du téléphone : elle ne peut pas en sortir, donc une copie des fichiers " +
            "est inutilisable sur un autre appareil.\n\n" +
            "Aucune sauvegarde (Google, Samsung, PC) ne contient Paranoid. Les captures d'écran sont bloquées, et l'aperçu dans " +
            "les applis récentes est masqué. L'appli se verrouille avec le cadenas, ou après 60 s en arrière-plan.\n\n" +
            "Chaque champ de saisie demande au clavier de ne rien apprendre ni mémoriser de ce que tu tapes (Gboard passe alors en mode incognito).",
        tech = "state.bin : l'état, en AES-256-GCM avec une clé de données aléatoire de 256 bits.\n" +
            "lock.bin : le coffre à deux codes, chiffré en AES-256-GCM par une clé non exportable de l'Android Keystore " +
            "(StrongBox si le téléphone en a une, sinon l'environnement d'exécution sécurisé, TEE).\n\n" +
            "Fichiers dans noBackupFilesDir, allowBackup=false, règles d'extraction excluant tout, FLAG_SECURE sur la fenêtre, " +
            "IME_FLAG_NO_PERSONALIZED_LEARNING sur tous les champs de saisie. " +
            "Au verrouillage, la clé de données est effacée de la mémoire.\n\n" +
            "Détruire l'entrée Keystore rend tous les fichiers illisibles instantanément, même s'il en reste des traces dans la mémoire flash.",
    ),
    Topic(
        title = "Pas de notifications",
        summary = "Les messages arrivent quand l'appli est ouverte",
        diagram = {
            Line { N("Appli ouverte\net déverrouillée", Kind.ME); Arrow("relève\ntoutes les 5 s"); N("Relais", Kind.RELAY, 1f) }
            Line { N("Google, Apple,\nautre service", Kind.NEUTRAL); Arrow("rien"); N("Téléphone", Kind.ME, 1f) }
        },
        simple = "Paranoid n'utilise aucun service de notification. Les messages t'attendent sur le relais, jusqu'à 7 jours, " +
            "et arrivent quand tu ouvres l'appli.\n\n" +
            "C'est moins pratique, mais personne d'autre (Google, Apple, un opérateur de notifications) ne sait quand tu reçois un message, " +
            "et aucun programme de Paranoid ne tourne en arrière-plan.",
        tech = "Relève toutes les 5 s quand l'appli est au premier plan et déverrouillée (15 s après une erreur), et immédiatement " +
            "au retour au premier plan. Rien en arrière-plan : pas de service, pas de tâche planifiée, pas de jeton Firebase.\n\n" +
            "Permissions demandées : INTERNET, et CAMERA pour scanner les QR. Rien d'autre.",
    ),
    Topic(
        title = "Changer de relais",
        summary = "Volontairement simple : on repart de zéro",
        diagram = {
            Line { N("Ancien relais", Kind.RELAY); Arrow("« a changé\nde relais »"); N("Tes contacts", Kind.PEER) }
            DownArrow("puis tout est effacé")
            Node("Nouvelle identité sur le nouveau relais", Kind.ME)
            DownArrow("nouveaux codes, puis nouvel appairage")
            Node("Tes contacts", Kind.PEER)
        },
        simple = "Il n'y a pas de migration : moins de code, c'est moins de failles possibles. Changer de relais efface donc tout.\n\n" +
            "Avant d'effacer, l'appli teste le nouveau relais, puis prévient chacun de tes contacts. Ensuite, tu choisis de nouveaux codes " +
            "et refais l'appairage avec chacun.",
        tech = "Le code actuel est exigé (le code de destruction y déclenche le bouton rouge). Le test vérifie la connexion TLS et " +
            "l'empreinte du certificat du nouveau relais : en cas d'échec, rien n'est effacé.\n\n" +
            "L'alerte est un message chiffré de type 3, envoyé par la session en cours, 5 s au maximum. Chez le contact, la conversation " +
            "se ferme. Les anciennes boîtes ne sont plus relevées, et l'ancien relais les supprime au bout de 90 jours.",
    ),
    Topic(
        title = "Limites et menaces",
        summary = "Ce contre quoi Paranoid protège, et ce contre quoi il ne peut rien",
        simple = "Paranoid protège contre :\n" +
            "• un relais curieux ou piraté : il ne lit rien ;\n" +
            "• l'écoute du réseau (Wi-Fi public, opérateur) : double chiffrement, TLS et bout en bout ;\n" +
            "• le vol du téléphone verrouillé : coffre protégé par le code et par la puce de sécurité ;\n" +
            "• la contrainte à déverrouiller : code de destruction ;\n" +
            "• le vol des clés après coup : les anciens messages restent illisibles.\n\n" +
            "Paranoid ne peut rien contre :\n" +
            "• un téléphone compromis (logiciel espion, téléphone rooté) : il voit l'écran et le clavier ;\n" +
            "• un clavier qui ignore la demande « sans apprentissage » de l'appli, ou qui envoie ce que tu tapes : préfère un clavier hors ligne ;\n" +
            "• les métadonnées : le relais voit les adresses IP et les heures, et peut deviner qui parle à qui ;\n" +
            "• ton contact lui-même, qui peut montrer ou photographier ses messages ;\n" +
            "• une photo de l'écran prise avec un autre appareil.\n\n" +
            "Limites connues :\n" +
            "• quelqu'un qui analyse les fichiers voit qu'il existe deux codes, sans savoir lequel détruit ;\n" +
            "• le compteur d'essais est un fichier, qu'un attaquant ayant les droits root pourrait restaurer ;\n" +
            "• sans réseau, l'alerte du bouton rouge ne part pas, mais l'effacement a lieu quand même ;\n" +
            "• un message non relevé en 7 jours est perdu ;\n" +
            "• le code n'a pas encore été relu par un expert indépendant.",
    ),
    Topic(
        title = "Vérifier soi-même",
        summary = "Ne pas croire sur parole : comment contrôler",
        simple = "1. Les empreintes de tes contacts : comparez-les à voix haute, en face à face (voir « Appairage et empreinte »).\n\n" +
            "2. Le relais : depuis un PC, cette commande affiche l'empreinte de son certificat, à comparer avec « Mes infos en direct » :\n" +
            "openssl s_client -connect ADRESSE:PORT </dev/null 2>/dev/null | openssl x509 -noout -fingerprint -sha256\n\n" +
            "3. L'appli installée : « Mes infos en direct » affiche l'empreinte du certificat qui a signé l'APK. Compare-la avec " +
            "« apksigner verify --print-certs paranoid.apk » sur le fichier téléchargé. Android refuse toute mise à jour signée par une autre clé.\n\n" +
            "4. Les autorisations : Paramètres → Applications → Paranoid → Autorisations. Seul l'appareil photo doit apparaître.\n\n" +
            "5. Le code : le dépôt du projet contient le code de l'appli et du relais, et la description exacte des échanges " +
            "(PROTOCOL.md pour le relais, MESSAGES.md pour le chiffrement). L'APK est compilé par GitHub Actions, avec ses empreintes SHA-256.\n\n" +
            "6. Le relais lui-même : relaycli, le client en ligne de commande, parle le même protocole que l'appli. Sa commande debug " +
            "affiche l'état d'une session, sans rien de secret.",
    ),
    Topic(
        title = "Glossaire",
        summary = "Les mots techniques, en une phrase",
        simple = "AES-256-GCM : chiffrement symétrique (même clé pour chiffrer et déchiffrer) qui détecte aussi toute modification.\n\n" +
            "Argon2id : transforme un code en clé, en exigeant beaucoup de mémoire et de calcul, pour ralentir qui essaie tous les codes.\n\n" +
            "Boîte : emplacement sur le relais où tes contacts déposent tes messages. Une boîte par contact.\n\n" +
            "Bouton rouge : le code de destruction. Il alerte tes contacts et efface tout.\n\n" +
            "Bourrage : octets ajoutés pour que tous les messages aient la même taille.\n\n" +
            "Clé privée / clé publique : la privée reste secrète sur ton téléphone ; la publique se partage.\n\n" +
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
    ),
)
