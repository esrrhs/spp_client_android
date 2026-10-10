#!/usr/bin/env python3
"""录制 Google Play 审核演示视频：SPP VPN 一键连接 + 实时流量可视化。

一镜到底策略（实际操作连续录屏，不做段内切出）：
  录屏从点击连接前 1.5s 开始，一直录到断开后停留结束，中间不暂停。
  screenrecord 单次硬上限 180s，ContinuousRecorder 到 168s 自动滚动到
  take2.mp4（安全兜底，正常紧凑流程不会触发）；所有分段最后统一拉回。
  本地后期只做掐头去尾 + 字幕叠加，不删除任何操作过程。

流程 MARK 时间戳日志用于后期字幕对齐：
  connect-tap / consent-visible / consent-ok / tunnel-connected /
  notification / chrome-done / sessions-screen / stats-screen /
  disconnect-tap / end
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
PKG = "com.esrrhs.spp.client"
ACT = PKG + "/.MainActivity"
HOST_ECHO_PORT = 19001
# 限速长流地址（ci_record_demo.sh 注入），用于 Sessions 展示持续连接
STREAM_URL = os.environ.get("DEMO_STREAM_URL", "")

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

# ---------------- 连续录屏（自动滚动分段） ----------------
class ContinuousRecorder:
    SEG_SECONDS = 168  # screenrecord 单次上限 180s，留 12s 余量给封装

    def __init__(self):
        self.proc = None
        self.seg = 0
        self.stop_evt = threading.Event()
        self.files = []
        self.thr = None

    def _finalize(self):
        try:
            if self.proc and self.proc.stdin:
                self.proc.stdin.close()
        except Exception:
            pass
        sh("killall -INT screenrecord 2>/dev/null")
        try:
            if self.proc:
                self.proc.wait(timeout=20)
        except Exception:
            sh("killall -INT screenrecord 2>/dev/null")
            try:
                if self.proc:
                    self.proc.wait(timeout=12)
            except Exception:
                pass

    def _run(self):
        while not self.stop_evt.is_set():
            self.seg += 1
            name = f"take{self.seg}.mp4"
            sh(f"rm -f /sdcard/{name}")
            self.proc = subprocess.Popen(
                [ADB, "shell", "screenrecord", "--bit-rate", "12000000",
                 "--time-limit", "179", f"/sdcard/{name}"],
                stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
            deadline = time.time() + self.SEG_SECONDS
            while (not self.stop_evt.is_set()
                   and time.time() < deadline
                   and self.proc.poll() is None):
                time.sleep(0.3)
            self._finalize()
            self.files.append(name)
            time.sleep(0.8)  # 滚动分段时最小化空档

    def start(self):
        self.thr = threading.Thread(target=self._run, daemon=True)
        self.thr.start()
        time.sleep(1.2)
        log("MARK rec-start")

    def stop(self):
        self.stop_evt.set()
        if self.thr:
            self.thr.join(timeout=40)
        time.sleep(2.0)  # 等文件系统 flush
        for name in self.files:
            adb("pull", f"/sdcard/{name}", name)
            ok = False
            for _ in range(4):
                r = subprocess.run(["ffprobe", "-v", "error", name],
                                   capture_output=True, text=True)
                if r.returncode == 0:
                    ok = True
                    break
                log("mp4 not finalized yet, re-pull")
                time.sleep(3.0)
                adb("pull", f"/sdcard/{name}", name)
            if ok:
                log(f"saved {name} ({os.path.getsize(name)} bytes)")
            else:
                log(f"WARNING {name} unusable")

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
    s.bind(("0.0.0.0", port))
    s.settimeout(1.0)
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

def center(node):
    x1, y1, x2, y2 = node_bounds(node)
    return (x1 + x2) // 2, (y1 + y2) // 2

def tap_node(n):
    x, y = center(n)
    label = n.get("text") or n.get("content-desc")
    adb("shell", "input", "tap", str(x), str(y))
    log(f"tap ({x},{y}) '{label}'")

def wait_any(*needles, timeout=60, period=0.7):
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

def anr_subject():
    """ANR 对话框标题（如 'SPP Client isn't responding'），返回卡死主体名。"""
    n = find_any("isn't responding", "未响应")
    if n is None:
        return None
    title = (n.get("text") or n.get("content-desc") or "").strip()
    m = re.match(r"(.+?)\s*(?:isn't responding|未响应)", title)
    return m.group(1).strip() if m else title

def dismiss_anr():
    if find_any("isn't responding", "未响应") is not None:
        n = find_any("Wait", "等待")
        if n is not None:
            tap_node(n)
        return True
    return False

FRE_LABELS = ("Use without an account", "No thanks", "Accept & continue",
              "使用时不登录账号", "不用了", "接受并继续")

def _find_in(ns, *labels):
    labels = [s.lower() for s in labels]
    for n in ns:
        if node_bounds(n) is None:
            continue
        hay = ((n.get("text") or "") + "\n" + (n.get("content-desc") or "")).lower()
        if any(k in hay for k in labels):
            return n
    return None

def dismiss_chrome_dialogs():
    # 单次 uiautomator dump 内完成全部判断；无弹窗时只花一次 dump 开销。
    # uiautomator 在多窗口下给的中心坐标偶尔偏移（实测差 ~150px），
    # 先按节点点，再补已知坐标，直到弹窗消失。
    ns = nodes()
    fre = _find_in(ns, *FRE_LABELS)
    if fre is None:
        allow = _find_in(ns, "Allow", "允许")
        if allow is not None:
            tap_node(allow)
            time.sleep(0.8)
        return True
    groups = [
        (("Use without an account", "使用时不登录账号"), [(540, 2247), (540, 2093)]),
        (("No thanks", "不用了"), None),
        (("Accept & continue", "接受并继续"), [(540, 2100)]),
    ]
    for labels, fallback in groups:
        n = _find_in(nodes(), *labels)
        if n is None:
            continue
        tap_node(n)
        time.sleep(1.0)
        for x, y in (fallback or []):
            if _find_in(nodes(), *labels) is None:
                break
            adb("shell", "input", "tap", str(x), str(y))
            time.sleep(1.0)
    return _find_in(nodes(), *FRE_LABELS) is None

def wait_page(*needles, timeout=12):
    """等网页特征文本出现，确认页面真正加载（而非停在引导页）。"""
    end = time.time() + timeout
    while time.time() < end:
        dismiss_chrome_dialogs()
        if find_any(*needles) is not None:
            return True
        time.sleep(1.0)
    return False

def chrome_present():
    return "package:com.android.chrome" in sh("pm list packages com.android.chrome")

def warmup_chrome():
    """录屏外走完 Chrome 首次引导并预热全部目标页，确保录屏内 VIEW intent 直达网页。"""
    if not chrome_present():
        log("Chrome not present, shell traffic only")
        return False
    log("warmup chrome FRE")
    open_url("https://www.bing.com")
    for _ in range(8):
        time.sleep(2.0)
        if find_any("Use without an account", "No thanks", "Accept & continue",
                    "使用时不登录账号", "不用了", "接受并继续") is None:
            break
        dismiss_chrome_dialogs()
    # Chrome 渲染是 CPU 大头：降其优先级，避免录制中把 App 主线程挤 ANR
    sh("renice -n 8 $(pidof com.android.chrome) 2>/dev/null || true")
    # 预热录制中要访问的三个页面：DNS/TLS/HTTP 缓存变热，录制内加载更快、峰值更低
    open_url("https://www.bing.com")
    wait_page("Search the web", "Images", "Bing", timeout=18)
    open_url("https://www.baidu.com")
    wait_page("百度一下", "百度热搜", timeout=18)
    open_url("https://www.bing.com/images/search?q=city+night&form=HDRSC2")
    wait_page("IMAGES", "Images", "Wallpaper", timeout=18)
    log("chrome warmup done")
    return True

def dump_texts(tag):
    print(f"--- UI DUMP {tag} ---", flush=True)
    for n in nodes():
        t = n.get("text") or n.get("content-desc")
        if t and node_bounds(n) is not None:
            print(f"  UI: {t}", flush=True)
    print("-------------------", flush=True)

def save_logcat(tag):
    """把设备 logcat 拉回宿主文件（ANR 原因/CPU 占用都在里面），随 artifact 上传。"""
    try:
        out = adb("shell", "logcat", "-d", check=False, timeout=60)
        with open(f"{tag}-logcat.txt", "w") as f:
            f.write(out)
        log(f"saved {tag}-logcat.txt ({len(out)} bytes)")
    except Exception as e:
        log(f"save_logcat failed: {e}")

def tunnel_up():
    if find_any("Connected", "已连接") is not None:
        return True
    out = sh("logcat -d -s SppVpnService:I HevTunnel:I").lower()
    return "tun established" in out or "hev tunnel started" in out

def wait_tunnel(timeout=75):
    end = time.time() + timeout
    while time.time() < end:
        dismiss_anr()
        if tunnel_up():
            return True
        time.sleep(0.7)
    return False

def start_shell_traffic(seconds=20):
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
    app_anr = 0       # SPP App 自身连续 ANR 轮数
    other_anr = 0     # Launcher 等其他进程连续 ANR 轮数
    imported = False  # 界面上是否已出现 SPP Demo 配置
    end = time.time() + 200
    while time.time() < end:
        subject = anr_subject()
        if subject is not None:
            if "SPP Client" in subject:
                # App 自身 ANR：先 Wait 一轮；连续两轮仍卡死就强杀冷启动，
                # 否则在 launcher 已禁用的一次性 AVD 上只会无限 Wait 到超时
                app_anr += 1
                other_anr = 0
                if app_anr < 2:
                    dismiss_anr()
                else:
                    log("SPP app ANR persists across rounds; cold-restart app")
                    close = find_any("Close app", "关闭应用")
                    if close is not None:
                        tap_node(close)
                        time.sleep(1.5)
                    sh(f"am force-stop {PKG}")
                    time.sleep(2.0)
                    if imported:
                        adb("shell", "am", "start", "-n", ACT, check=False)
                    else:
                        adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
                            "-d", make_deeplink(), ACT, check=False)
                    time.sleep(2.5)
                    app_anr = 0
            else:
                # Launcher 反复 ANR（冷启动 flaky）：Wait 两次仍复发就杀进程，
                # HOME 会让系统重启 Launcher，再强拉 Activity。
                other_anr += 1
                app_anr = 0
                dismiss_anr()
                if other_anr >= 2:
                    close = find_any("Close app", "关闭应用")
                    if close is not None:
                        tap_node(close)
                        time.sleep(2.0)
                    for pkg in ("com.google.android.apps.nexuslauncher",
                                "com.android.launcher3"):
                        sh(f"am force-stop {pkg}")
                    time.sleep(2.0)
                    other_anr = 0

        if find_any("SPP Demo") is not None:
            imported = True
        if imported and find_any("SPP VPN") is not None:
            break
        # 已导入则只把 Activity 拉前台，避免反复 deeplink 导入+Toast 加重主线程
        if imported:
            adb("shell", "am", "start", "-n", ACT, check=False)
        else:
            adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
                "-d", make_deeplink(), ACT, check=False)
        time.sleep(3.5)
    else:
        dump_texts("preflight")
        save_logcat("preflight")
        raise RuntimeError("main screen not ready")
    log("main screen ready")

    # 录屏外走完 Chrome 首次引导（慢 runner 上引导可能耗 30s+）
    has_chrome = warmup_chrome()
    adb("shell", "am", "start", "-n", ACT)
    time.sleep(1.5)

    # ============ 一镜到底：录屏贯穿全部操作 ============
    rec = ContinuousRecorder()
    rec.start()
    time.sleep(1.5)  # 片头：主界面停留

    # 1) 点击连接
    connect = None
    cend = time.time() + 45
    while time.time() < cend:
        # Chrome 刚预热完系统可能仍在抖动：ANR 弹窗时点 Wait 续命，别直接判死
        dismiss_anr()
        connect = find_any("SPP VPN")
        if connect is not None:
            break
        time.sleep(0.7)
    if connect is None:
        dump_texts("connect-button")
        save_logcat("connect")
        raise RuntimeError("connect button not found")
    tap_node(connect)
    log("MARK connect-tap")

    # 2) 系统 VPN 授权弹窗（停留 3s 给审核员看清）
    if wait_any("Connection request", "连接请求", timeout=90) is None:
        dump_texts("consent")
        raise RuntimeError("consent dialog did not appear")
    log("MARK consent-visible")
    time.sleep(0.8)
    ok_btn = wait_any("OK", "允许", "确定", timeout=8)
    time.sleep(2.8)
    if ok_btn is not None:
        tap_node(ok_btn)
    else:
        adb("shell", "input", "tap", "830", "1500")
        log("fallback consent tap (830,1500)")
    log("MARK consent-ok")

    # 3) 等待隧道建立，回主界面展示已连接
    if not wait_tunnel(timeout=75):
        dump_texts("connect")
        raise RuntimeError("did not reach Connected")
    log("MARK tunnel-connected")
    adb("shell", "am", "start", "-n", ACT)
    time.sleep(2.5)

    # 4) 常驻通知（实时速率）
    sh("cmd statusbar expand-notifications")
    log("MARK notification")
    time.sleep(3.2)
    sh("cmd statusbar collapse")
    time.sleep(1.2)

    # 后台持续造流（覆盖浏览器 + Sessions 展示窗口）
    start_shell_traffic(90)

    # 5) Chrome 真实网页流量：Bing → 百度 → 图片搜索，每页确认真正加载
    if has_chrome:
        open_url("https://www.bing.com")
        wait_page("Search the web", "Images", "Bing", timeout=8)
        time.sleep(1.2); swipe_up(); time.sleep(0.8)
        open_url("https://www.baidu.com")
        wait_page("百度一下", "百度热搜", timeout=8)
        time.sleep(1.2); swipe_up(); time.sleep(0.8)
        open_url("https://www.bing.com/images/search?q=city+night&form=HDRSC2")
        wait_page("IMAGES", "Images", "Wallpaper", timeout=10)
        time.sleep(2.0)
    log("MARK chrome-done")

    # 6) 回 App 看实时连接（Chrome 仍在后台保持连接）
    adb("shell", "am", "start", "-n", ACT)
    time.sleep(1.8)

    # Sessions 在右上角 ⋮ 溢出菜单内
    opened = None
    for x, y in ((1010, 145), (985, 165), (1010, 120)):
        adb("shell", "input", "tap", str(x), str(y))
        time.sleep(1.2)
        opened = find_any("Sessions", "当前连接")
        if opened is not None:
            break
    if opened is None:
        dump_texts("overflow")
        raise RuntimeError("overflow menu did not open")
    wait_tap("Sessions", "当前连接", timeout=15)
    log("MARK sessions-screen")
    time.sleep(2.0)
    if STREAM_URL and has_chrome:
        # 打开限速长流：octet-stream 会触发 Chrome 下载，可能弹 Keep/确认框，
        # 录屏内留足响应时间并处理确认，否则下载不发起、列表无连接
        open_url(STREAM_URL)
        time.sleep(6.0)
        dump_texts("after-stream-open")
        keep = find_any("Keep anyway", "Keep", "Download anyway",
                        "仍然下载", "保留")
        if keep is not None:
            tap_node(keep)
            time.sleep(2.0)
        adb("shell", "am", "start", "-n", ACT)
        wait_any("Active connections", "连接", timeout=8)
        for r in range(6):
            time.sleep(4.0)  # ≈24s 观察字节与速率持续更新
            diag = sh("logcat -d -s ConnRecDiag:I")
            tail = [l for l in diag.splitlines() if "ConnRecDiag" in l][-2:]
            for l in tail:
                log("DIAG " + l.split("ConnRecDiag:", 1)[-1].strip())
    else:
        # 兜底：无长流服务时用图片搜索制造短连接（留痕窗口内可见）
        for i in range(3):
            time.sleep(3.0)
            if has_chrome and i in (0, 1):
                open_url(f"https://www.bing.com/images/search?q=forest+{i}&form=HDRSC2")
                time.sleep(1.0)
                adb("shell", "am", "start", "-n", ACT)
                wait_any("Active connections", "连接", timeout=4)
    log("MARK sessions-done")
    # Chrome 任务结束，防止回退栈把它重新带到前台
    if has_chrome:
        sh("am force-stop com.android.chrome")

    # 7) 流量统计（SPP Demo 卡片 → 弹窗 → Stats）
    adb("shell", "input", "keyevent", "4"); time.sleep(1.2)
    adb("shell", "am", "start", "-n", ACT); time.sleep(1.5)
    wait_tap("SPP Demo", timeout=10)
    time.sleep(1.2)
    wait_tap("Stats", "统计", timeout=10)
    log("MARK stats-screen")
    time.sleep(4.5)

    # 8) 退回主界面，一键断开
    # StatsScreen 已有 BackHandler：一次 BACK 回到内部主界面，不会退出 Activity
    adb("shell", "input", "keyevent", "4"); time.sleep(1.5)
    adb("shell", "am", "start", "-n", ACT); time.sleep(1.2)
    wait_tap("SPP VPN", timeout=10)
    log("MARK disconnect-tap")
    # 系统繁忙时输入事件派发可能延迟数秒：等到真正 Disconnected 再停留，
    # 确保审核能看清断开后的状态
    dseen = False
    dend = time.time() + 12
    while time.time() < dend:
        if find_any("Disconnected", "已断开") is not None:
            dseen = True
            break
        time.sleep(0.7)
    if dseen:
        time.sleep(3.5)
    log("MARK end")

    rec.stop()
    stop.set()
    log(f"DONE segments={rec.files}")

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
        try:
            save_logcat("fatal")
        except Exception:
            pass
        raise
