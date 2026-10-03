package com.esrrhs.spp.client.util

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import com.esrrhs.spp.client.spp.PerAppMode
import com.esrrhs.spp.client.spp.Profile
import com.esrrhs.spp.client.tun.HevTunnel
import java.net.InetSocketAddress

/** 一条经过 TUN 的传输层连接（hev 会话）。 */
data class LiveConnection(
    val key: String,
    val protocol: String,
    val srcIp: String,
    val srcPort: Int,
    val remoteIp: String,
    val remotePort: Int,
    /** hev mapped-DNS 反查到的真实目标域名（fake-IP 场景）；无则为 null。 */
    val domain: String?,
    val uid: Int,
    val txBytes: Long,
    val rxBytes: Long,
    val txRate: Long,
    val rxRate: Long,
    val createdMs: Long,
)

/** 同一 UID（应用）下的连接聚合。 */
data class AppConnectionGroup(
    val uid: Int,
    val packageName: String?,
    val label: String,
    val connections: List<LiveConnection>,
    val txTotal: Long,
    val rxTotal: Long,
    val txRate: Long,
    val rxRate: Long,
    /** 组内最早一条现存连接的创建时刻。 */
    val firstSeenMs: Long,
)

data class ConnectionSample(
    val groups: List<AppConnectionGroup>,
    /** connection key → 上轮累计 (tx, rx)，用于差分速率。 */
    val prevBytes: Map<String, Pair<Long, Long>>,
)

/**
 * 当前隧道连接快照，数据源为 hev-socks5-tunnel 导出的会话表：
 * hev 在 TUN 协议栈握有每条连接的原始五元组、payload 字节计数与创建时间；
 * App 归属通过 [ConnectivityManager.getConnectionOwnerUid]（Android 10+）
 * 反查五元组对应应用 UID（VPN 应用专用 API，无需特权）。
 */
object ActiveConnections {

    fun empty(): ConnectionSample = ConnectionSample(emptyList(), emptyMap())

    fun snapshot(
        context: Context,
        profile: Profile?,
        prev: ConnectionSample?,
        nowMs: Long,
        intervalMs: Long,
    ): ConnectionSample {
        val text = HevTunnel.sessions().orEmpty()
        val sessions = parseSessions(text)

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val pm = context.packageManager
        val allowedPackages = profile?.perAppPackages?.toSet().orEmpty()

        data class Temp(
            val conn: LiveConnection,
            val packageName: String?,
            val label: String,
        )

        val rows = sessions.mapNotNull { s ->
            val uid = ownerUid(cm, s)
            val pkg = if (uid >= 0) pm.getPackagesForUid(uid)?.firstOrNull() else null
            // 按配置的分应用规则过滤
            val routed = when (profile?.perAppMode ?: PerAppMode.ALL) {
                PerAppMode.ALL -> true
                PerAppMode.ALLOWED -> pkg != null && pkg in allowedPackages
                PerAppMode.DISALLOWED -> pkg == null || pkg !in allowedPackages
            }
            if (!routed) return@mapNotNull null

            val label = pkg?.let {
                runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }
                    .getOrDefault(it)
            } ?: context.getString(com.esrrhs.spp.client.R.string.conn_unknown_app, uid)

            val prevPair = prev?.prevBytes?.get(s.key)
            Temp(
                conn = LiveConnection(
                    key = s.key,
                    protocol = if (s.proto == 6) "TCP" else "UDP",
                    srcIp = s.srcIp,
                    srcPort = s.srcPort,
                    remoteIp = s.dstIp,
                    remotePort = s.dstPort,
                    domain = s.domain,
                    uid = uid,
                    txBytes = s.upload,
                    rxBytes = s.download,
                    txRate = rateBetween(prevPair?.first, s.upload, intervalMs),
                    rxRate = rateBetween(prevPair?.second, s.download, intervalMs),
                    createdMs = s.createdMs,
                ),
                packageName = pkg,
                label = label,
            )
        }

        val groups = rows.groupBy { it.conn.uid }.values.map { groupRows ->
            val first = groupRows.first()
            val conns = groupRows.map { it.conn }
            AppConnectionGroup(
                uid = first.conn.uid,
                packageName = first.packageName,
                label = first.label,
                connections = conns.sortedWith(
                    compareBy({ it.protocol }, { it.remoteIp }, { it.remotePort }),
                ),
                txTotal = conns.sumOf { it.txBytes },
                rxTotal = conns.sumOf { it.rxBytes },
                txRate = conns.sumOf { it.txRate },
                rxRate = conns.sumOf { it.rxRate },
                firstSeenMs = conns.minOf { it.createdMs },
            )
        }.sortedWith(
            compareByDescending<AppConnectionGroup> { it.txRate + it.rxRate }
                .thenBy { it.label.lowercase() },
        )

        val bytesNow = rows.associate {
            it.conn.key to (it.conn.txBytes to it.conn.rxBytes)
        }
        return ConnectionSample(groups, bytesNow)
    }

    private fun rateBetween(old: Long?, new: Long, intervalMs: Long): Long {
        if (old == null || new < old || intervalMs <= 0L) return 0L
        return (new - old) * 1000L / intervalMs
    }

    private fun ownerUid(cm: ConnectivityManager?, s: RawSession): Int {
        if (cm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
        return runCatching {
            // 找不到归属时返回 -1（系统内部 UNKNOWN_UID，非公开常量）
            cm.getConnectionOwnerUid(
                s.proto,
                InetSocketAddress(s.srcIp, s.srcPort),
                InetSocketAddress(s.dstIp, s.dstPort),
            )
        }.getOrDefault(-1)
    }

    // ---- 原生会话文本解析（纯函数，便于单测） ----

    internal data class RawSession(
        val key: String,
        val proto: Int,
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int,
        val upload: Long,
        val download: Long,
        val createdMs: Long,
        val domain: String?,
    )

    /**
     * 解析 hev 导出的行：
     * proto|srcIp|srcPort|dstIp|dstPort|upload|download|createdMs[|domain]。
     * 第 9 字段 domain 为 mapped-DNS 反查到的真实域名，可能缺失或为空。
     * 非法行跳过。
     */
    internal fun parseSessions(text: String): List<RawSession> {
        if (text.isBlank()) return emptyList()
        return text.lineSequence().mapNotNull { line ->
            val p = line.trim().split('|')
            if (p.size !in 8..9) return@mapNotNull null
            val proto = p[0].toIntOrNull()?.takeIf { it == 6 || it == 17 }
                ?: return@mapNotNull null
            val srcPort = p[2].toIntOrNull()?.takeIf { it in 0..65535 } ?: return@mapNotNull null
            val dstPort = p[4].toIntOrNull()?.takeIf { it in 0..65535 } ?: return@mapNotNull null
            val upload = p[5].toLongOrNull() ?: return@mapNotNull null
            val download = p[6].toLongOrNull() ?: return@mapNotNull null
            val createdMs = p[7].toLongOrNull() ?: return@mapNotNull null
            RawSession(
                key = "$proto|${p[1]}:$srcPort->${p[3]}:$dstPort",
                proto = proto,
                srcIp = p[1],
                srcPort = srcPort,
                dstIp = p[3],
                dstPort = dstPort,
                upload = upload,
                download = download,
                createdMs = createdMs,
                domain = p.getOrNull(8)?.trim()?.takeIf { it.isNotBlank() },
            )
        }.toList()
    }
}
