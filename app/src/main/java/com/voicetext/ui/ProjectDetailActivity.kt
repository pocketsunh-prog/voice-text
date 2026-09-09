package com.voicetext.ui

import android.content.Intent
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.voicetext.R
import com.voicetext.data.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ProjectDetailActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "ProjectDetailActivity"
    }

    private lateinit var database: AppDatabase
    private lateinit var recordingDao: RecordingDao
    private lateinit var projectDao: ProjectDao
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var adapter: RecordingAdapter
    private lateinit var toolbar: MaterialToolbar

    private var projectId: Long = 0
    private var player: MediaPlayer? = null

    // Store the recording pending export (waiting for SAF result)
    private var pendingExportRecording: Recording? = null

    // SAF launcher: lets user pick where to save the audio file (MP3 or AAC)
    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("audio/*")
    ) { uri: Uri? ->
        uri?.let { handleExportResult(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_project_detail)

        projectId = intent.getLongExtra("project_id", 0)
        if (projectId == 0L) {
            finish()
            return
        }

        database = AppDatabase.getDatabase(this)
        recordingDao = database.recordingDao()
        projectDao = database.projectDao()

        toolbar = findViewById(R.id.toolbar)
        recyclerView = findViewById(R.id.recycler_recordings)
        emptyView = findViewById(R.id.empty_view)

        toolbar.setNavigationOnClickListener { finish() }

        adapter = RecordingAdapter(
            onPlayClick = { recording -> playRecording(recording) },
            onDeleteClick = { recording ->
                AlertDialog.Builder(this)
                    .setTitle(R.string.delete_recording)
                    .setPositiveButton(R.string.confirm) { _, _ ->
                        lifecycleScope.launch {
                            File(recording.audioPath).delete()
                            recordingDao.delete(recording)
                            // Update project recording count
                            val project = projectDao.getById(projectId)
                            if (project != null) {
                                project.recordingCount = recordingDao.getCountForProject(projectId)
                                projectDao.update(project)
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            },
            onExportMp3Click = { recording -> startExportFlow(recording) },
            onVoiceToTextClick = { recording -> startVoiceToText(recording) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fab_record).setOnClickListener {
            val intent = Intent(this, RecordActivity::class.java)
            intent.putExtra("project_id", projectId)
            startActivity(intent)
        }

        // Observe recordings with Flow for real-time updates
        lifecycleScope.launch {
            recordingDao.getRecordingsForProject(projectId).collectLatest { recordings ->
                adapter.submitList(recordings)
                emptyView.visibility = if (recordings.isEmpty()) View.VISIBLE else View.GONE
                recyclerView.visibility = if (recordings.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun playRecording(recording: Recording) {
        // Check if audio file exists (may not exist if recorded without audio)
        val audioFile = File(recording.audioPath)
        if (recording.audioPath.isEmpty() || !audioFile.exists()) {
            Toast.makeText(this, "No audio file available for this recording", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(recording.audioPath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot play audio: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Voice-to-Text: Play the audio and capture transcription via system speech dialog.
     * The audio plays through the speaker and the system dialog captures the speech.
     */
    private fun startVoiceToText(recording: Recording) {
        val audioFile = File(recording.audioPath)
        if (!audioFile.exists()) {
            Toast.makeText(this, "Audio file not found", Toast.LENGTH_SHORT).show()
            return
        }

        adapter.setTranscribing(recording.id, true)

        // Show instruction dialog
        AlertDialog.Builder(this)
            .setTitle("Voice to Text")
            .setMessage("The audio will play. Please ensure your microphone is ready to capture the speech.")
            .setPositiveButton("Start") { _, _ ->
                playAudioAndTranscribe(recording)
            }
            .setNegativeButton("Cancel") { _, _ ->
                adapter.setTranscribing(recording.id, false)
            }
            .show()
    }

    /**
     * Play the audio file and launch system speech dialog for transcription.
     */
    private fun playAudioAndTranscribe(recording: Recording) {
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(recording.audioPath)
                setOnCompletionListener {
                    // Audio finished - keep dialog open for a moment then save
                    Log.d(TAG, "Audio playback completed")
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "Audio playback error: what=$what, extra=$extra")
                    true
                }
                prepare()
                start()
            }

            // Launch speech dialog while audio is playing
            launchSpeechDialog(recording)
        } catch (e: Exception) {
            Log.e(TAG, "Playback failed", e)
            // If playback fails, still try the dialog
            launchSpeechDialog(recording)
        }
    }

    /**
     * Launch system speech recognition dialog.
     */
    private fun launchSpeechDialog(recording: Recording) {
        // Store reference for onActivityResult
        recordingBeingTranscribed = recording

        try {
            val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Listening...")
            }
            startActivityForResult(intent, 400)
        } catch (e: Exception) {
            Log.e(TAG, "Speech dialog failed", e)
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_LONG).show()
            adapter.setTranscribing(recording.id, false)
        }
    }

    /**
     * Start the export flow: store the recording and launch SAF document picker.
     * Uses the user's preferred export format from settings.
     */
    private fun startExportFlow(recording: Recording) {
        val inputFile = File(recording.audioPath)
        if (!inputFile.exists()) {
            Toast.makeText(this, "Audio file not found", Toast.LENGTH_SHORT).show()
            return
        }

        // Store recording for when SAF returns
        pendingExportRecording = recording
        adapter.setExporting(recording.id, true)

        // Determine the format based on user setting
        val exportFormat = SettingsManager.getExportFormat(this)
        val resolvedFormat = resolveExportFormat(exportFormat)

        // Launch SAF picker with a suggested filename
        val safeTitle = recording.title.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val suggestedName = "${safeTitle}.${resolvedFormat.extension}"
        createDocumentLauncher.launch(suggestedName)
    }

    /**
     * Resolve the export format based on user preference and device capability.
     */
    private fun resolveExportFormat(exportFormat: SettingsManager.ExportFormat): Mp3Exporter.Format {
        return when (exportFormat) {
            SettingsManager.ExportFormat.AUTO ->
                if (isEncoderAvailable(MediaFormat.MIMETYPE_AUDIO_MPEG)) Mp3Exporter.Format.MP3
                else Mp3Exporter.Format.AAC
            SettingsManager.ExportFormat.MP3 ->
                if (isEncoderAvailable(MediaFormat.MIMETYPE_AUDIO_MPEG)) Mp3Exporter.Format.MP3
                else Mp3Exporter.Format.AAC // Fall back if MP3 not available
            SettingsManager.ExportFormat.AAC ->
                Mp3Exporter.Format.AAC
        }
    }

    private fun isEncoderAvailable(mime: String): Boolean {
        val codecList = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
        return codecList.codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    }

    /**
     * Handle the SAF result: transcode and write to the selected URI.
     */
    private fun handleExportResult(uri: Uri) {
        val recording = pendingExportRecording
        pendingExportRecording = null

        if (recording == null) {
            return
        }

        val inputFile = File(recording.audioPath)
        // Get the user's preferred export format
        val exportFormat = SettingsManager.getExportFormat(this)
        val forcedFormat = when (exportFormat) {
            SettingsManager.ExportFormat.AUTO -> null // Let exporter auto-detect
            SettingsManager.ExportFormat.MP3 -> Mp3Exporter.Format.MP3
            SettingsManager.ExportFormat.AAC -> Mp3Exporter.Format.AAC
        }

        lifecycleScope.launch {
            try {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    when (val result = Mp3Exporter.export(inputFile, outputStream, forcedFormat)) {
                        is Mp3Exporter.Result.Success -> {
                            val formatName = result.format.name
                            Toast.makeText(
                                this@ProjectDetailActivity,
                                "$formatName ${getString(R.string.export_success)}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        is Mp3Exporter.Result.Error -> {
                            Toast.makeText(
                                this@ProjectDetailActivity,
                                "${getString(R.string.export_failed)}: ${result.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } ?: run {
                    Toast.makeText(
                        this@ProjectDetailActivity,
                        getString(R.string.export_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@ProjectDetailActivity,
                    "${getString(R.string.export_failed)}: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                adapter.setExporting(recording.id, false)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // Voice-to-Text speech dialog result
        if (requestCode == 400) {
            // Find the recording that was being transcribed
            // We need to track which recording triggered this
            val recording = findRecordingBeingTranscribed()

            if (recording != null) {
                adapter.setTranscribing(recording.id, false)

                if (resultCode == RESULT_OK && data != null) {
                    val matches = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                    if (!matches.isNullOrEmpty()) {
                        val transcribedText = matches[0]

                        // Save transcription to database
                        lifecycleScope.launch {
                            val updatedRecording = recording.copy(transcribedText = transcribedText)
                            recordingDao.update(updatedRecording)
                            adapter.updateRecording(updatedRecording)
                            Toast.makeText(this@ProjectDetailActivity, "Transcription saved", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Toast.makeText(this@ProjectDetailActivity, "Transcription cancelled", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Track the recording being transcribed
    private var recordingBeingTranscribed: Recording? = null

    private fun findRecordingBeingTranscribed(): Recording? {
        return recordingBeingTranscribed
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }

    class RecordingAdapter(
        private val onPlayClick: (Recording) -> Unit,
        private val onDeleteClick: (Recording) -> Unit,
        private val onExportMp3Click: (Recording) -> Unit,
        private val onVoiceToTextClick: (Recording) -> Unit
    ) : RecyclerView.Adapter<RecordingAdapter.ViewHolder>() {

        private var recordings: List<Recording> = emptyList()
        private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        private val exportingFlags = mutableMapOf<Long, Boolean>()
        private val transcribingFlags = mutableMapOf<Long, Boolean>()

        fun submitList(list: List<Recording>) {
            recordings = list
            notifyDataSetChanged()
        }

        fun setExporting(recordingId: Long, isExporting: Boolean) {
            if (recordingId <= 0) return
            exportingFlags[recordingId] = isExporting
            val index = recordings.indexOfFirst { it.id == recordingId }
            if (index >= 0) notifyItemChanged(index)
        }

        fun setTranscribing(recordingId: Long, isTranscribing: Boolean) {
            transcribingFlags[recordingId] = isTranscribing
            val index = recordings.indexOfFirst { it.id == recordingId }
            if (index >= 0) notifyItemChanged(index)
        }

        fun updateRecording(recording: Recording) {
            val index = recordings.indexOfFirst { it.id == recording.id }
            if (index >= 0) {
                recordings = recordings.toMutableList().also { it[index] = recording }
                notifyItemChanged(index)
            }
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val titleView: TextView = view.findViewById(R.id.recording_title)
            val dateView: TextView = view.findViewById(R.id.recording_date)
            val durationView: TextView = view.findViewById(R.id.recording_duration)
            val textView: TextView = view.findViewById(R.id.recording_text)
            val playBtn: View = view.findViewById(R.id.btn_play)
            val deleteBtn: View = view.findViewById(R.id.btn_delete)
            val voiceToTextBtn: View = view.findViewById(R.id.btn_voice_to_text)
            val exportMp3Btn: View = view.findViewById(R.id.btn_export_mp3)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_recording, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val recording = recordings[position]
            holder.titleView.text = recording.title
            holder.dateView.text = dateFormat.format(Date(recording.createdAt))
            holder.durationView.text = formatDuration(recording.duration)
            holder.textView.text = recording.transcribedText.ifEmpty { getString(holder, R.string.no_transcription) }
            holder.playBtn.setOnClickListener { onPlayClick(recording) }
            holder.deleteBtn.setOnClickListener { onDeleteClick(recording) }
            holder.voiceToTextBtn.setOnClickListener { onVoiceToTextClick(recording) }
            holder.exportMp3Btn.setOnClickListener { onExportMp3Click(recording) }

            // Show transcribing state
            val isTranscribing = transcribingFlags[recording.id] == true
            holder.voiceToTextBtn.isEnabled = !isTranscribing
            (holder.voiceToTextBtn as? TextView)?.text = if (isTranscribing) "..." else getString(holder, R.string.voice_to_text)

            // Show exporting state
            val isExporting = exportingFlags[recording.id] == true
            holder.exportMp3Btn.isEnabled = !isExporting
            (holder.exportMp3Btn as? TextView)?.text = if (isExporting) "..." else getString(holder, R.string.export_audio)
        }

        private fun getString(holder: ViewHolder, resId: Int): String {
            return holder.itemView.context.getString(resId)
        }

        override fun getItemCount() = recordings.size

        private fun formatDuration(ms: Long): String {
            val seconds = ms / 1000
            val minutes = seconds / 60
            val secs = seconds % 60
            return String.format("%02d:%02d", minutes, secs)
        }
    }
}
