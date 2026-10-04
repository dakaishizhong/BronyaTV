# BronyaTV 1.6.1 validation

## Build

JDK 17, Gradle 8.13, Android SDK / Build Tools 36, Kotlin 2.3.21, Compose BOM 2026.03 and TV Material 1.1.0. Media3 remains 1.11.1.

- 91 Debug and 91 Release JVM tests passed with no failures, errors or skips.
- `lintDebug` and `lintRelease` passed: zero errors, 87 nonblocking warnings, including existing platform deprecations and Compose style suggestions.
- Debug, instrumentation and Release APK builds passed. R8 and resource shrinking remain enabled.
- APK version 1.6.1 / versionCode 13, minimum API 23. Signature and 16 KiB-aware zip alignment verified. The certificate matches 1.4.0 onward; all eight native libraries across four ABIs are byte-for-byte identical to 1.6.0.
- SHA-256: `78b762604960a8e8d299ef501c4abfdc18743de81c1caced9699b5d993063fdb`.

## Remote and layout regression

Dedicated API 23 x86 software emulator and local HTTP Emby fixtures:

- Native 1920×1080, 320 dpi: 48 distinct device checks passed in two all-green invocations (2 new playback/header checks and 46 existing UI/player checks).
- 1280×720, 240 dpi: four additional checks passed for playback controls, every settings editor, five complete Home cards, and settings DPAD traversal / return focus. These repeat selected cases at the smaller resolution.
- Play/Pause center was x=960.0 on the 1920-pixel viewport and x=639.5 on the 1280-pixel viewport; the half-pixel difference is layout rounding. Button diameters were 100 and 67 pixels respectively. Changing focus did not move or resize the button.
- DPAD traversed sources → rewind → Play/Pause → forward → subtitles → More. Boundary movement stayed within the bar; Up reached the timeline and Down returned to Play/Pause. OK paused/resumed the real Media3 player and timeline Right advanced its playback position.
- The speaker shortcut was absent. Audio track selection remained available through More. The detail header and all six settings overview entry paths contained no header Back button; remote Back restored the previous settings-tile focus.

The existing checks cover saved English/Chinese preference, login/password recreation, actual-library Home headings, image-only card focus, scrolling and return focus, source selection/expiry/fresh negotiation, folders/seasons, server filters and pagination, image-cache isolation and request sharing, search, dialogs, intro/outro skipping, manual and automatic episodes, repeated seek keys, MP4/MKV/HEVC playback, audio/subtitles and actual SRT cues, lifecycle, 4/8-connection playback, Range fallback, 403/410/503 handling and decoder recovery. No failure was reported in these runs.

## Signed APK and screenshots

The final signed APK was installed separately and checked with native ADB input. English login, Home/category navigation, inline version selection with fresh VP8 negotiation, remote OK resume/pause (confirmed by real playback reports), menu/Back layers, settings overview/editor and search all passed. The captured focus ring measured x=959.5 in pixel indices, within half a pixel of screen center. Eight English screenshots are included with this release. The helper was resumed after correcting its TV Material label-child lookup and accessibility-idle/auto-hide assumptions; no APK code changes were required. Screenshots use mock-server media and metadata, including a generated VP8/Vorbis test video; application artwork sources are unchanged. See `docs/screenshots/1.6.1/` for Home, category, details, player, settings overview/editor, search and login.

## Scope and limits

This release changes Compose visuals and focus routing. Media3 buffering/decoding, FFmpeg libraries, API queries, server-library structure, cache policies and playback-address validation retain their existing implementation. Cache/heap strategies and previous measurements are documented in [1.6.0 validation](https://github.com/dakaishizhong/BronyaTV/blob/v1.6.0/releases/v1.6.0/VALIDATION.md); those performance measurements were not repeated for this visual update.

No physical TV or production Emby account was available. Physical-remote behavior/performance, vendor TrueHD/MLP/DTS/DTS-HD decoders, HDR/Dolby Vision, HDMI output, real DTS-HD MA media, and installed external-player compatibility were not verified. PCM fallback does not retain TrueHD Atmos/DTS:X object metadata.
