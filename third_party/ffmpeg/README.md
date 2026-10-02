# FFmpeg corresponding source

`ffmpeg-6.1.4.tar.xz` is the unmodified source archive downloaded from
[ffmpeg.org](https://ffmpeg.org/releases/ffmpeg-6.1.4.tar.xz).
`SHA256SUMS` records the archive hash. Source headers and license files in that
archive identify the applicable licenses; the enabled audio decoder build is
LGPL-2.1-or-later, with GPL and nonfree components disabled.

The complete source archive is included alongside BronyaTV source so the native
libraries can be rebuilt and relinked. The build flags, NDK version and JNI
wrapper are retained in [the build script](../../scripts/build_audio_decoders.sh)
and [decoder-ffmpeg](../../decoder-ffmpeg/README.md). No external codec libraries
are linked. FFmpeg is statically linked into `libffmpegJNI.so` for each ABI.

Rebuild the native libraries with `bash scripts/build_audio_decoders.sh`, then
build BronyaTV normally. Local development builds may use your own signing key.
See [development instructions](../../docs/DEVELOPMENT.md).
