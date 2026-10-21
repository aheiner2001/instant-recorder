package app.instantrecorder.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteFormatParserTest {

    @Test
    fun `parses title prefix into markdown heading`() {
        val result = NoteFormatParser.parse("Title: Sunday Sacrament Talk. Prepare thoughts on faith")
        assertEquals("Sunday Sacrament Talk", result.title)
        assertTrue(result.formattedText.startsWith("# Sunday Sacrament Talk\n\nPrepare thoughts on faith"))
    }

    @Test
    fun `parses spoken bullet triggers into markdown list`() {
        val result = NoteFormatParser.parse("Bullet read scriptures bullet say prayers bullet attend meetings")
        val expected = "- Read scriptures\n- Say prayers\n- Attend meetings"
        assertEquals(expected, result.formattedText)
    }

    @Test
    fun `extracts hashtag and tag keywords`() {
        val result = NoteFormatParser.parse("Great talk idea tag sermon and hashtag youth")
        assertTrue(result.tags.contains("sermon"))
        assertTrue(result.tags.contains("youth"))
    }

    @Test
    fun `parses add category command`() {
        val result = NoteFormatParser.parse("Add category Sunday Prep: review lesson material")
        assertEquals("Sunday Prep", result.addCategoryName)
        assertEquals("Review lesson material", result.formattedText)
    }

    @Test
    fun `parses append command`() {
        val result = NoteFormatParser.parse("Append to Church: check with choir director")
        assertEquals("Church", result.appendCategoryName)
        assertEquals("Check with choir director", result.formattedText)
    }

    @Test
    fun `identifies task prefix`() {
        val result = NoteFormatParser.parse("Task: call mom tomorrow at 5")
        assertTrue(result.isTask)
    }

    @Test
    fun `handles clean transcript without formatting commands`() {
        val clean = "Remember to pick up the dry cleaning tomorrow morning"
        val result = NoteFormatParser.parse(clean)
        assertNull(result.title)
        assertEquals(clean, result.formattedText)
        assertTrue(result.tags.isEmpty())
        assertNull(result.addCategoryName)
        assertNull(result.appendCategoryName)
        assertFalse(result.isTask)
    }

    @Test
    fun `handles empty and blank transcripts`() {
        val emptyResult = NoteFormatParser.parse("")
        assertEquals("", emptyResult.formattedText)
        assertNull(emptyResult.title)
        assertTrue(emptyResult.tags.isEmpty())

        val blankResult = NoteFormatParser.parse("   ")
        assertEquals("", blankResult.formattedText)
        assertNull(blankResult.title)
    }

    @Test
    fun `title without body formats heading only`() {
        val result = NoteFormatParser.parse("Title: Sunday Sacrament Talk")
        assertEquals("Sunday Sacrament Talk", result.title)
        assertEquals("# Sunday Sacrament Talk", result.formattedText)
    }

    @Test
    fun `parses bullet triggers with colons and commas`() {
        val result = NoteFormatParser.parse("bullet: one, bullet: two")
        val expected = "- One\n- Two"
        assertEquals(expected, result.formattedText)
    }

    @Test
    fun `parses add category with please prefix and that conjunction`() {
        val result = NoteFormatParser.parse("Please add category Choir that practice Tuesday at 7")
        assertEquals("Choir", result.addCategoryName)
        assertEquals("Practice Tuesday at 7", result.formattedText)
    }

    @Test
    fun `parses append with add to syntax`() {
        val result = NoteFormatParser.parse("Add to Work: submit quarterly report")
        assertEquals("Work", result.appendCategoryName)
        assertEquals("Submit quarterly report", result.formattedText)
    }

    @Test
    fun `identifies todo and remind me to task prefixes`() {
        val todoResult = NoteFormatParser.parse("Todo: buy milk and bread")
        assertTrue(todoResult.isTask)

        val remindResult = NoteFormatParser.parse("Remind me to call the dentist")
        assertTrue(remindResult.isTask)
    }

    @Test
    fun `deduplicates and lowercases tags`() {
        val result = NoteFormatParser.parse("Study session tag Gospel and tag GOSPEL and hashtag Sermon")
        assertEquals(listOf("gospel", "sermon"), result.tags)
    }

    @Test
    fun `parses combined title and bullets`() {
        val result = NoteFormatParser.parse("Title: Meeting Agenda. Bullet review budget bullet assign tasks")
        assertEquals("Meeting Agenda", result.title)
        val expected = "# Meeting Agenda\n\n- Review budget\n- Assign tasks"
        assertEquals(expected, result.formattedText)
    }

    @Test
    fun `supports apostrophes and curly apostrophes in category commands`() {
        val result1 = NoteFormatParser.parse("Add category Mom's list: buy birthday flowers")
        assertEquals("Mom's list", result1.addCategoryName)
        assertEquals("Buy birthday flowers", result1.formattedText)

        val result2 = NoteFormatParser.parse("Append to Aaron's prep: check mic levels")
        assertEquals("Aaron's prep", result2.appendCategoryName)
        assertEquals("Check mic levels", result2.formattedText)

        val result3 = NoteFormatParser.parse("Add category Mom’s list that buy flowers")
        assertEquals("Mom’s list", result3.addCategoryName)
        assertEquals("Buy flowers", result3.formattedText)
    }

    @Test
    fun `capitalizes addCategoryName`() {
        val result = NoteFormatParser.parse("add category grocery list: milk and eggs")
        assertEquals("Grocery list", result.addCategoryName)
        assertEquals("Milk and eggs", result.formattedText)
    }
}
