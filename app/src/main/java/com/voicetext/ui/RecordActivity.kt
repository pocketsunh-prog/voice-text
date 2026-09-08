package com.voicetext.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.util.Log
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.voicetext.R
import com.voicetext.data.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.*

class RecordActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "RecordActivity"
    }

    private lateinit var database: AppDatabase
    private lateinit var recordingDao: RecordingDao
    private lateinit var projectDao: ProjectDao

    private lateinit var fabRecord: FloatingActionButton
    private lateinit var fabImport: FloatingActionButton
    private lateinit var fabLanguage: FloatingActionButton
    private lateinit var tvStatus: TextView
    private lateinit var tvLiveCaption: TextView
    private lateinit var tvLiveTranslation: TextView
    private lateinit var chronometer: Chronometer
    private lateinit var recordingIndicator: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var toolbar: MaterialToolbar

    private var projectId: Long = 0
    private var isRecording = false
    private var isPaused = false
    private var recorder: MediaRecorder? = null
    private var currentAudioPath = ""
    private var startTime = 0L
    private var pausedDuration = 0L

    // Speech recognition
    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private var accumulatedText = ""

    // Translation (loaded from settings)
    private var targetLanguage = SettingsManager.DEFAULT_TRANSLATION_LANGUAGE
    private var sourceLanguage = SettingsManager.DEFAULT_SPEECH_LANGUAGE
    private var lastTranslatedText = ""

    // Zoom (text size in sp)
    private var captionTextSize = 15f
    private var translationTextSize = 16f
    private val minTextSize = 10f
    private val maxTextSize = 30f
    private val textSizeStep = 2f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_record)

        projectId = intent.getLongExtra("project_id", 0)
        if (projectId == 0L) {
            finish()
            return
        }

        // Load persisted language settings
        sourceLanguage = SettingsManager.getSpeechLanguage(this)
        targetLanguage = SettingsManager.getTranslationLanguage(this)

        database = AppDatabase.getDatabase(this)
        recordingDao = database.recordingDao()
        projectDao = database.projectDao()

        toolbar = findViewById(R.id.toolbar)
        fabRecord = findViewById(R.id.fab_record)
        fabImport = findViewById(R.id.fab_import)
        fabLanguage = findViewById(R.id.fab_language)
        tvStatus = findViewById(R.id.tv_status)
        tvLiveCaption = findViewById(R.id.tv_live_caption)
        tvLiveTranslation = findViewById(R.id.tv_live_translation)
        chronometer = findViewById(R.id.chronometer)
        recordingIndicator = findViewById(R.id.recording_indicator)
        progressBar = findViewById(R.id.progress_bar)

        // Zoom controls
        val btnZoomIn = findViewById<TextView>(R.id.btn_zoom_in)
        val btnZoomOut = findViewById<TextView>(R.id.btn_zoom_out)
        btnZoomIn.setOnClickListener { adjustZoom(textSizeStep) }
        btnZoomOut.setOnClickListener { adjustZoom(-textSizeStep) }

        // Apply initial text size
        applyTextSize()

        toolbar.setNavigationOnClickListener { finish() }

        fabRecord.setOnClickListener {
            if (!isRecording) startRecording() else stopRecording()
        }

        fabImport.setOnClickListener {
            importAudioFile()
        }

        fabLanguage.setOnClickListener {
            showLanguageDialog()
        }

        initSpeechRecognizer()
    }

    private fun getSpeechLanguageCode(language: String): String {
        return when (language.lowercase()) {
            "english", "en" -> "en-US"
            "chinese", "simplified chinese" -> "zh-CN"
            "traditional", "traditional chinese" -> "zh-TW"
            "japanese" -> "ja-JP"
            "korean" -> "ko-KR"
            "french" -> "fr-FR"
            "german" -> "de-DE"
            "spanish" -> "es-ES"
            else -> "en-US"
        }
    }

    private fun initSpeechRecognizer() {
        Log.d(TAG, "initSpeechRecognizer: checking availability...")
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            val msg = "Speech recognition not available — Google app required"
            tvStatus.text = msg
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            Log.e(TAG, msg)
            return
        }

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            Log.d(TAG, "initSpeechRecognizer: SpeechRecognizer created")
        } catch (e: Exception) {
            val msg = "Failed to create speech recognizer: ${e.message}"
            tvStatus.text = msg
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            Log.e(TAG, msg, e)
            return
        }

        if (speechRecognizer == null) {
            val msg = "Speech recognizer is null"
            tvStatus.text = msg
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            Log.e(TAG, msg)
            return
        }

        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, getSpeechLanguageCode(sourceLanguage))
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, getSpeechLanguageCode(sourceLanguage))
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            // Note: EXTRA_PREFER_OFFLINE removed - can cause ERROR_NO_MATCH if no offline model downloaded
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            private var speechDetected = false

            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "onReadyForSpeech")
                speechDetected = false
                tvStatus.text = getString(R.string.listening)
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "onBeginningOfSpeech")
                speechDetected = true
            }
            override fun onRmsChanged(rmsdB: Float) {
                // Update status with audio level feedback
                if (rmsdB > 2.0f) {
                    speechDetected = true
                }
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Log.d(TAG, "onEndOfSpeech, speechDetected=$speechDetected")
                if (isRecording && !isPaused) {
                    // Small delay to avoid client error from restarting too fast
                    tvStatus.text = if (speechDetected) getString(R.string.listening) else "Processing..."
                    tvStatus.postDelayed({
                        if (isRecording && !isPaused) {
                            restartListening()
                        }
                    }, 200)
                }
            }

            override fun onError(error: Int) {
                val errorMsg = getSpeechErrorMessage(error)
                tvStatus.text = errorMsg
                Log.e(TAG, "Speech recognition error: $errorMsg ($error)")
                // Also show toast so user sees it immediately
                Toast.makeText(this@RecordActivity, errorMsg, Toast.LENGTH_LONG).show()
                if (isRecording && !isPaused) {
                    // Don't auto-restart on client error - wait for user to tap again
                    if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                        error != SpeechRecognizer.ERROR_RECOGNIZER_BUSY &&
                        error != SpeechRecognizer.ERROR_CLIENT
                    ) {
                        tvStatus.postDelayed({
                            if (isRecording && !isPaused) {
                                restartListening()
                            }
                        }, 500)
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    // translateLive now handles accumulation and caption update
                    translateLive(text)
                }
                if (isRecording && !isPaused) {
                    restartListening()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val partial = matches[0]
                    tvLiveCaption.text = "$accumulatedText $partial"
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun restartListening() {
        Log.d(TAG, "restartListening: speechRecognizer=$speechRecognizer, intent=$recognizerIntent")
        recognizerIntent?.let {
            speechRecognizer?.startListening(it)
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "startRecording: requesting RECORD_AUDIO permission")
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return
        }

        Log.d(TAG, "startRecording: starting... speechRecognizer=$speechRecognizer, mic available=${isMicrophoneAvailable()}")

        isRecording = true
        isPaused = false
        accumulatedText = ""
        pausedDuration = 0L

        // Update UI
        fabRecord.setImageResource(android.R.drawable.ic_media_pause)
        recordingIndicator.visibility = View.VISIBLE
        chronometer.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.listening)
        tvLiveCaption.text = ""
        tvLiveTranslation.text = ""

        // Start chronometer
        startTime = SystemClock.elapsedRealtime()
        chronometer.base = startTime + pausedDuration
        chronometer.start()

        // This device cannot run SpeechRecognizer and MediaRecorder simultaneously.
        // Strategy: Use MediaRecorder for audio file + system speech dialog for transcription.
        // The system dialog handles microphone access and works on all devices with Google services.

        // Start audio recording first
        var audioStarted = false
        try {
            startAudioRecording()
            audioStarted = true
            Log.d(TAG, "startRecording: MediaRecorder started")
        } catch (e: Exception) {
            Log.w(TAG, "startRecording: MediaRecorder failed: ${e.message}")
        }

        // Launch system speech recognition dialog for transcription
        // This shows a dialog for the user to speak into and returns transcribed text
        startSystemSpeechRecognition()
    }

    /**
     * Check if the microphone is available (not busy).
     */
    private fun isMicrophoneAvailable(): Boolean {
        return try {
            val mediaRecorder = MediaRecorder()
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.DEFAULT)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.DEFAULT)
            mediaRecorder.setOutputFile("/dev/null")
            mediaRecorder.prepare()
            mediaRecorder.release()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Microphone not available: ${e.message}")
            false
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "RECORD_AUDIO permission granted")
                // Retry starting recording
                startRecording()
            } else {
                Log.d(TAG, "RECORD_AUDIO permission denied")
                tvStatus.text = "Microphone permission denied"
                Toast.makeText(this, "Microphone permission required for recording", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Launch the system speech recognition dialog for transcription.
     * This is the primary transcription method since SpeechRecognizer and MediaRecorder
     * cannot run simultaneously on this device.
     */
    private fun startSystemSpeechRecognition() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, getSpeechLanguageCode(sourceLanguage))
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now for transcription...")
            }
            startActivityForResult(intent, 300)
        } catch (e: Exception) {
            Log.e(TAG, "startSystemSpeechRecognition failed", e)
            tvStatus.text = "Speech recognition not available"
            Toast.makeText(this, "Speech recognition not available on this device", Toast.LENGTH_LONG).show()
        }
    }

    private fun startAudioRecording() {
        val audioDir = File(getExternalFilesDir(null), "recordings").also { it.mkdirs() }
        currentAudioPath = "${audioDir.absolutePath}/rec_${System.currentTimeMillis()}.m4a"

        recorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        recorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128000)
            setAudioSamplingRate(44100)
            setOutputFile(currentAudioPath)
            prepare()
            start()
        }
    }

    private fun stopRecording() {
        isRecording = false
        isPaused = false

        // Stop UI
        fabRecord.setImageResource(android.R.drawable.ic_btn_speak_now)
        recordingIndicator.visibility = View.GONE
        chronometer.stop()
        chronometer.visibility = View.GONE
        tvStatus.text = getString(R.string.tap_to_speak)

        // Stop speech recognition
        speechRecognizer?.stopListening()

        // Note: MediaRecorder is not used during speech recognition to avoid
        // microphone conflict. SpeechRecognizer handles audio internally.
        // Clean up any recorder that might exist.
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaRecorder stop error: ${e.message}")
        }
        recorder = null

        // Save recording
        saveRecording()
    }

    private fun saveRecording() {
        val duration = SystemClock.elapsedRealtime() - startTime - pausedDuration

        lifecycleScope.launch {
            val recording = Recording(
                projectId = projectId,
                title = "Recording ${Date().toString().take(19)}",
                audioPath = currentAudioPath,
                duration = duration,
                transcribedText = accumulatedText.trim(),
                translatedText = lastTranslatedText,
                translationLang = targetLanguage
            )
            recordingDao.insert(recording)

            // Update project count
            val project = projectDao.getById(projectId)
            project?.let {
                it.recordingCount = recordingDao.getCountForProject(projectId)
                projectDao.update(it)
            }

            Toast.makeText(this@RecordActivity, "Recording saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun translateLive(text: String) {
        // Accumulate text first
        accumulatedText += " $text"
        tvLiveCaption.text = accumulatedText

        // Translate the full accumulated text (not just the new chunk)
        lifecycleScope.launch {
            try {
                Log.d(TAG, "translateLive: translating '$accumulatedText' from $sourceLanguage to $targetLanguage")
                val result = TranslationHelper.translate(accumulatedText, targetLanguage, sourceLanguage)
                lastTranslatedText = result.translatedText
                tvLiveTranslation.text = result.translatedText
                Log.d(TAG, "translateLive: result = '${result.translatedText}'")
            } catch (e: Exception) {
                Log.e(TAG, "translateLive failed", e)
                tvLiveTranslation.text = "Translation failed: ${e.message ?: "Unknown error"}"
            }
        }
    }

    private fun showLanguageDialog() {
        val languages = arrayOf(
            "English → 简体中文",
            "English → 繁體中文",
            "English → 日本語",
            "English → 한국어",
            "English → Français",
            "English → Deutsch",
            "English → Español",
            "简体中文 → English",
            "繁體中文 → English",
            "日本語 → English",
            "한국어 → English"
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.select_language)
            .setItems(languages) { _, which ->
                when (which) {
                    0 -> { sourceLanguage = "english"; targetLanguage = "chinese" }
                    1 -> { sourceLanguage = "english"; targetLanguage = "traditional" }
                    2 -> { sourceLanguage = "english"; targetLanguage = "japanese" }
                    3 -> { sourceLanguage = "english"; targetLanguage = "korean" }
                    4 -> { sourceLanguage = "english"; targetLanguage = "french" }
                    5 -> { sourceLanguage = "english"; targetLanguage = "german" }
                    6 -> { sourceLanguage = "english"; targetLanguage = "spanish" }
                    7 -> { sourceLanguage = "chinese"; targetLanguage = "english" }
                    8 -> { sourceLanguage = "traditional"; targetLanguage = "english" }
                    9 -> { sourceLanguage = "japanese"; targetLanguage = "english" }
                    10 -> { sourceLanguage = "korean"; targetLanguage = "english" }
                }
                // Persist to settings
                SettingsManager.setSpeechLanguage(this, sourceLanguage)
                SettingsManager.setTranslationLanguage(this, targetLanguage)
                // Update recognizer intent with new source language
                recognizerIntent?.putExtra(RecognizerIntent.EXTRA_LANGUAGE, getSpeechLanguageCode(sourceLanguage))
                recognizerIntent?.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, getSpeechLanguageCode(sourceLanguage))
                Toast.makeText(this, "Translate: ${languages[which]}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun importAudioFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "audio/*"
        }
        startActivityForResult(intent, 200)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // System speech recognition dialog result (primary transcription method)
        if (requestCode == 300) {
            if (resultCode == RESULT_OK && data != null) {
                val matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    accumulatedText = if (accumulatedText.isBlank()) text else "$accumulatedText $text"
                    tvLiveCaption.text = accumulatedText
                    Log.d(TAG, "System speech dialog result: $text")

                    // Translate the full accumulated text
                    lifecycleScope.launch {
                        try {
                            val result = TranslationHelper.translate(accumulatedText, targetLanguage, sourceLanguage)
                            lastTranslatedText = result.translatedText
                            tvLiveTranslation.text = result.translatedText
                            Log.d(TAG, "Translation result: ${result.translatedText}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Translation failed", e)
                            tvLiveTranslation.text = "Translation failed: ${e.message ?: "Unknown error"}"
                        }
                    }

                    tvStatus.text = getString(R.string.listening)
                }
            } else {
                Log.d(TAG, "System speech dialog cancelled or failed: resultCode=$resultCode")
                tvStatus.text = "Speech recognition cancelled"
            }
            return
        }

        if (requestCode == 200 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                // Copy file to app storage
                val audioDir = File(getExternalFilesDir(null), "recordings").also { it.mkdirs() }
                val destFile = File(audioDir, "imported_${System.currentTimeMillis()}.m4a")
                contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                lifecycleScope.launch {
                    val recording = Recording(
                        projectId = projectId,
                        title = "Imported ${Date().toString().take(19)}",
                        audioPath = destFile.absolutePath,
                        duration = 0
                    )
                    recordingDao.insert(recording)
                    Toast.makeText(this@RecordActivity, "Audio imported", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getSpeechErrorMessage(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio error — microphone may be busy or unavailable"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied — grant permission in settings"
            SpeechRecognizer.ERROR_NETWORK -> "Network error — check internet connection"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected — check microphone and speak clearly"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy — try again"
            SpeechRecognizer.ERROR_SERVER -> "Server error — try again later"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input detected"
            else -> "Unknown error ($error)"
        }
    }

    // ── Zoom controls ──

    private fun adjustZoom(delta: Float) {
        captionTextSize = (captionTextSize + delta).coerceIn(minTextSize, maxTextSize)
        translationTextSize = (translationTextSize + delta).coerceIn(minTextSize, maxTextSize)
        applyTextSize()
    }

    private fun applyTextSize() {
        tvLiveCaption.textSize = captionTextSize
        tvLiveTranslation.textSize = translationTextSize
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) {
            try {
                recorder?.apply { stop(); release() }
            } catch (_: Exception) {}
            speechRecognizer?.stopListening()
        }
        speechRecognizer?.destroy()
        TranslationHelper.releaseAll()
    }
}
