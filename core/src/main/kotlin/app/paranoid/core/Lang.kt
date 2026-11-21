package app.paranoid.core

object Lang {
    @Volatile
    var english: Boolean = false
}

fun tr(fr: String, en: String): String = if (Lang.english) en else fr
