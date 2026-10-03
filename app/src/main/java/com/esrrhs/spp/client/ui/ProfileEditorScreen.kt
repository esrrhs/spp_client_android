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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.esrrhs.spp.client.R
import com.esrrhs.spp.client.spp.PerAppMode
import com.esrrhs.spp.client.spp.Profile
import com.esrrhs.spp.client.spp.SppConfig
import com.esrrhs.spp.client.spp.ValidationError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
    initial: Profile,
    isNew: Boolean,
    onSave: (Profile, (ValidationError?) -> Unit) -> Unit,
    onPickApps: (Set<String>) -> Unit,
    onBack: () -> Unit,
) {
    var profile by remember { mutableStateOf(initial) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isNew) R.string.editor_add_title else R.string.editor_edit_title,
                        ),
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(onClick = { onSave(profile) { error -> if (error == null) onBack() } }) {
                        Text(stringResource(R.string.action_save))
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
                label = { Text(stringResource(R.string.field_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            PerAppSection(
                profile = profile,
                onChange = { profile = it },
                onPickApps = onPickApps,
            )

            SmartSplitSection(
                profile = profile,
                onChange = { profile = it },
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
    Text(text = stringResource(R.string.section_per_app), style = typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PerAppMode.entries.forEach { mode ->
            FilterChip(
                selected = profile.perAppMode == mode,
                onClick = { onChange(profile.copy(perAppMode = mode)) },
                label = {
                    Text(
                        stringResource(
                            when (mode) {
                                PerAppMode.ALL -> R.string.per_app_all
                                PerAppMode.ALLOWED -> R.string.per_app_allowed
                                PerAppMode.DISALLOWED -> R.string.per_app_disallowed
                            },
                        ),
                    )
                },
            )
        }
    }
    if (profile.perAppMode != PerAppMode.ALL) {
        OutlinedButton(onClick = { onPickApps(profile.perAppPackages.toSet()) }) {
            Text(stringResource(R.string.action_choose_apps, profile.perAppPackages.size))
        }
    }
}

@Composable
private fun SmartSplitSection(
    profile: Profile,
    onChange: (Profile) -> Unit,
) {
    Text(text = stringResource(R.string.section_smart_split), style = typography.titleMedium)

    SplitToggle(
        title = stringResource(R.string.split_bypass_lan_title),
        subtitle = stringResource(R.string.split_bypass_lan_subtitle),
        checked = profile.bypassLan,
        onCheckedChange = { v -> onChange(profile.copy(bypassLan = v)) },
    )
    SplitToggle(
        title = stringResource(R.string.split_bypass_cn_title),
        subtitle = stringResource(R.string.split_bypass_cn_subtitle),
        checked = profile.bypassCn,
        onCheckedChange = { v -> onChange(profile.copy(bypassCn = v)) },
    )
}

@Composable
private fun SplitToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = typography.bodyLarge)
            Text(text = subtitle, style = typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionFields(
    config: SppConfig,
    onChange: (SppConfig) -> Unit,
) {
    var kindExpanded by remember { mutableStateOf(false) }
    var protoExpanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = kindExpanded,
        onExpandedChange = { kindExpanded = it },
    ) {
        OutlinedTextField(
            value = if (config.isSocks5) {
                stringResource(R.string.kind_socks5)
            } else {
                stringResource(R.string.kind_spp)
            },
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.field_kind)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = kindExpanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = kindExpanded,
            onDismissRequest = { kindExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.kind_spp)) },
                onClick = {
                    onChange(config.copy(kind = SppConfig.KIND_SPP))
                    kindExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.kind_socks5)) },
                onClick = {
                    onChange(config.copy(kind = SppConfig.KIND_SOCKS5))
                    kindExpanded = false
                },
            )
        }
    }

    OutlinedTextField(
        value = config.serverHost,
        onValueChange = { onChange(config.copy(serverHost = it)) },
        label = { Text(stringResource(R.string.field_server_host)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = config.serverPort.takeIf { it > 0 }?.toString() ?: "",
        onValueChange = { text -> onChange(config.copy(serverPort = text.toIntOrNull() ?: 0)) },
        label = { Text(stringResource(R.string.field_port)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    if (config.isSocks5) {
        OutlinedTextField(
            value = config.username,
            onValueChange = { onChange(config.copy(username = it)) },
            label = { Text(stringResource(R.string.field_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = config.password,
            onValueChange = { onChange(config.copy(password = it)) },
            label = { Text(stringResource(R.string.field_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (!config.isSocks5) ExposedDropdownMenuBox(
        expanded = protoExpanded,
        onExpandedChange = { protoExpanded = it },
    ) {
        OutlinedTextField(
            value = config.proto,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.field_proto)) },
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

    if (!config.isSocks5) OutlinedTextField(
        value = config.key,
        onValueChange = { onChange(config.copy(key = it)) },
        label = { Text(stringResource(R.string.field_key)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )

    if (!config.isSocks5) OutlinedTextField(
        value = config.encrypt,
        onValueChange = { onChange(config.copy(encrypt = it)) },
        label = { Text(stringResource(R.string.field_encrypt)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )

    if (!config.isSocks5) OutlinedTextField(
        value = config.compress.toString(),
        onValueChange = { text -> onChange(config.copy(compress = text.toIntOrNull() ?: 0)) },
        label = { Text(stringResource(R.string.field_compress)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(text = stringResource(R.string.field_ipv6_title), style = typography.bodyLarge)
            Text(
                text = stringResource(R.string.field_ipv6_subtitle),
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
