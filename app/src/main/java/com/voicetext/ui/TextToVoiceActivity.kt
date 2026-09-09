package com.voicetext.ui

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.voicetext.R
import java.util.*

class TextToVoiceActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var editText: EditText
    private lateinit var btnSpeak: Button
    private lateinit var btnStop: Button
    private lateinit var spinnerVoice: Spinner
    private lateinit var tvStatus: TextView

    private var tts: TextToSpeech? = null
    private var voices: List<VoiceInfo> = emptyList()
    private var selectedVoice: VoiceInfo? = null

    data class VoiceInfo(
        val name: String,
        val locale: Locale,
        val displayName: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_to_voice)

        toolbar = findViewById(R.id.toolbar)
        editText = findViewById(R.id.edit_text_input)
        btnSpeak = findViewById(R.id.btn_speak)
        btnStop = findViewById(R.id.btn_stop)
        spinnerVoice = findViewById(R.id.spinner_voice)
        tvStatus = findViewById(R.id.tv_status)

        toolbar.setNavigationOnClickListener { finish() }

        // Initialize TTS
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                loadVoices()
                btnSpeak.isEnabled = true
                tvStatus.text = "Ready — enter text and tap Speak"
            } else {
                tvStatus.text = "Text-to-Speech not available"
                btnSpeak.isEnabled = false
            }
        }

        btnSpeak.setOnClickListener { speakText() }
        btnStop.setOnClickListener { stopSpeaking() }
    }

    private fun loadVoices() {
        voices = try {
            val availableVoices = tts?.voices
            if (availableVoices == null || availableVoices.isEmpty()) {
                // Fallback: use default locale
                listOf(VoiceInfo("default", Locale.US, "Default Voice (US)"))
            } else {
                availableVoices
                    .filter { it.locale.language.isNotEmpty() }
                    .map { voice ->
                        val displayName = "${voice.locale.displayLanguage} (${voice.locale.country})"
                        VoiceInfo(voice.name, voice.locale, displayName)
                    }
                    .distinctBy { it.displayName }
                    .sortedBy { it.displayName }
            }
        } catch (e: Exception) {
            listOf(VoiceInfo("default", Locale.US, "Default Voice (US)"))
        }

        if (voices.isEmpty()) {
            voices = listOf(VoiceInfo("default", Locale.US, "Default Voice (US)"))
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, voices.map { it.displayName })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerVoice.adapter = adapter

        spinnerVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedVoice = voices.getOrNull(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Select US English by default
        val usIndex = voices.indexOfFirst { it.locale.language == "en" }
        if (usIndex >= 0) {
            spinnerVoice.setSelection(usIndex)
        }
    }

    private fun speakText() {
        val text = editText.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "Please enter text to speak", Toast.LENGTH_SHORT).show()
            return
        }

        // Set voice/locale
        val locale = selectedVoice?.locale ?: Locale.US
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tvStatus.text = "Language not supported, using default"
            tts?.setLanguage(Locale.US)
        }

        // Set the voice if available
        selectedVoice?.let { voiceInfo ->
            try {
                val voice = tts?.voices?.find { it.name == voiceInfo.name }
                if (voice != null) {
                    tts?.voice = voice
                }
            } catch (e: Exception) {
                // Voice selection failed, use default
            }
        }

        // Speak the text
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "tts1")

        tvStatus.text = "Speaking..."
        btnStop.isEnabled = true
    }

    private fun stopSpeaking() {
        tts?.stop()
        tvStatus.text = "Stopped"
        btnStop.isEnabled = false
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
