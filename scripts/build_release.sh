#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
python3 scripts/configure_resources.py | tee logs/resource-config.log
./gradlew testDebugUnitTest lintDebug assembleRelease lintRelease --console=plain 2>&1 | tee logs/release-build.log
BRONYA_VERSION=$("$ANDROID_HOME/build-tools/36.0.0/aapt" dump badging app/build/outputs/apk/release/app-release.apk | python3 -c "import sys,re; print(re.search(r\"versionName='([^']+)'\", sys.stdin.read()).group(1))")
BRONYA_APK="artifacts/BronyaTV-${BRONYA_VERSION}-release.apk"
cp app/build/outputs/apk/release/app-release.apk "$BRONYA_APK"
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs "$BRONYA_APK" > logs/apk-signature.log
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c 4 "$BRONYA_APK" > logs/apk-alignment.log
(cd artifacts && sha256sum "BronyaTV-${BRONYA_VERSION}-release.apk" > "BronyaTV-${BRONYA_VERSION}-SHA256SUMS")
