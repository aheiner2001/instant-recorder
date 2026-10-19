package app.instantrecorder.data

import android.content.Context
import android.view.KeyEvent
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TriggerGesture(val label: String, val description: String) {
    BOTH_BUTTONS("Both buttons", "Press Volume Up and Volume Down together. Normal volume presses work as usual."),
    HOLD_ONE("Hold one", "Hold a single volume button. Holding it no longer ramps the volume."),
}

enum class TriggerKey(val keyCode: Int, val label: String) {
    VOLUME_DOWN(KeyEvent.KEYCODE_VOLUME_DOWN, "Volume down"),
    VOLUME_UP(KeyEvent.KEYCODE_VOLUME_UP, "Volume up"),
}

enum class TriggerMode(val label: String, val description: String) {
    TOGGLE("Toggle", "Start with the gesture, then press a volume button to stop"),
    HOLD_TO_TALK("Walkie-talkie", "Records while the button(s) are held, stops on release"),
}

data class Settings(
    val volumeTrigger: Boolean = true,
    val triggerGesture: TriggerGesture = TriggerGesture.BOTH_BUTTONS,
    val triggerKey: TriggerKey = TriggerKey.VOLUME_DOWN,
    val triggerMode: TriggerMode = TriggerMode.TOGGLE,
    val holdMs: Int = 500,
    val haptics: Boolean = true,
    val pauseMedia: Boolean = true,
    /** Quick notes stop by themselves after this much silence; 0 = never. Long recordings never do. */
    val silenceStopSec: Int = 8,
    /** Hard limit for quick notes, so one left running in noise ends soon anyway. */
    val quickMaxSec: Int = 30,
    val longMaxMin: Int = 30,
    val modelId: String = "small.en-q5_1", // "Accurate": clearly better on real notes, worth a few seconds
    /** Calendars switched off for event reminders (all visible calendars are searched by default). */
    val excludedCalendarIds: Set<Long> = emptySet(),
)

/** How to start a recording with the volume buttons, in words for UI hints. */
val Settings.volumeGestureHint: String
    get() = when (triggerGesture) {
        TriggerGesture.BOTH_BUTTONS -> "press both volume buttons"
        TriggerGesture.HOLD_ONE -> "hold ${triggerKey.label.lowercase()}"
    }

/** SharedPreferences-backed settings, exposed as a StateFlow so every component sees changes immediately. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state.asStateFlow()
    val value: Settings get() = _state.value

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        prefs.edit {
            putBoolean("volumeTrigger", next.volumeTrigger)
            putString("triggerGesture", next.triggerGesture.name)
            putString("triggerKey", next.triggerKey.name)
            putString("triggerMode", next.triggerMode.name)
            putInt("holdMs", next.holdMs)
            putBoolean("haptics", next.haptics)
            putBoolean("pauseMedia", next.pauseMedia)
            putInt("silenceStopSec", next.silenceStopSec)
            putInt("quickMaxSec", next.quickMaxSec)
            putInt("longMaxMin", next.longMaxMin)
            putString("modelId", next.modelId)
            putString("excludedCalendarIds", next.excludedCalendarIds.joinToString(","))
        }
        _state.value = next
    }

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            volumeTrigger = prefs.getBoolean("volumeTrigger", d.volumeTrigger),
            triggerGesture = enumOr(prefs.getString("triggerGesture", null), d.triggerGesture),
            triggerKey = enumOr(prefs.getString("triggerKey", null), d.triggerKey),
            triggerMode = enumOr(prefs.getString("triggerMode", null), d.triggerMode),
            holdMs = prefs.getInt("holdMs", d.holdMs),
            haptics = prefs.getBoolean("haptics", d.haptics),
            pauseMedia = prefs.getBoolean("pauseMedia", d.pauseMedia),
            silenceStopSec = prefs.getInt("silenceStopSec", d.silenceStopSec),
            quickMaxSec = prefs.getInt("quickMaxSec", d.quickMaxSec),
            longMaxMin = prefs.getInt("longMaxMin", d.longMaxMin),
            modelId = prefs.getString("modelId", d.modelId) ?: d.modelId,
            excludedCalendarIds = prefs.getString("excludedCalendarIds", null).orEmpty()
                .split(',').mapNotNull { it.trim().toLongOrNull() }.toSet(),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
