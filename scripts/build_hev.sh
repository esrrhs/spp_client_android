#!/usr/bin/env bash
#
# 用 NDK ndk-build 编译 hev-socks5-tunnel（含官方 hev-jni.c JNI 桥接）。
#
# 产物：app/src/main/jniLibs/<abi>/libhev-socks5-tunnel.so
#
# 环境要求：Android NDK；首次运行会克隆源码并初始化子模块
# （third-part/lwip、yaml、hev-task-system 与 src/core）。
# 可用环境变量：HEV_ABIS（默认 "arm64-v8a x86_64"）、HEV_DIR、ANDROID_NDK_HOME。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HEV_DIR="${HEV_DIR:-$ROOT/third_party/hev-socks5-tunnel}"
OUT_DIR="$ROOT/app/src/main/jniLibs"
ABIS=(${HEV_ABIS:-arm64-v8a x86_64})

if [ ! -d "$HEV_DIR" ]; then
    git clone --depth 1 https://github.com/heiher/hev-socks5-tunnel.git "$HEV_DIR"
fi
cd "$HEV_DIR"
git submodule update --init --recursive --depth 1

NDK="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK" ]; then
    NDK=$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk/"* 2>/dev/null | sort -V | tail -1)
fi
[ -n "$NDK" ] || { echo "错误：未找到 Android NDK（请设置 ANDROID_NDK_HOME）" >&2; exit 1; }

echo "==> ndk-build hev-socks5-tunnel (${ABIS[*]})"
# 仓库目录名不是 ndk-build 约定的 jni/，需显式指定项目路径与构建脚本
"$NDK/ndk-build" -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu)" \
    NDK_PROJECT_PATH=. \
    NDK_APPLICATION_MK=Application.mk \
    APP_BUILD_SCRIPT=Android.mk \
    APP_ABI="${ABIS[*]}" \
    APP_PLATFORM=android-26 \
    APP_CFLAGS="-O3"

for ABI in "${ABIS[@]}"; do
    mkdir -p "$OUT_DIR/$ABI"
    cp "libs/$ABI/libhev-socks5-tunnel.so" "$OUT_DIR/$ABI/"
done

echo "完成："
ls -lh "$OUT_DIR"/*/libhev-socks5-tunnel.so
