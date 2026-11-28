#!/usr/bin/env bash
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
output=$(realpath -m "$1")
work=${DORMPANEL_AIRPLAY_WORK:-$HOME/dormpanel-native}
ndk=${DORMPANEL_AIRPLAY_NDK:-${ANDROID_NDK_HOME:-$work/android-ndk-r27}}
abi=${2:-armeabi-v7a}
case "$abi" in
    armeabi-v7a) openssl_arch=arm ;;
    arm64-v8a) openssl_arch=arm64 ;;
    x86_64) openssl_arch=x86_64 ;;
    *) exit 2 ;;
esac
if ! grep -q '27\.0\.12077973' "$ndk/source.properties"; then
    echo 'AirPlay requires the Linux Android NDK 27.0.12077973. Set DORMPANEL_AIRPLAY_NDK.' >&2
    exit 1
fi
mkdir -p "$work/cache"
version=$(cat "$here/build.sh" "$here/prepare.py" "$here/sources.json" | sha256sum | cut -c1-16)
build="$work/$version-$abi"
mkdir -p "$build"
exec 9>"$build/build.lock"
flock 9
if [[ ! -f "$build/prepared" ]]; then
    python3 "$here/prepare.py" "$build" "$work/cache"
    touch "$build/prepared"
fi

export ANDROID_NDK_ROOT="$ndk"
export PATH="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
ssl="$work/openssl-3.4.4-ndk27-api28-$abi"
exec 8>"$ssl.lock"
flock 8
if [[ ! -f "$ssl/lib/libcrypto.a" ]]; then
    (
        cd "$build/src/openssl"
        ./Configure "android-$openssl_arch" -D__ANDROID_API__=28 --prefix="$ssl" --libdir=lib \
            no-shared no-tests no-apps no-docs no-module no-legacy no-ssl
        make -j "${DORMPANEL_AIRPLAY_JOBS:-8}" build_libs
        make install_dev
    )
fi
flock -u 8
cmake -S "$build/src/airplay/app/src/main/cpp" -B "$build/cmake" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DCMAKE_BUILD_TYPE=Release \
    -DDORMPANEL_OPENSSL="$ssl" -DDORMPANEL_OBOE="$build/src/oboe"
cmake --build "$build/cmake" --parallel "${DORMPANEL_AIRPLAY_JOBS:-8}"
mkdir -p "$output/jni/$abi" "$output/assets/airplay"
"$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded \
    -o "$output/jni/$abi/libairplay_native.so" "$build/cmake/libairplay_native.so"
cp "$here/sources.json" "$output/assets/airplay/"
cp "$build/licenses/"* "$output/assets/airplay/"
