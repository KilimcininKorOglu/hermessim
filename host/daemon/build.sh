#!/usr/bin/env bash
# Build hermessimd-rs for both Android ABIs with the NDK and check the 16 KB segment alignment.
# Output: out/arm64-v8a/hermessimd and out/armeabi-v7a/hermessimd.
set -eu
cd "$(dirname "$0")"

if [ -n "${ANDROID_NDK_HOME:-}" ]; then
  NDK="$ANDROID_NDK_HOME"
else
  NDK=""
  for base in "$HOME/Library/Android/sdk/ndk" /opt/homebrew/share/android-commandlinetools/ndk; do
    [ -d "$base" ] || continue
    NDK="$(ls -d "$base"/* | sort -V | tail -1)"
  done
fi
[ -n "$NDK" ] || { echo "Android NDK not found: set ANDROID_NDK_HOME" >&2; exit 1; }

TOOLCHAIN="$(ls -d "$NDK"/toolchains/llvm/prebuilt/* | head -1)"
export ANDROID_NDK_HOME="$NDK"
export PATH="$TOOLCHAIN/bin:$PATH"
TARGET_DIR="${CARGO_TARGET_DIR:-target}"

rustup target add aarch64-linux-android armv7-linux-androideabi

for pair in "aarch64-linux-android:arm64-v8a" "armv7-linux-androideabi:armeabi-v7a"; do
  target="${pair%%:*}"
  abi="${pair##*:}"
  cargo build --release --target "$target"
  mkdir -p "out/$abi"
  cp "$TARGET_DIR/$target/release/hermessimd" "out/$abi/hermessimd"
  for a in $("$TOOLCHAIN/bin/llvm-readelf" -lW "out/$abi/hermessimd" | awk '$1 == "LOAD" { print $NF }'); do
    [ $((a)) -ge 16384 ] || { echo "hermessimd ($abi): a segment is aligned to $a, below 16 KB" >&2; exit 1; }
  done
  echo "built out/$abi/hermessimd"
done
