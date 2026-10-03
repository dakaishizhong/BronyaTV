# BronyaTV 1.5.0

Improves high-bitrate original-file playback and starts the Compose for TV migration with the settings dashboard.

- Limits the short 500ms/reserve recovery behavior to seeks. Normal playback and rebuffering use Media3 DefaultLoadControl and the configured BufferPolicy.
- Foreground cache reads bypass locked or unfinished cache spans immediately. Playback and disk read-ahead share one total 2/4/8 connection budget; foreground requests can cancel background transfers to obtain a slot.
- Streams each range as bytes arrive, preserves ordered output and file-version checks, caps production chunks at 512 KiB, and reuses bounded buffers.
- Raises high-bitrate automatic memory targets to up to 128 MiB with explicit heap headroom and low-memory reduction. Keeps largeHeap disabled after evaluating heap classes and allocator reuse.
- Prefers device MediaCodec/vendor TrueHD, MLP, DTS and DTS-HD decoding to PCM. Unsupported devices and runtime device decoder failures use FFmpeg. Initializes the FFmpeg lossless channel layout correctly and preserves it when recreating TrueHD after a seek.
- Diagnostics show actual decoder names, hardware/software classifications, observed AudioTrack output, and shared active/peak connection counts. Video keeps its MediaCodec-first behavior.
- Migrates the six-panel settings dashboard to Kotlin + Compose for TV / TV Material, with DPAD navigation, sidebar interoperability and restored focus. Existing category editors and player implementation remain in place.
- Preserves English / Simplified Chinese preferences and English documentation screenshots.

PCM output does not retain TrueHD Atmos or DTS:X object metadata. Other audio formats keep their existing output path. Real vendor lossless hardware decoding still requires validation on a compatible physical TV.

Uses the existing 1.4.0 signing key. Supports Android 6.0 or later; installs over 1.4.x. Versions 1.3.0 and earlier require uninstalling first.

Validation: [VALIDATION.md](https://github.com/dakaishizhong/BronyaTV/blob/v1.5.0/releases/v1.5.0/VALIDATION.md).
