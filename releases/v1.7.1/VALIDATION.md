# BronyaTV 1.7.1 validation

Baseline: BronyaTV 1.7.0, commit `f62825b`. This release contains the document-based Cinema UI and the R4 native-remote fixes. Application ID remains `tv.ember.client`; version is 1.7.1 / versionCode 15, minimum API 23.

## Build and package

```bash
./gradlew testDebugUnitTest testReleaseUnitTest lintDebug lintRelease \
  assembleRelease assembleDebugAndroidTest --console=plain
```

- 98 Debug and 98 Release JVM tests passed, with no failures, errors or skips.
- Both lint reports passed with zero errors and 89 warnings each.
- Release APK and device-test APK builds passed. R8 minification and resource shrinking are enabled; the APK is not debuggable.
- v1/v2 APK signatures and 16 KiB-aware ZIP alignment passed.
- All eight native libraries across arm64-v8a, armeabi-v7a, x86 and x86_64 are byte-for-byte identical to 1.7.0.
- The reference JSON and generated test videos are not packaged in the APK.
- APK: 7,238,799 bytes. SHA-256: `7471c3aedf32e2dcacfc730baa0de2dcfa99779738cabf45b4383a8e0e96e9af`.

The new release signing certificate SHA-256 is `7754dee23fe2711aa94c7a27b5e38df1a4c66f362469a69ce4d5dd1758eee212`. It differs from 1.7.0, as authorized by the user. Existing installations must be uninstalled before installing 1.7.1. Private signing files are excluded from Git and Release uploads; a separate private backup is supplied to the owner.

## Native remote checks

The final R4 production changes, before the 1.7.1 version increment, passed four native Android key-event tests at 1280 × 720: Home/sidebar/details (20 keys), sign-in (18), player (45), settings (16). All 99 operations passed. The sign-in path also passed at 1920 × 1080 with 18 operations, including Back out of editing immediately followed by Left to the user card.

Navigation in those tests uses `Instrumentation.sendKeyDownUpSync`, followed by actual focus assertions. No programmatic focus request, semantic click or scroll substitutes for navigation. See [REMOTE-DPAD-VALIDATION.md](../../docs/REMOTE-DPAD-VALIDATION.md) for paths, method and exact logs. The evidence archive preserves eight actual screenshots, five key traces and the final device/build logs.

Player geometry previously passed at 1080p English and 720p Chinese: horizontally centered Play/Pause, 44/56/44 base dp circles, 20 dp gaps, half-size icons and independent right-side parameter buttons. Those results and measured pixel bounds are documented in [CINEMA-UI-VALIDATION.md](../../docs/CINEMA-UI-VALIDATION.md).

## Signed Release smoke check

The exact signed APK was installed on the dedicated API 23 software emulator. Package inspection confirmed versionCode 15, versionName 1.7.1 and no DEBUGGABLE flag. Actual credential-form login, Home-to-details navigation, selection of the VP8 fixture and video playback were exercised using the document-based Emby fixture. The media-pause key changed actual playback telemetry to paused. Login, Home, details and playback screenshots were captured.

An additional accessibility-based helper did not complete its full run. Its attempts encountered controls auto-hiding during slow hierarchy reads, a short clip reaching EOF, and a fixture assertion tied to the first title while another reference title was selected. These attempts are not counted as passing full Release remote regression. The complete 99-key focus regression described above was run on R4 Debug before the version/signing change. No application code was changed in response to helper setup failures, and the unvalidated helper is excluded from the published source.

## Scope

These are software-emulator checks. Physical TV remotes, production Emby, vendor HDR/audio output and sustained high-bitrate hardware playback are not verified by this release task. Demo video is a synthetic clip generated from the reference image, not a copy of the named movie.

Historical packages and screenshots remain unchanged. Current screenshots and resource conventions are linked from both READMEs.
