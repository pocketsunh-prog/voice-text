package com.voicetext.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects")
data class Project(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var name: String,
    val createdAt: Long = System.currentTimeMillis(),
    var recordingCount: Int = 0
)

@Entity(
    tableName = "recordings",
    foreignKeys = [
        ForeignKey(
            entity = Project::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId")]
)
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val title: String,
    val audioPath: String,
    val duration: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    var transcribedText: String = "",
    var translatedText: String = "",
    var translationLang: String = ""
)

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings WHERE projectId = :projectId ORDER BY createdAt DESC")
    fun getRecordingsForProject(projectId: Long): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE projectId = :projectId ORDER BY createdAt DESC")
    suspend fun getRecordingsForProjectList(projectId: Long): List<Recording>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getRecordingById(id: Long): Recording?

    @Insert
    suspend fun insert(recording: Recording): Long

    @Update
    suspend fun update(recording: Recording)

    @Delete
    suspend fun delete(recording: Recording)

    @Query("DELETE FROM recordings WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: Long)

    @Query("SELECT COUNT(*) FROM recordings WHERE projectId = :projectId")
    suspend fun getCountForProject(projectId: Long): Int
}

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    fun getAllProjects(): Flow<List<Project>>

    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    suspend fun getAllProjectsList(): List<Project>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: Long): Project?

    @Insert
    suspend fun insert(project: Project): Long

    @Update
    suspend fun update(project: Project)

    @Delete
    suspend fun delete(project: Project)

    @Query("SELECT COUNT(*) FROM projects WHERE name = :name")
    suspend fun countByName(name: String): Int
}

@Database(entities = [Project::class, Recording::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun recordingDao(): RecordingDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "voice_text_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
