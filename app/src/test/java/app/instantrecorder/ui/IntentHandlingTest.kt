package app.instantrecorder.ui

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentHandlingTest {

    @Test
    fun `isQuickRecordIntent returns true for ACTION_QUICK_RECORD`() {
        assertTrue(MainActivity.isQuickRecordIntent(MainActivity.ACTION_QUICK_RECORD))
    }

    @Test
    fun `isQuickRecordIntent returns true for CREATE_NOTE intent`() {
        assertTrue(MainActivity.isQuickRecordIntent(Intent.ACTION_CREATE_NOTE))
        assertTrue(MainActivity.isQuickRecordIntent("android.intent.action.CREATE_NOTE"))
    }

    @Test
    fun `isQuickRecordIntent returns false for other intents`() {
        assertFalse(MainActivity.isQuickRecordIntent(Intent.ACTION_MAIN))
        assertFalse(MainActivity.isQuickRecordIntent(Intent.ACTION_VIEW))
        assertFalse(MainActivity.isQuickRecordIntent("android.intent.action.VIEW"))
        assertFalse(MainActivity.isQuickRecordIntent(null))
        assertFalse(MainActivity.isQuickRecordIntent("random.custom.action"))
    }

    @Test
    fun `isQuickRecord returns true for QuickRecord activity alias`() {
        assertTrue(MainActivity.isQuickRecord(action = null, componentClassName = MainActivity.QUICK_RECORD_ALIAS))
        assertTrue(MainActivity.isQuickRecord(action = MainActivity.ACTION_QUICK_RECORD, componentClassName = null))
        assertTrue(MainActivity.isQuickRecord(action = Intent.ACTION_CREATE_NOTE, componentClassName = null))
        assertFalse(MainActivity.isQuickRecord(action = Intent.ACTION_MAIN, componentClassName = "app.instantrecorder.ui.MainActivity"))
    }
}
