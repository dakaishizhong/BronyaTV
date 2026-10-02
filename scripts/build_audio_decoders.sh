#!/usr/bin/env bash
# Rebuild the bundled TrueHD/MLP/DTS audio decoders from the corresponding source.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
BRONYA_NDK_VERSION=28.2.13676358
BRONYA_NDK="$ANDROID_HOME/ndk/$BRONYA_NDK_VERSION"
BRONYA_TOOLCHAIN="$BRONYA_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [[ ! -x "$BRONYA_TOOLCHAIN/clang" ]]; then
    sdkmanager "ndk;$BRONYA_NDK_VERSION"
fi
(cd third_party/ffmpeg && sha256sum --check SHA256SUMS)
BRONYA_NATIVE_WORK="$BRONYA_ROOT/tools/native-audio"
mkdir -p "$BRONYA_NATIVE_WORK"
tar -xf third_party/ffmpeg/ffmpeg-6.1.4.tar.xz -C "$BRONYA_NATIVE_WORK"
BRONYA_FFMPEG_SRC="$BRONYA_NATIVE_WORK/ffmpeg-6.1.4"
for BRONYA_ABI in armeabi-v7a arm64-v8a x86 x86_64; do
    BRONYA_ARCH_FLAGS=()
    case "$BRONYA_ABI" in
        armeabi-v7a)
            BRONYA_TARGET=armv7a-linux-androideabi23; BRONYA_ARCH=arm
            BRONYA_ARCH_FLAGS=(--cpu=armv7-a '--extra-cflags=-march=armv7-a -mfloat-abi=softfp') ;;
        arm64-v8a) BRONYA_TARGET=aarch64-linux-android23; BRONYA_ARCH=aarch64 ;;
        x86) BRONYA_TARGET=i686-linux-android23; BRONYA_ARCH=x86; BRONYA_ARCH_FLAGS=(--disable-asm) ;;
        x86_64) BRONYA_TARGET=x86_64-linux-android23; BRONYA_ARCH=x86_64; BRONYA_ARCH_FLAGS=(--disable-asm) ;;
    esac
    BRONYA_BUILD="$BRONYA_NATIVE_WORK/build-$BRONYA_ABI"
    BRONYA_OUTPUT="$BRONYA_ROOT/decoder-ffmpeg/src/main/jniLibs/$BRONYA_ABI"
    mkdir -p "$BRONYA_BUILD" "$BRONYA_OUTPUT"
    (
        cd "$BRONYA_BUILD"
        "$BRONYA_FFMPEG_SRC/configure" --target-os=android --arch="$BRONYA_ARCH" \
            --enable-cross-compile --cc="$BRONYA_TOOLCHAIN/$BRONYA_TARGET-clang" \
            --cxx="$BRONYA_TOOLCHAIN/$BRONYA_TARGET-clang++" \
            --ar="$BRONYA_TOOLCHAIN/llvm-ar" --nm="$BRONYA_TOOLCHAIN/llvm-nm" \
            --ranlib="$BRONYA_TOOLCHAIN/llvm-ranlib" --strip="$BRONYA_TOOLCHAIN/llvm-strip" \
            --disable-autodetect --disable-everything --disable-programs --disable-doc \
            --disable-debug --disable-network --disable-shared --enable-static --enable-pic \
            --disable-avdevice --disable-avformat --disable-avfilter --disable-postproc \
            --disable-swscale --enable-swresample --enable-decoder=truehd --enable-decoder=mlp --enable-decoder=dca \
            "${BRONYA_ARCH_FLAGS[@]}"
        make -j"${BRONYA_NATIVE_JOBS:-2}"
        "$BRONYA_TOOLCHAIN/$BRONYA_TARGET-clang++" -std=c++11 -O2 -fPIC \
            -fvisibility=hidden -ffunction-sections -fdata-sections -shared -static-libstdc++ \
            -Wl,--no-undefined -Wl,--gc-sections -Wl,-Bsymbolic -Wl,-soname,libffmpegJNI.so \
            -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
            -I"$BRONYA_FFMPEG_SRC" -I"$BRONYA_BUILD" \
            "$BRONYA_ROOT/decoder-ffmpeg/src/main/jni/ffmpeg_jni.cc" \
            libswresample/libswresample.a libavcodec/libavcodec.a libavutil/libavutil.a \
            -llog -landroid -lm -latomic -o "$BRONYA_OUTPUT/libffmpegJNI.so"
        "$BRONYA_TOOLCHAIN/llvm-strip" --strip-unneeded "$BRONYA_OUTPUT/libffmpegJNI.so"
        chmod 644 "$BRONYA_OUTPUT/libffmpegJNI.so"
    )
done
(cd decoder-ffmpeg/src/main/jniLibs && sha256sum */libffmpegJNI.so > SHA256SUMS)
