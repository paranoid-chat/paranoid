package app.paranoid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private const val DAY = 86_400L

/** Crans du curseur, du plus permissif au plus strict. 0 = pour toujours. */
private val HISTORY_STEPS = listOf(
    0L to "Pour toujours",
    365 * DAY to "1 an",
    180 * DAY to "6 mois",
    90 * DAY to "3 mois",
    30 * DAY to "1 mois",
    7 * DAY to "1 semaine",
    3 * DAY to "3 jours",
    DAY to "24 heures",
)

private fun stepOf(seconds: Long): Int = HISTORY_STEPS.indexOfFirst { it.first == seconds }.coerceAtLeast(0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(ui: Ui, vm: MainViewModel) {
    var pos by remember { mutableFloatStateOf(stepOf(ui.historySeconds).toFloat()) }
    LaunchedEffect(ui.historySeconds) { pos = stepOf(ui.historySeconds).toFloat() }
    var confirm by remember { mutableStateOf<Pair<Int, Int>?>(null) } // (cran, messages à effacer)
    val index = pos.toInt()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paramètres") },
                navigationIcon = { IconButton(onClick = { vm.goContacts() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Historique des messages", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth()) {
                Text("Garder : ", style = MaterialTheme.typography.bodyLarge)
                Text(HISTORY_STEPS[index].second, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = pos,
                onValueChange = { pos = it },
                onValueChangeFinished = {
                    val i = pos.toInt()
                    val seconds = HISTORY_STEPS[i].first
                    if (seconds == ui.historySeconds) return@Slider
                    val n = vm.countOlderThan(seconds)
                    if (n > 0) confirm = i to n else vm.setHistory(seconds)
                },
                valueRange = 0f..(HISTORY_STEPS.size - 1).toFloat(),
                steps = HISTORY_STEPS.size - 2,
                enabled = !ui.busy,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Pour toujours", style = MaterialTheme.typography.labelSmall)
                Text("24 heures", style = MaterialTheme.typography.labelSmall)
            }
            Text(
                "Les messages plus anciens que cette durée sont effacés de ce téléphone : au déverrouillage, " +
                    "à chaque relève et dès que tu changes ce réglage. Rien n'est effacé chez tes contacts.\n\n" +
                    "Les alertes « bouton rouge » et « changement de relais » sont toujours gardées : elles indiquent " +
                    "pourquoi une conversation est fermée.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    confirm?.let { (i, n) ->
        AlertDialog(
            onDismissRequest = { confirm = null; pos = stepOf(ui.historySeconds).toFloat() },
            title = { Text("Effacer $n message(s) ?") },
            text = { Text("Les messages de plus de ${HISTORY_STEPS[i].second} seront effacés de ce téléphone tout de suite, sans retour possible.") },
            confirmButton = {
                TextButton(onClick = { confirm = null; vm.setHistory(HISTORY_STEPS[i].first) }) { Text("Effacer") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null; pos = stepOf(ui.historySeconds).toFloat() }) { Text("Annuler") }
            },
        )
    }
    Messages(ui, vm)
}
