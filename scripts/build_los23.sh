#!/usr/bin/env bash
# Path-independent metroid build entry point. Do not run this while another
# Soong/Kati/Ninja process is using the same OUT_DIR.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOP="${ANDROID_BUILD_TOP:-$(cd "$SCRIPT_DIR/../../../.." && pwd)}"
LUNCH_TARGET="${METROID_LUNCH_TARGET:-lineage_metroid-bp2a-userdebug}"
JOBS="${METROID_JOBS:-$(nproc)}"

cd "$TOP"
# shellcheck source=/dev/null
source build/envsetup.sh >/dev/null
lunch "$LUNCH_TARGET" >/dev/null
exec m -j"$JOBS" "$@"
