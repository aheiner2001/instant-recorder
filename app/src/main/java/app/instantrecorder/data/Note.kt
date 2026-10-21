package app.instantrecorder.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notes")
data class NoteItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val recordingId: Long? = null,
    val categoryId: Long? = null, // null = Unassigned
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val markdownPath: String? = null
)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<NoteItem>>

    @Query("SELECT * FROM notes WHERE categoryId = :categoryId ORDER BY createdAt DESC")
    fun observeByCategory(categoryId: Long): Flow<List<NoteItem>>

    @Query("SELECT * FROM notes WHERE categoryId IS NULL ORDER BY createdAt DESC")
    fun observeUnassigned(): Flow<List<NoteItem>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: Long): NoteItem?

    @Query("SELECT * FROM notes WHERE text LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    fun searchNotes(query: String): Flow<List<NoteItem>>

    @Insert
    suspend fun insert(note: NoteItem): Long

    @Update
    suspend fun update(note: NoteItem)

    @Delete
    suspend fun delete(note: NoteItem)

    @Query("UPDATE notes SET categoryId = :categoryId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setCategory(id: Long, categoryId: Long?, updatedAt: Long = System.currentTimeMillis())
}
