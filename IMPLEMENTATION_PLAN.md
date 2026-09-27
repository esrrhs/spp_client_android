# 实现计划：SPP Android VPN Client

本文档把「手机一点按钮 → 全流量 + DNS 走 SPP Server」拆成可执行阶段。架构对齐现代 Android VPN：`VpnService` + TUN + tun2socks + 本地 SOCKS5（SPP `socks5_client`）。

---

## 0. 背景与结论

### 0.1 为什么不能「只跑一个 spp socks5」

SPP 的 `socks5_client` 只在本机开 SOCKS 端口。普通 App 不会自动走它；要接管**整机**流量，必须走 Android 的 `VpnService`，把系统路由指到 TUN。

### 0.2 为什么采用「TUN → SOCKS → SPP」而不是自研协议栈

| 方案 | 工作量 | 风险 |
|------|--------|------|
| A. VpnService + hev-socks5-tunnel + 内嵌 spp socks5_client | 小～中 | 低，业界成熟 |
| B. 把 SPP 改成直接吃 TUN fd（类 sing-box tun inbound） | 大 | 需改 Go 核心、联调久 |
| C. 纯 Kotlin 解析 IP/TCP | 极大 | 性能与正确性差 |

**首版选定方案 A。** 成功后再评估是否把 tun 能力做进 spp 本体（方案 B）。

### 0.3 参考实现

| 项目 | 可借鉴点 |
|------|----------|
| [Android VpnService 官方文档](https://developer.android.com/develop/connectivity/vpn) | `prepare` → `protect` → `Builder` → `establish` |
| Clash Meta for Android `TunService` | TUN 地址段、`addDnsServer` 虚拟 DNS、foreground、`protect` |
| [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | TUN fd → SOCKS5（TCP + UDP），DNS 转发 |
| shadowsocks-android | VpnService 生命周期、进程/native 保活 |
| spp-shadowsocks-plugin-android | Android 上交叉编译 / 打包 spp 二进制为 `lib*.so` |

---

## 1. 目标拆解（Must / Should / Could）

### Must（MVP）

1. Compose 主界面：开关 + 服务器配置表单（server / proto / key / encrypt）。
2. `SppVpnService`：申请 VPN、建 TUN、前台通知、可停止。
3. 启动本地 SPP `socks5_client`（loopback）。
4. 启动 hev-socks5-tunnel，把 TUN 接到该 SOCKS。
5. `addRoute("0.0.0.0", 0)` + `addDnsServer(虚拟 DNS)`，DNS 经代理解析。
6. 所有 SPP 出站 fd `protect`。
7. 配置持久化；连接失败有错误信息。

### Should（紧随 MVP）

- IPv6 可选开关（`::/0` + DNS6）。
- 多协议 underlay（tcp / rudp / kcp / quic）与 UI 选择。
- 连接状态：Connecting / Connected / Disconnecting / Error。
- 日志页（tail 本地 log，便于排障）。
- `addDisallowedApplication(自身包名)`，降低环路风险（与 protect 双保险）。

### Could（后期）

- 分应用代理（allowed/disallowed apps）。
- Always-on VPN / 开机自启（需额外系统设置说明）。
- 流量统计、延迟 ping。
- gomobile / c-shared 把 spp 嵌成库，去掉独立进程。
- 配置二维码导入 / JSON 与桌面 spp 配置互通。

---

## 2. 系统数据流（实现时必须对齐）

```
App / 系统应用
    │  IP 包
    ▼
tun0（VpnService.Builder）
    │  read/write fd
    ▼
hev-socks5-tunnel
    │  SOCKS5 CONNECT / UDP ASSOCIATE
    ▼
127.0.0.1:SOCKS_PORT  ← spp socks5_client
    │  SPP session（AEAD + auth key）
    ▼
远端 spp -type server
    │
    ▼
真实目标 / DNS 上游（在 server 侧解析）
```

**DNS 路径（防泄漏关键）：**

1. `Builder.addDnsServer("172.19.0.2")`（示例，与 Clash Meta 同类私网段）。
2. 系统把 DNS 问到该地址 → 包进入 TUN。
3. tun2socks 识别 UDP/53（或配置的 DNS 劫持），经 SOCKS5 UDP ASSOCIATE 送出。
4. spp socks5 支持 UDP ASSOCIATE（USAGE 已说明）→ server 侧解析。

若只代理 TCP 不代理 UDP，DNS 会泄漏或失败——MVP 必须验证 UDP。

---

## 3. 分阶段计划

### Phase 1 — 工程骨架（约 0.5～1 天）

**交付：** 可安装的空壳 App，无 VPN 逻辑。

- [ ] 用 Android Studio / Gradle 建 `app` 模块（Kotlin、Compose、minSdk 24、target 34/35）。
- [ ] 包名建议：`com.esrrhs.spp.client`（可改）。
- [ ] 主界面：标题、开关（先 noop）、配置输入框、保存按钮。
- [ ] DataStore：读写 `SppConfig`（server、port、proto、key、encrypt、compress）。
- [ ] `AndroidManifest`：预留 `VpnService`、`FOREGROUND_SERVICE`、`POST_NOTIFICATIONS`（API 33+）、`INTERNET`。
- [ ] `.gitignore`、根 `settings.gradle` / 版本目录。

**验收：** Debug APK 能装、能改配置并重启后仍在。

---

### Phase 2 — VpnService 空隧道（约 1 天）

**交付：** 能开 VPN「钥匙」，但流量黑洞或直通测试均可（先不通网也可）。

- [ ] `SppVpnService : VpnService`，声明 `BIND_VPN_SERVICE` + `SERVICE_INTERFACE`。
- [ ] UI 调 `VpnService.prepare()`；用户同意后 `startForegroundService`。
- [ ] Foreground notification（Android 8+ 必需）。
- [ ] `Builder`：
  - `setSession("SPP")`
  - `addAddress("172.19.0.1", 30)`（段可与 hev 文档对齐后再定）
  - `addRoute("0.0.0.0", 0)`
  - `addDnsServer("172.19.0.2")`
  - `setMtu(8500` 或 `9000)`
  - `setBlocking(true/false)` 按 tun2socks 要求
  - `establish()` 得到 `ParcelFileDescriptor`
- [ ] 仅 hold 住 fd，或简单 loop discard，验证系统 VPN 图标出现。
- [ ] 停止：关 notification、关 fd、`stopSelf`。

**验收：** 开 VPN 后系统显示已连接；关后恢复。可暂时无外网。

**坑：** Android 14+ foreground service type（`specialUse` / 文档中的 VPN 相关 type）需按 targetSdk 声明。

---

### Phase 3 — 嵌入 SPP socks5_client（约 1～2 天）

**交付：** App 内能拉起本地 SOCKS，PC/`curl --socks5` 或本机测试工具可经 SPP 上网。

- [ ] 复用 / 改编 `spp-shadowsocks-plugin-android` 的 `make.bash`：交叉编译  
  `GOOS=android GOARCH=arm64`（及 arm、amd64、386）→ `libspp.so` 或放入 `assets` 后 chmod +x。
- [ ] `SppProcess`：组装参数等价于  
  `-type socks5_client -server host:port -fromaddr 127.0.0.1:PORT -proxyproto tcp -key ... -encrypt ... -proto ...`
- [ ] 启动前选空闲 loopback 端口；停止时杀进程 / 关 stdin。
- [ ] **关键：** 进程内创建的 socket 必须 `protect`。做法二选一或组合：
  1. 独立进程：`VpnService.protect` 无法直接保护子进程 socket → 优先用 **`addDisallowedApplication(packageName)`** 把自己排除出 VPN，或使用 **VPN 排除 + 仅 tun2socks 在 VPN 内** 的经典布局；更稳的是把 spp 跑在**同一进程**并用 JNI/`syscall` 在 dial 后 protect，或使用 `VpnService.Builder.allowFamily` + disallowed 自身。
  2. 推荐 MVP 布局（与 SS Android 类似）：
     - App 进程跑 VpnService + tun2socks（native）。
     - spp 以 **同包可执行文件** 启动时，对该二进制使用 **`addDisallowedApplication`** 不可行（同 UID）——因此应：
       - **要么** tun2socks / spp 都在 VpnService 进程，出站 socket 在建立后 `protect(fd)`；
       - **要么** 使用 `VpnService.protect` 配合 **LocalSocket / 继承 fd** 的启动器（SS 插件模式有先例）。
- [ ] 实操建议（MVP 降低风险）：
  1. 先用 `addDisallowedApplication` **不适用同 UID** 的结论下，采用 **gomobile / `-buildmode=c-shared` 把 socks5 跑在 VpnService 进程**，dial 回调里 `protect`；或
  2. 短期：先验证「TUN 未开、仅本机 SOCKS」通路；再开 TUN，并对 spp 使用 **`VpnService.Builder.addDisallowedApplication` 排除其它应用** 时，把 **server IP 的路由** 用 `protect` 保证——**最简可靠路径**：参考 hev + clash：core 在同进程，`protect` 回调交给 Java。

**本阶段子决策（开工前锁定一种）：**

| 选项 | 做法 | 推荐 |
|------|------|------|
| P3-A | spp 编成 so，Java 调 `StartSocks5(config)`，socket protect 回调 | ★ 推荐 |
| P3-B | spp 独立进程 + 用 `ConnectivityManager.bindProcessToNetwork` / 排除 VPN 的 hack | 备选，厂商差异大 |

**验收：** 不开 VPN 时，本机用 SOCKS 能通 SPP server；开 key 错误时立刻失败。

---

### Phase 4 — 接入 hev-socks5-tunnel（约 1～2 天）

**交付：** 全流量走 SOCKS → SPP。

- [ ] NDK 编译 hev-socks5-tunnel，产出 `jniLibs/<abi>/libhev-socks5-tunnel.so`。
- [ ] JNI：`start(tun_fd, socks_host, socks_port, dns...)` / `stop()`。
- [ ] VpnService 流程改为：
  1. 启动 spp socks5（或 so）并等到端口 listen；
  2. `establish()` 得 tun fd；
  3. `detachFd()` 交给 hev；
  4. hev 配置 socks5 = `127.0.0.1:PORT`，开启 UDP。
- [ ] 对齐 TUN 地址 / 网关 / DNS IP 与 hev 示例配置（避免与 Clash 文档抄错网段）。
- [ ] 断线顺序：停 hev → 关 tun → 停 spp。

**验收：**

- ifconfig / ip 检测网站显示 server 出口。
- `adb shell dumpsys connectivity` 可见 VPN network。
- 故意断 server，App 应显示断开或重连策略（MVP 可只报错停止）。

---

### Phase 5 — DNS 与泄漏验证（约 0.5～1 天）

- [ ] 确认 `addDnsServer` 虚拟地址与 hev DNS 劫持配置一致。
- [ ] 测试：对比 VPN 开关前后，DNS 查询是否仍打到运营商 53（可用抓包机、或 server 侧看 UDP DNS）。
- [ ] 处理 DoH/DoT App（浏览器自带）：系统级 DNS 已代理，但 App 内置 DoH 仍可能直连——文档中注明「系统 DNS 走代理；应用层 DoH 需另议」。MVP 以系统 DNS 为准。
- [ ] IPv6：若暂不支持，明确 `allowFamily` / 不添加 IPv6 路由，避免泄漏；或阻断 IPv6。

**验收：** 检查清单全部打勾（见 README）。

---

### Phase 6 — 体验与硬化（约 1 天）

- [ ] 状态机与按钮防抖（重复点击）。
- [ ] 通知显示 Connected / 延迟（可选 ping）。
- [ ] 崩溃恢复：Service 被杀后是否重启（MVP 可不自动重连）。
- [ ] ProGuard / R8 规则（native 方法 keep）。
- [ ] Release 签名脚本（可参考 plugin 的 `sign.sh`）。
- [ ] README 补充：截图、配置字段说明、与桌面 spp 参数对照表。

---

## 4. 模块设计（代码落地时）

```text
app/
  ui/
    MainScreen.kt          # 开关 + 表单
    MainViewModel.kt       # 状态、调用 VpnController
  vpn/
    SppVpnService.kt       # VpnService + foreground
    VpnController.kt       # prepare / start / stop API
    TunConfig.kt           # 地址、路由、DNS、MTU 常量
  spp/
    SppConfig.kt           # 数据类，与 JSON/flags 对齐
    SppEngine.kt           # 启动/停止 socks5（进程或 JNI）
  tun/
    HevTunnel.kt           # JNI 封装
  data/
    ConfigRepository.kt    # DataStore
```

**线程：** VpnService 内用单线程或协程 `SupervisorJob`；native 阻塞不放主线程。

**状态广播：** `StateFlow` / 本地 Broadcast / Bound Service；UI 只读状态，不直接碰 fd。

---

## 5. SPP 参数映射

| UI 字段 | spp 参数 | 备注 |
|---------|----------|------|
| Server | `-server host:port` | 必填 |
| Proto | `-proto tcp\|rudp\|kcp\|quic\|...` | 与 server 一致；ricmp 标注需 root |
| Auth Key | `-key` | 必填，禁弱密钥 |
| Encrypt | `-encrypt` | 可空=关闭加密 |
| Compress | `-compress` | 默认与桌面一致或 0 |
| 内部固定 | `-type socks5_client -fromaddr 127.0.0.1:x -proxyproto tcp` | 用户不可见 |

配置可导出为与 `config_client.json` 接近的 JSON，便于和桌面调试对照。

---

## 6. 风险与对策

| 风险 | 对策 |
|------|------|
| 路由环路（VPN 套住隧道） | 同进程 `protect`；验证阶段抓包确认 server IP 不走 TUN |
| DNS 泄漏 | 虚拟 DNS + tun2socks UDP；文档说明 DoH |
| 厂商杀后台 | Foreground Service + 用户引导关电池优化 |
| ABI / NDK 版本碎片 | 先只发 `arm64-v8a` Debug，再补全 |
| spp 与 hev 抢 CPU | 合理线程数；先功能后性能 |
| 子进程无法 protect | Phase 3 锁定 P3-A（同进程 so） |

---

## 7. 测试计划

1. **单元：** 配置序列化、端口选择、状态机转换。
2. **集成（模拟器）：** prepare 流程、Service 启停（模拟器 VPN 能力有限，真机优先）。
3. **真机：**
   - TCP 浏览、UDP（部分游戏/视频）、DNS。
   - 切换 Wi-Fi / 蜂窝（观察是否需重连）。
   - 错误 key / 错误地址。
4. **对照：** 同 server 用桌面 `socks5_client` + curl，确认服务端正常后再测手机。

---

## 8. 里程碑与顺序

```text
Week 1:  Phase1 骨架 → Phase2 空 VPN → Phase3 SOCKS（先通 SOCKS）
Week 2:  Phase4 tun2socks 全流量 → Phase5 DNS 验证 → Phase6 硬化发首版 APK
```

并行项：Go 交叉编译脚本可与 Phase1 UI 同时做。

---

## 9. 当前仓库状态与下一步

**已完成：**

- 清空原 frpsGUI 工程文件。
- 写入 `README.md`、`IMPLEMENTATION_PLAN.md`。

**下一步（等确认后开工）：**

1. 执行 Phase 1：创建 Gradle/Compose 工程骨架。
2. 锁定 Phase 3 嵌入方式（强烈建议 **P3-A：c-shared / 同进程 + protect 回调**）。
3. 选定 hev-socks5-tunnel 版本与 TUN 网段常量，写入 `TunConfig.kt`。

若你同意，下一轮对话可以直接从 **Phase 1 工程脚手架** 开始实现。
