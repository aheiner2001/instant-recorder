# Instant Recorder

Proof-of-concept Android app for Pixel: press a physical button, speak, and get on-device
transcription plus suggested tasks.

- **Triggers**
  - Hold **Volume Down** (configurable): an Accessibility Service watches the volume keys. Quick presses still change the volume. Works whenever the screen is on, including the lock screen. Android doesn't deliver volume keys to apps while the screen is off.
  - **Quick Tap** (double-tap the back): point it at the "Quick Record" launcher entry. Unlocked phone only.
  - Quick Settings tile, long-press app-icon shortcut, and the in-app mic button.
- **Transcription**: [whisper.cpp](https://github.com/ggml-org/whisper.cpp) v1.9.5, compiled into the app and running on the CPU. The model downloads once, then everything is offline.
- **Tasks**: rule-based extraction (`TaskExtractor`) splits the note into clauses, strips "remind me to…", and detects dates and times. Save tasks to the in-app list (with reminders), add them to Calendar, or share them to any task app. Notifications have a one-tap **Save all**.

## Build & install

Requirements: JDK 17, Android SDK platform 37.2, NDK 28.2.13676358, CMake 3.31.5 (all via Android Studio's SDK Manager).

```sh
git submodule update --init          # whisper.cpp
./gradlew :app:installRelease        # ~5 MB, minified; signed with the debug key
./gradlew :app:testDebugUnitTest     # task-extraction tests
```

On the phone:
1. Open **Instant Recorder** and work through the "Finish setting up" card: microphone, notifications, speech model download, accessibility service.
2. Optional: Settings → System → Gestures → Quick Tap → Open app → **Quick Record**.

If you install from a downloaded APK rather than `adb`, Android may block the accessibility toggle ("Restricted setting"). Allow it from App info → ⋮ → Allow restricted settings.

## Layout

```
app/src/main/cpp/                 CMake + JNI bridge to whisper.cpp
app/src/main/java/app/instantrecorder/
  recording/RecordingService.kt   foreground service: mic → 16 kHz WAV → whisper queue
  trigger/Triggers.kt             volume-key AccessibilityService, Quick Settings tile
  transcribe/                     JNI wrapper, model download, transcriber
  tasks/                          TaskExtractor, saving, reminders, calendar/share intents
  ui/                             Compose screens (home, review, settings)
third_party/whisper.cpp           git submodule, pinned to v1.9.5
```
