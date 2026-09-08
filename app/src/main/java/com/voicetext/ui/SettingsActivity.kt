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
    private lateinit var exportFormatSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        speechLanguageSummary = findViewById(R.id.setting_speech_language_summary)
        translationLanguageSummary = findViewById(R.id.setting_translation_language_summary)
        exportFormatSummary = findViewById(R.id.setting_export_format_summary)

        updateSummaries()

        findViewById<LinearLayout>(R.id.setting_speech_language).setOnClickListener {
            showSpeechLanguageDialog()
        }

        findViewById<LinearLayout>(R.id.setting_translation_language).setOnClickListener {
            showTranslationLanguageDialog()
        }

        findViewById<LinearLayout>(R.id.setting_export_format).setOnClickListener {
            showExportFormatDialog()
        }
    }

    private fun updateSummaries() {
        val speechLang = SettingsManager.getSpeechLanguage(this)
        val translationLang = SettingsManager.getTranslationLanguage(this)
        val exportFormat = SettingsManager.getExportFormat(this)
        speechLanguageSummary.text = SettingsManager.getLanguageDisplayName(speechLang)
        translationLanguageSummary.text = SettingsManager.getLanguageDisplayName(translationLang)
        exportFormatSummary.text = exportFormat.displayName
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

    private fun showExportFormatDialog() {
        val formats = SettingsManager.ExportFormat.entries.toTypedArray()
        val displayNames = formats.map { it.displayName }.toTypedArray()

        val currentFormat = SettingsManager.getExportFormat(this)
        val checkedItem = formats.indexOf(currentFormat).takeIf { it >= 0 } ?: 0

        AlertDialog.Builder(this)
            .setTitle(R.string.export_format)
            .setSingleChoiceItems(displayNames, checkedItem) { dialog, which ->
                SettingsManager.setExportFormat(this, formats[which])
                updateSummaries()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
