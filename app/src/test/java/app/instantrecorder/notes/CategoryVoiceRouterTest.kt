package app.instantrecorder.notes

import app.instantrecorder.data.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryVoiceRouterTest {

    private val categories = listOf(
        Category(id = 1, name = "Church", color = 0xFF43A047.toInt()),
        Category(id = 2, name = "Work", color = 0xFF1E88E5.toInt()),
        Category(id = 3, name = "Family", color = 0xFFFB8C00.toInt())
    )

    @Test
    fun `matching category prefix assigns id and cleans text with colon`() {
        val result = CategoryVoiceRouter.route("Church: Prepare the lesson on Sunday", categories)
        assertEquals(1L, result.categoryId)
        assertEquals("Prepare the lesson on Sunday", result.cleanedText)
    }

    @Test
    fun `matching category prefix with period`() {
        val result = CategoryVoiceRouter.route("Work. Follow up with Sarah regarding Q3 numbers", categories)
        assertEquals(2L, result.categoryId)
        assertEquals("Follow up with Sarah regarding Q3 numbers", result.cleanedText)
    }

    @Test
    fun `matching category prefix with dash and space`() {
        val result = CategoryVoiceRouter.route("Family - Plan weekend trip to the coast", categories)
        assertEquals(3L, result.categoryId)
        assertEquals("Plan weekend trip to the coast", result.cleanedText)
    }

    @Test
    fun `matching category prefix case-insensitive`() {
        val result = CategoryVoiceRouter.route("church, talk to bishop", categories)
        assertEquals(1L, result.categoryId)
        assertEquals("Talk to bishop", result.cleanedText)
    }

    @Test
    fun `matching category prefix with conversational lead-in`() {
        val result = CategoryVoiceRouter.route("For church: review sacrament schedule", categories)
        assertEquals(1L, result.categoryId)
        assertEquals("Review sacrament schedule", result.cleanedText)
    }

    @Test
    fun `unmatched category leaves text intact and category null for unassigned`() {
        val raw = "Remember to buy extra AAA batteries and paint"
        val result = CategoryVoiceRouter.route(raw, categories)
        assertNull(result.categoryId)
        assertEquals(raw, result.cleanedText)
    }

    @Test
    fun `does not create or match non-existent category`() {
        val raw = "Gym: do leg day tomorrow"
        val result = CategoryVoiceRouter.route(raw, categories)
        assertNull(result.categoryId)
        assertEquals(raw, result.cleanedText)
    }

    @Test
    fun `empty and blank transcripts return null category and empty text`() {
        val emptyResult = CategoryVoiceRouter.route("", categories)
        assertNull(emptyResult.categoryId)
        assertEquals("", emptyResult.cleanedText)

        val blankResult = CategoryVoiceRouter.route("   ", categories)
        assertNull(blankResult.categoryId)
        assertEquals("", blankResult.cleanedText)
    }

    @Test
    fun `matching category with notes suffix`() {
        val result = CategoryVoiceRouter.route("Work note: send weekly update", categories)
        assertEquals(2L, result.categoryId)
        assertEquals("Send weekly update", result.cleanedText)

        val resultPlural = CategoryVoiceRouter.route("Work notes: send weekly update", categories)
        assertEquals(2L, resultPlural.categoryId)
        assertEquals("Send weekly update", resultPlural.cleanedText)
    }

    @Test
    fun `matches longer category name first to avoid false prefix match`() {
        val extendedCategories = listOf(
            Category(id = 2, name = "Work", color = 0xFF1E88E5.toInt()),
            Category(id = 4, name = "Work Project", color = 0xFF9C27B0.toInt())
        )
        val result = CategoryVoiceRouter.route("Work Project: quarterly review", extendedCategories)
        assertEquals(4L, result.categoryId)
        assertEquals("Quarterly review", result.cleanedText)
    }
}
