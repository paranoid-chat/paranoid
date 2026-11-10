package app.paranoid

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pas de capture d'écran, pas d'aperçu dans les applis récentes.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()

        // Relève des messages uniquement quand l'appli est au premier plan (v0 : pas d'arrière-plan).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { vm.pollLoop() }
        }

        setContent {
            ParanoidTheme {
                val ui by vm.ui.collectAsStateWithLifecycle()
                BackHandler(enabled = ui.screen is Screen.Chat || ui.screen is Screen.AddContact) { vm.goContacts() }
                when (val s = ui.screen) {
                    Screen.Loading -> LoadingScreen()
                    Screen.Setup -> SetupScreen(ui, vm)
                    Screen.Contacts -> ContactsScreen(ui, vm)
                    Screen.AddContact -> AddContactScreen(ui, vm)
                    is Screen.Chat -> ChatScreen(s.name, ui, vm)
                    is Screen.Fatal -> FatalScreen(s.message, vm)
                }
            }
        }
    }
}

val ParanoidRed = Color(0xFFC62828)

@Composable
fun ParanoidTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFFFF8A80), onPrimary = Color(0xFF5F0000), primaryContainer = Color(0xFF8E0000), onPrimaryContainer = Color(0xFFFFDAD6))
    } else {
        lightColorScheme(primary = ParanoidRed, onPrimary = Color.White, primaryContainer = Color(0xFFFFDAD6), onPrimaryContainer = Color(0xFF410002))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
