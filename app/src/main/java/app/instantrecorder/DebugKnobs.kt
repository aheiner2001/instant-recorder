package app.instantrecorder

/** Overrides for on-device experiments, set only by the debug build's DebugImportReceiver. No effect by default. */
object DebugKnobs {
    /** Force whisper's thread count instead of sizing it to the allowed cores. */
    @Volatile var threads: Int? = null

    /** Skip the "tasks found" notification, so benchmark runs don't buzz the phone. */
    @Volatile var quietResults = false
}
