package com.ultiq.app.util

import android.content.Context
import android.content.res.Resources
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * §13 (i18n) — thin wrapper over AppCompat's per-app locale API.
 *
 * `setLanguage` recreates the visible activity so Compose + resources re-resolve
 * in the new language (below API 33 AppCompat backports the storage + recreate).
 * The applied tag is persisted by `AppLocalesMetadataHolderService`
 * (`autoStoreLocales=true`, wired in the manifest) so it survives cold start with
 * no default-locale flash. A *synced* copy also lives in `UserPreferences`
 * (`app_language`) and rides `/auth/me` to other devices.
 *
 * There is no "follow the system" mode: the app language is always one of
 * [SUPPORTED]. A user with no choice yet gets one picked once from the phone's
 * language ([ensureExplicitLanguage]) — Chinese phones start in Chinese,
 * everything else in English.
 */
object LocaleManager {

    /** BCP-47 tags the app ships translations for — must match res/xml/locales_config.xml. */
    val SUPPORTED: List<String> = listOf("en", "zh-Hans", "zh-Hant")

    private val TRADITIONAL_REGIONS = setOf("TW", "HK", "MO")

    /** Apply a language. [tag] must be one of [SUPPORTED]. */
    fun setLanguage(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    /**
     * Give users without an explicit app language (fresh install, the old
     * "System default", or a language we no longer ship) one picked from the
     * phone's language. Call from Activity.onCreate, after AppCompat has
     * restored the stored locales. No-op once a supported language is set.
     */
    fun ensureExplicitLanguage(context: Context) {
        if (currentTag() in SUPPORTED) return
        setLanguage(tagFor(systemLocale(context)))
    }

    /**
     * The phone's own language. On API 33+ `Resources.getSystem()` reflects a
     * per-app locale once one is applied (e.g. a stale "fr"), so ask the
     * platform LocaleManager instead. Below 33 there's no platform per-app
     * locale, so the system Resources are accurate.
     */
    private fun systemLocale(context: Context): Locale {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(android.app.LocaleManager::class.java)
                ?.systemLocales?.get(0)?.let { return it }
        }
        return Resources.getSystem().configuration.locales[0]
    }

    /** Map a phone locale to the app language it should start in. */
    fun tagFor(locale: Locale): String {
        if (locale.language != "zh") return "en"
        val traditional = locale.script == "Hant" ||
            (locale.script.isEmpty() && locale.country in TRADITIONAL_REGIONS)
        return if (traditional) "zh-Hant" else "zh-Hans"
    }

    /** Currently-applied app-language tag, or "" before one has been set. */
    fun currentTag(): String = AppCompatDelegate.getApplicationLocales().toLanguageTags()

    /**
     * Locale for date/number formatting. English always formats US-style,
     * regardless of the phone's region (en-AU, en-GB, …).
     */
    fun currentLocale(): Locale {
        val locale = AppCompatDelegate.getApplicationLocales()[0] ?: Locale.getDefault()
        return if (locale.language == "en") Locale.US else locale
    }
}
