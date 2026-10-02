package com.esrrhs.spp.client.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.esrrhs.spp.client.data.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("全局设置") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ToggleRow(
                title = "开机自动连接",
                subtitle = "设备重启后自动连接上次使用的配置",
                checked = settings.bootStart,
                onCheckedChange = { onChange(settings.copy(bootStart = it)) },
            )
            ToggleRow(
                title = "断线自动重连",
                subtitle = "连接意外中断或切换 WiFi/蜂窝网络时按 1s/2s/4s… 退避自动重连（最多 5 次）",
                checked = settings.autoReconnect,
                onCheckedChange = { onChange(settings.copy(autoReconnect = it)) },
            )
            ToggleRow(
                title = "新配置默认绕过局域网",
                subtitle = "新建配置时默认开启「绕过私有网段」智能分流",
                checked = settings.defaultBypassLan,
                onCheckedChange = { onChange(settings.copy(defaultBypassLan = it)) },
            )

            AlwaysOnCard()
        }
    }
}

/**
 * Always-on VPN 只能由用户在系统设置中开启，App 无权自行切换；
 * 这里给出步骤说明并提供直达入口（部分 ROM 无 VPN 设置页时回退到无线设置）。
 */
@Composable
private fun AlwaysOnCard() {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "系统常驻 VPN（Always-on）",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = "Android 不允许 App 自行开启常驻 VPN，需要手动设置：\n" +
                "1. 点下方按钮打开系统 VPN 设置；\n" +
                "2. 找到「SPP Client」，点旁边的齿轮/设置图标；\n" +
                "3. 开启「始终开启的 VPN」；\n" +
                "4. 如需全程防泄漏，再开启「阻止未使用 VPN 的连接」。\n\n" +
                "也可以在通知栏 Quick Settings 的编辑页把「SPP VPN」磁贴拖到常用位置，一键启停。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { openSystemVpnSettings(context) }) {
            Text("打开系统 VPN 设置")
        }
    }
}

private fun openSystemVpnSettings(context: Context) {
    val intent = Intent("android.net.vpn.SETTINGS")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    } else {
        val fallback = Intent(Settings.ACTION_WIRELESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(fallback) }.onFailure {
            Toast.makeText(context, "无法打开系统设置，请手动进入 VPN 设置", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
