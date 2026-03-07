package com.phoncoin.wallet.core.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Wallet-safe language switcher:
 * - UI only (no impact on mining/consensus/network)
 * - Persisted locally
 */
object LanguageManager {
    private const val PREFS = "phoncoin_prefs"
    private const val KEY_LANG = "app_language" // e.g. "en", "fr"

    fun applySavedLanguage(context: Context) {
        val lang = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, null) ?: return
        setLanguage(lang)
    }

    fun saveAndApply(context: Context, langTag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANG, langTag)
            .apply()
        setLanguage(langTag)
    }

    private fun setLanguage(langTag: String) {
        val locales = LocaleListCompat.forLanguageTags(langTag)
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
