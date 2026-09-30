# spp-client-android

基于 [SPP](https://github.com/esrrhs/spp) 的 Android VPN 客户端。

目标很简单：**在手机上点一下按钮开启 VPN，整机流量（含 DNS）全部走 SPP Server。**

> 仅供学习、研究与授权测试使用。请勿用于未授权或违法用途。

---

## 产品目标

| 能力 | 说明 |
|------|------|
| 一键 VPN | 主界面一个开关，开启/关闭系统级 VPN |
| 全流量代理 | `0.0.0.0/0`（可选 IPv6）经 TUN 接管 |
| DNS 走代理 | 系统 DNS 指向虚拟地址，查询经 SOCKS5/UDP ASSOCIATE 由服务端解析，避免 DNS 泄漏 |
| 对接 SPP Server | 本地运行 `socks5_client`，经 SPP 隧道连到远端 server |
| 防环路 | `addDisallowedApplication(自身包名)` 把 App 排除出 VPN，SPP 出站流量绕过 TUN |

**不做（首版）**：分流规则、订阅、流量统计面板、Always-on VPN 深度集成、RICMP（无 root 不可用）。

---

## 整体架构

现代 Android 代理类 VPN（Shadowsocks Android、Clash Meta、sing-box、v2rayNG）的通行做法：

```
  App UI（开关 / 配置）
        │
        ▼
  SppVpnService extends VpnService
        │  Builder.establish() → TUN fd
        │  addRoute(0.0.0.0/0) + addDnsServer(虚拟 DNS)
        ▼
  ┌─────────────────────────────────────────┐
  │  tun2socks（如 hev-socks5-tunnel）       │
  │  IP 包 ↔ 本地 SOCKS5（含 UDP ASSOCIATE） │
  └─────────────────┬───────────────────────┘
                    │ 127.0.0.1:本地 SOCKS 端口
                    ▼
  ┌─────────────────────────────────────────┐
  │  SPP socks5_client（libspp.so 子进程）   │
  │  -type socks5_client -server ...        │
  │  App 整体被 addDisallowedApplication    │
  │  排除出 VPN，出站 socket 绕过 TUN        │
  └─────────────────┬───────────────────────┘
                    │ SPP 隧道（tcp/rudp/kcp/quic…）
                    ▼
              SPP Server
```

数据面职责拆分：

1. **系统层**：`VpnService` 建虚拟网卡，把流量灌进 TUN。
2. **栈转换层**：tun2socks 把 IP/TCP/UDP 转成 SOCKS5，DNS 也作为 UDP/53 走 UDP ASSOCIATE。
3. **隧道层**：SPP client 把 SOCKS5 流量加密后送到 server，由 server 出网并解析 DNS。

这与 Clash Meta / hev-socks5-tunnel / shadowsocks-android 的「TUN → SOCKS → 协议核心」路径一致，只是协议核心换成 SPP。

---

## 与现有仓库的关系

| 仓库 | 角色 |
|------|------|
| [esrrhs/spp](https://github.com/esrrhs/spp) | 协议与 `socks5_client` / server 实现（本机可参考 `/home/project/spp`） |
| [esrrhs/spp-shadowsocks-plugin-android](https://github.com/esrrhs/spp-shadowsocks-plugin-android) | 已有 Android 上交叉编译 / 打包 `libspp.so` 的经验，可复用构建脚本 |
| **本仓库** | 独立 App：VpnService + UI + tun2socks + 内嵌 SPP client |

首版推荐路径：**不改 SPP 协议**，以「本地 SOCKS5 + tun2socks」组合达成 VPN，最快落地。

---

## 用户使用流程（目标形态）

1. 安装 APK，打开 App。
2. 填写：`server`、`proto`、`key`、可选 `encrypt`、压缩等（与 spp 一致）。
3. 点「连接」→ 系统弹出 VPN 授权 → 同意。
4. 前台通知显示已连接；浏览器 / App 流量与 DNS 均经 SPP Server。
5. 再点一次断开。

服务端示例：

```bash
./spp -type server -proto tcp -listen :8888 \
  -key 'your-auth-key' -encrypt 'your-encrypt-key'
```

客户端侧由 App 内部等价启动：

```text
socks5_client → server=<host:port> proto=tcp key=... encrypt=...
本地监听 127.0.0.1:<socks_port>
```

---

## 技术选型（建议）

| 层级 | 选型 | 理由 |
|------|------|------|
| 语言 / UI | Kotlin + Jetpack Compose | 与现代 Android 一致，UI 简单 |
| VPN | `android.net.VpnService` + Foreground Service | 官方能力，Android 8+ 必须前台 |
| TUN→SOCKS | [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | Clash Meta / 多家客户端在用，性能好、支持 DNS |
| SPP 嵌入 | Go 交叉编译（CGO 链 bionic，域名解析走系统），以 `libspp.so` 名装入 jniLibs，运行时 `exec` 子进程 | 不改 spp 源码；API 29+ 允许 exec nativeLibraryDir |
| 配置存储 | DataStore | 轻量持久化 |
| 最低 SDK | API 24+（可按需要抬到 26） | 覆盖主流机型 |

备选：`leaf`、`badvpn-tun2socks`、自研 gomobile 绑定；首版优先 hev-socks5-tunnel。

---

## 仓库结构

```text
spp_client_android/
├── README.md                 # 本文件
├── IMPLEMENTATION_PLAN.md    # 分阶段实现计划
├── app/                      # Android 应用（Kotlin + Compose）
│   └── src/main/
│       ├── java/com/esrrhs/spp/client/   # ui/ vpn/ spp/ tun/ data/
│       ├── java/hev/htproxy/             # hev JNI 绑定类（类名由 so 内注册决定，勿改）
│       ├── jniLibs/          # 构建产物：libspp.so / libhev-socks5-tunnel.so（gitignore）
│       └── res/
├── gradle/                   # wrapper + version catalog
└── scripts/                  # build_spp.sh / build_hev.sh / build_native.sh
```

`third_party/`（spp、hev-socks5-tunnel 源码）由脚本首次运行时克隆，不入库。

---

## 开发环境

- JDK 17
- Android SDK（platform 35 / build-tools 35 / NDK r27+ / cmake）
- Go 1.26+

```bash
# 1. 构建 native 产物（首次自动克隆 spp / hev-socks5-tunnel 并交叉编译）
./scripts/build_native.sh

# 2. 打包
./gradlew :app:assembleDebug
```

ABI 默认 `arm64-v8a` + `x86_64`（与 abiFilters 一致），可用 `SPP_ABIS` / `HEV_ABIS` 扩展。

---

## 验收标准（MVP）

- [ ] 真机授权后可建立 VPN 会话，状态栏有钥匙图标 / 前台通知
- [ ] 访问 `https://ifconfig.me`（或同类）显示 **SPP Server 出口 IP**
- [ ] DNS 查询不走运营商直连（可用抓包 / 对比未开 VPN 的解析路径验证）
- [ ] 断开后流量恢复直连
- [ ] SPP 出站流量已绕过 VPN（disallowed 自身），无「连上即断 / 死循环」
- [ ] 错误配置（错误 key / 不可达 server）有明确失败提示，不卡死

---

## 文档

详细分阶段任务、风险与参考实现见 **[IMPLEMENTATION_PLAN.md](./IMPLEMENTATION_PLAN.md)**。

## 当前进度

**Phase 1～6 已完成代码落地**（勾选状态见 IMPLEMENTATION_PLAN.md），Debug 与 Release（R8）均构建通过：

- 工程骨架：Gradle version catalog + Compose + DataStore 配置持久化
- `SppVpnService`：前台通知（Android 14 `specialUse` FGS type）、TUN 建立（IPv4/IPv6 全接管 + mapdns 虚拟 DNS）
- `SppProcess`：SPP socks5_client 子进程（空闲端口选择、监听等待、stderr 捕获、失败即报错、意外退出看门狗）
- `HevTunnel`：hev-socks5-tunnel 官方 `hev-jni.c` 接入（含 `TProxyGetStats` 流量统计）
- 硬化：状态机防抖、`onRevoke()` 系统侧断开、R8 keep 规则、release `keystore.properties` 签名注入
- 体验：IPv6 接管开关、运行日志页（spp/hev）、状态卡流量统计与实时速率

待办：真机按验收标准清单实测（Phase 5 抓包），配置签名后出首版 APK。

**模拟器端到端验收已通过（2026-09-30）**：宿主机跑 SPP server，App server 填 `10.0.2.2:8888`；
验证了连接建立（钥匙图标/前台通知）、`ifconfig.me` 显示 server 出口 IP、DNS（mapdns 会话携带域名）、
断开后 tun0 删除/agent 消失/Service 销毁/直连恢复、错误 key 6s 内明确报错（`auth proof error`）。
验收中修复两个真实 Bug：hev 不关闭外部传入的 tun fd（导致 VPN/Service 残留）、日志 ANSI 剥除正则失效。

### Release 签名

在项目根目录创建 `keystore.properties`（已 gitignore）：

```properties
storeFile=/absolute/path/to/your.jks
storePassword=******
keyAlias=spp
keyPassword=******
```

随后 `./gradlew :app:assembleRelease` 即产出已签名 APK；文件缺失时产出未签名 APK。

## License

与 SPP 项目对齐时再定；代码落地前可按 Apache-2.0 / MIT 之一选择。
