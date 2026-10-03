# BronyaTV audio decoders

The Java audio renderer/decoder and JNI wrapper are vendored from [AndroidX Media3 1.11.1](https://github.com/androidx/media/tree/1.11.1/libraries/decoder_ffmpeg),
under Apache-2.0. BronyaTV adds an explicit `audio/mlp` MIME mapping and initializes TrueHD/MLP with the source channel count and sample rate. TrueHD context recreation preserves those values after seeks. The experimental video renderer is intentionally omitted.
`build.gradle.kts` integrates the audio sources with BronyaTV's Media3 dependencies;
`consumer-rules.pro` preserves native method names and JNI callback descriptors.

The bundled `jniLibs` are built from the corresponding FFmpeg 6.1.4 source in
`third_party/ffmpeg/`, with only the TrueHD, MLP and DTS (`dca`) audio decoders enabled.
FFmpeg's video decoders, network protocols, encoders, GPL and nonfree components
are disabled. Other audio formats use the existing platform audio renderer.
HEVC, HDR and Dolby Vision continue to use the platform video renderer.

Rebuild on Linux x86_64 with JDK 17 and the Android SDK:

```bash
bash scripts/build_audio_decoders.sh
```

The script installs NDK r28c if needed, verifies the FFmpeg source hash, and builds
API 23 libraries for armeabi-v7a, arm64-v8a, x86 and x86_64. ELF load segments use
16 KiB alignment. Builds are staged in ignored `tools/native-audio/`; the stripped
libraries and their hashes are retained under `src/main/jniLibs/`.

TrueHD/MLP and DTS/DTS-HD prefer the device MediaCodec/vendor decoder and PCM output. Unsupported formats or runtime device decoder failures fall back to this FFmpeg audio renderer. This
compatibility path does not preserve TrueHD Atmos or DTS:X object metadata and
does not provide bitstream passthrough for these formats. It does not change E-AC-3/JOC handling. The official JNI wrapper
recreates the TrueHD decoder context when seeking instead of only flushing it.

See [Apache-2.0](LICENSE), [FFmpeg license and source](../third_party/ffmpeg/README.md),
and the app's packaged `assets/licenses/` notices.
