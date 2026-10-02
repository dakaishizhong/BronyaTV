#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p tools logs artifacts
if ! command -v java >/dev/null; then
    sudo apt-get update
    if apt-cache show openjdk-21-jdk-headless >/dev/null 2>&1; then
        sudo apt-get install -y openjdk-21-jdk-headless curl unzip python3
    else
        sudo apt-get install -y openjdk-17-jdk-headless curl unzip python3
    fi
fi
source scripts/env.sh
if [[ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
    curl -fL --retry 3 https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip -o tools/cmdline-tools.zip
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    unzip -q tools/cmdline-tools.zip -d "$ANDROID_HOME/cmdline-tools"
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
sdkmanager --sdk_root="$ANDROID_HOME" --licenses <<'EOF'
y
y
y
y
y
y
y
y
y
EOF
sdkmanager --sdk_root="$ANDROID_HOME" 'platforms;android-36' 'build-tools;36.0.0' 'platform-tools'
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
python3 scripts/configure_resources.py | tee logs/resource-config.log
python3 scripts/create_signing_key.py
chmod +x gradlew
./gradlew --version
