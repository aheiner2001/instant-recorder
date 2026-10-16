package app.instantrecorder.trigger

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import app.instantrecorder.R
import app.instantrecorder.app
import app.instantrecorder.data.TriggerMode
import app.instantrecorder.recording.RecorderState
import app.instantrecorder.recording.RecordingService
import app.instantrecorder.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Watches the volume buttons (screen on, including the lock screen). Holding the trigger key starts a
 * recording; quick presses are passed on as normal volume changes. Android doesn't deliver volume
 * keys to apps while the screen is off.
 */
class VolumeKeyTriggerService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val audio by lazy { getSystemService(AudioManager::class.java) }

    /** We swallowed the DOWN of the current press, so we own its UP too. */
    private var ownsPress = false
    private var longPressFired = false

    private val onLongPress = Runnable {
        longPressFired = true
        start()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val settings = app.settings.value
        if (!settings.volumeTrigger || event.keyCode != settings.triggerKey.keyCode) return false
        if (!ownsPress && event.action == KeyEvent.ACTION_DOWN && inCall()) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return ownsPress
                ownsPress = true
                longPressFired = false
                if (RecorderState.isRecording && settings.triggerMode == TriggerMode.TOGGLE) {
                    // While recording, a single press is enough to stop.
                    longPressFired = true
                    stop()
                } else {
                    handler.postDelayed(onLongPress, settings.holdMs.toLong())
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                if (!ownsPress) return false
                ownsPress = false
                handler.removeCallbacks(onLongPress)
                if (longPressFired) {
                    // Unconditional: the service may not have processed our START yet, and it handles
                    // intents in order, so STOP right behind it still ends that recording.
                    if (settings.triggerMode == TriggerMode.HOLD_TO_TALK) {
                        RecordingService.send(this, RecordingService.ACTION_STOP)
                    }
                } else {
                    // A quick press: do what the volume key would have done.
                    val direction = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    audio.adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
                }
                return true
            }
        }
        return false
    }

    private fun start() {
        try {
            RecordingService.send(this, RecordingService.ACTION_START)
        } catch (e: Exception) {
            // Android refused a background foreground-service start; accessibility services may still open
            // an activity, and recording from the activity is always allowed.
            Log.w(TAG, "Falling back to the activity to start recording", e)
            startActivity(MainActivity.quickRecordIntent(this))
        }
    }

    private fun stop() {
        if (RecorderState.isRecording) RecordingService.send(this, RecordingService.ACTION_STOP)
    }

    private fun inCall() = when (audio.mode) {
        AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE -> true
        else -> false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        private const val TAG = "VolumeKeyTrigger"

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val me = ComponentName(context, VolumeKeyTriggerService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        /** Accessibility settings, asking the Settings app to highlight our entry in the list. */
        fun settingsIntent(context: Context): Intent {
            val key = ComponentName(context, VolumeKeyTriggerService::class.java).flattenToString()
            return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(":settings:fragment_args_key", key)
                .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", key) })
        }
    }
}

/** Quick Settings tile. Opens the app's quick-record entry, which toggles recording. */
class RecordTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var listening: Job? = null

    override fun onStartListening() {
        listening = scope.launch {
            RecorderState.status.collect { status ->
                val tile = qsTile ?: return@collect
                val recording = status is RecorderState.Status.Recording
                tile.state = if (recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                tile.label = if (recording) "Stop recording" else getString(R.string.tile_label)
                tile.icon = Icon.createWithResource(this@RecordTileService, if (recording) R.drawable.ic_stop else R.drawable.ic_mic)
                tile.updateTile()
            }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
    }

    override fun onClick() {
        val intent = MainActivity.quickRecordIntent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
