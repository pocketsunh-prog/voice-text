package com.voicetext.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
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
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_SHORT).show()
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, getSpeechLanguageCode(sourceLanguage))
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, getSpeechLanguageCode(sourceLanguage))
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                tvStatus.text = getString(R.string.listening)
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                if (isRecording && !isPaused) {
                    restartListening()
                }
            }

            override fun onError(error: Int) {
                if (isRecording && !isPaused) {
                    restartListening()
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    accumulatedText += " $text"
                    tvLiveCaption.text = accumulatedText
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
        recognizerIntent?.let {
            speechRecognizer?.startListening(it)
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return
        }

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

        // Start audio recording
        startAudioRecording()

        // Start speech recognition
        recognizerIntent?.let {
            speechRecognizer?.startListening(it)
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

        // Stop audio recording
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        recorder = null

        // Stop speech recognition
        speechRecognizer?.stopListening()

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
        lifecycleScope.launch {
            try {
                val result = TranslationHelper.translate(text, targetLanguage, sourceLanguage)
                lastTranslatedText = result.translatedText
                tvLiveTranslation.text = result.translatedText
            } catch (e: Exception) {
                tvLiveTranslation.text = "Translation unavailable"
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
