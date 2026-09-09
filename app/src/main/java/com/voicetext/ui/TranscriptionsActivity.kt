package com.voicetext.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.voicetext.R
import com.voicetext.data.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class TranscriptionsActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "TranscriptionsActivity"
    }

    private lateinit var database: AppDatabase
    private lateinit var recordingDao: RecordingDao
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var adapter: TranscriptionAdapter
    private lateinit var toolbar: MaterialToolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transcriptions)

        database = AppDatabase.getDatabase(this)
        recordingDao = database.recordingDao()

        toolbar = findViewById(R.id.toolbar)
        recyclerView = findViewById(R.id.recycler_transcriptions)
        emptyView = findViewById(R.id.empty_view)

        toolbar.setNavigationOnClickListener { finish() }

        adapter = TranscriptionAdapter(
            onTranslateClick = { recording -> translateTranscription(recording) },
            onCopyClick = { recording -> copyText(recording) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        loadTranscriptions()
    }

    private fun loadTranscriptions() {
        lifecycleScope.launch {
            val transcriptions = recordingDao.getAllTranscriptions()
            adapter.submitList(transcriptions)
            emptyView.visibility = if (transcriptions.isEmpty()) View.VISIBLE else View.GONE
            recyclerView.visibility = if (transcriptions.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun translateTranscription(recording: Recording) {
        val textToTranslate = recording.transcribedText
        if (textToTranslate.isBlank()) {
            Toast.makeText(this, R.string.no_text_to_translate, Toast.LENGTH_SHORT).show()
            return
        }

        val targetLang = SettingsManager.getTranslationLanguage(this)
        // Auto-detect source: use "auto" so the API detects the language
        val sourceLang = "auto"

        adapter.setTranslating(recording.id, true)

        lifecycleScope.launch {
            try {
                Log.d(TAG, "Translating to $targetLang: ${textToTranslate.take(50)}...")
                val result = TranslationHelper.translate(textToTranslate, targetLang, sourceLang)
                Log.d(TAG, "Translation result: ${result.translatedText.take(50)}...")

                // Check if translation is different from original
                if (result.translatedText.trim() == textToTranslate.trim()) {
                    Toast.makeText(this@TranscriptionsActivity, "Translation returned same text — try changing target language in settings", Toast.LENGTH_LONG).show()
                }

                // Save translation to database
                val updatedRecording = recording.copy(
                    translatedText = result.translatedText,
                    translationLang = targetLang
                )
                recordingDao.update(updatedRecording)
                adapter.updateRecording(updatedRecording)
            } catch (e: Exception) {
                Log.e(TAG, "Translation failed", e)
                val errorMsg = "Translation failed: ${e.message ?: "Unknown error"}"
                Toast.makeText(this@TranscriptionsActivity, errorMsg, Toast.LENGTH_LONG).show()
            } finally {
                adapter.setTranslating(recording.id, false)
            }
        }
    }

    /**
     * Copy transcribed text to clipboard.
     */
    private fun copyText(recording: Recording) {
        val textToCopy = recording.transcribedText
        if (textToCopy.isBlank()) {
            Toast.makeText(this, "No text to copy", Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Transcription", textToCopy)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Text copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    class TranscriptionAdapter(
        private val onTranslateClick: (Recording) -> Unit,
        private val onCopyClick: (Recording) -> Unit
    ) : RecyclerView.Adapter<TranscriptionAdapter.ViewHolder>() {

        private var recordings: List<Recording> = emptyList()
        private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        private val translatingFlags = mutableMapOf<Long, Boolean>()

        fun submitList(list: List<Recording>) {
            recordings = list
            notifyDataSetChanged()
        }

        fun setTranslating(recordingId: Long, isTranslating: Boolean) {
            translatingFlags[recordingId] = isTranslating
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
            val titleView: TextView = view.findViewById(R.id.transcription_title)
            val dateView: TextView = view.findViewById(R.id.transcription_date)
            val textView: TextView = view.findViewById(R.id.transcription_text)
            val translatedTextView: TextView = view.findViewById(R.id.transcription_translated_text)
            val translateBtn: View = view.findViewById(R.id.btn_translate)
            val copyBtn: View = view.findViewById(R.id.btn_copy)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_transcription, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val recording = recordings[position]
            holder.titleView.text = recording.title
            holder.dateView.text = dateFormat.format(Date(recording.createdAt))
            holder.textView.text = recording.transcribedText
            holder.translateBtn.setOnClickListener { onTranslateClick(recording) }
            holder.copyBtn.setOnClickListener { onCopyClick(recording) }

            // Show translated text if available
            val translatedText = recording.translatedText
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
        }

        override fun getItemCount() = recordings.size
    }
}
