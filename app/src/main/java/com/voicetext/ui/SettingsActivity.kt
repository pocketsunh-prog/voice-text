package com.voicetext.ui

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.voicetext.R
import com.voicetext.data.SettingsManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var speechLanguageSummary: TextView
    private lateinit var translationLanguageSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        speechLanguageSummary = findViewById(R.id.setting_speech_language_summary)
        translationLanguageSummary = findViewById(R.id.setting_translation_language_summary)

        updateSummaries()

        findViewById<LinearLayout>(R.id.setting_speech_language).setOnClickListener {
            showSpeechLanguageDialog()
        }

        findViewById<LinearLayout>(R.id.setting_translation_language).setOnClickListener {
            showTranslationLanguageDialog()
        }
    }

    private fun updateSummaries() {
        val speechLang = SettingsManager.getSpeechLanguage(this)
        val translationLang = SettingsManager.getTranslationLanguage(this)
        speechLanguageSummary.text = SettingsManager.getLanguageDisplayName(speechLang)
        translationLanguageSummary.text = SettingsManager.getLanguageDisplayName(translationLang)
    }

    private fun showSpeechLanguageDialog() {
        val languages = SettingsManager.speechLanguages
        val displayNames = languages.map { it.second }.toTypedArray()
        val codes = languages.map { it.first }.toTypedArray()

        val currentLang = SettingsManager.getSpeechLanguage(this)
        val checkedItem = codes.indexOf(currentLang).takeIf { it >= 0 } ?: 0

        AlertDialog.Builder(this)
            .setTitle(R.string.speech_language)
            .setSingleChoiceItems(displayNames, checkedItem) { dialog, which ->
                SettingsManager.setSpeechLanguage(this, codes[which])
                updateSummaries()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showTranslationLanguageDialog() {
        val languages = SettingsManager.translationLanguages
        val displayNames = languages.map { it.second }.toTypedArray()
        val codes = languages.map { it.first }.toTypedArray()

        val currentLang = SettingsManager.getTranslationLanguage(this)
        val checkedItem = codes.indexOf(currentLang).takeIf { it >= 0 } ?: 1

        AlertDialog.Builder(this)
            .setTitle(R.string.translation_language_setting)
            .setSingleChoiceItems(displayNames, checkedItem) { dialog, which ->
                SettingsManager.setTranslationLanguage(this, codes[which])
                updateSummaries()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
