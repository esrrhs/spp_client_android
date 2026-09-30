package com.esrrhs.spp.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.esrrhs.spp.client.spp.SppConfig
import com.esrrhs.spp.client.vpn.VpnState

private val StateGreen = Color(0xFF2E7D32)
private val StateAmber = Color(0xFFEF6C00)
private val StateRed = Color(0xFFC62828)
private val StateGray = Color(0xFF9E9E9E)

@Composable
fun MainScreen(
    form: SppConfig,
    vpnState: VpnState,
    saved: Boolean,
    traffic: TrafficStats?,
    onFormChange: (SppConfig) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSave: () -> Unit,
    onShowLogs: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "SPP Client", style = typography.headlineMedium)
            TextButton(onClick = onShowLogs) { Text("运行日志") }
        }
        Text(
            text = "整机流量与 DNS 经 SPP Server 转发",
            style = typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        StatusCard(vpnState, traffic)

        ConnectButton(
            state = vpnState,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
        )

        ConfigForm(
            form = form,
            saved = saved,
            onFormChange = onFormChange,
            onSave = onSave,
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "首次连接需授予系统 VPN 权限；断开按钮随时可停。" +
                "DNS 查询在本地被映射应答，不会直连运营商。",
            style = typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusCard(state: VpnState, traffic: TrafficStats?) {
    val (color, label) = when (state) {
        VpnState.Disconnected -> StateGray to "未连接"
        VpnState.Connecting -> StateAmber to "连接中…"
        VpnState.Connected -> StateGreen to "已连接"
        VpnState.Disconnecting -> StateAmber to "断开中…"
        is VpnState.Error -> StateRed to "错误"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(color, CircleShape),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = label, style = typography.titleMedium)
            }
            if (state is VpnState.Error && state.message.isNotBlank()) {
                Text(
                    text = state.message,
                    style = typography.bodySmall,
                    color = StateRed,
                )
            }
            if (state is VpnState.Connected && traffic != null) {
                Text(
                    text = "↑ ${formatBytes(traffic.txBytes)}（${formatBytes(traffic.txRate)}/s）" +
                        "    ↓ ${formatBytes(traffic.rxBytes)}（${formatBytes(traffic.rxRate)}/s）",
                    style = typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 自适应单位格式化字节数。 */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = -1
    do {
        value /= 1024.0
        unitIndex++
    } while (value >= 1024 && unitIndex < units.lastIndex)
    return String.format("%.1f%s", value, units[unitIndex])
}

@Composable
private fun ConnectButton(
    state: VpnState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val busy = state is VpnState.Connecting || state is VpnState.Disconnecting
    val connected = state is VpnState.Connected
    Button(
        onClick = if (connected) onDisconnect else onConnect,
        enabled = !busy,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        colors = if (connected) {
            ButtonDefaults.buttonColors(containerColor = StateRed)
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(
            text = when (state) {
                VpnState.Connecting -> "连接中…"
                VpnState.Disconnecting -> "断开中…"
                VpnState.Connected -> "断开连接"
                is VpnState.Error -> "重试连接"
                VpnState.Disconnected -> "连接"
            },
            style = typography.titleMedium,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigForm(
    form: SppConfig,
    saved: Boolean,
    onFormChange: (SppConfig) -> Unit,
    onSave: () -> Unit,
) {
    var protoExpanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "服务器配置", style = typography.titleMedium)

        OutlinedTextField(
            value = form.serverHost,
            onValueChange = { onFormChange(form.copy(serverHost = it)) },
            label = { Text("服务器地址（host）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = form.serverPort.takeIf { it > 0 }?.toString() ?: "",
            onValueChange = { text ->
                onFormChange(form.copy(serverPort = text.toIntOrNull() ?: 0))
            },
            label = { Text("端口") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        ExposedDropdownMenuBox(
            expanded = protoExpanded,
            onExpandedChange = { protoExpanded = it },
        ) {
            OutlinedTextField(
                value = form.proto,
                onValueChange = {},
                readOnly = true,
                label = { Text("传输协议（proto）") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = protoExpanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = protoExpanded,
                onDismissRequest = { protoExpanded = false },
            ) {
                SppConfig.PROTOS.forEach { proto ->
                    DropdownMenuItem(
                        text = { Text(proto) },
                        onClick = {
                            onFormChange(form.copy(proto = proto))
                            protoExpanded = false
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = form.key,
            onValueChange = { onFormChange(form.copy(key = it)) },
            label = { Text("认证 Key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = form.encrypt,
            onValueChange = { onFormChange(form.copy(encrypt = it)) },
            label = { Text("加密 Key（留空则不加密）") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = form.compress.toString(),
            onValueChange = { text ->
                onFormChange(form.copy(compress = text.toIntOrNull() ?: 0))
            },
            label = { Text("压缩阈值（字节，0=关闭）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(text = "接管 IPv6 流量", style = typography.bodyLarge)
                Text(
                    text = "关闭则仅代理 IPv4（IPv6 可能直连）",
                    style = typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = form.enableIpv6,
                onCheckedChange = { onFormChange(form.copy(enableIpv6 = it)) },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onSave) {
                Text("保存配置")
            }
            Spacer(modifier = Modifier.width(12.dp))
            if (saved) {
                Text(
                    text = "已保存",
                    style = typography.bodyMedium,
                    color = StateGreen,
                )
            }
        }
    }
}
