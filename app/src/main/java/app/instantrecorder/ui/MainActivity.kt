package app.instantrecorder.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import app.instantrecorder.recording.RecordingService
import app.instantrecorder.ui.theme.InstantRecorderTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var startAfterPermission = false

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && startAfterPermission) RecordingService.send(this, RecordingService.ACTION_START)
        startAfterPermission = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Folding/unfolding recreates the activity with the same intent; only act on fresh launches.
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) handleIntent(intent)
        setContent {
            InstantRecorderTheme {
                AppRoot(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val recordingId = intent.getLongExtra(EXTRA_RECORDING_ID, -1)
        when {
            isQuickRecord(intent) -> quickRecord()
            recordingId >= 0 -> viewModel.open(Route.Review(recordingId))
        }
    }

    /** Quick Tap, the home-screen shortcut and the tile all land here: toggle recording. */
    private fun quickRecord() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            RecordingService.send(this, RecordingService.ACTION_TOGGLE)
        } else {
            startAfterPermission = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun isQuickRecord(intent: Intent) =
        intent.action == ACTION_QUICK_RECORD || intent.component?.className == QUICK_RECORD_ALIAS

    companion object {
        const val ACTION_QUICK_RECORD = "app.instantrecorder.action.QUICK_RECORD"
        const val EXTRA_RECORDING_ID = "recordingId"

        /** The manifest's activity-alias; named from the code namespace, not the app ID. */
        private const val QUICK_RECORD_ALIAS = "app.instantrecorder.QuickRecord"

        fun intent(context: Context): Intent =
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun quickRecordIntent(context: Context): Intent = intent(context).setAction(ACTION_QUICK_RECORD)
    }
}
