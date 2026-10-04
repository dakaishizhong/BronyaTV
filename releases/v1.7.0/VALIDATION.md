# BronyaTV 1.7.0 validation

## Build and package

JDK 17, Gradle 8.13, Android SDK / Build Tools 36, Kotlin 2.3.21, Compose BOM 2026.03 and TV Material 1.1.0. Media3 remains 1.11.1.

- 96 Debug and 96 Release JVM tests passed with no failures, errors or skips.
- `lintDebug` and `lintRelease` passed: zero errors and 86 existing nonblocking warnings per report. Debug, instrumentation and Release APK builds passed; R8/resource shrinking remain enabled.
- APK version 1.7.0 / versionCode 14, minimum API 23. Signature and 16 KiB-aware zip alignment verified. The signing certificate matches 1.6.1; all eight native libraries across four ABIs are byte-for-byte identical to 1.6.1.
- APK: 7,206,599 bytes. SHA-256: `88d5650a6e9598e8761f22dbd0cd1f91ea3b8fecd7ccbf210e659e97f7b09fd9`.

## Layout, remote and search regression

Dedicated API 23 x86 software emulator and local HTTP Emby fixtures:

- 51 distinct device checks passed in one complete run. After the final Home spacing adjustment, 13 UI/search cases passed again at native 1920×1080 / 320 dpi, and four selected layout/settings cases passed at 1280×720 / 240 dpi.
- Seven equal-size playback controls, three mirrored pairs, and Play/Pause at the viewport center. At 1080p the measured diameter was 84 px and center x=960. Focus did not resize or move controls. DPAD follows Sources → Subtitles → Rewind → Play/Pause → Forward → Diagnostics → More; Up/Down traverse the timeline and return to Play/Pause. OK changed the real Media3 pause state and timeline Right advanced playback.
- Home, category, search and related-title cards use 16:9 artwork. Focus encloses only the image and causes no layout shift. Final Home checks assert that both rows' title and auxiliary-text bounds are fully visible at both resolutions, including all five lower-row cards.
- Empty search makes no item/facet request. Typing coalesces at 300 ms; explicit submission is immediate and does not duplicate the current request. Tests cover full-width characters, Chinese, whitespace, accents, history, clearing, server media-type scope, and an obsolete two-second response arriving after a newer query.
- The existing regression covers English/Chinese preference, login recreation, actual-library Home sections, page/focus/scroll restoration, selected versions and fresh address negotiation, folders/seasons, upstream filters/sort/pagination, cache isolation, dialogs, intro/outro skipping, episodes, repeated seeking, MP4/MKV/HEVC playback, audio/subtitles, lifecycle, 4/8-connection playback, Range fallback, HTTP failure handling and decoder recovery.

## Signed APK and screenshots

The final signed APK was installed separately and exercised with native ADB input. English login, Home/category navigation, inline version selection, fresh VP8 negotiation, real playback pause/resume reports, menu/Back layers, settings overview/editor and live search passed. The screenshot focus ring measured x=959.5 in pixel indices, within half a pixel of screen center. No volume shortcut or top-left Back button was present.

Eight English screenshots are in [docs/screenshots/1.7.0](../../docs/screenshots/1.7.0). The smoke helper completed through search, then its last caption assertion was corrected: a resumed search result shows remaining time rather than the production year. The search check/capture was resumed on the same final APK; no application change was necessary.

Artwork is from six distinct real public-domain classic-film images, with exact Commons license/source records and SHA-256 hashes in [tests/media](../../tests/media/README.md). The screenshot catalog contains only these six distinct films. A separate synthetic 45-entry catalog exercises paging and filters. Test videos are generated from the stills with original tones/subtitles, rather than copies of the films; all generated media is excluded from the APK.

## Requests, cache and observed timings

- Search uses the official Emby ItemsService `SearchTerm`; optional `SortBy`/`SortOrder` are omitted until explicitly chosen. Genre/year facets load on demand, and server filters and pagination remain intact. Support was checked against the upstream API documentation and local contract fixtures, not a production server.
- Returns to a recently loaded page reuse its data for 15 seconds and merge current playback progress. Refresh invalidates/reloads data and facets. Evicted page payloads reload on return. Card requests omit descriptions/cast; details still request complete metadata.
- Identical image URLs share encoded disk data across display sizes; decoded entries retain their size-specific keys. Image loads remain capped at three and decoded memory at 2–12 MiB according to device budget, reduced to 3 MiB during playback. Image and metadata caches remain separate and isolated by server/user; transient playback addresses are not persisted.
- Sixteen simultaneous identical image observers produced one image network request. Loading the same URL at another display size did not redownload it or increase disk usage. Peak active image loads were three; the playback decoded-memory budget was 3,145,728 bytes.
- Observed replacement-search completion was 1,235 ms, including the 300 ms debounce and emulator/test synchronization. Three recent Home returns took 1,190 / 1,137 / 1,746 ms; the library-request counter stayed at three throughout those returns. These are individual measurements on a software-emulated device, not frame-rate benchmarks or a physical-TV performance comparison.

## Scope and limits

This release adjusts Compose layout/focus, search/request scheduling, page reuse, encoded-image sharing and test artwork. Media3 buffering/decoding, range-reader concurrency, FFmpeg binaries, playback-address validation and external-player support retain the existing core implementation.

No physical TV or production Emby account was available. Physical-remote performance, sustained high-bitrate buffering, vendor TrueHD/MLP/DTS/DTS-HD decoders, HDR/Dolby Vision, HDMI output, real DTS-HD MA media and installed external-player compatibility were not verified. There is no claim of a measured frame-rate improvement or proof that every device is leak-free. PCM fallback does not retain TrueHD Atmos/DTS:X object metadata.

## 简体中文

Debug / Release 各 96 个单元测试通过，lint 零错误，Release 构建、签名和对齐检查通过。51 个设备测试通过，最终首页间距调整后又完成 1080p 的 13 项回归和 720p 的 4 项回归。另安装最终签名 APK，检查登录、首页、分类、版本选择、真实播放暂停/恢复、菜单返回、设置和搜索，并保存八张英文截图。

播放/暂停与两侧各三个按钮同尺寸、居中对称；卡片统一 16:9，首屏两行文字完整。搜索合并输入、取消过期请求，筛选分页由服务器执行；页面短时复用并同步进度，图片请求合并且有内存/并发上限。六部公有领域电影的真实剧照附原始来源、版权到期说明及校验值，测试资源不进入 APK。

性能数字仅来自软件模拟器，不代表真机帧率。生产 Emby、真机遥控器、高码率持续播放、厂商音频解码器及 HDMI/HDR 输出尚未实测。
