#!/usr/bin/env bash
# 在 emulator-runner 的设备就绪后执行（单进程脚本，避免变量不跨行）。
set -euxo pipefail

APK=app/build/outputs/apk/debug/app-debug.apk
adb install -r "$APK"
adb shell input keyevent 82 || true

# 冷启动的 Pixel Launcher 在软件渲染下极易持续 ANR 并弹出模态框；
# 一次性 AVD 不需要桌面，禁用 Launcher 后全部页面用 am start 直达。
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher >/dev/null 2>&1 || true
adb shell pm disable-user --user 0 com.android.launcher3 >/dev/null 2>&1 || true
sleep 2
adb shell am start -n com.esrrhs.spp.client/.MainActivity || true

# 宿主 SPP server（App 配置指向 10.0.2.2:19003）
"$RUNNER_TEMP/spp-server" -type server -proto tcp -listen :19003 -key testkey -nolog 1 &
SPP_PID=$!

# 限速长流 HTTP 服务（用于 Sessions 页展示持续存在的实时连接）
python3 -u scripts/demo_stream_server.py 19080 64 0.25 60 &
STREAM_PID=$!
trap 'kill $SPP_PID $STREAM_PID 2>/dev/null || true' EXIT

# SPP server 代设备拨号时使用宿主自身的可达 IP（不能用 10.0.2.2 模拟器别名）
LAN_IP=$(hostname -I 2>/dev/null | awk '{print $1}')
[ -z "$LAN_IP" ] && LAN_IP=$(ip -4 addr show | awk '/inet /{print $2}' | cut -d/ -f1 | grep -v '^127\.' | head -1)
export DEMO_STREAM_URL="http://${LAN_IP}:19080/slow"
echo "DEMO_STREAM_URL=$DEMO_STREAM_URL"

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
