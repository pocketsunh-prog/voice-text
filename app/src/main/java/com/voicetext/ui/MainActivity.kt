package com.voicetext.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.voicetext.R
import com.voicetext.data.AppDatabase
import com.voicetext.data.Project
import com.voicetext.data.ProjectDao
import com.voicetext.data.RecordingDao
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var database: AppDatabase
    private lateinit var projectDao: ProjectDao
    private lateinit var recordingDao: RecordingDao
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: android.view.View
    private lateinit var adapter: ProjectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        database = AppDatabase.getDatabase(this)
        projectDao = database.projectDao()
        recordingDao = database.recordingDao()

        recyclerView = findViewById(R.id.recycler_projects)
        emptyView = findViewById(R.id.empty_view)

        adapter = ProjectAdapter(
            onProjectClick = { project ->
                val intent = Intent(this, ProjectDetailActivity::class.java)
                intent.putExtra("project_id", project.id)
                startActivity(intent)
            },
            onProjectDelete = { project ->
                AlertDialog.Builder(this)
                    .setTitle(R.string.delete_project)
                    .setMessage("Delete \"${project.name}\" and all recordings?")
                    .setPositiveButton(R.string.confirm) { _, _ ->
                        lifecycleScope.launch {
                            // Delete all recordings for this project first
                            recordingDao.deleteForProject(project.id)
                            projectDao.delete(project)
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fab_add_project).setOnClickListener {
            showNewProjectDialog()
        }

        findViewById<FloatingActionButton>(R.id.fab_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<TextView>(R.id.btn_transcriptions).setOnClickListener {
            startActivity(Intent(this, TranscriptionsActivity::class.java))
        }

        loadProjects()
    }

    private fun loadProjects() {
        lifecycleScope.launch {
            projectDao.getAllProjects().collectLatest { projects ->
                adapter.submitList(projects)
                emptyView.visibility = if (projects.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                recyclerView.visibility = if (projects.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            }
        }
    }

    private fun showNewProjectDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.enter_project_name)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.new_project)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    if (projectDao.countByName(name) > 0) {
                        Toast.makeText(this@MainActivity, R.string.project_exists, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val project = Project(name = name)
                    projectDao.insert(project)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    class ProjectAdapter(
        private val onProjectClick: (Project) -> Unit,
        private val onProjectDelete: (Project) -> Unit
    ) : RecyclerView.Adapter<ProjectAdapter.ViewHolder>() {

        private var projects: List<Project> = emptyList()
        private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

        fun submitList(list: List<Project>) {
            projects = list
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
            val nameView: android.widget.TextView = view.findViewById(R.id.project_name)
            val dateView: android.widget.TextView = view.findViewById(R.id.project_date)
            val countView: android.widget.TextView = view.findViewById(R.id.recording_count)
            val deleteBtn: android.view.View = view.findViewById(R.id.btn_delete_project)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_project, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val project = projects[position]
            holder.nameView.text = project.name
            holder.dateView.text = dateFormat.format(Date(project.createdAt))
            holder.countView.text = "${project.recordingCount} recordings"
            holder.itemView.setOnClickListener { onProjectClick(project) }
            holder.deleteBtn.setOnClickListener { onProjectDelete(project) }
        }

        override fun getItemCount() = projects.size
    }
}
