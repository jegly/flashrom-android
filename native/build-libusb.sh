#!/bin/bash
# Build libusb for Android/arm64 with the Android file-descriptor patch.
#
# Replaces build_libusb_custom.sh, which built on the phone itself under Termux
# against an unpinned clone of libusb master. Differences that matter:
#   - cross-compiled from the host with the NDK, not on-device
#   - upstream pinned to a tag, not "whatever master is today"
#   - the patch is a real .patch applied with `git apply`, which FAILS if
#     upstream moves; the original patch_libusb.py did fuzzy text matching and
#     silently skipped hunks when they no longer matched, producing a libusb
#     with no Android support and no error
#   - USE_PC_NAME=1 so the soname is libusb-1.0.so, which is what flashrom's
#     DT_NEEDED and AssetHelper's symlink both expect
set -euo pipefail

NDK="${NDK:-$HOME/Android/Sdk/ndk/30.0.16248370}"
LIBUSB_TAG="${LIBUSB_TAG:-v1.0.30}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$HERE/work"
SRC="$WORK/libusb"
PATCH="$HERE/patches/0001-libusb-android-usb-fd.patch"
OUT="$HERE/out/arm64-v8a"

[ -d "$NDK" ]   || { echo "NDK not found: $NDK" >&2; exit 1; }
[ -f "$PATCH" ] || { echo "Patch not found: $PATCH" >&2; exit 1; }

echo "==> Cloning libusb $LIBUSB_TAG"
rm -rf "$SRC"
mkdir -p "$WORK"
git clone --quiet --depth 1 --branch "$LIBUSB_TAG" \
    https://github.com/libusb/libusb.git "$SRC" 2>/dev/null

echo "==> Applying Android fd patch"
# --check first: if upstream moved, stop the build rather than shipping a
# libusb silently missing the patch.
git -C "$SRC" apply --check "$PATCH"
git -C "$SRC" apply "$PATCH"

echo "==> Building with ndk-build"
# APP_ALLOW_MISSING_DEPS: examples.mk/tests.mk still reference the old module
# name "usb1.0"; APP_MODULES already limits what actually gets built.
"$NDK/ndk-build" -C "$SRC/android/jni" \
    USE_PC_NAME=1 \
    APP_ABI=arm64-v8a \
    APP_PLATFORM=android-23 \
    APP_MODULES=usb-1.0 \
    APP_ALLOW_MISSING_DEPS=true \
    APP_CFLAGS="-Oz -fPIC -fstack-protector-strong -D_FORTIFY_SOURCE=2 -Wno-error" \
    APP_LDFLAGS="-llog -Wl,-z,max-page-size=16384 -Wl,-z,relro,-z,now" \
    -j"$(nproc)" 2>&1 | grep -vE 'Entering directory|Leaving directory' || true

mkdir -p "$OUT"
cp "$SRC/android/libs/arm64-v8a/libusb-1.0.so" "$OUT/"

echo
echo "==> Verifying"
SO="$OUT/libusb-1.0.so"

soname=$(readelf -d "$SO" | awk -F'[][]' '/SONAME/{print $2}')
[ "$soname" = "libusb-1.0.so" ] || { echo "wrong soname: $soname" >&2; exit 1; }
echo "  soname             $soname"

align=$(readelf -lW "$SO" | awk '/LOAD/{print $NF; exit}')
[ "$align" = "0x4000" ] || { echo "not 16KB aligned: $align" >&2; exit 1; }
echo "  LOAD alignment     $align (16KB)"

# Note: `grep -c`, not `grep -q`. Under `set -o pipefail`, grep -q exits at the
# first match and closes the pipe; the producer gets SIGPIPE, exits non-zero,
# and pipefail then fails the whole pipeline even though the check passed.
if [ "$(nm -D --defined-only "$SO" | grep -c ' libusb_wrap_sys_device$')" -eq 0 ]; then
    echo "libusb_wrap_sys_device missing" >&2; exit 1
fi
echo "  exports            libusb_wrap_sys_device"

if [ "$(strings "$SO" | grep -c ANDROID_USB_FD)" -eq 0 ]; then
    echo "Android patch is not in the binary" >&2; exit 1
fi
echo "  Android patch      ANDROID_USB_FD present"

if [ "$(strings "$SO" | grep -c 'libusb-android')" -eq 0 ]; then
    echo "expected English log prefix not found" >&2; exit 1
fi
echo "  log messages       English"

echo
echo "OK -> $SO"
