package com.voicetext.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists user preferences (speech language, translation language) using SharedPreferences.
 */
object SettingsManager {

    private const val PREFS_NAME = "voice_text_settings"
    private const val KEY_SPEECH_LANGUAGE = "speech_language"
    private const val KEY_TRANSLATION_LANGUAGE = "translation_language"

    const val DEFAULT_SPEECH_LANGUAGE = "english"
    const val DEFAULT_TRANSLATION_LANGUAGE = "chinese"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSpeechLanguage(context: Context): String =
        prefs(context).getString(KEY_SPEECH_LANGUAGE, DEFAULT_SPEECH_LANGUAGE) ?: DEFAULT_SPEECH_LANGUAGE

    fun setSpeechLanguage(context: Context, language: String) {
        prefs(context).edit().putString(KEY_SPEECH_LANGUAGE, language).apply()
    }

    fun getTranslationLanguage(context: Context): String =
        prefs(context).getString(KEY_TRANSLATION_LANGUAGE, DEFAULT_TRANSLATION_LANGUAGE) ?: DEFAULT_TRANSLATION_LANGUAGE

    fun setTranslationLanguage(context: Context, language: String) {
        prefs(context).edit().putString(KEY_TRANSLATION_LANGUAGE, language).apply()
    }

    /**
     * Returns a human-readable display name for a language code.
     */
    fun getLanguageDisplayName(languageCode: String): String {
        return when (languageCode.lowercase()) {
            "english" -> "English"
            "chinese" -> "简体中文"
            "traditional" -> "繁體中文"
            "japanese" -> "日本語"
            "korean" -> "한국어"
            "french" -> "Français"
            "german" -> "Deutsch"
            "spanish" -> "Español"
            else -> languageCode.replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * All supported speech languages (for recognition).
     */
    val speechLanguages = listOf(
        "english" to "English",
        "chinese" to "简体中文",
        "traditional" to "繁體中文",
        "japanese" to "日本語",
        "korean" to "한국어"
    )

    /**
     * All supported translation target languages.
     */
    val translationLanguages = listOf(
        "english" to "English",
        "chinese" to "简体中文",
        "traditional" to "繁體中文",
        "japanese" to "日本語",
        "korean" to "한국어",
        "french" to "Français",
        "german" to "Deutsch",
        "spanish" to "Español"
    )
}
