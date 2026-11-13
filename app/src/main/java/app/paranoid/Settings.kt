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
import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.paranoid.core.Lang
import app.paranoid.core.tr
import androidx.compose.ui.unit.dp

private const val DAY = 86_400L

/** Crans du curseur, du plus permissif au plus strict. 0 = pour toujours. */
private val HISTORY_SECONDS = listOf(0L, 365 * DAY, 180 * DAY, 90 * DAY, 30 * DAY, 7 * DAY, 3 * DAY, DAY)

private fun historyLabel(i: Int): String = when (i) {
    0 -> tr("Pour toujours", "Forever")
    1 -> tr("1 an", "1 year")
    2 -> tr("6 mois", "6 months")
    3 -> tr("3 mois", "3 months")
    4 -> tr("1 mois", "1 month")
    5 -> tr("1 semaine", "1 week")
    6 -> tr("3 jours", "3 days")
    else -> tr("24 heures", "24 hours")
}

private fun stepOf(seconds: Long): Int = HISTORY_SECONDS.indexOf(seconds).coerceAtLeast(0)

/**
 * Langue de l'appli, par le réglage Android par appli (Android 13+) : l'activité est recréée dans
 * la nouvelle langue, et le choix apparaît aussi dans Paramètres Android → Applis → Paranoid → Langue.
 */
fun setAppLanguage(ctx: Context, english: Boolean) {
    ctx.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(if (english) "en" else "fr")
}

@Composable
private fun LanguageChoice(label: String, english: Boolean) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable { if (Lang.english != english) setAppLanguage(ctx, english) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = Lang.english == english, onClick = { if (Lang.english != english) setAppLanguage(ctx, english) })
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

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
                title = { Text(tr("Paramètres", "Settings")) },
                navigationIcon = { IconButton(onClick = { vm.goContacts() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Retour", "Back")) } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tr("Conservation de l'historique", "History retention"), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth()) {
                Text(tr("Garder : ", "Keep: "), style = MaterialTheme.typography.bodyLarge)
                Text(historyLabel(index), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = pos,
                onValueChange = { pos = it },
                onValueChangeFinished = {
                    val i = pos.toInt()
                    val seconds = HISTORY_SECONDS[i]
                    if (seconds == ui.historySeconds) return@Slider
                    val n = vm.countOlderThan(seconds)
                    if (n > 0) confirm = i to n else vm.setHistory(seconds)
                },
                valueRange = 0f..(HISTORY_SECONDS.size - 1).toFloat(),
                steps = HISTORY_SECONDS.size - 2,
                enabled = !ui.busy,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(historyLabel(0), style = MaterialTheme.typography.labelSmall)
                Text(historyLabel(HISTORY_SECONDS.size - 1), style = MaterialTheme.typography.labelSmall)
            }
            Text(
                tr(
                    "Les messages plus anciens que cette durée sont effacés de ce téléphone : au déverrouillage, " +
                        "à chaque relève et dès que ce réglage change. Rien n'est effacé chez les contacts.",
                    "Messages older than this are erased from this phone: when unlocking, at every check " +
                        "and as soon as this setting changes. Nothing is erased on the contacts' side.",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Text(tr("Langue", "Language"), style = MaterialTheme.typography.titleMedium)
            LanguageChoice("Français", english = false)
            LanguageChoice("English", english = true)
        }
    }

    confirm?.let { (i, n) ->
        AlertDialog(
            onDismissRequest = { confirm = null; pos = stepOf(ui.historySeconds).toFloat() },
            title = { Text(tr("Effacer $n message(s) ?", "Erase $n message(s)?")) },
            text = {
                Text(
                    tr(
                        "Les messages de plus de ${historyLabel(i)} seront effacés de ce téléphone tout de suite, sans retour possible.",
                        "Messages older than ${historyLabel(i)} will be erased from this phone right away, with no way back.",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { confirm = null; vm.setHistory(HISTORY_SECONDS[i]) }) { Text(tr("Effacer", "Erase")) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null; pos = stepOf(ui.historySeconds).toFloat() }) { Text(tr("Annuler", "Cancel")) }
            },
        )
    }
    Messages(ui, vm)
}
