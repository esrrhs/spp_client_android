package com.esrrhs.spp.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.esrrhs.spp.client.spp.Profile
import com.esrrhs.spp.client.util.Formatters
import com.esrrhs.spp.client.util.TrafficTotals
import com.esrrhs.spp.client.vpn.VpnState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    profiles: List<Profile>,
    activeId: String?,
    vpnState: VpnState,
    session: SessionTraffic,
    onResetTraffic: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmReset by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("流量统计") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (vpnState is VpnState.Connected || vpnState is VpnState.Connecting) {
                SessionCard(activeId, vpnState, session, profiles)
            }

            val totals = TrafficTotals.of(profiles)
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("累计流量（全部配置）", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "↑ ${Formatters.formatBytes(totals.txBytes)}    " +
                            "↓ ${Formatters.formatBytes(totals.rxBytes)}    " +
                            "合计 ${Formatters.formatBytes(totals.totalBytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            Text("分配置统计", style = MaterialTheme.typography.titleSmall)

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileTrafficRow(
                        profile = profile,
                        active = profile.id == activeId &&
                            (vpnState is VpnState.Connected || vpnState is VpnState.Connecting),
                        session = session,
                    )
                }
            }

            OutlinedButton(
                onClick = { confirmReset = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            ) {
                Text("清空累计流量")
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("清空累计流量？") },
            text = { Text("各配置的累计上下行将被清零，配置本身不受影响。此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    onResetTraffic()
                    confirmReset = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SessionCard(
    activeId: String?,
    vpnState: VpnState,
    session: SessionTraffic,
    profiles: List<Profile>,
) {
    val activeProfile = profiles.firstOrNull { it.id == activeId }
    // 当前配置总量 = 历史累计 + 本会话实时
    val tx = (activeProfile?.txBytes ?: 0L) + session.tx
    val rx = (activeProfile?.rxBytes ?: 0L) + session.rx
    val durationMs = session.connectedAtMs?.let { System.currentTimeMillis() - it } ?: 0L

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (vpnState is VpnState.Connected) "当前会话" else "重连中…",
                style = MaterialTheme.typography.titleMedium,
            )
            Text("连接时长：${Formatters.formatDuration(durationMs)}", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "↑ ${Formatters.formatBytes(tx)}（${Formatters.formatRate(session.txRate)}）",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "↓ ${Formatters.formatBytes(rx)}（${Formatters.formatRate(session.rxRate)}）",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ProfileTrafficRow(
    profile: Profile,
    active: Boolean,
    session: SessionTraffic,
) {
    val tx = profile.txBytes + if (active) session.tx else 0L
    val rx = profile.rxBytes + if (active) session.rx else 0L
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "↑ ${Formatters.formatBytes(tx)}    ↓ ${Formatters.formatBytes(rx)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
