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

- [x] 用 Android Studio / Gradle 建 `app` 模块（Kotlin、Compose、minSdk 26、target 35）。
- [x] 包名：`com.esrrhs.spp.client`。
- [x] 主界面：状态卡、连接开关、配置输入框、保存按钮。
- [x] DataStore：读写 `SppConfig`（server、port、proto、key、encrypt、compress）。
- [x] `AndroidManifest`：`VpnService`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`POST_NOTIFICATIONS`（API 33+）、`INTERNET`。
- [x] `.gitignore`、根 `settings.gradle`、version catalog（`gradle/libs.versions.toml`）。

**验收：** Debug APK 能装、能改配置并重启后仍在。

---

### Phase 2 — VpnService 空隧道（约 1 天）

**交付：** 能开 VPN「钥匙」，但流量黑洞或直通测试均可（先不通网也可）。

- [x] `SppVpnService : VpnService`，声明 `BIND_VPN_SERVICE` + `SERVICE_INTERFACE`。
- [x] UI 调 `VpnService.prepare()`；用户同意后 `startForegroundService`。
- [x] Foreground notification（Android 8+ 必需；Android 14+ 用 `specialUse` FGS type + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`）。
- [x] `Builder`：
  - `setSession("SPP")`
  - `addAddress("198.18.0.1", 30)` + `addAddress("fdfe:dcba:9876::1", 126)`（与 hev 示例对齐）
  - `addRoute("0.0.0.0", 0)` + `addRoute("::", 0)`
  - `addDnsServer("198.18.0.2")`（hev mapdns 虚拟 DNS）
  - `setMtu(8500)`
  - fd 由 hev 自行 `ioctl(FIONBIO)` 设非阻塞，无需 Kotlin 侧处理
- [x] 启动 spp → establish → detach fd → 交给 hev，全链路串起（并入了 Phase 4 流程）。
- [x] 停止：停 hev → 关 fd → 停 spp → 关通知 → `stopSelf`。

**验收：** 开 VPN 后系统显示已连接；关后恢复。

**坑（已处理）：** Android 14+ foreground service type 用 `specialUse` 并声明 `FOREGROUND_SERVICE_SPECIAL_USE` 权限。

---

### Phase 3 — 嵌入 SPP socks5_client（约 1～2 天）

> **结论（已实现，2026-09-30）：** 未采用 P3-A（c-shared 同进程 + protect 回调），而是
> **独立子进程 + `addDisallowedApplication(自身包名)`**：`protect()` 无法覆盖子进程 fd，
> 而 disallowed 自身后整个 App 的出站流量都绕过 TUN（hev 直接读写 TUN fd 不受影响），
> 防环路效果等价，且完全不改 spp 源码、不需要 JNI protect 回调。
> spp 用 NDK clang + `CGO_ENABLED=1` 交叉编译：Android 无 `/etc/resolv.conf`，
> 纯 Go 解析器无法解析 server 域名，CGO 链 bionic 走系统解析器。
> 可执行文件以 `libspp.so` 名装入 jniLibs（API 29+ 允许 exec nativeLibraryDir），
> 配合 `useLegacyPackaging = true` 保证落盘。

**交付：** App 内能拉起本地 SOCKS，PC/`curl --socks5` 或本机测试工具可经 SPP 上网。

- [x] `scripts/build_spp.sh`：`GOOS=android`（arm64-v8a / x86_64，`SPP_ABIS` 可扩展）→ `jniLibs/<abi>/libspp.so`。
- [x] `SppProcess`：组装参数 `-type socks5_client -server host:port -fromaddr 127.0.0.1:PORT -proxyproto tcp -proto tcp -key ... -encrypt ... -compress ...`。
- [x] 启动前选空闲 loopback 端口；等待端口监听（15s 超时）；失败时回放 stderr；停止时 destroy / destroyForcibly。

**验收（待真机）：** 不开 VPN 时，本机用 SOCKS 能通 SPP server；开 key 错误时立刻失败。

---

### Phase 4 — 接入 hev-socks5-tunnel（约 1～2 天）

> **已实现（2026-09-30）：** `scripts/build_hev.sh` 用 ndk-build 编译（含官方 `hev-jni.c`，
> JNI 注册在固定类名 `hev/htproxy/TProxyService`，App 内置同名绑定类）。
> DNS 采用 hev 内置 **mapdns（fake-ip）**：`addDnsServer(198.18.0.2)` 的查询由 hev
> 本地应答并映射到 `100.64.0.0/10`，后续连接经 SOCKS5 携带**域名**由 server 解析——
> DNS 查询包根本不出设备，无泄漏；UDP/53 不再依赖 spp 的 UDP ASSOCIATE。

**交付：** 全流量走 SOCKS → SPP。

- [x] NDK 编译 hev-socks5-tunnel，产出 `jniLibs/<abi>/libhev-socks5-tunnel.so`。
- [x] JNI：`TProxyStartService(config_path, tun_fd)` / `TProxyStopService()` / `TProxyIsRunning()` / `TProxyGetStats()`（官方桥接）。
- [x] VpnService 流程：启动 spp 并等待 listen → `establish()` → `detachFd()` → 写 hev YAML 配置（tunnel/socks5/mapdns 段）→ 启动 hev。
- [x] TUN 地址 / DNS / MTU 与 hev 示例配置对齐（198.18.0.1/30、MTU 8500）。
- [x] 断线顺序：停 hev → 关 tun → 停 spp。

**验收（模拟器已过 2026-09-30，真机待补）：**

- [x] ifconfig / ip 检测网站显示 server 出口：Chrome 开 `https://ifconfig.me/ip`
  显示 SPP Server 出口 IP（宿主机公网 IP）。
- [x] `adb shell dumpsys connectivity` 可见 VPN network（`ni{VPN CONNECTED}`）。
- [x] 错误 key：App 6s 内进入 Error 态，回放 spp 日志 `processLoginRsp fail tcp auth proof error`。
- [ ] 故意断 server（MVP 报错停止路径已由错误 key 覆盖；运行中掉线由 spp 看门狗处理，待真机/补测）。

> **Bug 修复（验收中发现，2026-09-30）：**
> 1. hev 对**外部传入**的 tun fd 在 `tunnel_fini` 不关闭（`!tun_fd_local` 直接 return），
>    原代码 `detachFd()` 后失去 PFD，导致 tun0 不被内核删除、框架收不到
>    `interfaceRemoved`，VPN agent / 系统绑定 / Service 全部残留。改为 `getFd()` 只传 int、
>    保留 PFD，teardown 顺序「停 hev → close PFD → 停 spp」，关闭后接口删除、框架解绑。
> 2. 采集 spp 日志的 ANSI 正则源码中曾混入不可见 ESC 字符导致永不匹配；已改为
>    `27.toChar()` 由码值构造 ESC，并在全新日志上验证 0 ESC 字节。

---

### Phase 5 — DNS 与泄漏验证（约 0.5～1 天）

- [x] DNS 方案：hev mapdns 虚拟地址（`198.18.0.2`），查询本地应答，见 Phase 4 说明。
- [x] 模拟器功能验证：hev.log 中所有会话均携带**域名**（`socks5 client tcp -> [ifconfig.me]:80`），
  证明 mapdns 本地应答后由 server 侧解析；抓包级无泄漏验证待真机。
- [x] IPv6：已默认接管（`fdfe:dcba:9876::1/126` + `addRoute("::", 0)`），不留直连泄漏路径；UI 提供开关可关闭（关闭后仅代理 IPv4）。
- [x] 处理 DoH/DoT App（浏览器自带）：系统级 DNS 已代理，但 App 内置 DoH 仍可能直连——文档中注明「系统 DNS 走代理；应用层 DoH 需另议」。MVP 以系统 DNS 为准。

**验收：** 检查清单全部打勾（见 README）。

---

### Phase 6 — 体验与硬化（约 1 天）

- [x] 状态机与按钮防抖（重复点击）：`lifecycleMutex` 串行化 connect/teardown；busy 态按钮禁用；重复 start 命令直接忽略。
- [x] 通知显示 Connected / 延迟（可选 ping）：通知随状态更新为「已连接 · server」；ping 未做（可选）。
- [x] 崩溃恢复：Service 被杀后不自动重连（MVP 行为，`START_NOT_STICKY`）；`onDestroy` 同步兜底清理。
- [x] ProGuard / R8 规则（native 方法 keep）：keep `hev.htproxy.TProxyService`（JNI FindClass 硬编码类名），release mapping 已验证类名保留。
- [x] Release 签名：`keystore.properties`（storeFile/storePassword/keyAlias/keyPassword）注入 signingConfig，密钥不入库；缺失时产出未签名 APK。
- [x] 系统侧断开处理：重写 `onRevoke()`（系统设置中断开 / 被其它 VPN 抢占）→ 拆链并复位状态。
- [x] spp 进程看门狗：已连接时若 spp 意外退出，自动拆链并进入 Error 态提示重连。
- [x] 日志页（Should 项）：spp stdout 落盘 `spp.log`（256KB 滚动）+ hev `hev.log`，主界面入口查看、2s 自动刷新。
- [x] 流量统计（Could 项）：每秒轮询 `TProxyGetStats`，状态卡展示累计上下行与实时速率。
- [ ] README 补充：截图（待真机）。

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

**已完成（2026-09-30）：**

- Phase 1～4 代码全部落地，Debug / Release（R8）均构建通过。
- Phase 5：DNS 方案、IPv6 开关、DoH 说明完成；mapdns 域名携带经模拟器验证。
- Phase 6：状态机防抖、`onRevoke`、spp 进程看门狗、R8 规则、release 签名注入、
  日志页、流量统计均完成。
- **模拟器端到端验收通过**：连接 → 出口 IP（ifconfig.me 显示 server IP）→ DNS →
  断开彻底恢复（tun0 删除 / agent 消失 / Service 销毁 / 直连恢复）→ 错误 key 明确报错；
  验收中修复两个真实 Bug（见 Phase 4 说明：hev 外部 fd 不关闭、ANSI 正则隐藏字符）。
- 构建脚本：`scripts/build_native.sh`（spp 交叉编译 + hev ndk-build）。
- README 已同步实际架构（防环路机制、mapdns DNS、构建步骤）。

**模拟器验收方法：**

宿主机跑 `./spp -type server -proto tcp -listen :8888 -key <k> -encrypt <e>`，
App 配置 server=`10.0.2.2:8888`（模拟器到宿主机 loopback 的专用地址）。

**下一步：**

1. 真机验收：README 验收标准逐项打勾（VPN 授权、出口 IP、DNS、断开恢复、错误提示）。
2. 抓包验证 DNS 无泄漏；确认 spp UDP ASSOCIATE 通路（非 DNS 的 UDP 流量）。
3. 配置 `keystore.properties` 产出可安装的签名 release APK；补充真机截图。
4. 备选升级路径：如需 `protect()`（例如 Always-on VPN 场景下 disallowed 失效），再评估
   c-shared 方案 P3-A。
