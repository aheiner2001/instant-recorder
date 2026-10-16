package app.instantrecorder.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

enum class RecordingStatus { RECORDED, TRANSCRIBING, DONE, NO_SPEECH, NEEDS_MODEL, FAILED }

@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val createdAt: Long,
    val durationMs: Long,
    val status: RecordingStatus = RecordingStatus.RECORDED,
    val transcript: String? = null,
    val error: String? = null,
    /** Wall-clock time the last transcription took, to judge model speed on this phone. */
    val transcribeMs: Long? = null,
    val modelId: String? = null,
    val tasksCreated: Int = 0,
)

@Entity(tableName = "tasks")
data class TaskItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String? = null,
    /** Epoch millis. When [dueHasTime] is false this is local midnight of the due day. */
    val dueAt: Long? = null,
    val dueHasTime: Boolean = false,
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val recordingId: Long? = null,
)

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observe(id: Long): Flow<Recording?>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun get(id: Long): Recording?

    @Insert
    suspend fun insert(recording: Recording): Long

    @Update
    suspend fun update(recording: Recording)

    @Delete
    suspend fun delete(recording: Recording)

    @Query("UPDATE recordings SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: RecordingStatus)

    @Query("UPDATE recordings SET transcript = :transcript WHERE id = :id")
    suspend fun setTranscript(id: Long, transcript: String)

    @Query("UPDATE recordings SET tasksCreated = tasksCreated + :count WHERE id = :id")
    suspend fun addTasksCreated(id: Long, count: Int)

    @Query("UPDATE recordings SET status = :to WHERE status = :from")
    suspend fun resetStatus(from: RecordingStatus, to: RecordingStatus)

    @Query("SELECT id FROM recordings WHERE status = :status ORDER BY createdAt")
    suspend fun idsWithStatus(status: RecordingStatus): List<Long>

    @Query("SELECT id FROM recordings")
    suspend fun allIds(): List<Long>
}

@Dao
interface TaskDao {
    @Query(
        "SELECT * FROM tasks ORDER BY done ASC, CASE WHEN dueAt IS NULL THEN 1 ELSE 0 END, dueAt ASC, createdAt DESC"
    )
    fun observeAll(): Flow<List<TaskItem>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: Long): TaskItem?

    @Insert
    suspend fun insertAll(tasks: List<TaskItem>): List<Long>

    @Update
    suspend fun update(task: TaskItem)

    @Delete
    suspend fun delete(task: TaskItem)

    @Query("SELECT * FROM tasks WHERE done = 0 AND dueAt IS NOT NULL")
    suspend fun openWithDueDate(): List<TaskItem>
}

@Database(entities = [Recording::class, TaskItem::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao
    abstract fun tasks(): TaskDao
}
