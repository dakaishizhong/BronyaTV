# BronyaTV 1.4.1 validation

Build: Linux x86_64, JDK 17, Android SDK 36 / Build Tools 36.0.0, Gradle 8.13.

- `testDebugUnitTest`: 74 tests passed, zero failures or errors.
- `lintDebug`, `lintRelease`: passed with zero errors.
- `assembleDebug`, `assembleDebugAndroidTest`, `assembleRelease`: passed.
- Signed release: version 1.4.1 / versionCode 10, minimum Android 6.0 (API 23), R8 and resource shrinking enabled.
- `apksigner verify` and `zipalign -c 4`: passed. The signing certificate matches 1.4.0.
- All four packaged native decoder libraries are byte-identical to 1.4.0.

On the API 23 emulator at 1280×720, `VisualNavigationDeviceTest` passed all seven tests. They cover saved English/Chinese switching, sidebar DPAD navigation and Favorites/Back, playback and subtitle controls, an unreachable server, inline search and recent queries, details/settings actions, and Home card layout/focus/menu navigation.

The final Home check additionally verifies that five complete images fit, image aspect ratios are within 0.06 of 2.6:1, navigation labels are not ellipsized, card backgrounds stay transparent, focus scales only artwork, enlarged artwork remains fully visible, and movement between already visible rows preserves their settled positions. The test waits for stable geometry before comparing positions.

The signed release was installed and signed in with the local Emby fixture on a physical emulator display configured to 1920×1080 at 320 dpi. All five Recently added images and their titles/metadata fit. DPAD Down followed by four Right presses reaches the fifth card with an artwork-only focus outline. The Menu key opens Sort and Refresh; Back dismisses it. The [1080p signed-release Home capture](../../docs/screenshots/1.4.1/home-1080p.png) is also included.

All five README screenshots use the running English interface and a local mock Emby server. The fixture has only one resume item; the client displays that single item without duplicating it to fill the row. Production browsing and metadata continue to use the existing server API. Server artwork was not replaced.

Playback control checks use a real VP8/Vorbis WebM. The software emulator has a system H.264 decoder instruction issue, as documented for 1.4.0. Physical-TV performance, HDR/Dolby Vision and HDMI audio output were not validated in this environment. Emulator app data was cleared after its old keystore entry became invalid during an environment restart; clean sign-in then succeeded. Session encryption code is unchanged.
