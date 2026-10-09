#!/usr/bin/env python3
"""演示用限速长流 HTTP 服务：让 Chrome 在 Sessions 展示期间持有一条
持续的 TCP 连接（字节数与速率持续增长）。

用法: python3 demo_stream_server.py [port] [chunk_kb] [interval_s] [total_mb]
所有路径都返回同一份 octet-stream 响应。
"""
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 19080
CHUNK = int(sys.argv[2]) if len(sys.argv) > 2 else 64          # KB
INTERVAL = float(sys.argv[3]) if len(sys.argv) > 3 else 0.25  # s
TOTAL_MB = int(sys.argv[4]) if len(sys.argv) > 4 else 60


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        total = TOTAL_MB * 1024 * 1024
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(total))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        chunk = b"\0" * (CHUNK * 1024)
        sent = 0
        try:
            while sent < total:
                self.wfile.write(chunk)
                self.wfile.flush()
                sent += len(chunk)
                time.sleep(INTERVAL)  # ~CHUNK/INTERVAL KB/s，制造长连接
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print(f"stream server on 0.0.0.0:{PORT} (~{CHUNK/INTERVAL:.0f}KB/s, {TOTAL_MB}MB cap)",
          flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
