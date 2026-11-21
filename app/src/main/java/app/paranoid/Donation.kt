package app.paranoid

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.paranoid.core.tr

const val MONERO_ADDRESS =
    "439L8VnuoL25KmyM5TvEsf4zaCWKcdXhe62hryuMCNJZVxSfxqvT5iXiMaPPznvdQVL7pB3gL3e1K3xem2AeccTiFbYLMNX"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DonationScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Donation") },
                navigationIcon = { IconButton(onClick = { vm.goContacts() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Retour", "Back")) } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tr("Les dons couvrent les frais des deux relais hébergés en France par Paranoid, ainsi que l'hébergement du site getparanoid.fr chez OVH.",
                "Donations cover the costs of the two relays hosted in France by Paranoid, as well as hosting the getparanoid.fr website at OVH."))
            Text(tr("Ils se font en Monero et sont 100 % anonymes : ni compte, ni nom, ni adresse e-mail.",
                "They are made in Monero and are 100% anonymous: no account, no name, no e-mail address."))
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Adresse Monero", "Monero address"), style = MaterialTheme.typography.titleSmall)
                    SelectionContainer {
                        Text(MONERO_ADDRESS, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    }
                    Button(onClick = { copyAddress(ctx) }, modifier = Modifier.fillMaxWidth()) {
                        Text(tr("Copier l'adresse", "Copy the address"))
                    }
                    OutlinedButton(onClick = { shareAddress(ctx) }, modifier = Modifier.fillMaxWidth()) {
                        Text(tr("Partager", "Share"))
                    }
                }
            }
            Text(tr("Vous découvrez Monero ? Le site getmonero.org explique comment obtenir un portefeuille.",
                "New to Monero? The getmonero.org site explains how to get a wallet."),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun copyAddress(ctx: Context) {
    val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText(tr("Adresse Monero", "Monero address"), MONERO_ADDRESS))
}

private fun shareAddress(ctx: Context) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, tr("Adresse Monero de Paranoid", "Paranoid Monero address"))
        .putExtra(Intent.EXTRA_TEXT, MONERO_ADDRESS)
    ctx.startActivity(Intent.createChooser(send, tr("Partager l'adresse", "Share the address")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
