#!/usr/bin/env bash
# 生成中国大陆 IPv4 CIDR 列表（chnroute）到 app assets，供「CN 直连」智能分流使用。
# 数据源：APNIC delegated stats（https://ftp.apnic.net/stats/apnic/delegated-apnic-latest）
# 用法：./scripts/build_chnroute.sh [delegated-stats-file]
#   无参数时自动下载（已缓存则离线复用）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$ROOT/third_party/delegated-apnic-latest"
OUT_DIR="$ROOT/app/src/main/assets"
OUT="$OUT_DIR/cn_ipv4_cidr.txt"
mkdir -p "$(dirname "$CACHE")" "$OUT_DIR"

if [ "${1:-}" != "" ]; then
    SRC="$1"
elif [ -f "$CACHE" ]; then
    # 存在缓存：默认离线复用；需更新时删除该文件再跑
    SRC="$CACHE"
else
    URL="https://ftp.apnic.net/stats/apnic/delegated-apnic-latest"
    echo "==> 下载 $URL"
    if command -v curl >/dev/null; then
        curl -fsSL --retry 3 -o "$CACHE" "$URL"
    else
        wget -q -O "$CACHE" "$URL"
    fi
    SRC="$CACHE"
fi

# 提取 CN IPv4 分配段：registry|CC|type|start|value(cnt for v4)|...
# 用内置 Go 程序把 (start, count) 区间转成最小 CIDR 列表
TMP_GEN="$(mktemp -d)/gen.go"
cat > "$TMP_GEN" <<'EOF'
package main

import (
	"bufio"
	"fmt"
	"os"
	"sort"
	"strings"
)

type cidr struct{ ip uint32; prefix int }

func ipToUint32(s string) uint32 {
	var v uint32
	for _, p := range strings.Split(s, ".") {
		var n uint32
		fmt.Sscanf(p, "%d", &n)
		v = v<<8 | n
	}
	return v
}

func main() {
	f, _ := os.Open(os.Args[1])
	defer f.Close()

	seen := map[uint32]bool{}
	var ips []uint32
	type rng struct{ start, count uint32 }
	var ranges []rng

	sc := bufio.NewScanner(f)
	for sc.Scan() {
		f := strings.Split(sc.Text(), "|")
		if len(f) < 5 || f[1] != "CN" || f[2] != "ipv4" {
			continue
		}
		var count uint32
		fmt.Sscanf(f[4], "%d", &count)
		ranges = append(ranges, rng{ipToUint32(f[3]), count})
	}

	// 区间转最小 CIDR
	var out []cidr
	for _, r := range ranges {
		ip, remaining := r.start, r.count
		for remaining > 0 {
			// 受限于数量与对齐的最大 2^n 块
			block := uint32(1)
			for block<<1 <= remaining && ip%(block<<1) == 0 {
				block <<= 1
			}
			prefix := 32
			for b := block; b > 1; b >>= 1 {
				prefix--
			}
			if !seen[ip] {
				seen[ip] = true
				out = append(out, cidr{ip, prefix})
				ips = append(ips, ip)
			}
			ip += block
			remaining -= block
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ip < out[j].ip })

	w := bufio.NewWriter(os.Stdout)
	defer w.Flush()
	for _, c := range out {
		fmt.Fprintf(w, "%d.%d.%d.%d/%d\n",
			(c.ip>>24)&255, (c.ip>>16)&255, (c.ip>>8)&255, c.ip&255, c.prefix)
	}
	fmt.Fprintf(os.Stderr, "CN cidrs: %d\n", len(out))
}
EOF

go run "$TMP_GEN" "$SRC" > "$OUT"
rm -rf "$(dirname "$TMP_GEN")"

echo "==> 已生成 $OUT ($(wc -l < "$OUT") prefixes, $(du -h "$OUT" | cut -f1))"
