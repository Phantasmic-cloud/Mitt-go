#!/bin/sh
# 用 Android NDK 把 core/ 编成 libmittgocore.so（Go c-shared，供 JNI 加载）。
#
# 需要环境变量 ANDROID_NDK_HOME；ANDROID_API 可选，默认 30。
#
# 输出：app/src/main/jniLibs/arm64-v8a/libmittgocore.so
set -eu

: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME is not set}"
API=${ANDROID_API:-30}
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

HOST_TAG=$(ls "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" | head -n 1)
NDK_BIN=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin

OUT=$ROOT/app/src/main/jniLibs/arm64-v8a
mkdir -p "$OUT"

cd "$ROOT/core"
# -mod=mod so a clean checkout may adjust go.mod/go.sum for the toolchain in use.
export GOFLAGS=-mod=mod
CGO_ENABLED=1 GOOS=android GOARCH=arm64 \
    CC="$NDK_BIN/aarch64-linux-android$API-clang" \
    CGO_LDFLAGS="-Wl,-z,max-page-size=16384" \
    go build -a -trimpath -buildmode=c-shared -ldflags="-s -w" \
    -o "$OUT/libmittgocore.so" ./cmd/mittgod

# c-shared 还会生成一个 C 头文件，不能打进 APK。
rm -f "$OUT/libmittgocore.h"

echo ">> built $OUT/libmittgocore.so"
ls -lh "$OUT/libmittgocore.so"