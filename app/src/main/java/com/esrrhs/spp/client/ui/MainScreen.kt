package com.esrrhs.spp.client.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esrrhs.spp.client.R
import com.esrrhs.spp.client.spp.Profile
import com.esrrhs.spp.client.util.Formatters
import com.esrrhs.spp.client.vpn.VpnState

// Shadowsocks 风格的状态色
private val SsGreen = Color(0xFF5FB878)
private val SsAmber = Color(0xFFF0A020)
private val SsRed = Color(0xFFE05252)
private val SsGray = Color(0xFF6E7681)
private val CircleOff = Color(0xFF2A2D31)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    profiles: List<Profile>,
    activeId: String?,
    vpnState: VpnState,
    session: SessionTraffic,
    testingPings: Boolean,
    qrProfile: Profile?,
    onProfileClick: (String) -> Unit,
    onSelectProfile: (String) -> Unit,
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
    var menuExpanded by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
    val activeProfile = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
    val connected = vpnState is VpnState.Connected
    val busy = vpnState is VpnState.Connecting || vpnState is VpnState.Disconnecting

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onScan) {
                        Icon(
                            Icons.Filled.QrCodeScanner,
                            contentDescription = stringResource(R.string.action_scan),
                        )
                    }
                    IconButton(onClick = onShowLeak) {
                        Icon(
                            Icons.Filled.Security,
                            contentDescription = stringResource(R.string.leak_title),
                        )
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null)
                        }
                        OverflowMenu(
                            expanded = menuExpanded,
                            testingPings = testingPings,
                            onDismiss = { menuExpanded = false },
                            onImport = onImport,
                            onExport = onExport,
                            onTestAll = onTestAll,
                            onFastest = onSelectFastest,
                            onCheck = onRunCheck,
                            onStats = onShowStats,
                            onHistory = onShowHistory,
                            onLogs = onShowLogs,
                            onSettings = onSettings,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd, containerColor = SsGreen) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_add_profile))
            }
        },
        bottomBar = {
            BottomActionsBar(
                connected = connected,
                onCheck = onRunCheck,
                onStats = onShowStats,
                onHistory = onShowHistory,
                onSettings = onSettings,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            PowerButton(
                state = vpnState,
                enabled = activeProfile != null,
                onClick = {
                    val id = activeProfile?.id ?: return@PowerButton
                    onProfileClick(id)
                },
            )

            Spacer(Modifier.height(28.dp))

            ProfileSelector(
                profile = activeProfile,
                connected = connected,
                onClick = { sheetOpen = true },
            )

            Spacer(Modifier.height(16.dp))

            StatusLine(vpnState)

            Spacer(Modifier.height(8.dp))

            if (activeProfile != null) {
                val tx = activeProfile.txBytes + if (activeProfile.id == activeId) session.tx else 0L
                val rx = activeProfile.rxBytes + if (activeProfile.id == activeId) session.rx else 0L
                Text(
                    text = stringResource(
                        R.string.format_traffic,
                        Formatters.formatBytes(tx),
                        Formatters.formatBytes(rx),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (connected || busy) {
                    Text(
                        text = "↑ ${Formatters.formatRate(session.txRate)}   " +
                            "↓ ${Formatters.formatRate(session.rxRate)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (vpnState is VpnState.Error && vpnState.message.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = vpnState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = SsRed,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    if (sheetOpen) {
        ProfileSheet(
            profiles = profiles,
            activeId = activeProfile?.id,
            onDismiss = { sheetOpen = false },
            onSelect = {
                onSelectProfile(it)
                sheetOpen = false
            },
            onTestPing = onTestPing,
            onShowQr = onShowQr,
            onEdit = onEdit,
            onDelete = onDelete,
        )
    }

    if (qrProfile != null) {
        QrShareDialog(profile = qrProfile, onDismiss = onDismissQr)
    }

    SelfCheckDialog(state = selfCheckState, onDismiss = onDismissCheck)
}

@Composable
private fun OverflowMenu(
    expanded: Boolean,
    testingPings: Boolean,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onTestAll: () -> Unit,
    onFastest: () -> Unit,
    onCheck: () -> Unit,
    onStats: () -> Unit,
    onHistory: () -> Unit,
    onLogs: () -> Unit,
    onSettings: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        MenuItem(Icons.Filled.FileDownload, R.string.action_import) { onImport(); onDismiss() }
        MenuItem(Icons.Filled.FileUpload, R.string.action_export) { onExport(); onDismiss() }
        HorizontalDivider()
        MenuItem(
            Icons.Filled.Speed,
            if (testingPings) R.string.action_testing else R.string.action_test_all,
        ) { onTestAll(); onDismiss() }
        MenuItem(Icons.Filled.Bolt, R.string.action_fastest) { onFastest(); onDismiss() }
        MenuItem(Icons.Filled.CheckCircle, R.string.action_check) { onCheck(); onDismiss() }
        HorizontalDivider()
        MenuItem(Icons.Filled.BarChart, R.string.action_stats) { onStats(); onDismiss() }
        MenuItem(Icons.Filled.History, R.string.action_history) { onHistory(); onDismiss() }
        MenuItem(Icons.Filled.Description, R.string.menu_logs) { onLogs(); onDismiss() }
        MenuItem(Icons.Filled.Settings, R.string.action_settings) { onSettings(); onDismiss() }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, labelRes: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(labelRes)) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

@Composable
private fun BottomActionsBar(
    connected: Boolean,
    onCheck: () -> Unit,
    onStats: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    androidx.compose.material3.BottomAppBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        BarItem(Icons.Filled.CheckCircle, R.string.action_check, connected, onCheck, Modifier.weight(1f))
        BarItem(Icons.Filled.BarChart, R.string.action_stats, true, onStats, Modifier.weight(1f))
        BarItem(Icons.Filled.History, R.string.action_history, true, onHistory, Modifier.weight(1f))
        BarItem(Icons.Filled.Settings, R.string.action_settings, true, onSettings, Modifier.weight(1f))
    }
}

@Composable
private fun BarItem(
    icon: ImageVector,
    labelRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = stringResource(labelRes),
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
        )
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
        )
    }
}

/** 大圆钮：未连接灰、连接中琥珀带进度环、已连接绿、出错红。 */
@Composable
private fun PowerButton(
    state: VpnState,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val color = when (state) {
        VpnState.Connected -> SsGreen
        VpnState.Connecting, VpnState.Disconnecting -> SsAmber
        VpnState.Paused -> SsAmber
        is VpnState.Error -> SsRed
        VpnState.Disconnected -> CircleOff
    }
    val busy = state is VpnState.Connecting || state is VpnState.Disconnecting

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(184.dp)
                .clip(CircleShape)
                .background(color)
                .clickable(enabled = enabled && !busy, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 5.dp,
                    modifier = Modifier.size(132.dp),
                )
            }
            Icon(
                Icons.Filled.PowerSettingsNew,
                contentDescription = stringResource(R.string.tile_label),
                tint = if (state == VpnState.Disconnected) SsGray else Color.White,
                modifier = Modifier.size(72.dp),
            )
        }
    }
}

@Composable
private fun ProfileSelector(
    profile: Profile?,
    connected: Boolean,
    onClick: () -> Unit,
) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(
            1.dp,
            if (connected) SsGreen else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile?.name ?: stringResource(R.string.empty_profiles_short),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = profile?.let { "${it.config.proto}  ${it.config.serverAddr}" } ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusLine(state: VpnState) {
    val (color, labelRes) = when (state) {
        VpnState.Disconnected -> SsGray to R.string.state_disconnected
        VpnState.Connecting -> SsAmber to R.string.state_connecting
        VpnState.Connected -> SsGreen to R.string.state_connected
        VpnState.Disconnecting -> SsAmber to R.string.state_disconnecting
        VpnState.Paused -> SsAmber to R.string.state_paused
        is VpnState.Error -> SsRed to R.string.state_error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSheet(
    profiles: List<Profile>,
    activeId: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onTestPing: (String) -> Unit,
    onShowQr: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.sheet_choose_profile),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (profiles.isEmpty()) {
                Text(
                    text = stringResource(R.string.empty_profiles),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            } else {
                LazyColumn {
                    items(profiles, key = { it.id }) { profile ->
                        SheetProfileRow(
                            profile = profile,
                            selected = profile.id == activeId,
                            onClick = { onSelect(profile.id) },
                            onTestPing = { onTestPing(profile.id) },
                            onShowQr = { onShowQr(profile.id) },
                            onEdit = { onEdit(profile.id) },
                            onDelete = { onDelete(profile.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetProfileRow(
    profile: Profile,
    selected: Boolean,
    onClick: () -> Unit,
    onTestPing: () -> Unit,
    onShowQr: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selected) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(SsGreen),
                    )
                }
            }
            val ping = profile.pingMs
            Text(
                text = "${profile.config.serverAddr} · " +
                    if (ping >= 0) stringResource(R.string.ping_ms, ping)
                    else stringResource(R.string.ping_untested),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onTestPing) {
            Icon(Icons.Filled.Speed, contentDescription = stringResource(R.string.action_ping))
        }
        IconButton(onClick = onShowQr) {
            Icon(Icons.Filled.QrCode, contentDescription = stringResource(R.string.action_qr))
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.action_delete),
                tint = SsRed,
            )
        }
    }
}
