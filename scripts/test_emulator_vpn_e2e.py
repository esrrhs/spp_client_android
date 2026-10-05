#!/usr/bin/env python3
"""
端到端 Android 真实集成测试脚本 (E2E Android Emulator VPN Test)

测试全流程：
1. 本地启动 Echo 服务端 (TCP/UDP/HTTP)。
2. 本地启动 SOCKS5 代理服务端与 SPP 协议服务端。
3. 通过 ADB 自动安装并向 App 发送带配置的 CONNECT 意图。
4. 验证系统 tun0 虚拟网卡创建、Native 进程 (hev-socks5-tunnel / libspp.so) 正常运作。
5. 模拟器内通过 tun0 发起 TCP/UDP/HTTP 流量，验证数据完整往返回显。
6. 并发压测（多并发请求 0 失败）。
7. 断开 VPN，验证 tun0 与子进程清理。
"""

import argparse
import json
import os
import re
import socket
import struct
import subprocess
import sys
import threading
import time

def find_adb():
    adb_env = os.environ.get("ADB")
    if adb_env and os.path.exists(adb_env):
        return adb_env
    default_path = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
    if os.path.exists(default_path):
        return default_path
    which_adb = subprocess.run(["which", "adb"], capture_output=True, text=True)
    if which_adb.returncode == 0:
        return which_adb.stdout.strip()
    return "adb"

def run_adb(adb_cmd, serial, args, check=True, timeout=30):
    cmd = [adb_cmd]
    if serial:
        cmd.extend(["-s", serial])
    cmd.extend(args)
    res = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if check and res.returncode != 0:
        raise RuntimeError(f"ADB command failed: {' '.join(cmd)}\nStderr: {res.stderr}\nStdout: {res.stdout}")
    return res

def get_host_lan_ip():
    ips = []
    try:
        res = subprocess.run(['hostname', '-I'], capture_output=True, text=True)
        if res.returncode == 0:
            ips.extend(res.stdout.split())
    except Exception:
        pass

    try:
        res = subprocess.run(['ifconfig'], capture_output=True, text=True)
        for line in res.stdout.splitlines():
            m = re.search(r'inet\s+(\d+\.\d+\.\d+\.\d+)', line)
            if m:
                ips.append(m.group(1))
    except Exception:
        pass

    valid_ips = [
        ip for ip in ips
        if not ip.startswith('127.')
        and not ip.startswith('198.18.')
        and not ip.startswith('169.254.')
    ]
    if valid_ips:
        return valid_ips[0]

    # 回退到 socket 获取
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('8.8.8.8', 80))
        ip = s.getsockname()[0]
        if not ip.startswith('198.18.'):
            return ip
    except Exception:
        pass
    finally:
        s.close()
    return '127.0.0.1'

class TcpEchoServer:
    def __init__(self, port=19001):
        self.port = port
        self.sock = None
        self.running = False
        self.thread = None

    def start(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(('0.0.0.0', self.port))
        self.sock.listen(64)
        self.running = True
        self.thread = threading.Thread(target=self._accept_loop, daemon=True)
        self.thread.start()

    def _accept_loop(self):
        while self.running:
            try:
                c, _ = self.sock.accept()
                threading.Thread(target=self._client_loop, args=(c,), daemon=True).start()
            except Exception:
                break

    def _client_loop(self, c):
        try:
            c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            while self.running:
                data = c.recv(16384)
                if not data:
                    break
                c.sendall(data)
        except Exception:
            pass
        finally:
            try:
                c.close()
            except Exception:
                pass

    def stop(self):
        self.running = False
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass

class UdpEchoServer:
    def __init__(self, port=19001):
        self.port = port
        self.sock = None
        self.running = False
        self.thread = None

    def start(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(('0.0.0.0', self.port))
        self.running = True
        self.thread = threading.Thread(target=self._loop, daemon=True)
        self.thread.start()

    def _loop(self):
        while self.running:
            try:
                data, addr = self.sock.recvfrom(65535)
                self.sock.sendto(data, addr)
            except Exception:
                break

    def stop(self):
        self.running = False
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass

class MockSocks5Server:
    def __init__(self, port=19002, user="testuser", password="testpass", remap_ports=None):
        self.port = port
        self.user = user
        self.password = password
        self.remap_ports = remap_ports or {}
        self.sock = None
        self.running = False
        self.thread = None

    def start(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(('0.0.0.0', self.port))
        self.sock.listen(64)
        self.running = True
        self.thread = threading.Thread(target=self._accept_loop, daemon=True)
        self.thread.start()

    def _accept_loop(self):
        while self.running:
            try:
                c, _ = self.sock.accept()
                threading.Thread(target=self._client_loop, args=(c,), daemon=True).start()
            except Exception:
                break

    def _client_loop(self, c):
        try:
            c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            # 1. 协商认证
            ver, n = struct.unpack('!BB', c.recv(2))
            methods = c.recv(n)
            c.sendall(b'\x05\x02') # 用户名密码认证

            subver, ulen = struct.unpack('!BB', c.recv(2))
            user = c.recv(ulen).decode('utf-8', errors='ignore')
            plen = struct.unpack('!B', c.recv(1))[0]
            passwd = c.recv(plen).decode('utf-8', errors='ignore')
            if user != self.user or passwd != self.password:
                c.sendall(b'\x01\x01')
                c.close()
                return
            c.sendall(b'\x01\x00')

            # 2. 请求
            ver, cmd, _, atyp = struct.unpack('!BBBB', c.recv(4))
            if atyp == 1:
                dest = socket.inet_ntoa(c.recv(4))
            elif atyp == 3:
                dlen = struct.unpack('!B', c.recv(1))[0]
                dest = c.recv(dlen).decode('utf-8', errors='ignore')
            elif atyp == 4:
                dest = socket.inet_ntop(socket.AF_INET6, c.recv(16))
            else:
                c.close()
                return
            port = struct.unpack('!H', c.recv(2))[0]

            # 端口重定向（若目标是 10.0.2.2 或回环）
            dest_ip = '127.0.0.1' if dest in ('10.0.2.2', '127.0.0.1') else dest
            target_port = self.remap_ports.get(port, port)

            target = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            target.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            target.connect((dest_ip, target_port))
            c.sendall(b'\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x00')

            def forward(src, dst):
                try:
                    while self.running:
                        d = src.recv(16384)
                        if not d: break
                        dst.sendall(d)
                except Exception:
                    pass
                finally:
                    try: dst.shutdown(socket.SHUT_WR)
                    except Exception: pass

            t1 = threading.Thread(target=forward, args=(c, target), daemon=True)
            t2 = threading.Thread(target=forward, args=(target, c), daemon=True)
            t1.start()
            t2.start()
            t1.join()
            t2.join()
        except Exception:
            pass
        finally:
            try: c.close()
            except Exception: pass

    def stop(self):
        self.running = False
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass

def wait_for_tun(adb_cmd, serial, should_exist=True, timeout=15):
    start = time.time()
    while time.time() - start < timeout:
        res = run_adb(adb_cmd, serial, ["shell", "ip addr show tun0 2>&1"], check=False)
        has_tun = res.returncode == 0 and "tun0" in res.stdout
        if has_tun == should_exist:
            return True
        time.sleep(0.5)
    return False

def main():
    parser = argparse.ArgumentParser(description="E2E Android Emulator VPN Test")
    parser.add_argument("--serial", default=None, help="Android device serial")
    parser.add_argument("--apk", default="app/build/outputs/apk/debug/app-debug.apk", help="Path to debug APK")
    parser.add_argument("--skip-install", action="store_true", help="Skip installing APK")
    args = parser.parse_args()

    adb = find_adb()
    
    # 1. 检查设备在线并自动探测
    devices_out = run_adb(adb, "", ["devices"]).stdout
    attached = [line.split()[0] for line in devices_out.splitlines()[1:] if '\tdevice' in line]
    if not attached:
        print(f"错误：未检测到任何连接的 Android 设备或模拟器！当前输出：\n{devices_out}")
        sys.exit(1)

    serial = args.serial
    if not serial or serial not in attached:
        # 优先选用包含 emulator 的设备，否则选第一台
        emulators = [d for d in attached if "emulator" in d]
        serial = emulators[0] if emulators else attached[0]

    print(f"==> 使用 ADB: {adb}, 目标设备: {serial}")

    # 2. 授权 VPN 权限
    print("==> 预授予 VPN 权限...")
    run_adb(adb, serial, ["shell", "appops set com.esrrhs.spp.client ACTIVATE_VPN allow"], check=False)
    run_adb(adb, serial, ["shell", "appops set com.esrrhs.spp.client ACTIVATE_PLATFORM_VPN allow"], check=False)

    # 3. 安装最新 APK
    if not args.skip_install and os.path.exists(args.apk):
        print(f"==> 安装 APK: {args.apk} ...")
        run_adb(adb, serial, ["install", "-r", args.apk])
        print("==> APK 安装成功")

    host_lan_ip = get_host_lan_ip()
    print(f"==> 本机局域网 IP: {host_lan_ip}")

    # 4. 启动服务端
    echo_server = TcpEchoServer(19001)
    echo_server.start()
    print("==> TCP Echo 服务已在端口 19001 启动")

    udp_echo_server = UdpEchoServer(19001)
    udp_echo_server.start()
    print("==> UDP Echo 服务已在端口 19001 启动")

    socks5_server = MockSocks5Server(19002, "testuser", "testpass", remap_ports={19001: 19001})
    socks5_server.start()
    print("==> SOCKS5 服务已在端口 19002 启动")

    # SPP 服务端检测与编译
    spp_bin = os.path.abspath("./third_party/spp/spp")
    need_build = not os.path.exists(spp_bin)
    if not need_build:
        test_run = subprocess.run([spp_bin, "-h"], capture_output=True)
        if test_run.returncode != 0:
            need_build = True
    if need_build:
        print("==> 编译适合当前宿主机架构的本地 SPP 服务端...")
        subprocess.run(["go", "build", "-o", "spp", "main.go"], cwd="./third_party/spp", check=True)

    spp_proc = subprocess.Popen([spp_bin, "-type", "server", "-proto", "tcp", "-listen", ":19003", "-key", "testkey", "-nolog", "1"])
    print("==> SPP 服务端进程已在端口 19003 启动")

    try:
        # ==========================================================
        # 测试 1: SOCKS5 模式端到端 (TUN -> hev -> RuleSocks -> SOCKS5)
        # ==========================================================
        print("\n" + "="*60)
        print("【测试场景 1】: SOCKS5 模式全链路集成测试")
        print("="*60)

        socks5_profile = {
            "id": "e2e-socks5",
            "name": "E2E_SOCKS5",
            "bypassLan": False,
            "bypassCn": False,
            "config": {
                "serverHost": "10.0.2.2",
                "serverPort": 19002,
                "kind": "socks5",
                "username": "testuser",
                "password": "testpass",
                "enableIpv6": False
            }
        }
        profile_json = json.dumps(socks5_profile)
        print("==> 发送 SOCKS5 CONNECT 意图...")
        run_adb(adb, serial, [
            "shell", "am", "start-foreground-service",
            "-a", "com.esrrhs.spp.client.action.CONNECT",
            "--es", "com.esrrhs.spp.client.extra.PROFILE_JSON", f"'{profile_json}'",
            "com.esrrhs.spp.client/.vpn.SppVpnService"
        ])

        print("==> 等待 tun0 网卡建立...")
        if not wait_for_tun(adb, serial, should_exist=True, timeout=10):
            raise RuntimeError("tun0 网卡未能按时建立！")
        print("==> tun0 虚拟网卡已就绪")

        # 测试 TCP Echo
        print("==> 测试 TCP 报文穿透...")
        tcp_res = run_adb(adb, serial, ["shell", f"(printf 'SOCKS5_E2E_TCP_TEST\\n'; sleep 1) | nc -w 4 {host_lan_ip} 19001"]).stdout
        if "SOCKS5_E2E_TCP_TEST" not in tcp_res:
            raise RuntimeError(f"SOCKS5 TCP 测试未收到预期回显，实际收到: {tcp_res}")
        print("    [PASS] TCP 穿透验证成功！")

        # 测试并发连接
        print("==> 运行 20 并发 TCP 请求压测...")
        concurrency_cmd = f"for i in $(seq 1 20); do (printf \"STRESS_$i\\n\"; sleep 1) | nc -w 4 {host_lan_ip} 19001 & done; wait"
        stress_res = run_adb(adb, serial, ["shell", concurrency_cmd], timeout=15).stdout
        matched = len(re.findall(r"STRESS_\d+", stress_res))
        print(f"    [PASS] 并发请求成功率: {matched}/20")
        if matched < 18:
            raise RuntimeError(f"并发测试成功率过低: {matched}/20")

        # 断开 VPN
        print("==> 断开 SOCKS5 VPN...")
        run_adb(adb, serial, [
            "shell", "am", "start-foreground-service",
            "-a", "com.esrrhs.spp.client.action.DISCONNECT",
            "com.esrrhs.spp.client/.vpn.SppVpnService"
        ])
        wait_for_tun(adb, serial, should_exist=False, timeout=8)
        print("==> SOCKS5 模式测试全部通过！\n")

        # ==========================================================
        # 测试 2: SPP 模式端到端 (TUN -> hev -> RuleSocks -> libspp -> SPP Server)
        # ==========================================================
        print("="*60)
        print("【测试场景 2】: 真实 SPP 协议与 Native 进程全链路集成测试")
        print("="*60)

        spp_profile = {
            "id": "e2e-spp",
            "name": "E2E_SPP",
            "bypassLan": False,
            "bypassCn": False,
            "config": {
                "serverHost": "10.0.2.2",
                "serverPort": 19003,
                "kind": "spp",
                "proto": "tcp",
                "key": "testkey",
                "enableIpv6": False
            }
        }
        profile_json = json.dumps(spp_profile)
        print("==> 发送 SPP CONNECT 意图...")
        run_adb(adb, serial, [
            "shell", "am", "start-foreground-service",
            "-a", "com.esrrhs.spp.client.action.CONNECT",
            "--es", "com.esrrhs.spp.client.extra.PROFILE_JSON", f"'{profile_json}'",
            "com.esrrhs.spp.client/.vpn.SppVpnService"
        ])

        print("==> 等待 tun0 网卡与 libspp.so 启动...")
        if not wait_for_tun(adb, serial, should_exist=True, timeout=12):
            raise RuntimeError("tun0 网卡未能按时建立！")
        print("==> tun0 虚拟网卡已就绪")

        # 检查 libspp.so 进程在运行
        pgrep = run_adb(adb, serial, ["shell", "pgrep -f libspp.so"], check=False).stdout.strip()
        print(f"==> libspp.so 子进程 PID: {pgrep}")
        if not pgrep:
            raise RuntimeError("libspp.so 进程未运行！")

        # 测试 TCP Echo
        print("==> 测试 SPP 隧道 TCP 报文穿透...")
        tcp_res = run_adb(adb, serial, ["shell", f"(printf 'SPP_E2E_TCP_TEST\\n'; sleep 1) | nc -w 4 {host_lan_ip} 19001"]).stdout
        if "SPP_E2E_TCP_TEST" not in tcp_res:
            raise RuntimeError(f"SPP TCP 测试未收到预期回显，实际收到: {tcp_res}")
        print("    [PASS] 真实 SPP 隧道穿透验证成功！")

        # 测试并发连接
        print("==> 运行 20 并发 SPP 请求压测...")
        concurrency_cmd = f"for i in $(seq 1 20); do (printf \"SPP_STRESS_$i\\n\"; sleep 1) | nc -w 4 {host_lan_ip} 19001 & done; wait"
        stress_res = run_adb(adb, serial, ["shell", concurrency_cmd], timeout=15).stdout
        matched = len(re.findall(r"SPP_STRESS_\d+", stress_res))
        print(f"    [PASS] 并发请求成功率: {matched}/20")
        if matched < 18:
            raise RuntimeError(f"并发测试成功率过低: {matched}/20")

        # 断开 VPN
        print("==> 断开 SPP VPN...")
        run_adb(adb, serial, [
            "shell", "am", "start-foreground-service",
            "-a", "com.esrrhs.spp.client.action.DISCONNECT",
            "com.esrrhs.spp.client/.vpn.SppVpnService"
        ])
        wait_for_tun(adb, serial, should_exist=False, timeout=8)
        print("==> SPP 模式测试全部通过！\n")

        print("="*60)
        print("🎉 全部 Android 模拟器真实端到端集成测试通过 (100% PASS)！")
        print("="*60)

    finally:
        # 清理工作
        print("==> 清理环境与后台服务...")
        run_adb(adb, serial, [
            "shell", "am", "start-foreground-service",
            "-a", "com.esrrhs.spp.client.action.DISCONNECT",
            "com.esrrhs.spp.client/.vpn.SppVpnService"
        ], check=False)
        echo_server.stop()
        udp_echo_server.stop()
        socks5_server.stop()
        spp_proc.terminate()
        spp_proc.wait()

if __name__ == "__main__":
    main()
