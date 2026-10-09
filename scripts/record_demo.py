#!/usr/bin/env python3
"""录制 Google Play 审核演示视频：SPP VPN 一键连接 + 实时流量可视化。

在已启动的模拟器（KVM/x86_64，API 34 google_apis）内执行：
  宿主侧：TCP/UDP echo（19001）供设备制造确定性的隧道流量；
  设备侧：安装 App → 导入配置 → 一键连接（保留系统 VPN 授权弹窗）
          → 通知栏常驻 VPN → Chrome 真实网页流量
          →「Active connections」实时会话/速率/字节 →「Stats」流量统计
          → 断开。
全程 screenrecord 录屏，结束后拉取到 $OUT（默认 demo.mp4）。

前置（由 workflow 准备）：
  - spp 服务端运行在宿主 :19003（-key testkey）
  - app-debug.apk 已安装
"""
import base64
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB", "adb")
OUT = os.environ.get("OUT", "demo.mp4")
PKG = "com.esrrhs.spp.client"
ACT = PKG + "/.MainActivity"
HOST_ECHO_PORT = 19001

t0 = time.time()
def log(msg):
    print(f"[{time.time()-t0:6.1f}s] {msg}", flush=True)

def adb(*args, check=True, timeout=30):
    res = subprocess.run([ADB, *args], capture_output=True, text=True, errors="ignore", timeout=timeout)
    if check and res.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {res.stderr or res.stdout}")
    return res.stdout

def sh(cmd):
    return adb("shell", cmd, check=False)

# ---------------- 宿主 echo 服务（制造确定性流量） ----------------
import socket
import threading

def tcp_echo_server(port, stop):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("0.0.0.0", port)); s.listen(128)
    s.settimeout(1.0)
    def handle(c):
        try:
            c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            while not stop.is_set():
                c.settimeout(1.0)
                try:
                    data = c.recv(4096)
                except socket.timeout:
                    continue
                if not data:
                    break
                c.sendall(data)
        except Exception:
            pass
        finally:
            c.close()
    while not stop.is_set():
        try:
            c, _ = s.accept()
            threading.Thread(target=handle, args=(c,), daemon=True).start()
        except socket.timeout:
            continue
    s.close()

def udp_echo_server(port, stop):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("0.0.0.0", port)); s.settimeout(1.0)
    while not stop.is_set():
        try:
            data, addr = s.recvfrom(65500)
            s.sendto(data, addr)
        except socket.timeout:
            continue
    s.close()

# ---------------- UI 驱动 ----------------
def dump_ui():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
    return adb("exec-out", "cat", "/sdcard/ui.xml", check=False)

def nodes():
    try:
        root = ET.fromstring(dump_ui())
        return list(root.iter("node"))
    except Exception:
        return []

def node_bounds(node):
    m = re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds") or "")
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    if x2 - x1 <= 0 or y2 - y1 <= 0:
        return None
    return x1, y1, x2, y2

def find_node(*needles):
    needles = [s.lower() for s in needles]
    for n in nodes():
        if node_bounds(n) is None:
            continue
        hay = ((n.get("text") or "") + "\n" + (n.get("content-desc") or "")).lower()
        if all(k in hay for k in needles):
            return n
    return None

def center(node):
    x1, y1, x2, y2 = node_bounds(node)
    return (x1 + x2) // 2, (y1 + y2) // 2

def tap_node(n):
    x, y = center(n)
    label = n.get("text") or n.get("content-desc")
    adb("shell", "input", "tap", str(x), str(y))
    log(f"tap ({x},{y}) '{label}'")

def wait_tap(*needles, timeout=15):
    end = time.time() + timeout
    while time.time() < end:
        n = find_node(*needles)
        if n is not None:
            tap_node(n)
            return n
        time.sleep(0.6)
    raise RuntimeError(f"node not found: {needles}")

def wait_text(*needles, timeout=20):
    end = time.time() + timeout
    while time.time() < end:
        n = find_node(*needles)
        if n is not None:
            return n
        time.sleep(0.6)
    return None

def swipe_up():
    adb("shell", "input", "swipe", "540", "1600", "540", "600", "300")

def open_url(url):
    log(f"open {url}")
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", url, check=False)

def dismiss_chrome_dialogs():
    """Chrome 首启可能弹 FRE/账号选择，尝试通用按钮关闭。"""
    for label in ("Accept & continue", "No thanks", "Use without an account",
                  "接受并继续", "不用了", "接受并继续"):
        n = find_node(label)
        if n is not None:
            tap_node(n)
            time.sleep(2.0)
    # 偶发的通知/同步提示
    for label in "Allow", "Deny", "允许", "拒绝":
        if find_node("Chrome"):
            break
        n = find_node(label)
        if n is not None:
            tap_node(n); time.sleep(1.0)

def start_shell_traffic(seconds=25):
    """在设备上用 shell 发起大量经隧道的 TCP/UDP 连接（确定性补充流量）。"""
    log("start on-device shell traffic generator")
    script = (
        "end=$(( $(date +%s) + %d }); "
        "while [ $(date +%s) -lt $end ]; do "
        "(printf 'ping-$$\\n' | timeout 2 toybox nc 10.0.2.2 %d >/dev/null 2>&1) & "
        "(echo q | timeout 2 toybox nc -u -w1 10.0.2.2 %d >/dev/null 2>&1) & "
        "sleep 0.2; done" % (seconds, HOST_ECHO_PORT, HOST_ECHO_PORT)
    )
    subprocess.Popen([ADB, "shell", script], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

# ---------------- 配置深链 ----------------
def make_deeplink():
    p = {
        "id": "demo-spp", "name": "SPP Demo",
        "config": {
            "serverHost": "10.0.2.2", "serverPort": 19003,
            "proto": "tcp", "key": "testkey",
            "enableIpv6": False, "kind": "spp",
        },
        "perAppMode": "ALL", "bypassLan": False, "bypassCn": False, "pingMs": -1,
    }
    raw = json.dumps(p, separators=(",", ":"))
    return "spp://" + base64.b64encode(raw.encode()).decode()

def dump_texts(tag):
    print(f"--- UI DUMP {tag} ---", flush=True)
    for n in nodes():
        t = n.get("text") or n.get("content-desc")
        if t and node_bounds(n) is not None:
            print(f"  UI: {t}", flush=True)
    print("-------------------", flush=True)

def preflight_main(timeout=45):
    """确保主界面就绪：配置卡片与电源钮都在。"""
    end = time.time() + timeout
    while time.time() < end:
        adb("shell", "am", "start", "-n", ACT, check=False)
        time.sleep(3.0)
        if find_node("SPP Demo") is not None and find_node("SPP VPN") is not None:
            return True
        time.sleep(2.0)
    return False

# ---------------- main ----------------
def main():
    stop = threading.Event()
    threading.Thread(target=tcp_echo_server, args=(HOST_ECHO_PORT, stop), daemon=True).start()
    threading.Thread(target=udp_echo_server, args=(HOST_ECHO_PORT, stop), daemon=True).start()
    log("host echo servers up")

    # 设备准备（不预授 VPN，保留授权弹窗；只给通知权限以展示常驻通知）
    sh("settings put global window_animation_scale 1.0")
    sh("settings put global transition_animation_scale 1.0")
    sh("settings put global animator_duration_scale 1.0")
    sh("svc power stayon usb")
    sh("input keyevent 82")
    sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
    sh("logcat -c")
    sh("rm -f /sdcard/demo.mp4")

    # 导入配置并预检（不录屏，避免把启动等待拍进视频）
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
        "-d", make_deeplink(), PKG + "/.MainActivity", check=False)
    if not preflight_main():
        dump_texts("preflight")
        raise RuntimeError("main screen not ready")
    log("main screen ready")
    time.sleep(1.5)

    # 开始录屏（≤150s）
    rec = subprocess.Popen(
        [ADB, "shell", "screenrecord", "--bit-rate", "12000000",
         "--time-limit", "150", "/sdcard/demo.mp4"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    log("screenrecord started")
    time.sleep(1.0)

    # 1. 主界面
    adb("shell", "am", "start", "-n", ACT)
    time.sleep(3.0)

    # 2. 大圆钮 → 系统 VPN 授权（给审核员看清弹窗内容）
    wait_tap("SPP VPN")
    time.sleep(2.0)
    ok = wait_text("OK", "允许", "确定", timeout=15)
    log("VPN consent dialog visible")
    # 记住按钮坐标，停留 4s 后点同一位置（避免重新 dump 抖动丢失节点）
    ok_x, ok_y = center(ok)
    time.sleep(4.0)
    adb("shell", "input", "tap", str(ok_x), str(ok_y))
    log(f"consent granted at ({ok_x},{ok_y})")

    # 3. Connected：UI 文本与 logcat 隧道日志双通道判定
    def tunnel_up():
        if find_node("Connected", "已连接") is not None:
            return True
        out = sh("logcat -d -s SppVpnService:I HevTunnel:I").lower()
        return "tun established" in out or "hev tunnel started" in out

    end = time.time() + 40
    connected = False
    while time.time() < end:
        if tunnel_up():
            connected = True
            break
        time.sleep(1.0)
    if not connected:
        log("DUMP current UI texts:")
        for n in nodes():
            t = n.get("text") or n.get("content-desc")
            if t:
                print("  UI:", t, flush=True)
        print(sh("logcat -d -s SppVpnService:* SppProcess:* HevTunnel:* | tail -40"))
        raise RuntimeError("did not reach Connected")
    # 确保回前台再确认状态文本
    adb("shell", "am", "start", "-n", ACT)
    time.sleep(3.0)
    log("connected")
    time.sleep(2.0)

    # 4. 通知栏常驻 VPN
    sh("cmd statusbar expand-notifications")
    time.sleep(4.0)
    sh("cmd statusbar collapse")
    time.sleep(1.5)

    # 5. 真实网页流量（Chrome 不存在时降级为纯 shell 流量）
    has_chrome = "package:com.android.chrome" in sh("pm list packages com.android.chrome")
    if has_chrome:
        open_url("https://www.bing.com")
        time.sleep(6.0)
        dismiss_chrome_dialogs()
        time.sleep(1.0)
        dismiss_chrome_dialogs()
        # FRE 弹窗可能吞掉首次导航，关闭后重新打开
        open_url("https://www.bing.com")
        time.sleep(6.0)
        swipe_up(); time.sleep(2.0); swipe_up(); time.sleep(2.0)
        open_url("https://www.baidu.com")
        time.sleep(5.0)
        swipe_up(); time.sleep(2.0)
        for i, q in enumerate(("city+night", "mountain+lake", "ocean+wave")):
            open_url(f"https://www.bing.com/images/search?q={q}&form=HDRSC2")
            time.sleep(1.5)
    else:
        log("Chrome not present, shell traffic only")

    # 6. 回 App，注入持续后台流量
    start_shell_traffic(seconds=40)
    adb("shell", "am", "start", "-n", ACT)
    time.sleep(2.0)

    # 7. Active connections 实时会话页
    wait_tap("Sessions", "当前连接", timeout=8)
    log("sessions screen")
    for i in range(5):
        time.sleep(5)
        if has_chrome and i in (1, 3):
            open_url(f"https://www.bing.com/images/search?q=forest+{i}&form=HDRSC2")
            time.sleep(1.5)
            adb("shell", "am", "start", "-n", ACT)
            wait_text("Active connections", "连接", timeout=5)
    log("sessions hold done")

    # 8. Stats
    adb("shell", "input", "keyevent", "4"); time.sleep(1.5)
    wait_tap("Stats", "统计", timeout=6)
    log("stats screen")
    time.sleep(6.0)

    # 9. 断开
    adb("shell", "input", "keyevent", "4"); time.sleep(1.5)
    wait_tap("SPP VPN")
    log("disconnect tapped")
    time.sleep(6.0)

    stop.set()
    log("waiting screenrecord finalize")
    while rec.poll() is None:
        time.sleep(0.5)

    pull = subprocess.run([ADB, "pull", "/sdcard/demo.mp4", OUT], capture_output=True, text=True)
    print(pull.stdout, pull.stderr)
    if not os.path.exists(OUT):
        sys.exit("pull failed")
    log(f"saved {OUT} size={os.path.getsize(OUT)}")

if __name__ == "__main__":
    try:
        main()
    except Exception:
        import traceback
        traceback.print_exc()
        try:
            dump_texts("fatal")
        except Exception:
            pass
        raise
