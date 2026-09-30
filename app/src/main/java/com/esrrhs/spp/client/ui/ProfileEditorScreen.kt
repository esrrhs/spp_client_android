package com.esrrhs.spp.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.esrrhs.spp.client.spp.PerAppMode
import com.esrrhs.spp.client.spp.Profile
import com.esrrhs.spp.client.spp.SppConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
    initial: Profile,
    isNew: Boolean,
    onSave: (Profile, (String?) -> Unit) -> Unit,
    onPickApps: (Set<String>) -> Unit,
    onBack: () -> Unit,
) {
    var profile by remember { mutableStateOf(initial) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "添加配置" else "编辑配置") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    TextButton(onClick = { onSave(profile) { error -> if (error == null) onBack() } }) {
                        Text("保存")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = profile.name,
                onValueChange = { profile = profile.copy(name = it) },
                label = { Text("配置名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            PerAppSection(
                profile = profile,
                onChange = { profile = it },
                onPickApps = onPickApps,
            )

            LanBypassSection(
                checked = profile.bypassLan,
                onChange = { v -> profile = profile.copy(bypassLan = v) },
            )

            ConnectionFields(
                config = profile.config,
                onChange = { c -> profile = profile.copy(config = c) },
            )
        }
    }
}

@Composable
private fun PerAppSection(
    profile: Profile,
    onChange: (Profile) -> Unit,
    onPickApps: (Set<String>) -> Unit,
) {
    Text(text = "分应用代理", style = typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PerAppMode.entries.forEach { mode ->
            FilterChip(
                selected = profile.perAppMode == mode,
                onClick = { onChange(profile.copy(perAppMode = mode)) },
                label = {
                    Text(
                        when (mode) {
                            PerAppMode.ALL -> "全部"
                            PerAppMode.ALLOWED -> "仅选中"
                            PerAppMode.DISALLOWED -> "排除选中"
                        },
                    )
                },
            )
        }
    }
    if (profile.perAppMode != PerAppMode.ALL) {
        OutlinedButton(onClick = { onPickApps(profile.perAppPackages.toSet()) }) {
            Text("选择应用（${profile.perAppPackages.size}）")
        }
    }
}

@Composable
private fun LanBypassSection(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column {
            Text(text = "智能分流：绕过局域网", style = typography.bodyLarge)
            Text(
                text = "私有网段直连，其余走代理（含 IPv6 全球单播）",
                style = typography.bodySmall,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionFields(
    config: SppConfig,
    onChange: (SppConfig) -> Unit,
) {
    var protoExpanded by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = config.serverHost,
        onValueChange = { onChange(config.copy(serverHost = it)) },
        label = { Text("服务器地址") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = config.serverPort.takeIf { it > 0 }?.toString() ?: "",
        onValueChange = { text -> onChange(config.copy(serverPort = text.toIntOrNull() ?: 0)) },
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
            value = config.proto,
            onValueChange = {},
            readOnly = true,
            label = { Text("传输协议") },
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
                        onChange(config.copy(proto = proto))
                        protoExpanded = false
                    },
                )
            }
        }
    }

    OutlinedTextField(
        value = config.key,
        onValueChange = { onChange(config.copy(key = it)) },
        label = { Text("认证 Key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = config.encrypt,
        onValueChange = { onChange(config.copy(encrypt = it)) },
        label = { Text("加密 Key（留空则不加密）") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = config.compress.toString(),
        onValueChange = { text -> onChange(config.copy(compress = text.toIntOrNull() ?: 0)) },
        label = { Text("压缩阈值（0=关闭）") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(text = "接管 IPv6 流量", style = typography.bodyLarge)
            Text(
                text = "关闭则仅代理 IPv4（IPv6 可能直连）",
                style = typography.bodySmall,
            )
        }
        Switch(
            checked = config.enableIpv6,
            onCheckedChange = { onChange(config.copy(enableIpv6 = it)) },
        )
    }

    Spacer(modifier = Modifier.width(1.dp))
}
