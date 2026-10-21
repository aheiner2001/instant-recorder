package app.instantrecorder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteEntityTest {

    @Test
    fun `note item default parameters are correctly initialized`() {
        val before = System.currentTimeMillis()
        val note = NoteItem(text = "Review meeting notes")
        val after = System.currentTimeMillis()

        assertEquals(0L, note.id)
        assertEquals("Review meeting notes", note.text)
        assertNull(note.recordingId)
        assertNull(note.categoryId)
        assertTrue("createdAt should be >= $before, was ${note.createdAt}", note.createdAt in before..after)
        assertTrue("updatedAt should be >= $before, was ${note.updatedAt}", note.updatedAt in before..after)
        assertNull(note.markdownPath)
        assertEquals(false, note.pinned)
        assertEquals("", note.tags)
    }

    @Test
    fun `note item custom parameters are preserved`() {
        val note = NoteItem(
            id = 42L,
            text = "Full custom note",
            recordingId = 101L,
            categoryId = 7L,
            createdAt = 1000L,
            updatedAt = 2000L,
            markdownPath = "/notes/custom.md",
            pinned = true,
            tags = "work,urgent"
        )

        assertEquals(42L, note.id)
        assertEquals("Full custom note", note.text)
        assertEquals(101L, note.recordingId)
        assertEquals(7L, note.categoryId)
        assertEquals(1000L, note.createdAt)
        assertEquals(2000L, note.updatedAt)
        assertEquals("/notes/custom.md", note.markdownPath)
        assertEquals(true, note.pinned)
        assertEquals("work,urgent", note.tags)
    }

    @Test
    fun `copy note item allows assigning category while preserving other fields`() {
        val original = NoteItem(
            id = 10L,
            text = "Unassigned note",
            recordingId = 55L,
            categoryId = null,
            createdAt = 5000L,
            updatedAt = 5000L,
            markdownPath = "/notes/orig.md"
        )

        assertNull(original.categoryId)

        val updatedTime = 8000L
        val assigned = original.copy(
            categoryId = 3L,
            updatedAt = updatedTime
        )

        assertEquals(10L, assigned.id)
        assertEquals("Unassigned note", assigned.text)
        assertEquals(55L, assigned.recordingId)
        assertEquals(3L, assigned.categoryId)
        assertEquals(5000L, assigned.createdAt)
        assertEquals(8000L, assigned.updatedAt)
        assertEquals("/notes/orig.md", assigned.markdownPath)
    }

    @Test
    fun `migration 6 to 7 has correct start and end versions`() {
        assertEquals(6, AppDatabase.MIGRATION_6_7.startVersion)
        assertEquals(7, AppDatabase.MIGRATION_6_7.endVersion)
    }

    @Test
    fun `migration 6 to 7 executes create table statement with correct columns`() {
        val executedSql = mutableListOf<String>()
        val db = java.lang.reflect.Proxy.newProxyInstance(
            androidx.sqlite.db.SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executedSql.add(args[0] as String)
            }
            null
        } as androidx.sqlite.db.SupportSQLiteDatabase

        AppDatabase.MIGRATION_6_7.migrate(db)

        assertTrue(executedSql.isNotEmpty())
        val sql = executedSql.first()
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `notes`"))
        assertTrue(sql.contains("`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL"))
        assertTrue(sql.contains("`text` TEXT NOT NULL"))
        assertTrue(sql.contains("`recordingId` INTEGER"))
        assertTrue(sql.contains("`categoryId` INTEGER"))
        assertTrue(sql.contains("`createdAt` INTEGER NOT NULL"))
        assertTrue(sql.contains("`updatedAt` INTEGER NOT NULL"))
        assertTrue(sql.contains("`markdownPath` TEXT"))
    }

    @Test
    fun `migration 7 to 8 has correct start and end versions`() {
        assertEquals(7, AppDatabase.MIGRATION_7_8.startVersion)
        assertEquals(8, AppDatabase.MIGRATION_7_8.endVersion)
    }

    @Test
    fun `migration 7 to 8 executes alter table statements for pinned and tags`() {
        val executedSql = mutableListOf<String>()
        val db = java.lang.reflect.Proxy.newProxyInstance(
            androidx.sqlite.db.SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executedSql.add(args[0] as String)
            }
            null
        } as androidx.sqlite.db.SupportSQLiteDatabase

        AppDatabase.MIGRATION_7_8.migrate(db)

        assertEquals(2, executedSql.size)
        assertTrue(executedSql[0].contains("ALTER TABLE `notes` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(executedSql[1].contains("ALTER TABLE `notes` ADD COLUMN `tags` TEXT NOT NULL DEFAULT ''"))
    }

    @Test
    fun `note dao defines setPinned, setText, and getLatestToday methods and implementations exist`() {
        val setPinnedMethod = NoteDao::class.java.methods.firstOrNull { it.name.startsWith("setPinned") }
        assertNotNull("NoteDao should define setPinned method", setPinnedMethod)

        val setTextMethod = NoteDao::class.java.methods.firstOrNull { it.name.startsWith("setText") }
        assertNotNull("NoteDao should define setText method", setTextMethod)

        val getLatestTodayMethod = NoteDao::class.java.methods.firstOrNull { it.name.startsWith("getLatestToday") }
        assertNotNull("NoteDao should define getLatestToday method", getLatestTodayMethod)

        val implClass = Class.forName("app.instantrecorder.data.NoteDao_Impl")
        assertTrue("NoteDao_Impl must implement NoteDao", NoteDao::class.java.isAssignableFrom(implClass))

        val implSetPinned = implClass.methods.firstOrNull { it.name.startsWith("setPinned") }
        assertNotNull("NoteDao_Impl should implement setPinned method", implSetPinned)

        val implSetText = implClass.methods.firstOrNull { it.name.startsWith("setText") }
        assertNotNull("NoteDao_Impl should implement setText method", implSetText)

        val implGetLatestToday = implClass.methods.firstOrNull { it.name.startsWith("getLatestToday") }
        assertNotNull("NoteDao_Impl should implement getLatestToday method", implGetLatestToday)
    }

    @Test
    fun `note dao defines clearCategory method and implementation exists`() {
        val method = NoteDao::class.java.methods.firstOrNull { it.name.startsWith("clearCategory") }
        assertNotNull("NoteDao should define clearCategory method", method)

        val implClass = Class.forName("app.instantrecorder.data.NoteDao_Impl")
        assertTrue("NoteDao_Impl must implement NoteDao", NoteDao::class.java.isAssignableFrom(implClass))
        val implMethod = implClass.methods.firstOrNull { it.name.startsWith("clearCategory") }
        assertNotNull("NoteDao_Impl should implement clearCategory method", implMethod)
    }
}
