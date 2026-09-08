package com.voicetext.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Translation helper using Google Translate's free API endpoint.
 * No model downloads or API keys required.
 *
 * Uses the unofficial Google Translate endpoint:
 * https://translate.googleapis.com/translate_a/single?client=gtx&sl={source}&tl={target}&dt=t&q={text}
 */
object TranslationHelper {

    private const val TAG = "TranslationHelper"

    data class TranslationResult(
        val originalText: String,
        val translatedText: String,
        val sourceLanguage: String,
        val targetLanguage: String
    )

    /**
     * Translate text from source language to target language.
     */
    suspend fun translate(
        text: String,
        targetLanguage: String,
        sourceLanguage: String = "en"
    ): TranslationResult {
        if (text.isBlank()) {
            return TranslationResult(text, text, sourceLanguage, targetLanguage)
        }

        val targetLangCode = getLanguageCode(targetLanguage)
        val sourceLangCode = getLanguageCode(sourceLanguage)

        if (sourceLangCode == targetLangCode) {
            return TranslationResult(text, text, sourceLanguage, targetLanguage)
        }

        val translatedText = withContext(Dispatchers.IO) {
            performTranslation(text, sourceLangCode, targetLangCode)
        }

        return TranslationResult(
            originalText = text,
            translatedText = translatedText,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage
        )
    }

    /**
     * Perform the actual HTTP request to Google Translate API.
     */
    private fun performTranslation(text: String, sourceLang: String, targetLang: String): String {
        val encodedText = URLEncoder.encode(text, "UTF-8")
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sourceLang&tl=$targetLang&dt=t&q=$encodedText"

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        connection.connectTimeout = 15000
        connection.readTimeout = 15000

        try {
            val responseCode = connection.responseCode
            if (responseCode != 200) {
                throw Exception("HTTP error: $responseCode")
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()

            // Parse the JSON response: [[["translated text","original text",null,null,10]],null,"en"]
            return parseTranslationResponse(response)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Parse the Google Translate JSON response to extract translated text.
     */
    private fun parseTranslationResponse(response: String): String {
        // Response format: [[["translated text","original",null,null,10]],null,"en"]
        // We need to extract all translated segments and join them
        val result = StringBuilder()
        var i = 0
        val len = response.length

        while (i < len) {
            // Find the opening quote of a translated segment
            val startQuote = response.indexOf('"', i)
            if (startQuote == -1) break

            // Find the closing quote
            val endQuote = findClosingQuote(response, startQuote + 1)
            if (endQuote == -1) break

            val segment = response.substring(startQuote + 1, endQuote)
            // Skip empty segments and the original text (which appears second)
            if (segment.isNotEmpty() && !segment.contains("\\u003e")) {
                // Unescape JSON string
                val unescaped = unescapeJson(segment)
                if (result.isNotEmpty()) result.append(" ")
                result.append(unescaped)
            }

            i = endQuote + 1
            // Skip ahead to next segment (after the comma and original text)
            val nextBracket = response.indexOf("],[", i)
            if (nextBracket != -1) {
                i = nextBracket + 3
            } else {
                break
            }
        }

        return result.toString().trim()
    }

    private fun findClosingQuote(str: String, start: Int): Int {
        var i = start
        while (i < str.length) {
            val c = str[i]
            if (c == '\\') {
                i += 2 // Skip escaped character
                continue
            }
            if (c == '"') return i
            i++
        }
        return -1
    }

    private fun unescapeJson(str: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < str.length) {
            val c = str[i]
            if (c == '\\' && i + 1 < str.length) {
                val next = str[i + 1]
                when (next) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'u' -> {
                        if (i + 5 < str.length) {
                            val hex = str.substring(i + 2, i + 6)
                            sb.append(hex.toInt(16).toChar())
                            i += 6
                        } else {
                            sb.append(c)
                            i++
                        }
                    }
                    else -> { sb.append(c); i++ }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /**
     * Convert language name to ISO 639-1 code.
     */
    private fun getLanguageCode(languageName: String): String {
        return when (languageName.lowercase()) {
            "english", "en" -> "en"
            "chinese", "simplified chinese", "zh", "zh-cn" -> "zh-CN"
            "traditional chinese", "zh-tw", "zh-hk" -> "zh-TW"
            "japanese", "ja", "jp" -> "ja"
            "korean", "ko", "kr" -> "ko"
            "french", "fr" -> "fr"
            "german", "de" -> "de"
            "spanish", "es" -> "es"
            "russian", "ru" -> "ru"
            "arabic", "ar" -> "ar"
            "hindi", "hi" -> "hi"
            "vietnamese", "vi" -> "vi"
            "thai", "th" -> "th"
            "italian", "it" -> "it"
            "portuguese", "pt" -> "pt"
            else -> "en"
        }
    }

    /**
     * @deprecated No longer needed with API-based translation.
     */
    fun releaseAll() {
        // No-op: API-based translation doesn't hold resources
    }

    fun getSupportedLanguages(): List<Pair<String, String>> {
        return listOf(
            "English" to "en",
            "简体中文" to "zh-CN",
            "繁體中文" to "zh-TW",
            "日本語" to "ja",
            "한국어" to "ko",
            "Français" to "fr",
            "Deutsch" to "de",
            "Español" to "es",
            "Русский" to "ru",
            "العربية" to "ar",
            "हिन्दी" to "hi",
            "Tiếng Việt" to "vi",
            "ไทย" to "th",
            "Italiano" to "it",
            "Português" to "pt"
        )
    }
}
