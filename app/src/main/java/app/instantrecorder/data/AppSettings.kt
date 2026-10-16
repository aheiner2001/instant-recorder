package app.instantrecorder.data

import android.content.Context
import android.view.KeyEvent
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TriggerKey(val keyCode: Int, val label: String) {
    VOLUME_DOWN(KeyEvent.KEYCODE_VOLUME_DOWN, "Volume down"),
    VOLUME_UP(KeyEvent.KEYCODE_VOLUME_UP, "Volume up"),
}

enum class TriggerMode(val label: String, val description: String) {
    TOGGLE("Toggle", "Hold to start, press again to stop"),
    HOLD_TO_TALK("Walkie-talkie", "Records while held, stops on release"),
}

data class Settings(
    val volumeTrigger: Boolean = true,
    val triggerKey: TriggerKey = TriggerKey.VOLUME_DOWN,
    val triggerMode: TriggerMode = TriggerMode.TOGGLE,
    val holdMs: Int = 500,
    val haptics: Boolean = true,
    val pauseMedia: Boolean = true,
    val modelId: String = "base.en",
)

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
            putString("triggerKey", next.triggerKey.name)
            putString("triggerMode", next.triggerMode.name)
            putInt("holdMs", next.holdMs)
            putBoolean("haptics", next.haptics)
            putBoolean("pauseMedia", next.pauseMedia)
            putString("modelId", next.modelId)
        }
        _state.value = next
    }

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            volumeTrigger = prefs.getBoolean("volumeTrigger", d.volumeTrigger),
            triggerKey = enumOr(prefs.getString("triggerKey", null), d.triggerKey),
            triggerMode = enumOr(prefs.getString("triggerMode", null), d.triggerMode),
            holdMs = prefs.getInt("holdMs", d.holdMs),
            haptics = prefs.getBoolean("haptics", d.haptics),
            pauseMedia = prefs.getBoolean("pauseMedia", d.pauseMedia),
            modelId = prefs.getString("modelId", d.modelId) ?: d.modelId,
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
