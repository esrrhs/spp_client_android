# spp-client-android

An Android VPN client based on [SPP](https://github.com/esrrhs/spp).

The goal is simple: **tap one button on your phone to turn on the VPN, and all device traffic (including DNS) goes through an SPP Server.**

> For learning, research, and authorized testing only. Do not use it for unauthorized or illegal purposes.

---

## Goals

| Capability | Description |
|------|------|
| One-tap VPN | A single switch on the main screen to start/stop the system VPN |
| Full-traffic proxy | `0.0.0.0/0` (IPv6 optional) is captured via TUN |
| DNS over proxy | System DNS points to a virtual address; queries are resolved by the server via SOCKS5/UDP ASSOCIATE to prevent DNS leaks |
| SPP Server integration | Runs a local `socks5_client` that connects to the remote server through an SPP tunnel |
| Smart split | Bypass private/LAN ranges, or route China (CN) IPs directly (chnroute) while proxying everything else |
| Loop prevention | `addDisallowedApplication(own package name)` excludes the app from the VPN, so SPP outbound traffic bypasses the TUN |

**Out of scope (first release):** split-routing rules, subscriptions, a traffic-statistics dashboard, deep Always-on VPN integration, and RICMP (unavailable without root).

---

## Architecture

The standard approach used by modern Android proxy VPNs (Shadowsocks Android, Clash Meta, sing-box, v2rayNG):

```
  App UI (switch / config)
        │
        ▼
  SppVpnService extends VpnService
        │  Builder.establish() → TUN fd
        │  addRoute(0.0.0.0/0) + addDnsServer(virtual DNS)
        ▼
  ┌─────────────────────────────────────────┐
  │  tun2socks (e.g. hev-socks5-tunnel)     │
  │  IP packets ↔ local SOCKS5 (incl. UDP)  │
  └─────────────────┬───────────────────────┘
                    │ 127.0.0.1:local SOCKS port
                    ▼
  ┌─────────────────────────────────────────┐
  │  SPP socks5_client (libspp.so process)  │
  │  -type socks5_client -server ...        │
  │  The whole app is excluded from the VPN │
  │  via addDisallowedApplication, so      │
  │  outbound sockets bypass the TUN        │
  └─────────────────┬───────────────────────┘
                    │ SPP tunnel (tcp/rudp/kcp/quic…)
                    ▼
              SPP Server
```

Data-plane responsibilities:

1. **System layer**: `VpnService` creates the virtual interface and feeds traffic into the TUN.
2. **Stack-conversion layer**: tun2socks converts IP/TCP/UDP into SOCKS5; DNS also travels as UDP/53 via UDP ASSOCIATE.
3. **Tunnel layer**: the SPP client encrypts SOCKS5 traffic and sends it to the server, which accesses the network and resolves DNS.

This matches the "TUN → SOCKS → protocol core" path of Clash Meta / hev-socks5-tunnel / shadowsocks-android; only the protocol core is replaced with SPP.

---

## Relationship to Existing Repositories

| Repository | Role |
|------|------|
| [esrrhs/spp](https://github.com/esrrhs/spp) | The protocol and the `socks5_client` / server implementation (locally available at `/home/project/spp`) |
| [esrrhs/spp-shadowsocks-plugin-android](https://github.com/esrrhs/spp-shadowsocks-plugin-android) | Prior experience cross-compiling / packaging `libspp.so` on Android; its build scripts can be reused |
| **This repository** | A standalone app: VpnService + UI + tun2socks + embedded SPP client |

Recommended path for the first release: **do not modify the SPP protocol**; use a "local SOCKS5 + tun2socks" combination to achieve the VPN with the fastest delivery.

---

## User Flow (Target)

1. Install the APK and open the app.
2. Fill in `server`, `proto`, `key`, and optionally `encrypt`, compression, etc. (same as spp).
3. Tap "Connect" → the system shows the VPN authorization prompt → approve it.
4. A foreground notification shows the connection; browser/app traffic and DNS all go through the SPP Server.
5. Tap again to disconnect.

Server example:

```bash
./spp -type server -proto tcp -listen :8888 \
  -key 'your-auth-key' -encrypt 'your-encrypt-key'
```

The equivalent client side is started internally by the app:

```text
socks5_client → server=<host:port> proto=tcp key=... encrypt=...
Listening locally on 127.0.0.1:<socks_port>
```

---

## Technology Choices

| Layer | Choice | Reason |
|------|------|------|
| Language / UI | Kotlin + Jetpack Compose | Consistent with modern Android; simple UI |
| VPN | `android.net.VpnService` + Foreground Service | Official capability; foreground is required on Android 8+ |
| TUN→SOCKS | [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | Used by Clash Meta and other clients; good performance and DNS support |
| SPP embedding | Go cross-compilation (CGO linked against bionic for system DNS resolution), packaged as `libspp.so` in jniLibs and run via `exec` | No spp source changes; API 29+ allows exec in nativeLibraryDir |
| Config storage | DataStore | Lightweight persistence |
| Minimum SDK | API 24+ (can be raised to 26 as needed) | Covers mainstream devices |

Alternatives: `leaf`, `badvpn-tun2socks`, and a custom gomobile binding; hev-socks5-tunnel is preferred for the first release.

---

## Repository Layout

```text
spp_client_android/
├── README.md                 # This file
├── IMPLEMENTATION_PLAN.md    # Phased implementation plan
├── app/                      # Android app (Kotlin + Compose)
│   └── src/main/
│       ├── assets/           # cn_ipv4_cidr.txt chnroute data (generated, committed)
│       ├── java/com/esrrhs/spp/client/   # ui/ vpn/ spp/ tun/ data/
│       ├── java/hev/htproxy/             # hev JNI binding class (name is registered in the so; do not change)
│       ├── jniLibs/          # Build artifacts: libspp.so / libhev-socks5-tunnel.so (gitignored)
│       └── res/
├── gradle/                   # Wrapper + version catalog
└── scripts/                  # build_spp.sh / build_hev.sh / build_native.sh / build_chnroute.sh
```

`third_party/` (spp and hev-socks5-tunnel sources) is cloned by the scripts on first run and is not committed.

---

## Development Environment

- JDK 17+
- Android SDK (platform 37.2 / build-tools 37 / NDK r27+ / cmake)
- Go 1.26+ (the native build script pulls the latest spp master and runs `go get github.com/esrrhs/gohome@latest`)

```bash
# 1. Build native artifacts (clones spp / hev-socks5-tunnel and cross-compiles on first run)
./scripts/build_native.sh

# 2. Package
./gradlew :app:assembleDebug
```

Default ABIs are `arm64-v8a` + `x86_64` (matching abiFilters); extend with `SPP_ABIS` / `HEV_ABIS`.

### Update chnroute data

```bash
# Generates app/src/main/assets/cn_ipv4_cidr.txt and cn_ipv6_cidr.txt from
# APNIC delegated stats (downloads on first run; delete
# third_party/delegated-apnic-latest to refresh)
./scripts/build_chnroute.sh
```

CN-direct mode covers both IPv4 and IPv6: CN allocations go direct and every
other public/global address is proxied. The TUN interface is **always created
with the two default routes only** (`0.0.0.0/0`, plus `::/0` when IPv6 is on);
CN/LAN split decisions are performed by the in-process userspace proxy
(`RuleSocksServer`) against the **exact** CIDR sets (8.8k IPv4 / 2k IPv6
ranges, sorted interval + binary search). This avoids the
`TransactionTooLargeException` that Android 15 NetworkMonitor throws when the
VPN LinkProperties (one Binder transaction) carry thousands of routes — an
earlier TUN-route approach that expanded CN segments to aligned /12 blocks
forced 300M+ non-CN addresses (e.g. `1.1.1.1`) direct and made those sites
unreachable, and is removed. Private/CGNAT ranges (`100.64.0.0/10` included)
go direct only in bypass-LAN mode; the mapdns fake-ip pool lives inside
`198.18.64.0/18` and never overlaps carrier CGNAT.

---

## Acceptance Criteria (MVP)

- [ ] A VPN session can be established after authorization on a real device, with a key icon in the status bar / foreground notification
- [ ] Visiting `https://ifconfig.me` (or similar) shows the **SPP Server exit IP**
- [ ] DNS queries do not go directly to the carrier (verifiable via packet capture / by comparing resolution paths with the VPN off)
- [ ] Traffic returns to direct connection after disconnecting
- [ ] SPP outbound traffic bypasses the VPN (itself disallowed), with no "disconnect on connect / infinite loop"
- [ ] Bad configuration (wrong key / unreachable server) gives a clear failure message without hanging

---

## Documentation

See **[IMPLEMENTATION_PLAN.md](./IMPLEMENTATION_PLAN.md)** for detailed phased tasks, risks, and reference implementations.

## Current Status

**Phases 1–6 are implemented** (checkboxes in IMPLEMENTATION_PLAN.md); both Debug and Release (R8) builds pass:

- Project skeleton: Gradle version catalog + Compose + DataStore config persistence
- `SppVpnService`: foreground notification (Android 14 `specialUse` FGS type), TUN setup (full IPv4/IPv6 capture + mapdns virtual DNS)
- `SppProcess`: SPP socks5_client subprocess (free-port selection, listen wait, stderr capture, fail-fast error, unexpected-exit watchdog)
- `HevTunnel`: integration of the official hev-socks5-tunnel `hev-jni.c` (including `TProxyGetStats` traffic stats)
- Hardening: state-machine debouncing, `onRevoke()` system-side disconnect, R8 keep rules, release signing via `keystore.properties`
- Experience: IPv6 capture switch, runtime logs screen (spp/hev), traffic stats and real-time rate on the status card

Remaining: run the acceptance checklist on a real device (Phase 5 packet capture) and produce the first signed APK.

**Emulator end-to-end acceptance passed (2026-09-30)**: run an SPP server on the host and set the app's server to `10.0.2.2:8888`. Verified connection establishment (key icon / foreground notification), the server exit IP shown on `ifconfig.me`, DNS (mapdns sessions carrying domain names), and after disconnect: tun0 removed / agent gone / service destroyed / direct connection restored; a wrong key gives a clear error within 6s (`auth proof error`). Two real bugs were fixed during acceptance: hev does not close an externally supplied tun fd (which caused VPN/service residue), and the log ANSI-stripping regex was ineffective.

### Release Signing

Create `keystore.properties` in the project root (gitignored):

```properties
storeFile=/absolute/path/to/your.jks
storePassword=******
keyAlias=spp
keyPassword=******
```

Then `./gradlew :app:assembleRelease` produces a signed APK; if the file is missing, it produces an unsigned APK.

## License

To be aligned with the SPP project; before that, choose either Apache-2.0 or MIT.
