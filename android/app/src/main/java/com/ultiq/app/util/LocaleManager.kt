package com.ultiq.app.util

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
 */
object LocaleManager {

    /** BCP-47 tags the app ships translations for — must match res/xml/locales_config.xml. */
    val SUPPORTED: List<String> = listOf(
        "en", "es", "pt-BR", "fr", "de", "ja",
        "zh-Hans", "zh-Hant", "ko", "hi", "vi", "th", "ar",
    )

    /** Apply a language. Empty/blank tag = follow the system language. */
    fun setLanguage(tag: String) {
        val locales = if (tag.isBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    /** Currently-applied app-language tag, or "" when following the system. */
    fun currentTag(): String = AppCompatDelegate.getApplicationLocales().toLanguageTags()

    /** Effective locale for formatting (falls back to the system default). */
    fun currentLocale(): Locale =
        AppCompatDelegate.getApplicationLocales()[0] ?: Locale.getDefault()
}
