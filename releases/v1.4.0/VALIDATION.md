# BronyaTV 1.4.0 validation

Build environment: Linux x86_64, JDK 17, Android SDK 36 / Build Tools 36.0.0, Gradle 8.13.

- `testDebugUnitTest`: 74 tests passed. Covers search types, favorites, sorting and pagination, inherited backdrops and poster fallback, language defaults, dynamic label switching, and preservation of arguments and numeric formatting in both languages.
- `lintDebug`, `lintRelease`: passed with zero errors.
- `assembleDebug`, `assembleDebugAndroidTest`, `assembleRelease`: passed.
- Release APK: R8 and resource shrinking enabled, Android 6.0 (API 23) or later, version 1.4.0 / versionCode 9.
- `apksigner verify --verbose --print-certs` and `zipalign -c 4`: passed.

Device checks: Android 6.0 / API 23 x86 emulator at 1280×720, with a local mock Emby server. `VisualNavigationDeviceTest` passed all six tests:

- Language picker saves Chinese, updates a newly opened Home screen, then returns to English.
- DPAD moves sidebar focus; Favorites loads filtered server content and Back returns home.
- Playback opens the subtitle shortcut and continues advancing after returning.
- Inline search removes unrelated results and retains the recent query.
- Detail playback actions and settings controls remain reachable.
- An unreachable server shows Retry without crashing Home.

The five README screenshots were captured from the running English interface. Titles, metadata, artwork, and video are synthetic test fixtures.

Playback control checks used a real VP8 / Vorbis WebM via `playbackSource=vp8`. The software emulator's system H.264 decoder crashed on an SSE instruction, so this run does not validate H.264 playback, HDR / Dolby Vision, final HDMI audio output, or physical TV performance. The app's decoder binaries were not changed to work around the emulator.

This release uses a new signing key; installation instructions require uninstalling the old version. Decoder binaries are unchanged. Cache changes only localize status messages.
