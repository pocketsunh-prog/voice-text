package com.voicetext.ui

import android.content.Intent
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
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
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            },
            onTranslateClick = { recording -> translateRecording(recording) },
            onExportMp3Click = { recording -> startExportFlow(recording) }
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
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(recording.audioPath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot play audio", Toast.LENGTH_SHORT).show()
        }
    }

    private fun translateRecording(recording: Recording) {
        val textToTranslate = recording.transcribedText
        if (textToTranslate.isBlank()) {
            Toast.makeText(this, "No text to translate", Toast.LENGTH_SHORT).show()
            return
        }

        // Use the recording's stored translation language, or fall back to settings
        val targetLang = if (recording.translationLang.isNotEmpty()) {
            recording.translationLang
        } else {
            SettingsManager.getTranslationLanguage(this)
        }
        val sourceLang = SettingsManager.getSpeechLanguage(this)

        adapter.setTranslating(recording.id, true)

        lifecycleScope.launch {
            try {
                val result = TranslationHelper.translate(textToTranslate, targetLang, sourceLang)
                adapter.setTranslatedText(recording.id, result.translatedText)
            } catch (e: Exception) {
                val errorMsg = "Translation failed: ${e.message ?: "Unknown error"}"
                adapter.setTranslatedText(recording.id, errorMsg)
                Toast.makeText(this@ProjectDetailActivity, errorMsg, Toast.LENGTH_LONG).show()
            } finally {
                adapter.setTranslating(recording.id, false)
            }
        }
    }

    /**
     * Start the export flow: store the recording and launch SAF document picker.
     * The exporter will try MP3 first, then fall back to AAC (M4A) automatically.
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

        // Determine the best available format for the suggested filename
        val format = if (isEncoderAvailable(MediaFormat.MIMETYPE_AUDIO_MPEG)) {
            Mp3Exporter.Format.MP3
        } else {
            Mp3Exporter.Format.AAC
        }

        // Launch SAF picker with a suggested filename
        val safeTitle = recording.title.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val suggestedName = "${safeTitle}.${format.extension}"
        createDocumentLauncher.launch(suggestedName)
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

        lifecycleScope.launch {
            try {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    when (val result = Mp3Exporter.export(inputFile, outputStream)) {
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

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }

    class RecordingAdapter(
        private val onPlayClick: (Recording) -> Unit,
        private val onDeleteClick: (Recording) -> Unit,
        private val onTranslateClick: (Recording) -> Unit,
        private val onExportMp3Click: (Recording) -> Unit
    ) : RecyclerView.Adapter<RecordingAdapter.ViewHolder>() {

        private var recordings: List<Recording> = emptyList()
        private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        private val translatedTexts = mutableMapOf<Long, String>()
        private val translatingFlags = mutableMapOf<Long, Boolean>()
        private val exportingFlags = mutableMapOf<Long, Boolean>()

        fun submitList(list: List<Recording>) {
            recordings = list
            // Pre-populate with existing translations from recordings
            list.forEach { recording ->
                if (!recording.translatedText.isNullOrEmpty()) {
                    translatedTexts[recording.id] = recording.translatedText
                }
            }
            notifyDataSetChanged()
        }

        fun setTranslatedText(recordingId: Long, text: String) {
            translatedTexts[recordingId] = text
            val index = recordings.indexOfFirst { it.id == recordingId }
            if (index >= 0) notifyItemChanged(index)
        }

        fun setTranslating(recordingId: Long, isTranslating: Boolean) {
            translatingFlags[recordingId] = isTranslating
            val index = recordings.indexOfFirst { it.id == recordingId }
            if (index >= 0) notifyItemChanged(index)
        }

        fun setExporting(recordingId: Long, isExporting: Boolean) {
            if (recordingId <= 0) return
            exportingFlags[recordingId] = isExporting
            val index = recordings.indexOfFirst { it.id == recordingId }
            if (index >= 0) notifyItemChanged(index)
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val titleView: TextView = view.findViewById(R.id.recording_title)
            val dateView: TextView = view.findViewById(R.id.recording_date)
            val durationView: TextView = view.findViewById(R.id.recording_duration)
            val textView: TextView = view.findViewById(R.id.recording_text)
            val playBtn: View = view.findViewById(R.id.btn_play)
            val deleteBtn: View = view.findViewById(R.id.btn_delete)
            val translateBtn: View = view.findViewById(R.id.btn_translate)
            val exportMp3Btn: View = view.findViewById(R.id.btn_export_mp3)
            val translatedTextView: TextView = view.findViewById(R.id.recording_translated_text)
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
            holder.textView.text = recording.transcribedText.ifEmpty { "No transcription" }
            holder.playBtn.setOnClickListener { onPlayClick(recording) }
            holder.deleteBtn.setOnClickListener { onDeleteClick(recording) }
            holder.translateBtn.setOnClickListener { onTranslateClick(recording) }
            holder.exportMp3Btn.setOnClickListener { onExportMp3Click(recording) }

            // Show translated text if available
            val translatedText = translatedTexts[recording.id]
            if (!translatedText.isNullOrBlank()) {
                holder.translatedTextView.text = translatedText
                holder.translatedTextView.visibility = View.VISIBLE
            } else {
                holder.translatedTextView.visibility = View.GONE
            }

            // Show translating state
            val isTranslating = translatingFlags[recording.id] == true
            holder.translateBtn.isEnabled = !isTranslating
            (holder.translateBtn as? TextView)?.text = if (isTranslating) "..." else holder.itemView.context.getString(R.string.translate)

            // Show exporting state
            val isExporting = exportingFlags[recording.id] == true
            holder.exportMp3Btn.isEnabled = !isExporting
            (holder.exportMp3Btn as? TextView)?.text = if (isExporting) "..." else holder.itemView.context.getString(R.string.export_audio)
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
