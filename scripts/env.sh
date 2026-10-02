#!/usr/bin/env bash
export BRONYA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ANDROID_HOME="$BRONYA_ROOT/tools/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$BRONYA_ROOT/tools/gradle-cache"
export TMPDIR="$BRONYA_ROOT/tools/tmp"
mkdir -p "$TMPDIR"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
