#!/usr/bin/env bash
# Cross-compile set_mixer_ctl for arm64-v8a (Android 9 / API 28).
# Run from any machine with the Android NDK installed.
#
# Usage:
#   NDK=/path/to/ndk ./build_arm64.sh
#   # or set NDK env var before calling
#
# Output: ../../../app/src/main/assets/set_mixer_ctl  (arm64 static ELF)
# The app unpacks it to /data/data/<pkg>/files/set_mixer_ctl at runtime.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT="$REPO_ROOT/app/src/main/assets/set_mixer_ctl"

# --- locate NDK toolchain ---
NDK="${NDK:-}"
if [ -z "$NDK" ]; then
    # common locations
    for candidate in \
        "$HOME/android-sdk/ndk-bundle" \
        "$HOME/android-sdk/ndk/"* \
        "$HOME/Android/Sdk/ndk/"* \
        "/opt/android-ndk"* \
        "/opt/android-sdk/ndk/"*; do
        if [ -d "$candidate" ]; then NDK="$candidate"; break; fi
    done
fi
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    echo "ERROR: Android NDK not found. Set NDK=/path/to/ndk"
    exit 1
fi

HOST_TAG="linux-x86_64"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
CC="$TOOLCHAIN/bin/aarch64-linux-android28-clang"

if [ ! -x "$CC" ]; then
    echo "ERROR: compiler not found: $CC"
    echo "NDK=$NDK"
    exit 1
fi

echo "CC: $CC"
echo "OUT: $OUT"

SYSROOT="$TOOLCHAIN/sysroot"

"$CC" \
    --sysroot="$SYSROOT" \
    -O2 \
    -o "$OUT" \
    "$SCRIPT_DIR/set_mixer_ctl.c"

echo "Built: $OUT"
file "$OUT" || true
ls -lh "$OUT"

OUT_PCM_PLAY="$REPO_ROOT/app/src/main/assets/pcm_play"
"$CC" \
    --sysroot="$SYSROOT" \
    -O2 \
    -o "$OUT_PCM_PLAY" \
    "$SCRIPT_DIR/pcm_play.c"

echo "Built: $OUT_PCM_PLAY"
file "$OUT_PCM_PLAY" || true
ls -lh "$OUT_PCM_PLAY"
