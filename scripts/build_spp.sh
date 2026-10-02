#!/usr/bin/env bash
#
# 交叉编译 SPP socks5_client 为 Android 可执行文件。
#
# 产物：app/src/main/jniLibs/<abi>/libspp.so
#   —— 实际是 Go 可执行文件，借 .so 名装入 APK；安装后位于 nativeLibraryDir，
#      该目录在 API 29+ 仍允许 exec（files/ 目录已不允许）。
#   —— 需在 app/build.gradle.kts 中保持 useLegacyPackaging = true 让 so 落盘。
#
# 说明：
#   开启 CGO（链接 bionic）以使用系统解析器：Android 没有 /etc/resolv.conf，
#   纯 Go 解析器（CGO_ENABLED=0）无法解析 server 域名。
#
# 环境要求：Go 1.26+、Android NDK。
# 可用环境变量：SPP_ABIS（默认 "arm64-v8a x86_64"，与 abiFilters 一致）、
#               SPP_DIR（默认 third_party/spp）、ANDROID_NDK_HOME。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SPP_DIR="${SPP_DIR:-$ROOT/third_party/spp}"
OUT_DIR="$ROOT/app/src/main/jniLibs"
ABIS=(${SPP_ABIS:-arm64-v8a x86_64})

if [ ! -d "$SPP_DIR" ]; then
    git clone --depth 1 https://github.com/esrrhs/spp.git "$SPP_DIR"
fi

# 更新到上游默认分支的最新提交，并把 gohome 等 Go 依赖升到最新
cd "$SPP_DIR"
BRANCH="$(git ls-remote --symref origin HEAD | head -1 | sed -E 's#ref: refs/heads/##; s#[[:space:]].*##')"
echo "==> 更新 spp（origin/${BRANCH:-HEAD}）"
git fetch --depth 1 origin "${BRANCH:-master}"
git reset --hard FETCH_HEAD
echo "==> 更新 gohome 等 Go 依赖"
go get github.com/esrrhs/gohome@latest
go mod tidy
cd "$ROOT"

NDK="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK" ]; then
    NDK=$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk/"* 2>/dev/null | sort -V | tail -1)
fi
[ -n "$NDK" ] || { echo "错误：未找到 Android NDK（请设置 ANDROID_NDK_HOME）" >&2; exit 1; }
HOST_TAG=darwin-x86_64
[ "$(uname -s)" = "Linux" ] && HOST_TAG=linux-x86_64
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
[ -d "$TOOLCHAIN" ] || { echo "错误：NDK 工具链不存在：$TOOLCHAIN" >&2; exit 1; }

cd "$SPP_DIR"
VERSION="$(git describe --tags 2>/dev/null || echo dev)"
# CGO 经外部链接器（clang）最终链接，要求其按 16KB 页对齐 LOAD 段，
# 满足 Android 15+ 的 16KB page size 要求（否则系统提示应用不合规）。
LDFLAGS="-s -w -X 'github.com/esrrhs/spp/version.Version=$VERSION-android' -extldflags=-Wl,-z,max-page-size=16384"

for ABI in "${ABIS[@]}"; do
    case "$ABI" in
        arm64-v8a)   GOARCH=arm64; CLANG=aarch64-linux-android26-clang ;;
        armeabi-v7a) GOARCH=arm;   CLANG=armv7a-linux-androideabi26-clang ;;
        x86_64)      GOARCH=amd64; CLANG=x86_64-linux-android26-clang ;;
        x86)         GOARCH=386;   CLANG=i686-linux-android26-clang ;;
        *) echo "错误：未知 ABI $ABI" >&2; exit 1 ;;
    esac
    echo "==> 编译 spp ($ABI, go$GOARCH)"
    mkdir -p "$OUT_DIR/$ABI"
    CC="$TOOLCHAIN/$CLANG" \
    GOOS=android GOARCH="$GOARCH" CGO_ENABLED=1 \
        go build -trimpath -ldflags "$LDFLAGS" -o "$OUT_DIR/$ABI/libspp.so" .
done

echo "完成："
ls -lh "$OUT_DIR"/*/libspp.so
