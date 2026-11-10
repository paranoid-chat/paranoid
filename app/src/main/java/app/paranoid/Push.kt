package app.paranoid

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Notifications par UnifiedPush (ntfy ou autre distributeur).
 *
 * Le relais n'envoie qu'un réveil chiffré d'un octet, sans contenu. Si l'appli est déverrouillée
 * et au premier plan, le réveil déclenche une relève immédiate ; sinon, une notification
 * générique « Nouveau message » s'affiche (ni expéditeur, ni texte), et la relève se fera au
 * déverrouillage.
 */
data class PushTarget(val url: String, val pubKey: String, val auth: String)

object PushBus {
    val endpoint = MutableStateFlow<PushTarget?>(null)
    val wake = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Volatile
    var foregroundUnlocked: Boolean = false
}

/** Point de notification courant (fichier privé de l'appli, exclu des sauvegardes). */
object PushPrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("push", Context.MODE_PRIVATE)

    fun load(ctx: Context): PushTarget? {
        val p = prefs(ctx)
        val url = p.getString("url", null) ?: return null
        val key = p.getString("pub", null) ?: return null
        val auth = p.getString("auth", null) ?: return null
        return PushTarget(url, key, auth)
    }

    fun save(ctx: Context, t: PushTarget) {
        prefs(ctx).edit().putString("url", t.url).putString("pub", t.pubKey).putString("auth", t.auth).apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}

object Push {
    /** Choisit le distributeur (celui en place ou celui par défaut), puis s'enregistre avec la clé VAPID du relais. */
    fun register(activity: Activity, vapid: String, done: (Boolean) -> Unit) {
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { success ->
            if (success) UnifiedPush.register(activity, messageForDistributor = "Paranoid", vapid = vapid)
            done(success)
        }
    }

    fun distributorAvailable(ctx: Context): Boolean = UnifiedPush.getDistributors(ctx).isNotEmpty()

    /** Désinscription et oubli du point de notification (verrou rouge, effacement). */
    fun forget(ctx: Context) {
        runCatching { UnifiedPush.unregister(ctx) }
        PushPrefs.clear(ctx)
        PushBus.endpoint.value = null
        Notifier.cancel(ctx)
    }
}

object Notifier {
    private const val CHANNEL = "messages"
    private const val ID = 1

    fun showGeneric(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Nouveaux messages (sans contenu ni expéditeur)"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle("Paranoid")
            .setContentText("Nouveau message")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
        nm.notify(ID, n)
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ID)
    }
}

/** Service appelé par le connecteur UnifiedPush. */
class ParanoidPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val keys = endpoint.pubKeySet ?: return // sans clés Web Push, le relais ne peut pas chiffrer
        val t = PushTarget(endpoint.url, keys.pubKey, keys.auth)
        PushPrefs.save(this, t)
        PushBus.endpoint.value = t
    }

    override fun onMessage(message: PushMessage, instance: String) {
        if (PushBus.foregroundUnlocked) PushBus.wake.tryEmit(Unit) else Notifier.showGeneric(this)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        PushPrefs.clear(this)
        PushBus.endpoint.value = null
    }

    override fun onUnregistered(instance: String) {
        PushPrefs.clear(this)
        PushBus.endpoint.value = null
    }
}
