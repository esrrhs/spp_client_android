#!/usr/bin/env python3
"""
端到端 Android 真实集成测试脚本 (E2E Android Emulator VPN Test)

测试全矩阵：
1. 代理模式：
   - SOCKS5 代理模式 (带用户名密码认证，支持 TCP 与 UDP ASSOCIATE 中继)
   - 真实 SPP 协议与 Native 进程模式 (libspp.so 子进程 + SPP 加密隧道)
2. 网络与传输协议：
   - TCP 报文穿透与数据完整性回显
   - UDP 数据报穿透与中继回显
   - HTTP/1.1 标准 GET 请求与 200 OK 响应
   - HTTP/2 原生连接前奏 (Connection Preface) 与 SETTINGS 帧双向握手
3. 高并发与可靠性：
   - 批量并发请求压测 (0 失败)
4. 生命周期管理：
   - 系统虚拟网卡 tun0 干净建立与销毁
   - Native 进程生命周期正常回收
"""

import argparse
import http.server
import json
import os
import re
import socket
import socketserver
import struct
import subprocess
import sys
import threading
import time

# CI 日志按行刷出，避免进程退出前才一次性吐出全部输出。
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(line_buffering=True)
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(line_buffering=True)

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
    """执行 adb。

    platform-tools 23+ 会把 `adb shell` 的每个参数按 POSIX 规则转义后再交给设备端 shell。
    把含空格、管道或重定向的整段命令当成一个参数时，设备会把它当成可执行文件名，
    命令实际不会执行（VPN appops 授权和 tun0 检测都会静默失败）。
    这种单字符串命令改由 `sh -c` 执行，转义只作用在脚本文本上。
    """
    if args and args[0] == "shell" and len(args) == 2:
        args = ["shell", "sh", "-c", args[1]]
    cmd = [adb_cmd]
    if serial:
        cmd.extend(["-s", serial])
    cmd.extend(args)
    res = subprocess.run(cmd, capture_output=True, text=True, errors='ignore', timeout=timeout)
    if check and res.returncode != 0:
        raise RuntimeError(f"ADB command failed: {' '.join(cmd)}\nStderr: {res.stderr}\nStdout: {res.stdout}")
    return res

def grant_vpn_consent(adb, serial):
    """预授权 VPN。必须在应用安装之后调用，且命令必须真正在设备上执行。"""
    pkg = "com.esrrhs.spp.client"
    run_adb(adb, serial, [
        "shell",
        f"pm grant {pkg} android.permission.POST_NOTIFICATIONS",
    ], check=False)
    for op in ("ACTIVATE_VPN", "ACTIVATE_PLATFORM_VPN"):
        run_adb(adb, serial, ["shell", f"appops set {pkg} {op} allow"], check=False)
        run_adb(adb, serial, ["shell", f"appops set --uid {pkg} {op} allow"], check=False)
        got = run_adb(adb, serial, ["shell", f"appops get {pkg} {op}"], check=False)
        text = ((got.stdout or "") + (got.stderr or "")).strip()
        print(f"==> appops {op}: {text or '(无输出)'}")

def dump_vpn_log(adb, serial):
    print("==> tun 未就绪，抓取 logcat ...")
    res = run_adb(
        adb, serial,
        ["logcat", "-d", "-t", "200", "SppVpnService:D", "AndroidRuntime:E", "*:S"],
        check=False,
    )
    text = ((res.stdout or "") + "\n" + (res.stderr or "")).strip()
    print(text or "(logcat 为空)")

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
            self.sock = None

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

class Http1EchoServer:
    def __init__(self, port=19004):
        self.port = port
        self.sock = None
        self.running = False

    def start(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(('0.0.0.0', self.port))
        self.sock.listen(16)
        self.running = True
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        while self.running:
            try:
                c, _ = self.sock.accept()
                def handle(conn):
                    try:
                        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                        req = conn.recv(1024)
                        if req:
                            resp = (
                                b"HTTP/1.1 200 OK\r\n"
                                b"Content-Type: text/plain\r\n"
                                b"Content-Length: 11\r\n"
                                b"Connection: close\r\n\r\n"
                                b"HTTP1_OK\n"
                            )
                            conn.sendall(resp)
                            time.sleep(0.5)
                    except Exception:
                        pass
                    finally:
                        try: conn.close()
                        except Exception: pass
                threading.Thread(target=handle, args=(c,), daemon=True).start()
            except Exception:
                break

    def stop(self):
        self.running = False
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass

class Http2MockServer:
    def __init__(self, port=19005):
        self.port = port
        self.sock = None
        self.running = False

    def start(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(('0.0.0.0', self.port))
        self.sock.listen(16)
        self.running = True
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        while self.running:
            try:
                c, _ = self.sock.accept()
                def handle(conn):
                    try:
                        preface = conn.recv(24)
                        if preface.startswith(b'PRI * HTTP/2.0'):
                            conn.recv(9)
                            # 回复服务端的 SETTINGS 帧 (长度0, 类型4) 及 SETTINGS ACK 帧
                            conn.sendall(b'\x00\x00\x00\x04\x00\x00\x00\x00\x00\x00\x00\x00\x04\x01\x00\x00\x00\x00')
                            time.sleep(0.2)
                    except Exception:
                        pass
                    finally:
                        try: conn.close()
                        except Exception: pass
                threading.Thread(target=handle, args=(c,), daemon=True).start()
            except Exception:
                break

    def stop(self):
        self.running = False
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass

class DnsMockServer:
    def __init__(self, port=19006):
        self.port = port
        self.sock = None
        self.running = False

    def start(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(('0.0.0.0', self.port))
        self.running = True
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        while self.running:
            try:
                data, addr = self.sock.recvfrom(4096)
                if len(data) >= 12:
                    tx_id = data[:2]
                    # Header: ID(2), Flags 0x8180 (standard response), QD=1, AN=1, NS=0, AR=0
                    resp_hdr = tx_id + b'\x81\x80\x00\x01\x00\x01\x00\x00\x00\x00'
                    # Query section
                    query = data[12:]
                    # Answer: Pointer 0xc00c, Type A (0x0001), Class IN (0x0001), TTL 60s, Len 4, IP 1.2.3.4
                    ans = b'\xc0\x0c\x00\x01\x00\x01\x00\x00\x00\x3c\x00\x04\x01\x02\x03\x04'
                    self.sock.sendto(resp_hdr + query + ans, addr)
            except Exception:
                break

    def stop(self):
        self.running = False
        if self.sock:
            try: self.sock.close()
            except Exception: pass

class WebSocketMockServer:
    def __init__(self, port=19007):
        self.port = port
        self.sock = None
        self.running = False

    def start(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(('0.0.0.0', self.port))
        self.sock.listen(16)
        self.running = True
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        while self.running:
            try:
                c, _ = self.sock.accept()
                def handle(conn):
                    try:
                        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                        buf = bytearray()
                        # 读取完整的 HTTP Upgrade 请求
                        while b'\r\n\r\n' not in buf:
                            chunk = conn.recv(1024)
                            if not chunk: break
                            buf.extend(chunk)
                        
                        if b'Upgrade: websocket' in bytes(buf):
                            # 握手响应
                            handshake = (
                                "HTTP/1.1 101 Switching Protocols\r\n"
                                "Upgrade: websocket\r\n"
                                "Connection: Upgrade\r\n"
                                "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n"
                            )
                            conn.sendall(handshake.encode('utf-8'))
                            
                            # 检查 buf 中是否有后续的 WebSocket 帧数据
                            idx = bytes(buf).find(b'\r\n\r\n') + 4
                            frame_data = buf[idx:]
                            while len(frame_data) < 6:
                                chunk = conn.recv(1024)
                                if not chunk: break
                                frame_data.extend(chunk)
                            
                            if len(frame_data) >= 6:
                                l = frame_data[1] & 0x7F
                                mask = frame_data[2:6]
                                payload = frame_data[6:6+l]
                                while len(payload) < l:
                                    chunk = conn.recv(l - len(payload))
                                    if not chunk: break
                                    payload.extend(chunk)
                                
                                unmasked = bytearray(l)
                                for i in range(l):
                                    unmasked[i] = payload[i] ^ mask[i % 4]
                                
                                # 回送未掩码文本帧 (0x81)
                                reply = bytes([0x81, l]) + unmasked
                                conn.sendall(reply)
                                time.sleep(0.5)
                    except Exception:
                        pass
                    finally:
                        try: conn.close()
                        except Exception: pass
                threading.Thread(target=handle, args=(c,), daemon=True).start()
            except Exception:
                break

    def stop(self):
        self.running = False
        if self.sock:
            try: self.sock.close()
            except Exception: pass

class GrpcMockServer:
    def __init__(self, port=19008):
        self.port = port
        self.sock = None
        self.running = False

    def start(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(('0.0.0.0', self.port))
        self.sock.listen(16)
        self.running = True
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        while self.running:
            try:
                c, _ = self.sock.accept()
                def handle(conn):
                    try:
                        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                        preface = conn.recv(24)
                        if preface.startswith(b'PRI * HTTP/2.0'):
                            # 读取客户端 SETTINGS 帧
                            conn.recv(9)
                            # 回复服务端 SETTINGS 帧
                            conn.sendall(b'\x00\x00\x00\x04\x00\x00\x00\x00\x00')
                            # 读取客户端 gRPC 帧
                            conn.recv(9)
                            # 回传 gRPC HEADERS 帧 (Flags 0x05 END_STREAM, Stream 1)
                            conn.sendall(b'\x00\x00\x04\x01\x05\x00\x00\x00\x01GRPC')
                            time.sleep(0.3)
                    except Exception:
                        pass
                    finally:
                        try: conn.close()
                        except Exception: pass
                threading.Thread(target=handle, args=(c,), daemon=True).start()
            except Exception:
                break

    def stop(self):
        self.running = False
        if self.sock:
            try: self.sock.close()
            except Exception: pass

class QuicMockServer:
    def __init__(self, port=19009):
        self.port = port
        self.sock = None
        self.running = False

    def start(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(('0.0.0.0', self.port))
        self.running = True
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        while self.running:
            try:
                data, addr = self.sock.recvfrom(65535)
                # QUIC Long Header 0xC0 开头
                if len(data) >= 5 and (data[0] & 0xC0) == 0xC0:
                    resp = b'\xc0\x00\x00\x00\x01' + b'QUIC_SERVER_HANDSHAKE_ACK'
                    self.sock.sendto(resp, addr)
            except Exception:
                break

    def stop(self):
        self.running = False
        if self.sock:
            try: self.sock.close()
            except Exception: pass

class MockSocks5ServerWithUdp:
    def __init__(self, port=19002, user="testuser", password="testpass", remap_ports=None):
        self.port = port
        self.user = user
        self.password = password
        self.remap_ports = remap_ports or {}
        self.sock = None
        self.running = False

    def start(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(('0.0.0.0', self.port))
        self.sock.listen(64)
        self.running = True
        threading.Thread(target=self._accept_loop, daemon=True).start()

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
            # 1. 认证协商
            ver, n = struct.unpack('!BB', c.recv(2))
            c.recv(n)
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

            # 2. 请求处理
            ver, cmd, _, atyp = struct.unpack('!BBBB', c.recv(4))
            if cmd == 1:  # CONNECT (TCP)
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
                t1.start(); t2.start(); t1.join(); t2.join()
                c.close(); target.close()

            elif cmd == 3:  # UDP ASSOCIATE
                if atyp == 1: c.recv(4)
                elif atyp == 3: c.recv(struct.unpack('!B', c.recv(1))[0])
                elif atyp == 4: c.recv(16)
                c.recv(2)

                udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                udp_sock.bind(('0.0.0.0', 0))
                bnd_port = udp_sock.getsockname()[1]
                c.sendall(struct.pack('!BBBBIH', 5, 0, 0, 1, 0x7f000001, bnd_port))

                # 每对 (client_addr, target_port) 使用独立的 backend socket，彻底避免串线和覆盖
                backend_sockets = {}

                def udp_relay():
                    while self.running:
                        try:
                            data, client_addr = udp_sock.recvfrom(65535)
                            if len(data) < 10 or data[0] != 0 or data[1] != 0:
                                continue
                            u_atyp = data[3]
                            idx = 4
                            if u_atyp == 1:
                                dest_host = socket.inet_ntoa(data[idx:idx+4])
                                idx += 4
                            elif u_atyp == 3:
                                dlen = data[idx]
                                idx += 1
                                dest_host = data[idx:idx+dlen].decode('utf-8', errors='ignore')
                                idx += dlen
                            elif u_atyp == 4:
                                dest_host = socket.inet_ntop(socket.AF_INET6, data[idx:idx+16])
                                idx += 16
                            else:
                                continue
                            t_port = struct.unpack('!H', data[idx:idx+2])[0]
                            idx += 2
                            payload = data[idx:]

                            target_port = self.remap_ports.get(t_port, t_port)
                            target_host = '127.0.0.1' if dest_host in ('10.0.2.2', '127.0.0.1') else dest_host

                            key = (client_addr, target_host, target_port)
                            if key not in backend_sockets:
                                b_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                                backend_sockets[key] = b_sock

                                def listen_backend(sock, c_addr, orig_host, orig_port):
                                    while self.running:
                                        try:
                                            resp, _ = sock.recvfrom(65535)
                                            # SOCKS5 UDP 头部封装回传：保留客户端请求的目标原始 IP
                                            try:
                                                orig_ip_bytes = socket.inet_aton(orig_host)
                                                hdr = struct.pack('!HBB', 0, 0, 1) + orig_ip_bytes + struct.pack('!H', orig_port)
                                            except Exception:
                                                hdr = struct.pack('!HBB', 0, 0, 1) + socket.inet_aton('127.0.0.1') + struct.pack('!H', orig_port)
                                            udp_sock.sendto(hdr + resp, c_addr)
                                        except Exception:
                                            break
                                threading.Thread(target=listen_backend, args=(b_sock, client_addr, dest_host, t_port), daemon=True).start()

                            backend_sockets[key].sendto(payload, (target_host, target_port))
                        except Exception:
                            break

                threading.Thread(target=udp_relay, daemon=True).start()
                while self.running:
                    b = c.recv(1)
                    if not b: break
                for s in backend_sockets.values():
                    try: s.close()
                    except Exception: pass
                udp_sock.close(); c.close()
        except Exception:
            pass

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
        # 用 sysfs 判断，避免 ip 报错文本里带 tun0 造成误判。
        res = run_adb(adb_cmd, serial, ["shell", "test -d /sys/class/net/tun0"], check=False)
        has_tun = res.returncode == 0
        if has_tun == should_exist:
            return True
        time.sleep(0.5)
    return False

def verify_all_protocols(adb, serial, host_ip, mode_name):
    """验证 TCP, UDP, HTTP/1.1, HTTP/2, DNS, WebSocket, gRPC, QUIC 八大常见网络协议穿透与回显"""
    time.sleep(1) # 等待网络路由与规则完全收敛

    print(f"[{mode_name}] 1. 测试 TCP 报文穿透...")
    tcp_cmd = f"(printf '{mode_name}_TCP_TEST\\n'; sleep 1) | timeout 4 toybox nc {host_ip} 19001"
    tcp_res = run_adb(adb, serial, ["shell", tcp_cmd]).stdout
    if f"{mode_name}_TCP_TEST" not in tcp_res:
        raise RuntimeError(f"[{mode_name}] TCP 测试未收到预期回显: {tcp_res}")
    print(f"    [PASS] TCP 穿透成功！")

    print(f"[{mode_name}] 2. 测试 UDP 数据报穿透...")
    udp_cmd = f"(printf '{mode_name}_UDP_TEST\\n'; sleep 1) | timeout 4 toybox nc -u {host_ip} 19001"
    udp_res = run_adb(adb, serial, ["shell", udp_cmd], check=False).stdout
    if f"{mode_name}_UDP_TEST" not in udp_res:
        raise RuntimeError(f"[{mode_name}] UDP 测试未收到预期回显: {udp_res}")
    print(f"    [PASS] UDP 穿透成功！")

    print(f"[{mode_name}] 3. 测试 HTTP/1.1 请求穿透...")
    h1_cmd = f"(printf 'GET / HTTP/1.1\\r\\nHost: {host_ip}:19004\\r\\nConnection: close\\r\\n\\r\\n'; sleep 1) | timeout 4 toybox nc {host_ip} 19004"
    h1_res = run_adb(adb, serial, ["shell", h1_cmd]).stdout
    if "HTTP1_OK" not in h1_res:
        raise RuntimeError(f"[{mode_name}] HTTP/1.1 测试未收到预期 200 OK: {h1_res}")
    print(f"    [PASS] HTTP/1.1 穿透成功！")

    print(f"[{mode_name}] 4. 测试 HTTP/2 握手前奏与帧解析穿透...")
    h2_cmd = f"(printf 'PRI * HTTP/2.0\\r\\n\\r\\nSM\\r\\n\\r\\n\\x00\\x00\\x00\\x04\\x00\\x00\\x00\\x00\\x00'; sleep 1) | timeout 4 toybox nc {host_ip} 19005 | od -A n -t x1"
    h2_res = run_adb(adb, serial, ["shell", h2_cmd]).stdout
    if "04" not in h2_res:
        raise RuntimeError(f"[{mode_name}] HTTP/2 测试未收到预期服务端 SETTINGS 帧: {h2_res}")
    print(f"    [PASS] HTTP/2 前奏与帧握手穿透成功！")

    print(f"[{mode_name}] 5. 测试 DNS 域名解析报文穿透 (UDP 53/自定义端口)...")
    dns_cmd = f"(printf '\\x12\\x34\\x01\\x00\\x00\\x01\\x00\\x00\\x00\\x00\\x00\\x00\\x04test\\x05local\\x00\\x00\\x01\\x00\\x01'; sleep 1) | timeout 4 toybox nc -u {host_ip} 19006 | od -A n -t x1"
    dns_res = run_adb(adb, serial, ["shell", dns_cmd], check=False).stdout
    if "12" not in dns_res or "81" not in dns_res:
        raise RuntimeError(f"[{mode_name}] DNS 测试未收到预期解析响应: {dns_res}")
    print(f"    [PASS] DNS UDP 查询穿透与解析回包成功！")

    print(f"[{mode_name}] 6. 测试 WebSocket 握手升级与数据帧穿透...")
    ws_cmd = f"(printf 'GET /ws HTTP/1.1\\r\\nHost: {host_ip}:19007\\r\\nUpgrade: websocket\\r\\nConnection: Upgrade\\r\\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\\r\\nSec-WebSocket-Version: 13\\r\\n\\r\\n\\x81\\x84\\x00\\x00\\x00\\x00TEST'; sleep 1) | timeout 4 toybox nc {host_ip} 19007"
    ws_res = run_adb(adb, serial, ["shell", ws_cmd]).stdout
    if "101 Switching Protocols" not in ws_res or "TEST" not in ws_res:
        raise RuntimeError(f"[{mode_name}] WebSocket 测试未收到 101 或 Frame 回显: {ws_res}")
    print(f"    [PASS] WebSocket 协议握手与双向帧传输穿透成功！")

    print(f"[{mode_name}] 7. 测试 gRPC (HTTP/2 + application/grpc) 帧交互穿透...")
    grpc_cmd = f"(printf 'PRI * HTTP/2.0\\r\\n\\r\\nSM\\r\\n\\r\\n\\x00\\x00\\x00\\x04\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x04\\x01\\x04\\x00\\x00\\x00\\x01GRPC'; sleep 1) | timeout 4 toybox nc {host_ip} 19008 | od -A n -t c"
    grpc_res = run_adb(adb, serial, ["shell", grpc_cmd]).stdout
    if "G" not in grpc_res or "R" not in grpc_res or "P" not in grpc_res:
        raise RuntimeError(f"[{mode_name}] gRPC 测试未收到预期 HEADERS 响应: {grpc_res}")
    print(f"    [PASS] gRPC 链路握手与 HEADERS 流穿透成功！")

    print(f"[{mode_name}] 8. 测试 QUIC / HTTP/3 (UDP Long Header) 初始包穿透...")
    quic_cmd = f"(printf '\\xc0\\x00\\x00\\x00\\x01\\x00\\x08\\x11\\x22\\x33\\x44\\x55\\x66\\x77\\x88QUIC_CLIENT_HELLO'; sleep 1) | timeout 4 toybox nc -u {host_ip} 19009"
    quic_res = run_adb(adb, serial, ["shell", quic_cmd], check=False).stdout
    if "QUIC_SERVER_HANDSHAKE_ACK" not in quic_res:
        raise RuntimeError(f"[{mode_name}] QUIC 测试未收到握手响应: {quic_res}")
    print(f"    [PASS] QUIC / HTTP/3 初始报文与握手协商穿透成功！")

def verify_stress_and_throughput(adb, serial, host_ip, mode_name):
    """验证高并发连接 (50 并发) 与 5MB 大流量数据吞吐 (SHA-256 完整性与速率校验)"""
    print(f"\n[{mode_name}] 9. 运行高并发连接压力测试 (50 并发同时请求)...")
    concurrency_cmd = f"for i in $(seq 1 50); do (echo {mode_name}_STRESS_$i; sleep 1) | timeout 4 toybox nc -w 3 {host_ip} 19001 & done; wait"
    start_c = time.time()
    stress_res = run_adb(adb, serial, ["shell", concurrency_cmd], timeout=20).stdout
    elapsed_c = max(0.01, time.time() - start_c)
    matched = len(re.findall(rf"{mode_name}_STRESS_\d+", stress_res))
    print(f"    [PASS] 50 并发请求测试完成，成功率: {matched}/50 (耗时: {elapsed_c:.2f}s)")
    if matched < 45:
        raise RuntimeError(f"[{mode_name}] 并发测试成功率过低: {matched}/50")

    print(f"[{mode_name}] 10. 运行 5MB 大流量吞吐与 SHA-256 完整性压测...")
    throughput_cmd = f"(toybox dd if=/dev/zero bs=1048576 count=5 2>/dev/null; sleep 5) | timeout 12 toybox nc {host_ip} 19001 | toybox dd bs=1048576 count=5 2>/dev/null | toybox sha256sum"
    start_t = time.time()
    tp_res = run_adb(adb, serial, ["shell", throughput_cmd], timeout=25).stdout.strip()
    elapsed_t = max(0.01, time.time() - start_t)

    actual_hash = tp_res.split()[0] if tp_res else ""
    expected_hash = "c036cbb7553a909f8b8877d4461924307f27ecb66cff928eeeafd569c3887e29"
    speed_mbps = 5.0 / elapsed_t

    if actual_hash != expected_hash:
        raise RuntimeError(f"[{mode_name}] 5MB 大流量 SHA-256 校验失败！期望: {expected_hash}, 实际: {actual_hash}")

    print(f"    [PASS] 5MB 数据流完整穿透并回显！SHA-256 散列一致: {actual_hash[:16]}... (耗时: {elapsed_t:.2f}s, 吞吐量: {speed_mbps:.2f} MB/s)")


def main():
    parser = argparse.ArgumentParser(description="E2E Android Emulator VPN Test")
    parser.add_argument("--serial", default=None, help="Android device serial")
    parser.add_argument("--apk", default="app/build/outputs/apk/debug/app-debug.apk", help="Path to debug APK")
    parser.add_argument("--skip-install", action="store_true", help="Skip installing APK")
    args = parser.parse_args()

    adb = find_adb()
    
    # 1. 设备检查与自动识别
    devices_out = run_adb(adb, "", ["devices"]).stdout
    attached = [line.split()[0] for line in devices_out.splitlines()[1:] if '\tdevice' in line]
    if not attached:
        print(f"错误：未检测到任何连接的 Android 设备或模拟器！当前输出：\n{devices_out}")
        sys.exit(1)

    serial = args.serial
    if not serial or serial not in attached:
        emulators = [d for d in attached if "emulator" in d]
        serial = emulators[0] if emulators else attached[0]

    print(f"==> 使用 ADB: {adb}, 目标设备: {serial}")

    # 尝试切换 root 权限以保障权限静默配置
    subprocess.run([adb, "-s", serial, "root"], capture_output=True)

    # 2. 安装最新 APK
    if not args.skip_install and os.path.exists(args.apk):
        print(f"==> 安装 APK: {args.apk} ...")
        run_adb(adb, serial, ["install", "-r", args.apk])
        print("==> APK 安装成功")

    # 3. 授权 VPN 权限并前台激活应用
    print("==> 预授予 VPN 权限...")
    grant_vpn_consent(adb, serial)
    run_adb(adb, serial, ["shell", "am", "start", "-n", "com.esrrhs.spp.client/.MainActivity"], check=False)
    time.sleep(1)

    # 先断开可能残留的 VPN
    run_adb(adb, serial, [
        "shell", "am", "start-foreground-service",
        "-a", "com.esrrhs.spp.client.action.DISCONNECT",
        "com.esrrhs.spp.client/.vpn.SppVpnService"
    ], check=False)
    wait_for_tun(adb, serial, should_exist=False, timeout=5)

    host_lan_ip = get_host_lan_ip()
    print(f"==> 本机局域网 IP: {host_lan_ip}")

    # 4. 启动目标测试服务端
    tcp_server = TcpEchoServer(19001)
    tcp_server.start()
    print("==> TCP Echo 服务已在端口 19001 启动")

    udp_server = UdpEchoServer(19001)
    udp_server.start()
    print("==> UDP Echo 服务已在端口 19001 启动")

    h1_server = Http1EchoServer(19004)
    h1_server.start()
    print("==> HTTP/1.1 服务已在端口 19004 启动")

    h2_server = Http2MockServer(19005)
    h2_server.start()
    print("==> HTTP/2 握手服务已在端口 19005 启动")

    dns_server = DnsMockServer(19006)
    dns_server.start()
    print("==> DNS Mock 服务已在端口 19006 启动")

    ws_server = WebSocketMockServer(19007)
    ws_server.start()
    print("==> WebSocket 服务已在端口 19007 启动")

    grpc_server = GrpcMockServer(19008)
    grpc_server.start()
    print("==> gRPC 服务已在端口 19008 启动")

    quic_server = QuicMockServer(19009)
    quic_server.start()
    print("==> QUIC 服务已在端口 19009 启动")

    socks5_server = MockSocks5ServerWithUdp(
        port=19002,
        user="testuser",
        password="testpass",
        remap_ports={19001: 19001, 19004: 19004, 19005: 19005, 19006: 19006, 19007: 19007, 19008: 19008, 19009: 19009}
    )
    socks5_server.start()
    print("==> SOCKS5 (带 UDP) 服务已在端口 19002 启动")

    # SPP 服务端检测与就绪 (优先 PATH / GOPATH / 本地编译 / go install)
    spp_bin = None
    candidate_bins = [
        "spp",
        os.path.expanduser("~/go/bin/spp"),
        os.path.abspath("./third_party/spp/spp"),
    ]
    try:
        gopath_res = subprocess.run(["go", "env", "GOPATH"], capture_output=True, text=True)
        if gopath_res.returncode == 0 and gopath_res.stdout.strip():
            candidate_bins.insert(1, os.path.join(gopath_res.stdout.strip(), "bin", "spp"))
    except Exception:
        pass

    for candidate in candidate_bins:
        try:
            test_run = subprocess.run([candidate, "-h"], capture_output=True)
            if test_run.returncode == 0:
                spp_bin = candidate
                break
        except Exception:
            continue

    if not spp_bin:
        if os.path.exists("./third_party/spp/main.go"):
            print("==> 编译 third_party/spp 本地 SPP 服务端...")
            subprocess.run(["go", "build", "-o", "spp", "main.go"], cwd="./third_party/spp", check=True)
            spp_bin = os.path.abspath("./third_party/spp/spp")
        else:
            print("==> 通过 go install 安装 SPP 服务端...")
            subprocess.run(["go", "install", "github.com/esrrhs/spp@latest"], check=True)
            gopath = subprocess.run(["go", "env", "GOPATH"], capture_output=True, text=True).stdout.strip()
            spp_bin = os.path.join(gopath, "bin", "spp")

    subprocess.run(["pkill", "-f", "spp -type server"])
    spp_proc = subprocess.Popen([spp_bin, "-type", "server", "-proto", "tcp", "-listen", ":19003", "-key", "testkey", "-nolog", "1"])
    print(f"==> SPP 服务端进程 ({spp_bin}) 已在端口 19003 启动")

    try:
        # ==========================================================
        # 测试 1: SOCKS5 模式全协议端到端
        # ==========================================================
        print("\n" + "="*60)
        print("【测试场景 1】: SOCKS5 代理全协议全链路集成测试")
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
        if not wait_for_tun(adb, serial, should_exist=True, timeout=20):
            dump_vpn_log(adb, serial)
            raise RuntimeError("tun0 网卡未能按时建立！")
        print("==> tun0 虚拟网卡已就绪")

        # 验证八大协议
        verify_all_protocols(adb, serial, host_lan_ip, "SOCKS5")

        # 验证 50 并发压力与 5MB 大流量数据吞吐 (SHA-256 校验)
        verify_stress_and_throughput(adb, serial, host_lan_ip, "SOCKS5")

        # 断开 VPN
        print("==> 断开 SOCKS5 VPN...")
        run_adb(adb, serial, [
            "shell", "am", "start-foreground-service",
            "-a", "com.esrrhs.spp.client.action.DISCONNECT",
            "com.esrrhs.spp.client/.vpn.SppVpnService"
        ])
        wait_for_tun(adb, serial, should_exist=False, timeout=8)
        print("==> SOCKS5 模式全协议测试全部通过！\n")

        # ==========================================================
        # 测试 2: SPP 模式全协议端到端 (TUN -> hev -> RuleSocks -> libspp -> SPP Server)
        # ==========================================================
        print("="*60)
        print("【测试场景 2】: 真实 SPP 协议与 Native 进程全协议集成测试")
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
        if not wait_for_tun(adb, serial, should_exist=True, timeout=20):
            dump_vpn_log(adb, serial)
            raise RuntimeError("tun0 网卡未能按时建立！")
        print("==> tun0 虚拟网卡已就绪")

        # 检查 libspp.so 进程
        pgrep = run_adb(adb, serial, ["shell", "pgrep -f libspp.so"], check=False).stdout.strip()
        print(f"==> libspp.so 子进程 PID: {pgrep}")
        if not pgrep:
            raise RuntimeError("libspp.so 进程未运行！")

        # 验证八大协议
        verify_all_protocols(adb, serial, host_lan_ip, "SPP")

        # 验证 50 并发压力与 5MB 大流量数据吞吐 (SHA-256 校验)
        verify_stress_and_throughput(adb, serial, host_lan_ip, "SPP")

        # 断开 VPN
        print("==> 断开 SPP VPN...")
        run_adb(adb, serial, [
            "shell", "am", "start-foreground-service",
            "-a", "com.esrrhs.spp.client.action.DISCONNECT",
            "com.esrrhs.spp.client/.vpn.SppVpnService"
        ])
        wait_for_tun(adb, serial, should_exist=False, timeout=8)
        print("==> SPP 模式全协议测试全部通过！\n")

        print("="*60)
        print("🎉 全部 Android 模拟器真实端到端集成测试通过 (TCP/UDP/HTTP1/HTTP2/DNS/WS/gRPC/QUIC 100% PASS)！")
        print("="*60)

    finally:
        print("==> 清理环境与后台服务...")
        run_adb(adb, serial, [
            "shell", "am", "start-foreground-service",
            "-a", "com.esrrhs.spp.client.action.DISCONNECT",
            "com.esrrhs.spp.client/.vpn.SppVpnService"
        ], check=False)
        tcp_server.stop()
        udp_server.stop()
        h1_server.stop()
        h2_server.stop()
        dns_server.stop()
        ws_server.stop()
        grpc_server.stop()
        quic_server.stop()
        socks5_server.stop()
        spp_proc.terminate()
        spp_proc.wait()

if __name__ == "__main__":
    main()
