# BronyaTV 1.6.0 validation

Build environment: JDK 17, Android SDK 36 / Build Tools 36.0.0, Gradle 8.13, Kotlin 2.3.21, Compose BOM 2026.03 and TV Material 1.1.0. Media3 1.11.1 and all four existing native audio ABIs remain intact.

## Build and regression checks

- 91 Debug and 91 Release JVM tests passed with zero failures, errors or skips. Includes ordered ranges, precise EOF, shared connection budgets, seek load control, heap policies, audio fallback, API filter/paging scope, search compatibility, metadata sanitization, cache isolation/eviction, request sharing/cancellation and transient progress expiry.
- `lintDebug` and `lintRelease` passed with zero errors and 86 nonblocking warnings. Debug, test and Release APK builds passed; R8 and resource shrinking remain enabled.
- Version 1.6.0 / versionCode 12, minimum API 23. Release APK signature and 16 KiB-aware zip alignment verified. The signing certificate matches 1.4.0 onward. Native decoder libraries retain their existing checksums and 16 KiB ELF alignment.
- `git diff --check` passed before publication.

## Device regression scope

API 23 x86 software emulator, native 1920×1080 at 320 dpi. Across the full regression run and corrective reruns, 59 distinct device checks passed. One opt-in test against an official Emby instance was skipped because no account/server was configured. The initial runs identified Home DPAD expectation and asynchronous detail-return test issues; the final targeted rerun passed both. This count describes the union of the runs, not a single all-green suite invocation. A final four-case rerun also passed after the input-field DPAD and typography fixes: login, freshly negotiated version playback, settings navigation and Home geometry/focus.

Checks cover login and password recreation, saved English/Chinese preference, actual-library Home headings (including UserView DTOs), five complete 2.6:1 cards and image-only focus, horizontal scrolling and return focus, movie/series navigation through folders/seasons, server filters and subsequent pages, detail version selection/expiry and fresh negotiation, selected-version focus recreation, all settings editors and dialogs, search, Menu/Back layers, playback lifecycle, seeking, tracks, actual SRT cues, episodes and continuous playback. Player controls were exercised using VP8/Vorbis for the interface run; the existing MP4/MKV and HEVC integration checks also passed on the compatible emulator CPU configuration.

Real local TrueHD and DTS fixtures produced FFmpeg PCM output, rendered buffers and advancing position when device decoder lookup was forced empty, and continued after a 6-second seek. Native MLP decoder availability was checked. These checks do not establish vendor hardware decoding or DTS-HD output.

The final signed APK was installed separately. Its English login/Home/category flow, inline VP8 selection and fresh PlaybackInfo request, Compose player controls, actual Menu/Back layers, settings and search were checked using native ADB input. Screenshots of all seven pages are saved under `docs/screenshots/1.6.0/` and referenced from both READMEs. The screenshot helper was resumed after correcting its fixed-Back-count assumption when controls had already auto-hidden; the final playback-to-details return check passed.

## Cache and performance observations

- The high-bitrate allocator selected 128 MiB with 153/384 MiB live Java heap. Initial allocation added one GC; the measured release/reuse pass added zero GC cycles (156 → 156). Normal and large heap classes both reported 384 MiB, so largeHeap remains disabled.
- Sixteen simultaneous image observers shared one download. A small fixture poster decoded to 63×94 pixels for a 160×90 target; a changed ImageTag and a different user each issued a new request. Peak image concurrency was three. Playback reduced the image memory budget to 3 MiB; clearing images returned disk usage to zero while metadata remained available.
- Images use sampled decoding, bounded memory/disk LRU and lifecycle cancellation. Stopped pages release bitmap references. Metadata uses at most four shared requests; Home retains at most eight response frames. Lists are lazy with stable keys, and refresh captures its focus/scroll anchor before network work. Network, disk and JSON processing run off the main thread.
- These are controlled emulator/fixture observations, not measurements of a physical TV or a guarantee for every server and network.

## Upstream capabilities and limits

Library headings and their order come from Emby [User Views](https://dev.emby.media/reference/RestAPI/UserViewsService/getUsersByUseridViews.html); scoped rows use [Latest with ParentId](https://dev.emby.media/reference/RestAPI/UserLibraryService/getUsersByUseridItemsLatest.html). They are actual media libraries, not guessed regional groupings or locally calculated largest-library rankings.

Category requests use documented [Items](https://dev.emby.media/reference/RestAPI/ItemsService/getUsersByUseridItems.html) parameters: ParentId, IncludeItemTypes, Genres, Years, IsPlayed, Filters, SortBy, SortOrder, StartIndex and Limit. Genre/year choices come from scoped [Genres](https://dev.emby.media/reference/RestAPI/GenresService/getGenres.html) and [Years](https://dev.emby.media/reference/RestAPI/TagService/getYears.html) responses, including subsequent facet pages. Unsupported facet endpoints hide their controls. Unfiltered folders keep the original nonrecursive hierarchy. Local HTTP fixtures verify the contract; a production Emby instance was not available.

Physical-TV performance and remote hardware, HDR/Dolby Vision, HDMI output, vendor TrueHD/MLP/DTS/DTS-HD decoding, real DTS-HD MA fixtures and installed external-player compatibility were not verified. PCM fallback does not preserve TrueHD Atmos/DTS:X object metadata.
