package app.instantrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.instantrecorder.data.Recording
import app.instantrecorder.recording.RecordingService
import app.instantrecorder.recording.WavFile
import app.instantrecorder.transcribe.Transcriber
import app.instantrecorder.transcribe.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug-only test hook. Imports a 16 kHz mono WAV from the app's files dir as a new recording and
 * queues it for transcription, so the pipeline can be exercised without speaking:
 *
 *   adb exec-in run-as app.instantrecorder sh -c 'cat > files/import.wav' < note.wav
 *   adb shell am start -n app.instantrecorder/.ui.MainActivity     # foreground, so the service may start
 *   adb shell am broadcast -n app.instantrecorder/.DebugImportReceiver --es file import.wav
 *
 * Experiment knobs on import: --ei threads N (force thread count, 0 = automatic), --ez quiet true
 * (no result notification). Purge every imported recording: --ez purge true
 *
 * Benchmark encoder window sizes on existing files (logged under "Bench"):
 *
 *   adb shell am broadcast -n app.instantrecorder/.DebugImportReceiver --es bench recordings/a.wav,recordings/b.wav
 */
class DebugImportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.hasExtra("threads")) DebugKnobs.threads = intent.getIntExtra("threads", 0).takeIf { it > 0 }
        if (intent.hasExtra("quiet")) DebugKnobs.quietResults = intent.getBooleanExtra("quiet", false)
        if (intent.getBooleanExtra("purge", false)) purge(context)
        // --es setModel <id>: switch the selected speech model (changes the user's setting; set it back after).
        intent.getStringExtra("setModel")?.let { id -> context.app.settings.update { it.copy(modelId = id) } }
        intent.getStringExtra("bench")?.let { bench(context, it.split(','), intent.getStringExtra("model")) }
        val name = intent.getStringExtra("file") ?: return
        val pending = goAsync()
        context.app.appScope.launch {
            try {
                val now = System.currentTimeMillis()
                val dest = File(File(context.filesDir, "recordings").apply { mkdirs() }, "$IMPORT_PREFIX$now.wav")
                File(context.filesDir, name).copyTo(dest, overwrite = true)
                val samples = WavFile.readAsFloat(dest).size.toLong()
                val id = context.app.db.recordings().insert(
                    Recording(filePath = dest.absolutePath, createdAt = now, durationMs = WavFile.durationMs(samples))
                )
                RecordingService.send(context, RecordingService.ACTION_TRANSCRIBE) {
                    putExtra(RecordingService.EXTRA_RECORDING_ID, id)
                }
                Log.i("DebugImport", "Imported $name as recording $id")
            } catch (e: Exception) {
                Log.e("DebugImport", "Import failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun bench(context: Context, files: List<String>, modelId: String?) {
        val pending = goAsync()
        context.app.appScope.launch(Dispatchers.Default) {
            try {
                val model = modelId?.let(WhisperModel::byId) ?: context.app.models.modelForTranscription() ?: return@launch
                Transcriber(model, context.app.models.file(model)).use { t ->
                    for (name in files) {
                        val wav = File(context.filesDir, name)
                        val samples = WavFile.readAsFloat(wav).size
                        val windows = linkedMapOf(
                            "full" to 0,
                            "+2.0s" to Transcriber.encoderWindow(samples, 2.0),
                            "+1.0s" to Transcriber.encoderWindow(samples, 1.0),
                        )
                        t.transcribe(wav, 0) // warm-up: model load and first-run allocations
                        for ((label, window) in windows) {
                            val start = android.os.SystemClock.elapsedRealtime()
                            val text = t.transcribe(wav, window)
                            val ms = android.os.SystemClock.elapsedRealtime() - start
                            Log.i("Bench", "${model.id} | $name | $label (ctx $window) | $ms ms | $text")
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** Deletes every recording this receiver imported (files, rows and notifications). */
    private fun purge(context: Context) {
        val pending = goAsync()
        context.app.appScope.launch {
            try {
                val dao = context.app.db.recordings()
                for (id in dao.allIds()) {
                    val r = dao.get(id) ?: continue
                    if (!File(r.filePath).name.startsWith(IMPORT_PREFIX)) continue
                    File(r.filePath).delete()
                    dao.delete(r)
                    Notifications.cancelResult(context, r.id)
                }
                Log.i("DebugImport", "Purged imported recordings")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val IMPORT_PREFIX = "dbg_"
    }
}
