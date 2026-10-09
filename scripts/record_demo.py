#!/usr/bin/env python3
"""录制 Google Play 审核演示视频：SPP VPN 一键连接 + 实时流量可视化。

设计为两段录屏后用 ffmpeg 拼接（慢启动的等待发生在录屏之外）：
  part1_consent.mp4：主界面 → 系统 VPN 授权弹窗（保留 4s）→ 连接成功
  part2_traffic.mp4：通知栏 → Chrome 真实网页 → 实时会话/速率/字节
                      → 流量统计 → 断开
输出 demo.mp4。
"""
import base64
import json
import os
import re
import socket
import subprocess
import sys
import threading
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

# ---------------- 录屏 ----------------
class Recorder:
    def __init__(self, name):
        self.name = name
        self.proc = None

    def start(self):
        sh(f"rm -f /sdcard/{self.name}")
        self.proc = subprocess.Popen(
            [ADB, "shell", "screenrecord", "--bit-rate", "12000000",
             "--time-limit", "180", f"/sdcard/{self.name}"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        time.sleep(1.0)
        log(f"recording {self.name}")

    def stop(self):
        # SIGINT/SIGTERM 触发 screenrecord 正常封装 mp4
        sh("pkill -TERM screenrecord 2>/dev/null; killall screenrecord 2>/dev/null")
        if self.proc:
            try:
                self.proc.wait(timeout=15)
            except Exception:
                pass
        time.sleep(1.0)
        adb("pull", f"/sdcard/{self.name}", self.name)
        log(f"saved {self.name} ({os.path.getsize(self.name)} bytes)")

# ---------------- 宿主 echo ----------------
def tcp_echo_server(port, stop):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("0.0.0.0", port)); s.listen(128); s.settimeout(1.0)
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

# ---------------- UI ----------------
def dump_ui():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
    return adb("exec-out", "cat", "/sdcard/ui.xml", check=False)

def nodes():
    try:
        return list(ET.fromstring(dump_ui()).iter("node"))
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

def find_any(*needles):
    needles = [s.lower() for s in needles]
    for n in nodes():
        if node_bounds(n) is None:
            continue
        hay = ((n.get("text") or "") + "\n" + (n.get("content-desc") or "")).lower()
        if any(k in hay for k in needles):
            return n
    return None

def find_all_text(*needles):
    return find_any(*needles)

def center(node):
    x1, y1, x2, y2 = node_bounds(node)
    return (x1 + x2) // 2, (y1 + y2) // 2

def tap_node(n):
    x, y = center(n)
    label = n.get("text") or n.get("content-desc")
    adb("shell", "input", "tap", str(x), str(y))
    log(f"tap ({x},{y}) '{label}'")

def wait_any(*needles, timeout=60, period=1.0):
    end = time.time() + timeout
    while time.time() < end:
        n = find_any(*needles)
        if n is not None:
            return n
        time.sleep(period)
    return None

def wait_tap(*needles, timeout=60):
    n = wait_any(*needles, timeout=timeout)
    if n is None:
        raise RuntimeError(f"node not found: {needles}")
    tap_node(n)
    return n

def swipe_up():
    adb("shell", "input", "swipe", "540", "1600", "540", "600", "300")

def open_url(url):
    log(f"open {url}")
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", url, check=False)

def dismiss_anr():
    if find_any("isn't responding", "未响应") is not None:
        n = find_any("Wait", "等待")
        if n is not None:
            tap_node(n)
        return True
    return False

def dismiss_chrome_dialogs():
    for label in ("Use without an account", "Accept & continue", "No thanks",
                  "使用时不登录账号", "不用了", "接受并继续"):
        n = find_any(label)
        if n is not None:
            tap_node(n); time.sleep(1.5); return
    for label in ("Allow", "允许"):
        n = find_any(label)
        if n is not None:
            tap_node(n); time.sleep(1.0); return

def dump_texts(tag):
    print(f"--- UI DUMP {tag} ---", flush=True)
    for n in nodes():
        t = n.get("text") or n.get("content-desc")
        if t and node_bounds(n) is not None:
            print(f"  UI: {t}", flush=True)
    print("-------------------", flush=True)

def tunnel_up():
    if find_any("Connected", "已连接") is not None:
        return True
    out = sh("logcat -d -s SppVpnService:I HevTunnel:I").lower()
    return "tun established" in out or "hev tunnel started" in out

def wait_tunnel(timeout=120):
    end = time.time() + timeout
    while time.time() < end:
        dismiss_anr()
        if tunnel_up():
            return True
        time.sleep(1.0)
    return False

def start_shell_traffic(seconds=30):
    log("start on-device shell traffic generator")
    # 占位符替换，避免 %s 被当成格式化占位
    script = (
        "end=$(( $(date +XsX) + SECS )); "
        "while [ $(date +XsX) -lt $end ]; do "
        "(printf 'ping-x\\n' | timeout 2 toybox nc 10.0.2.2 PORT >/dev/null 2>&1) & "
        "sleep 0.2; done"
    )
    script = script.replace("XsX", "%s").replace("SECS", str(seconds)).replace("PORT", str(HOST_ECHO_PORT))
    subprocess.Popen([ADB, "shell", script], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

def make_deeplink():
    p = {
        "id": "demo-spp", "name": "SPP Demo",
        "config": {"serverHost": "10.0.2.2", "serverPort": 19003, "proto": "tcp",
                   "key": "testkey", "enableIpv6": False, "kind": "spp"},
        "perAppMode": "ALL", "bypassLan": False, "bypassCn": False, "pingMs": -1,
    }
    return "spp://" + base64.b64encode(json.dumps(p, separators=(",", ":")).encode()).decode()

def main():
    stop = threading.Event()
    threading.Thread(target=tcp_echo_server, args=(HOST_ECHO_PORT, stop), daemon=True).start()
    threading.Thread(target=udp_echo_server, args=(HOST_ECHO_PORT, stop), daemon=True).start()
    log("host echo servers up")

    sh("svc power stayon usb")
    sh("input keyevent 82")
    sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
    sh("logcat -c")

    # 导入配置（录屏外等待，慢 runner 不污染成片）
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
        "-d", make_deeplink(), ACT, check=False)
    end = time.time() + 90
    while time.time() < end:
        dismiss_anr()
        adb("shell", "am", "start", "-n", ACT, check=False)
        time.sleep(3.0)
        if find_any("SPP Demo") is not None and find_any("SPP VPN") is not None:
            break
        time.sleep(2.0)
    else:
        dump_texts("preflight")
        raise RuntimeError("main screen not ready")
    log("main screen ready")
    time.sleep(2.0)

    # ============ Part 1: 授权 + 连接 ============
    rec1 = Recorder("part1.mp4")
    rec1.start()
    time.sleep(2.0)
    wait_tap("SPP VPN", timeout=30)
    # 等系统授权弹窗（慢 runner 可能 60s+）
    ok = wait_any("Connection request", "连接请求", timeout=120)
    log("consent dialog visible")
    time.sleep(1.0)
    ok_btn = wait_any("OK", "允许", "确定", timeout=8)
    time.sleep(4.0)  # 让审核员看清弹窗
    if ok_btn is not None:
        tap_node(ok_btn)
    else:
        x, y = 830, 1500
        adb("shell", "input", "tap", str(x), str(y))
        log(f"fallback consent tap ({x},{y})")
    if not wait_tunnel(timeout=120):
        dump_texts("connect")
        raise RuntimeError("did not reach Connected")
    adb("shell", "am", "start", "-n", ACT)
    time.sleep(4.0)  # 绿色已连接状态停留
    rec1.stop()

    # ============ Part 2: 通知/流量/统计/断开 ============
    rec2 = Recorder("part2.mp4")
    rec2.start()

    # 通知栏常驻 VPN
    sh("cmd statusbar expand-notifications"); time.sleep(4.0)
    sh("cmd statusbar collapse"); time.sleep(1.5)

    has_chrome = "package:com.android.chrome" in sh("pm list packages com.android.chrome")
    if has_chrome:
        open_url("https://www.bing.com")
        time.sleep(5.0); dismiss_chrome_dialogs()
        open_url("https://www.bing.com")
        time.sleep(5.0); swipe_up(); time.sleep(1.5)
        open_url("https://www.baidu.com")
        time.sleep(4.0); swipe_up(); time.sleep(1.5)
        for q in ("city+night", "mountain+lake", "ocean+wave"):
            open_url(f"https://www.bing.com/images/search?q={q}&form=HDRSC2")
            time.sleep(1.0)
    else:
        log("Chrome not present, shell traffic only")

    # 持续后台隧道流量 + 回 App
    start_shell_traffic(30)
    adb("shell", "am", "start", "-n", ACT)
    time.sleep(2.0)

    # Sessions 在右上角 ⋮ 溢出菜单内（按钮无 contentDescription，用坐标 + 重试）
    opened = None
    for x, y in ((1010, 145), (985, 165), (1010, 120)):
        adb("shell", "input", "tap", str(x), str(y))
        time.sleep(1.5)
        opened = find_any("Sessions", "当前连接")
        if opened is not None:
            break
    if opened is None:
        dump_texts("overflow")
        raise RuntimeError("overflow menu did not open")
    wait_tap("Sessions", "当前连接", timeout=15)
    log("sessions screen")
    for i in range(5):
        time.sleep(5)
        if has_chrome and i in (1, 3):
            open_url(f"https://www.bing.com/images/search?q=forest+{i}&form=HDRSC2")
            time.sleep(1.5)
            adb("shell", "am", "start", "-n", ACT)
            wait_any("Active connections", "连接", timeout=5)
    log("sessions hold done")

    adb("shell", "input", "keyevent", "4"); time.sleep(1.5)
    wait_tap("Stats", "统计", timeout=10)
    log("stats screen")
    time.sleep(6.0)

    adb("shell", "input", "keyevent", "4"); time.sleep(1.5)
    wait_tap("SPP VPN", timeout=10)
    log("disconnect tapped")
    time.sleep(6.0)

    rec2.stop()
    stop.set()

    # ffmpeg 拼接
    with open("concat.txt", "w") as f:
        f.write("file 'part1.mp4'\nfile 'part2.mp4'\n")
    r = subprocess.run(["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", "concat.txt",
                        "-c", "copy", OUT], capture_output=True, text=True)
    if r.returncode != 0 or not os.path.exists(OUT):
        log("concat -c copy failed, re-encoding")
        r = subprocess.run(["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", "concat.txt",
                            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", OUT],
                           capture_output=True, text=True)
    if not os.path.exists(OUT):
        print(r.stderr[-2000:])
        sys.exit("concat failed")
    log(f"FINAL {OUT} size={os.path.getsize(OUT)}")

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
