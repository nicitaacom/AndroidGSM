#!/bin/bash
# Build set_mixer_ctl arm64 PIE ELF for Android
# Usage: NDK=/path/to/ndk ./build_arm64.sh
set -e
NDK="${NDK:?Set NDK=/path/to/ndk}"
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
SRC="$(dirname "$0")/set_mixer_ctl.c"
OUT="$(dirname "$0")/../../app/src/main/assets/set_mixer_ctl"
"$CC" -O2 -static-pie -o "$OUT" "$SRC"
chmod +x "$OUT"
echo "Built: $OUT ($(wc -c < "$OUT") bytes)"
