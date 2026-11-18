#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/.local/share/android-sdk}"
export ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-$ANDROID_HOME/ndk/27.2.12479018}"
ndkbin="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ndkbin/aarch64-linux-android26-clang"
export CC_aarch64_linux_android="$ndkbin/aarch64-linux-android26-clang"
export CXX_aarch64_linux_android="$ndkbin/aarch64-linux-android26-clang++"
export AR_aarch64_linux_android="$ndkbin/llvm-ar"
export CARGO_BUILD_JOBS="${CARGO_BUILD_JOBS:-6}"
cargo build --manifest-path rust/Cargo.toml --target aarch64-linux-android --release
mkdir -p app/src/main/jniLibs/arm64-v8a
cp rust/target/aarch64-linux-android/release/librocky_radio.so app/src/main/jniLibs/arm64-v8a/
./gradlew --offline :app:assembleOptimized
