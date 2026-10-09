#!/usr/bin/env bash
# 在 emulator-runner 的设备就绪后执行（单进程脚本，避免变量不跨行）。
set -euxo pipefail

APK=app/build/outputs/apk/debug/app-debug.apk
adb install -r "$APK"
adb shell input keyevent 82 || true

# 宿主 SPP server（App 配置指向 10.0.2.2:19003）
"$RUNNER_TEMP/spp-server" -type server -proto tcp -listen :19003 -key testkey -nolog 1 &
SPP_PID=$!
trap 'kill $SPP_PID 2>/dev/null || true' EXIT

python3 -u scripts/record_demo.py
RC=$?

ls -la take*.mp4 2>/dev/null || true
# 失败时尽量从设备拉回分段，便于诊断
if [ $RC -ne 0 ]; then
  adb shell killall -INT screenrecord 2>/dev/null || true
  sleep 2
  for f in $(adb shell ls /sdcard/take*.mp4 2>/dev/null | tr -d '\r'); do
    adb pull "$f" "$(basename "$f")" 2>/dev/null || true
  done
fi
exit $RC
