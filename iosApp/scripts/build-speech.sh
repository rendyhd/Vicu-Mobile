#!/bin/sh
# Builds Vicu's local speech engine (native/speech: vicu_speech.h over whisper.cpp) for iOS as one
# static library, libvicu_speech_all.a (vicu_speech, whisper, parakeet and ggml with Metal and
# Accelerate), which the shared module's cinterop links into the Kotlin framework and the iOS tests.
# Gradle runs it before the cinterop of each iOS target (shared/build.gradle.kts); by hand:
#
#   iosApp/scripts/build-speech.sh iphoneos|iphonesimulator [output folder]
#
# The output folder (default shared/build/speech-ios/<sdk>) gets lib/libvicu_speech_all.a and
# include/vicu_speech.h. Nothing is rebuilt while the sources and this script are unchanged.
# CMake comes from PATH or the Android SDK (cmake/<version>/bin); whisper.cpp is downloaded by
# CMake (pinned by SHA-256) unless VICU_WHISPER_SOURCE_DIR names an unpacked copy.
set -eu

SDK="${1:-}"
case "$SDK" in
  iphoneos|iphonesimulator) ;;
  *) echo "usage: $0 iphoneos|iphonesimulator [output folder]" >&2; exit 2 ;;
esac

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SRC="$ROOT/native/speech"
OUT="${2:-$ROOT/shared/build/speech-ios/$SDK}"
DEPLOYMENT_TARGET=17.0
LIB="$OUT/lib/libvicu_speech_all.a"

# Up to date when the inputs hash the same as for the library there.
STAMP_VALUE=$( (cat "$SRC/CMakeLists.txt" "$SRC/vicu_speech.cpp" "$SRC/vicu_speech.h" "$0"; \
  echo "$SDK $DEPLOYMENT_TARGET ${VICU_WHISPER_SOURCE_DIR:-}") | shasum -a 256 | cut -d' ' -f1)
if [ -f "$LIB" ] && [ -f "$OUT/stamp" ] && [ "$(cat "$OUT/stamp")" = "$STAMP_VALUE" ]; then
  echo "vicu_speech for $SDK is up to date"
  exit 0
fi

# Xcode's build phases run with a small PATH.
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
if ! command -v cmake >/dev/null 2>&1; then
  for dir in /opt/homebrew/bin /usr/local/bin $(ls -d "$SDK_ROOT"/cmake/*/bin 2>/dev/null | sort -r); do
    if [ -x "$dir/cmake" ]; then PATH="$dir:$PATH"; break; fi
  done
fi
command -v cmake >/dev/null 2>&1 || { echo "error: cmake not found (install it, or CMake through the Android SDK)" >&2; exit 1; }
CMAKE_DIR=$(dirname "$(command -v cmake)")
if [ -x "$CMAKE_DIR/ninja" ]; then PATH="$CMAKE_DIR:$PATH"; fi
if command -v ninja >/dev/null 2>&1; then GENERATOR=Ninja; else GENERATOR="Unix Makefiles"; fi

BUILD="$OUT/build"
rm -rf "$OUT/lib" "$OUT/include" "$OUT/stamp"
mkdir -p "$BUILD" "$OUT/lib" "$OUT/include"

set -- \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_SYSTEM_NAME=iOS \
  -DCMAKE_OSX_SYSROOT="$SDK" \
  -DCMAKE_OSX_ARCHITECTURES=arm64 \
  -DCMAKE_OSX_DEPLOYMENT_TARGET="$DEPLOYMENT_TARGET" \
  -DVICU_SPEECH_SHARED=OFF \
  -DGGML_METAL=ON \
  -DGGML_METAL_EMBED_LIBRARY=ON \
  -DGGML_BLAS=ON \
  -DGGML_BLAS_VENDOR=Apple
if [ -n "${VICU_WHISPER_SOURCE_DIR:-}" ]; then
  set -- "$@" -DVICU_WHISPER_SOURCE_DIR="$VICU_WHISPER_SOURCE_DIR"
fi

echo "Building vicu_speech for $SDK with $(cmake --version | head -1) ($GENERATOR)"
cmake -S "$SRC" -B "$BUILD" -G "$GENERATOR" "$@"
cmake --build "$BUILD" --target vicu_speech --parallel

# One archive for cinterop: vicu_speech and every library it needs.
ARCHIVES=$(find "$BUILD" -name '*.a' -not -path '*/CMakeFiles/*' | sort)
echo "Merging:"
echo "$ARCHIVES" | sed "s|$BUILD/|  |"
echo "$ARCHIVES" | tr '\n' '\0' | xargs -0 xcrun libtool -static -no_warning_for_no_symbols -o "$LIB"
cp "$SRC/vicu_speech.h" "$OUT/include/"
echo "$STAMP_VALUE" > "$OUT/stamp"
echo "Wrote $LIB"
