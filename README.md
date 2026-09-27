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
| 防环路 | 对 SPP 出站 socket 调用 `VpnService.protect()` |

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
  │  SPP socks5_client（嵌入式二进制 / so）  │
  │  -type socks5_client -server ...        │
  │  socket 经 protect() 绕过 VPN           │
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
| SPP 嵌入 | 交叉编译 `spp` 为各 ABI 可执行文件或 `.so`，进程内 `exec` / JNI 启动 | 复用 plugin-android 构建方式，避免重写协议 |
| 配置存储 | DataStore | 轻量持久化 |
| 最低 SDK | API 24+（可按需要抬到 26） | 覆盖主流机型 |

备选：`leaf`、`badvpn-tun2socks`、自研 gomobile 绑定；首版优先 hev-socks5-tunnel。

---

## 仓库结构（规划）

```text
spp_client_android/
├── README.md                 # 本文件
├── IMPLEMENTATION_PLAN.md    # 分阶段实现计划
├── app/                      # Android 应用
│   └── src/main/
│       ├── java/.../         # UI、VpnService、进程管理
│       ├── jniLibs/          # hev-socks5-tunnel / spp so
│       └── assets/           # 可选：各 ABI spp 二进制
├── core/                     # （可选）tun / 配置 / 状态机
└── scripts/                  # 交叉编译 spp、打包 so
```

当前阶段只提交文档；代码按 `IMPLEMENTATION_PLAN.md` 分阶段落地。

---

## 开发环境（后续编码时）

与 `spp-shadowsocks-plugin-android` 类似：

- JDK 17
- Android SDK（platform / build-tools）
- NDK（编 hev-socks5-tunnel）
- Go（交叉编译 spp 到 `android/arm64` 等）

```bash
# 示意
./gradlew :app:assembleDebug
```

---

## 验收标准（MVP）

- [ ] 真机授权后可建立 VPN 会话，状态栏有钥匙图标 / 前台通知
- [ ] 访问 `https://ifconfig.me`（或同类）显示 **SPP Server 出口 IP**
- [ ] DNS 查询不走运营商直连（可用抓包 / 对比未开 VPN 的解析路径验证）
- [ ] 断开后流量恢复直连
- [ ] SPP 隧道 socket 已 `protect`，无「连上即断 / 死循环」
- [ ] 错误配置（错误 key / 不可达 server）有明确失败提示，不卡死

---

## 文档

详细分阶段任务、风险与参考实现见 **[IMPLEMENTATION_PLAN.md](./IMPLEMENTATION_PLAN.md)**。

## License

与 SPP 项目对齐时再定；代码落地前可按 Apache-2.0 / MIT 之一选择。
