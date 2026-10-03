package com.esrrhs.spp.client.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.esrrhs.spp.client.R
import com.esrrhs.spp.client.util.IpGeoInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IpScreen(
    state: IpQueryState,
    connected: Boolean,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) {
        if (state is IpQueryState.Idle) onRefresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ip_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = state !is IpQueryState.Running) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.ip_refresh))
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (state) {
                is IpQueryState.Idle, is IpQueryState.Running -> LoadingBox()
                is IpQueryState.Done -> ResultContent(state, connected, onRefresh)
            }
        }
    }
}

@Composable
private fun LoadingBox() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.ip_querying),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ResultContent(
    state: IpQueryState.Done,
    connected: Boolean,
    onRefresh: () -> Unit,
) {
    val anyResult = state.tunnel != null || state.direct != null
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.tunnel != null) {
            IpCard(
                icon = Icons.Filled.Security,
                title = stringResource(R.string.ip_exit),
                info = state.tunnel,
                highlight = true,
            )
        }
        if (state.direct != null) {
            IpCard(
                icon = Icons.Filled.Smartphone,
                title = stringResource(R.string.ip_direct),
                info = state.direct,
                highlight = false,
            )
        }
        if (!anyResult) {
            Text(
                text = stringResource(R.string.ip_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.ip_retry))
            }
        }
        if (!connected) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.ip_not_connected_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IpCard(
    icon: ImageVector,
    title: String,
    info: IpGeoInfo,
    highlight: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (highlight) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (info.flagEmoji.isNotEmpty()) {
                    Text(
                        text = info.flagEmoji,
                        fontSize = 30.sp,
                    )
                    Spacer(Modifier.size(10.dp))
                }
                Text(
                    text = info.ip,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (info.locationLine.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = info.locationLine,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            info.isp?.let { isp ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = isp,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
