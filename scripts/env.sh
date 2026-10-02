#!/usr/bin/env bash
export EMBER_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ANDROID_HOME="$EMBER_ROOT/tools/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$EMBER_ROOT/tools/gradle-cache"
export TMPDIR="$EMBER_ROOT/tools/tmp"
mkdir -p "$TMPDIR"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
