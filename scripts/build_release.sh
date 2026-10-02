#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
python3 scripts/configure_resources.py | tee logs/resource-config.log
./gradlew testDebugUnitTest lintDebug assembleRelease lintRelease --console=plain 2>&1 | tee logs/release-build.log
EMBER_VERSION=$("$ANDROID_HOME/build-tools/36.0.0/aapt" dump badging app/build/outputs/apk/release/app-release.apk | python3 -c "import sys,re; print(re.search(r\"versionName='([^']+)'\", sys.stdin.read()).group(1))")
EMBER_APK="artifacts/EmberTV-${EMBER_VERSION}-release.apk"
cp app/build/outputs/apk/release/app-release.apk "$EMBER_APK"
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs "$EMBER_APK" > logs/apk-signature.log
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c 4 "$EMBER_APK" > logs/apk-alignment.log
(cd artifacts && sha256sum "EmberTV-${EMBER_VERSION}-release.apk" > "EmberTV-${EMBER_VERSION}-SHA256SUMS")
