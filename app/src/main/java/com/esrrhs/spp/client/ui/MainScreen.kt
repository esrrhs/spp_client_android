package com.esrrhs.spp.client.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.esrrhs.spp.client.R
import com.esrrhs.spp.client.spp.Profile
import com.esrrhs.spp.client.util.Formatters
import com.esrrhs.spp.client.vpn.VpnState

private val StateGreen = Color(0xFF2E7D32)
private val StateAmber = Color(0xFFEF6C00)
private val StateRed = Color(0xFFC62828)
private val StateGray = Color(0xFF9E9E9E)

@Composable
fun MainScreen(
    profiles: List<Profile>,
    activeId: String?,
    vpnState: VpnState,
    session: SessionTraffic,
    testingPings: Boolean,
    qrProfile: Profile?,
    onProfileClick: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
    onShowLogs: () -> Unit,
    onSettings: () -> Unit,
    onScan: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onTestPing: (String) -> Unit,
    onTestAll: () -> Unit,
    onSelectFastest: () -> Unit,
    onShowStats: () -> Unit,
    onShowHistory: () -> Unit,
    onShowLeak: () -> Unit,
    onRunCheck: () -> Unit,
    onDismissCheck: () -> Unit,
    selfCheckState: SelfCheckState,
    onShowQr: (String) -> Unit,
    onDismissQr: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = stringResource(R.string.app_name), style = typography.headlineSmall)
            TextButton(onClick = onShowLogs) {
                Text(stringResource(R.string.menu_logs))
            }
        }

        GlobalState(vpnState)

        ToolsRow(
            testingPings = testingPings,
            connected = vpnState is VpnState.Connected,
            onScan = onScan,
            onImport = onImport,
            onExport = onExport,
            onTestAll = onTestAll,
            onSelectFastest = onSelectFastest,
            onStats = onShowStats,
            onHistory = onShowHistory,
            onLeak = onShowLeak,
            onCheck = onRunCheck,
            onSettings = onSettings,
        )

        Box(modifier = Modifier.weight(1f)) {
            if (profiles.isEmpty()) {
                Text(
                    text = stringResource(R.string.empty_profiles),
                    style = typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 40.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(profiles, key = { it.id }) { profile ->
                        ProfileItem(
                            profile = profile,
                            active = profile.id == activeId,
                            vpnState = vpnState,
                            session = session,
                            onClick = { onProfileClick(profile.id) },
                            onEdit = { onEdit(profile.id) },
                            onDelete = { onDelete(profile.id) },
                            onTestPing = { onTestPing(profile.id) },
                            onShowQr = { onShowQr(profile.id) },
                        )
                    }
                }
            }
        }

        OutlinedButton(
            onClick = onAdd,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .height(48.dp),
        ) {
            Text(stringResource(R.string.action_add_profile), style = typography.titleMedium)
        }
    }

    if (qrProfile != null) {
        QrShareDialog(profile = qrProfile, onDismiss = onDismissQr)
    }

    SelfCheckDialog(state = selfCheckState, onDismiss = onDismissCheck)
}

@Composable
private fun ToolsRow(
    testingPings: Boolean,
    connected: Boolean,
    onScan: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onTestAll: () -> Unit,
    onSelectFastest: () -> Unit,
    onStats: () -> Unit,
    onHistory: () -> Unit,
    onLeak: () -> Unit,
    onCheck: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ToolButton(stringResource(R.string.action_scan), onScan)
        ToolButton(stringResource(R.string.action_import), onImport)
        ToolButton(stringResource(R.string.action_export), onExport)
        ToolButton(
            stringResource(
                if (testingPings) R.string.action_testing else R.string.action_test_all,
            ),
            onTestAll,
        )
        ToolButton(stringResource(R.string.action_fastest), onSelectFastest)
        ToolButton(stringResource(R.string.action_stats), onStats)
        ToolButton(stringResource(R.string.action_history), onHistory)
        ToolButton(stringResource(R.string.action_leak), onLeak)
        ToolButton(stringResource(R.string.action_check), onCheck, enabled = connected)
        ToolButton(stringResource(R.string.action_settings), onSettings)
    }
}

@Composable
private fun ToolButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) { Text(text, style = typography.labelLarge) }
}

@Composable
private fun GlobalState(state: VpnState) {
    val (color, labelRes) = when (state) {
        VpnState.Disconnected -> StateGray to R.string.state_disconnected
        VpnState.Connecting -> StateAmber to R.string.state_connecting
        VpnState.Connected -> StateGreen to R.string.state_connected
        VpnState.Disconnecting -> StateAmber to R.string.state_disconnecting
        VpnState.Paused -> StateAmber to R.string.state_paused
        is VpnState.Error -> StateRed to R.string.state_error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = stringResource(labelRes), style = typography.bodyMedium)
        if (state is VpnState.Error && state.message.isNotBlank()) {
            Text(
                text = "：${state.message}",
                style = typography.bodySmall,
                color = StateRed,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProfileItem(
    profile: Profile,
    active: Boolean,
    vpnState: VpnState,
    session: SessionTraffic,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTestPing: () -> Unit,
    onShowQr: () -> Unit,
) {
    val connected = active && vpnState is VpnState.Connected
    val borderColor = when {
        connected -> StateGreen
        active -> StateAmber
        else -> Color.Transparent
    }
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(if (connected || active) 2.dp else 1.dp, borderColor),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = profile.name,
                    style = typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                PingLabel(profile.pingMs)
            }

            Text(
                text = "${profile.config.proto}  ${profile.config.serverAddr}",
                style = typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            val tx = profile.txBytes + if (active) session.tx else 0
            val rx = profile.rxBytes + if (active) session.rx else 0
            Text(
                text = stringResource(
                    R.string.format_traffic,
                    Formatters.formatBytes(tx),
                    Formatters.formatBytes(rx),
                ),
                style = typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ItemButton(stringResource(R.string.action_ping), onTestPing)
                ItemButton(stringResource(R.string.action_qr), onShowQr)
                ItemButton(stringResource(R.string.action_edit), onEdit)
                ItemButton(stringResource(R.string.action_delete), onDelete, color = StateRed)
            }
        }
    }
}

@Composable
private fun PingLabel(pingMs: Int) {
    val (text, color) = when {
        pingMs < 0 -> stringResource(R.string.ping_untested) to StateGray
        else -> stringResource(R.string.ping_ms, pingMs) to
            if (pingMs < 200) StateGreen else StateRed
    }
    Text(text = text, style = typography.labelMedium, color = color)
}

@Composable
private fun ItemButton(
    text: String,
    onClick: () -> Unit,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) { Text(text, style = typography.labelMedium, color = color) }
}
