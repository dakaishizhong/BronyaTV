#!/usr/bin/env bash
# Real loopback HTTP/1.1, independent paced consumption and SHA-256 validation.
set -euo pipefail
verification_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$verification_root"
source scripts/env.sh
if [[ "${BRONYA_CLOUD_ENV:-0}" == 1 ]]; then
  source tools/cloud-env.sh
fi
verification_mode="${1:-normal}"
unset BRONYA_PIPELINE_MATRIX BRONYA_PIPELINE_SOURCE_MIB BRONYA_PIPELINE_CASES BRONYA_PIPELINE_BASELINE
verification_tasks=(:app:testDebugUnitTest)
case "$verification_mode" in
  normal)
    verification_tasks+=(:app:assembleDebug :app:compileDebugAndroidTestKotlin :app:lintDebug)
    ;;
  matrix)
    export BRONYA_PIPELINE_MATRIX=1
    verification_tasks+=(--tests tv.ember.client.RangePipelineBenchmarkTest)
    ;;
  soak)
    export BRONYA_PIPELINE_SOURCE_MIB=256
    verification_tasks+=(--tests tv.ember.client.RangePipelineBenchmarkTest
      --tests tv.ember.client.ProductionDiskRangeTest.productionDiskCacheKeepsFiftyEightyAndHundredMbpsConsumersFed)
    ;;
  *)
    echo "Usage: $0 [normal|matrix|soak]" >&2
    exit 2
    ;;
esac
mkdir -p build/range-validation
verification_output="$(mktemp -d "$verification_root/build/range-validation/$verification_mode.XXXXXX")"
export BRONYA_PIPELINE_REPORT="$verification_output/results.jsonl"
echo "Range pipeline results: $BRONYA_PIPELINE_REPORT"
./gradlew "${verification_tasks[@]}" --console=plain
