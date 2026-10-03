# BronyaTV 1.5.0 validation

Build environment: JDK 17, Android SDK 36 / Build Tools 36.0.0, Gradle 8.13, Kotlin 2.3.21. Native audio: NDK r28c and the existing FFmpeg 6.1.4 source.

- 82 JVM tests passed with zero failures/errors. Covers DefaultLoadControl parity outside seeks, bounded seek recovery, heap headroom, shared 2/4/8 request limits and foreground preemption, ordered streaming, HTTP fallback, changed-file rejection, cancellation and lossless fallback policy.
- A paced 50 Mbps consumer received 48 MiB with matching SHA-256, four active connections, a 2.5 MiB queue peak and no multi-second read stalls. The longest measured read was below 1 ms in this controlled fixture. The separate per-connection-throttled sample showed about 3.7–3.9× the single-connection throughput. These are reproducible local network tests, not a physical-TV bandwidth guarantee.
- `lintDebug` and `lintRelease` passed with zero errors; `assembleDebug`, `assembleDebugAndroidTest` and `assembleRelease` passed. R8 and resource shrinking remain enabled.
- Release version 1.5.0 / versionCode 11, minimum API 23. `apksigner verify` and 16 KiB-aware `zipalign` passed. The signing certificate matches 1.4.0/1.4.1.
- Rebuilt all four native audio ABIs. Checksums are recorded in the decoder module; ELF LOAD segments retain 16 KiB alignment.

API 23 emulator checks used a native 1920×1080 display at 320 dpi. Fourteen navigation/cache/memory scenarios passed, followed by successful TrueHD/DTS fallback and playback checks after the native fix. Together they cover all 15 device scenarios: Compose DPAD movement across both rows, View sidebar interoperability, opening existing category editors and restoring focus, English/Chinese persistence, Home cards/menu, search, details, unreachable-server retry, playback/subtitles, seek load control, memory allocation, cache write/read/seek/clear and nonblocking foreground access to locked cache holes.

The final signed release APK was installed separately and checked with English login, the Home/Menu flow, DPAD traversal across both Compose rows and the View sidebar, opening the existing network/cache editor, and Back with restored focus. The Home and Settings screenshots were captured from this signed package.

Real local TrueHD and DTS audio files selected FFmpeg when the device codec selector reported no decoder. Both produced observed AudioTrack PCM output, advancing playback and rendered audio buffers; both resumed after a 6-second seek. MLP native decoder availability was checked. DTS-HD retains the bundled `dca` fallback; a real DTS-HD fixture was not available for this run. Actual decoder names/software classifications remain visible in diagnostics. Vendor lossless hardware decoding and runtime vendor failures require a compatible physical TV for final validation.

The high-bitrate allocator used 128 MiB with about 142–148 MiB total live Java heap on a 384 MiB heap. Releasing/reusing the allocator blocks added zero GC cycles in the measured reuse pass. Both normal and large heap classes were 384 MiB; largeHeap stays disabled. Low-memory/occupied-heap cases are covered by policy tests.

README screenshots use English and a local mock Emby server with real playable fixtures. Production data and metadata continue to come from the existing Emby API; sparse rows are not duplicated. Backdrop assets were not replaced.

Physical-TV performance, HDR/Dolby Vision, HDMI output and vendor TrueHD/DTS hardware decoding were not tested in this software emulator. PCM output does not preserve TrueHD Atmos/DTS:X object metadata.
