package app.paranoid.core

/**
 * Langue de l'interface : français ou anglais. L'appli la fixe au démarrage d'après la langue
 * choisie (Android, réglage par appli) ; le core s'en sert pour ses messages d'erreur.
 */
object Lang {
    @Volatile
    var english: Boolean = false
}

/** Texte dans la langue de l'interface. */
fun tr(fr: String, en: String): String = if (Lang.english) en else fr
