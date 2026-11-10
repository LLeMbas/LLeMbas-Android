package eu.llembas.android

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app's language. By default the account's, from the server (`/api/v2/me`'s `language`), so
 * a phone set to one language shows the app in the language the person chose on their instance;
 * Settings › Language can choose the phone's language instead, or any language the app has.
 *
 * Android 13 and newer keep a per-app language themselves (`LocaleManager`, and the system's own
 * Settings › Apps › LLeMbas › Language, which `res/xml/locales_config.xml` feeds). Older phones
 * have none: there the chosen language is kept in a small synchronous file and every activity
 * wraps its context in it ([wrap]), because the DataStore is read too late for `attachBaseContext`.
 */
object AppLanguage {
    /** Prefs.language: the account's language (the default). */
    const val ACCOUNT = ""

    /** Prefs.language: the phone's language. */
    const val PHONE = "system"

    /**
     * The languages the app has, by code, each named in itself. The same set as the
     * `values-*` folders `scripts/gen-strings.py` writes, plus English; a test holds them equal.
     */
    val LANGUAGES: List<Pair<String, String>> = listOf(
        "en" to "English",
        "cs" to "Čeština",
        "de" to "Deutsch",
        "es" to "Español",
        "fr" to "Français",
        "hu" to "Magyar",
        "it" to "Italiano",
        "pl" to "Polski",
        "sk" to "Slovenčina",
    )

    private val CODES = LANGUAGES.map { it.first }.toSet()

    /** A language's own name, or null for one the app does not have. */
    fun name(code: String?): String? = LANGUAGES.firstOrNull { it.first == code?.lowercase()?.substringBefore('-')?.substringBefore('_') }?.second

    /** The app's code for a server's language (`sk`, `pt_BR` -> `pt`), or null if the app lacks it. */
    fun known(code: String?): String? = code?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_')?.takeIf { it in CODES }

    /**
     * The language the app should be in: a language tag, or null for the phone's own.
     * [choice] is Prefs.language; [account] the account's language as the server last said it.
     * An account in a language the app does not have follows the phone, rather than English.
     */
    fun effective(choice: String, account: String): String? = when (choice) {
        ACCOUNT -> known(account)
        PHONE -> null
        else -> known(choice)
    }

    private const val FILE = "language"
    private const val APPLIED = "applied"

    /** What [apply] last set; null if it never ran. */
    fun applied(context: Context): String? = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(APPLIED, null)

    /**
     * What the system says the app's language is now (Android 13+): "" for the phone's. Differs
     * from [applied] when the person chose a language in the system's settings for the app.
     */
    fun systemChoice(context: Context): String? {
        if (Build.VERSION.SDK_INT < 33) return null
        val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return null
        return if (locales.isEmpty) "" else locales[0].language
    }

    /**
     * Put the app in [tag] (null: the phone's language). Does nothing when that is already so,
     * so a running app is not recreated for nothing. Android 13+ recreates the activities
     * itself; an older phone recreates [activity].
     */
    fun apply(context: Context, tag: String?, activity: Activity? = null) {
        val value = tag.orEmpty()
        if (applied(context) == value && (Build.VERSION.SDK_INT < 33 || systemChoice(context) == value)) return
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(APPLIED, value).commit()
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (value.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(value)
        } else {
            activity?.recreate()
        }
    }

    /**
     * [base] in the chosen language, for `attachBaseContext` and for a window that is not an
     * activity's (the assistant's sheet). Android 13+ does this itself; the context is returned
     * as it is there.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = base.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(APPLIED, null)
        if (tag.isNullOrEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList(locale)) }
        return base.createConfigurationContext(config)
    }
}
