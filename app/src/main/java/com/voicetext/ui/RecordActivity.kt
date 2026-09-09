package com.voicetext.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.*
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
    private lateinit var tvStatus: TextView
    private lateinit var chronometer: Chronometer
    private lateinit var recordingIndicator: LinearLayout
    private lateinit var soundWaveView: SoundWaveView
    private lateinit var toolbar: MaterialToolbar

    private var projectId: Long = 0
    private var isRecording = false
    private var recorder: MediaRecorder? = null
    private var currentAudioPath = ""
    private var startTime = 0L

    // Sound wave animation
    private val handler = Handler(android.os.Looper.getMainLooper())
    private var amplitudeRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_record)

        projectId = intent.getLongExtra("project_id", 0)
        if (projectId == 0L) {
            finish()
            return
        }

        database = AppDatabase.getDatabase(this)
        recordingDao = database.recordingDao()
        projectDao = database.projectDao()

        toolbar = findViewById(R.id.toolbar)
        fabRecord = findViewById(R.id.fab_record)
        fabImport = findViewById(R.id.fab_import)
        tvStatus = findViewById(R.id.tv_status)
        chronometer = findViewById(R.id.chronometer)
        recordingIndicator = findViewById(R.id.recording_indicator)
        soundWaveView = findViewById(R.id.sound_wave_view)

        toolbar.setNavigationOnClickListener { finish() }

        fabRecord.setOnClickListener {
            if (!isRecording) startRecording() else stopRecording()
        }

        fabImport.setOnClickListener {
            importAudioFile()
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
        startTime = SystemClock.elapsedRealtime()

        // Update UI
        fabRecord.setImageResource(android.R.drawable.ic_media_pause)
        recordingIndicator.visibility = View.VISIBLE
        chronometer.visibility = View.VISIBLE
        soundWaveView.visibility = View.VISIBLE
        tvStatus.text = "Recording..."
        tvStatus.visibility = View.VISIBLE

        // Start chronometer
        chronometer.base = SystemClock.elapsedRealtime()
        chronometer.start()

        // Start audio recording
        startAudioRecording()

        // Start sound wave animation
        startSoundWaveAnimation()
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

    private fun startSoundWaveAnimation() {
        amplitudeRunnable = object : Runnable {
            override fun run() {
                if (isRecording && recorder != null) {
                    try {
                        val amplitude = recorder?.maxAmplitude ?: 0
                        // Normalize amplitude to 0-1 range (max amplitude is 32767)
                        val normalized = (amplitude / 32767f).coerceIn(0f, 1f)
                        soundWaveView.updateAmplitude(normalized)
                    } catch (e: Exception) {
                        // Ignore - recorder might not be ready
                    }
                    handler.postDelayed(this, 50) // Update every 50ms
                }
            }
        }
        handler.post(amplitudeRunnable!!)
    }

    private fun stopRecording() {
        isRecording = false

        // Stop UI
        fabRecord.setImageResource(android.R.drawable.ic_btn_speak_now)
        recordingIndicator.visibility = View.GONE
        chronometer.stop()
        chronometer.visibility = View.GONE
        soundWaveView.visibility = View.GONE
        tvStatus.text = R.string.tap_to_speak.toString()

        // Stop sound wave animation
        amplitudeRunnable?.let { handler.removeCallbacks(it) }
        soundWaveView.updateAmplitude(0f)

        // Stop audio recording
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
        val duration = SystemClock.elapsedRealtime() - startTime

        lifecycleScope.launch {
            val recording = Recording(
                projectId = projectId,
                title = "Recording ${Date().toString().take(19)}",
                audioPath = currentAudioPath,
                duration = duration,
                transcribedText = "",
                translatedText = "",
                translationLang = ""
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording()
            } else {
                tvStatus.text = "Microphone permission denied"
                Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) {
            try {
                recorder?.apply { stop(); release() }
            } catch (_: Exception) {}
        }
        amplitudeRunnable?.let { handler.removeCallbacks(it) }
    }
}
