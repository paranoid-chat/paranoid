package app.paranoid

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Clavier sans apprentissage : chaque champ de saisie de l'appli demande au clavier de ne rien
 * mémoriser (IME_FLAG_NO_PERSONALIZED_LEARNING : pas de dictionnaire personnel, pas d'historique,
 * mode incognito sur Gboard). C'est une demande faite au clavier, que la plupart respectent ;
 * un clavier tiers peut l'ignorer.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NoPersonalizedLearning(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val modified = object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                    val connection = request.createInputConnection(outAttributes)
                    outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    return connection
                }
            }
            nextHandler.startInputMethod(modified)
        },
        content = content,
    )
}
