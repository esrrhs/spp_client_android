package com.esrrhs.spp.client.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.esrrhs.spp.client.R
import com.esrrhs.spp.client.data.TimingRecord
import com.esrrhs.spp.client.util.BrowserTimingBridge
import com.esrrhs.spp.client.vpn.ActiveSession
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor
import kotlinx.coroutines.launch
import java.net.URI
import java.util.Locale

// 时序阶段标准色彩（Chrome / Firefox DevTools 同款色系）
private val ColorDns = Color(0xFF1E88E5)       // 蓝色: DNS 查询
private val ColorConnect = Color(0xFFFB8C00)   // 橙色: TCP 握手
private val ColorSsl = Color(0xFF8E24AA)       // 紫色: SSL/TLS
private val ColorTtfb = Color(0xFF43A047)      // 绿色: 等待首字节 (TTFB)
private val ColorDownload = Color(0xFF00ACC1)  // 青色: 下载
private val ColorOther = Color(0xFF757575)     // 灰色: 排队/其他

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticBrowserScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var urlInput by remember { mutableStateOf("https://www.google.com") }
    var currentUrl by remember { mutableStateOf("https://www.google.com") }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var isPageLoading by remember { mutableStateOf(false) }
    var showDevTools by remember { mutableStateOf(true) }
    var devToolsHeightFraction by remember { mutableFloatStateOf(0.45f) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0: 网络时序 1: 控制台日志

    val timingBridge = remember { BrowserTimingBridge() }
    val records by timingBridge.records.collectAsState()
    val consoleLogs by timingBridge.logs.collectAsState()

    var selectedRecord by remember { mutableStateOf<TimingRecord?>(null) }

    BackHandler {
        if (webViewInstance?.canGoBack() == true) {
            webViewInstance?.goBack()
        } else {
            onBack()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                val executor = Executor { it.run() }
                ProxyController.getInstance().clearProxyOverride(executor) {}
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = {
                                val target = sanitizeUrl(urlInput)
                                urlInput = target
                                currentUrl = target
                                timingBridge.currentPageUrl = target
                                selectedRecord = null
                                webViewInstance?.loadUrl(target)
                            }),
                            trailingIcon = {
                                if (urlInput.isNotBlank()) {
                                    IconButton(
                                        onClick = { urlInput = "" },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Filled.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        )
                        Spacer(Modifier.width(6.dp))
                        IconButton(onClick = {
                            val target = sanitizeUrl(urlInput)
                            urlInput = target
                            currentUrl = target
                            timingBridge.currentPageUrl = target
                            selectedRecord = null
                            webViewInstance?.loadUrl(target)
                        }) {
                            Icon(Icons.Filled.ArrowForward, contentDescription = stringResource(R.string.diag_go))
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        selectedRecord = null
                        webViewInstance?.reload()
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.diag_refresh))
                    }
                    IconButton(onClick = { showDevTools = !showDevTools }) {
                        BadgedBox(
                            badge = {
                                if (records.isNotEmpty()) {
                                    Badge { Text("${records.size}") }
                                }
                            }
                        ) {
                            Icon(
                                if (showDevTools) Icons.Filled.KeyboardArrowDown else Icons.Filled.Timeline,
                                contentDescription = stringResource(R.string.diag_toggle_devtools),
                                tint = if (showDevTools) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 加载进度条
            if (isPageLoading && loadProgress < 100) {
                LinearProgressIndicator(
                    progress = { loadProgress / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // WebView 与时序面板的垂直布局
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(if (showDevTools) (1f - devToolsHeightFraction) else 1f)
            ) {
                AndroidView(
                    factory = { ctx ->
                        createWebView(
                            context = ctx,
                            timingBridge = timingBridge,
                            onProgressChanged = { loadProgress = it },
                            onLoadingStateChanged = { loading, url ->
                                isPageLoading = loading
                                if (url != null) {
                                    urlInput = url
                                    currentUrl = url
                                    timingBridge.currentPageUrl = url
                                }
                            }
                        ).also {
                            webViewInstance = it
                            it.loadUrl(currentUrl)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 时序面板 (时序表与 Console 日志)
            AnimatedVisibility(visible = showDevTools) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(devToolsHeightFraction / (1f - devToolsHeightFraction + devToolsHeightFraction)),
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // DevTools Header / Tabs
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(modifier = Modifier.weight(1f)) {
                                FilterChip(
                                    selected = selectedTab == 0,
                                    onClick = { selectedTab = 0 },
                                    label = { Text("${stringResource(R.string.diag_tab_network)} (${records.size})") },
                                    leadingIcon = { Icon(Icons.Filled.Timeline, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                                FilterChip(
                                    selected = selectedTab == 1,
                                    onClick = { selectedTab = 1 },
                                    label = { Text("${stringResource(R.string.diag_tab_console)} (${consoleLogs.size})") },
                                    leadingIcon = { Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                )
                            }
                            IconButton(onClick = { timingBridge.clear(); selectedRecord = null }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.action_clear), modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = {
                                devToolsHeightFraction = if (devToolsHeightFraction < 0.6f) 0.75f else 0.40f
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    if (devToolsHeightFraction > 0.5f) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // 阶段色彩图例
                        TimingLegendBar()

                        HorizontalDivider()

                        // Tab 内容
                        when (selectedTab) {
                            0 -> NetworkWaterfallTab(
                                records = records,
                                selected = selectedRecord,
                                onSelect = { selectedRecord = it }
                            )
                            1 -> ConsoleLogsTab(logs = consoleLogs)
                        }
                    }
                }
            }
        }
    }

    // 资源详情弹出框
    if (selectedRecord != null) {
        RecordDetailDialog(record = selectedRecord!!, onDismiss = { selectedRecord = null })
    }
}

@Composable
private fun TimingLegendBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LegendDot(ColorDns, stringResource(R.string.diag_phase_dns))
        LegendDot(ColorConnect, stringResource(R.string.diag_phase_connect))
        LegendDot(ColorSsl, stringResource(R.string.diag_phase_ssl))
        LegendDot(ColorTtfb, stringResource(R.string.diag_phase_ttfb))
        LegendDot(ColorDownload, stringResource(R.string.diag_phase_download))
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
    }
}

@Composable
private fun NetworkWaterfallTab(
    records: List<TimingRecord>,
    selected: TimingRecord?,
    onSelect: (TimingRecord) -> Unit,
) {
    if (records.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.diag_empty_network),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // 计算整批记录的最大总跨度，用于对齐瀑布流百分比
    val maxTimelineMs = remember(records) {
        records.maxOfOrNull { it.startTime + it.duration }?.coerceAtLeast(10.0) ?: 100.0
    }

    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
    ) {
        items(records) { r ->
            WaterfallRow(
                record = r,
                totalTimelineMs = maxTimelineMs,
                isSelected = r == selected,
                onClick = { onSelect(r) }
            )
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun WaterfallRow(
    record: TimingRecord,
    totalTimelineMs: Double,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val displayName = remember(record.name) {
        runCatching {
            val uri = URI(record.name)
            val path = uri.path?.substringAfterLast('/').orEmpty()
            if (path.isNotBlank()) path else (uri.host ?: record.name)
        }.getOrDefault(record.name)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 名称与类型
        Column(modifier = Modifier.width(110.dp)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = record.initiatorType.ifBlank { "other" },
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (record.nextHopProtocol.isNotBlank()) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = record.nextHopProtocol,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Spacer(Modifier.width(6.dp))

        // 瀑布流甘特条 (Waterfall Bar)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(18.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            val startWeight = (record.startTime / totalTimelineMs).coerceIn(0.0, 1.0).toFloat()
            val durationWeight = (record.duration / totalTimelineMs).coerceIn(0.01, 1.0).toFloat()

            // 整个资源的有效条
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = (startWeight + durationWeight).coerceAtMost(1f))
                    .padding(start = (startWeight * 200).coerceAtLeast(0f).dp) // 相对偏移
            ) {
                // 如果有阶段明细，按比例绘制多段彩色条
                if (record.duration > 0) {
                    val d = record.duration
                    val dnsW = (record.dnsDuration / d).toFloat().coerceIn(0f, 1f)
                    val connW = ((record.connectDuration - record.sslDuration).coerceAtLeast(0.0) / d).toFloat().coerceIn(0f, 1f)
                    val sslW = (record.sslDuration / d).toFloat().coerceIn(0f, 1f)
                    val ttfbW = (record.ttfb / d).toFloat().coerceIn(0f, 1f)
                    val downW = (record.downloadDuration / d).toFloat().coerceIn(0f, 1f)

                    if (dnsW > 0) Box(Modifier.fillMaxHeight().weight(dnsW).background(ColorDns))
                    if (connW > 0) Box(Modifier.fillMaxHeight().weight(connW).background(ColorConnect))
                    if (sslW > 0) Box(Modifier.fillMaxHeight().weight(sslW).background(ColorSsl))
                    if (ttfbW > 0) Box(Modifier.fillMaxHeight().weight(ttfbW).background(ColorTtfb))
                    if (downW > 0) Box(Modifier.fillMaxHeight().weight(downW).background(ColorDownload))

                    // 如果以上阶段都是 0（如缓存命中），给一段默认颜色
                    if (dnsW <= 0 && connW <= 0 && sslW <= 0 && ttfbW <= 0 && downW <= 0) {
                        Box(Modifier.fillMaxSize().background(ColorOther))
                    }
                } else {
                    Box(Modifier.fillMaxSize().background(ColorOther))
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        // 总耗时数字
        Text(
            text = "${record.duration.toInt()} ms",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            fontSize = 11.sp,
            modifier = Modifier.width(52.dp)
        )
    }
}

@Composable
private fun ConsoleLogsTab(logs: List<String>) {
    if (logs.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.diag_empty_console),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
        items(logs) { log ->
            val color = when {
                log.startsWith("[ERR]") -> Color(0xFFE53935)
                log.startsWith("[WARN]") -> Color(0xFFFB8C00)
                else -> MaterialTheme.colorScheme.onSurface
            }
            Text(
                text = log,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                color = color,
                modifier = Modifier.padding(vertical = 2.dp)
            )
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun RecordDetailDialog(
    record: TimingRecord,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.diag_timing_detail_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = record.name,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))

                DetailTimingRow(ColorDns, stringResource(R.string.diag_phase_dns), record.dnsDuration)
                DetailTimingRow(ColorConnect, stringResource(R.string.diag_phase_connect), record.connectDuration)
                if (record.sslDuration > 0) {
                    DetailTimingRow(ColorSsl, stringResource(R.string.diag_phase_ssl), record.sslDuration, isSub = true)
                }
                DetailTimingRow(ColorTtfb, stringResource(R.string.diag_phase_ttfb), record.ttfb)
                DetailTimingRow(ColorDownload, stringResource(R.string.diag_phase_download), record.downloadDuration)

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = stringResource(R.string.diag_total_duration), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    Text(text = "${record.duration.toInt()} ms", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                }

                if (record.transferSize > 0) {
                    Spacer(Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = stringResource(R.string.diag_transfer_size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(text = "${record.transferSize} bytes", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
private fun DetailTimingRow(color: Color, label: String, durationMs: Double, isSub: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = if (isSub) 12.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(8.dp))
            Text(text = label, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            text = if (durationMs > 0) "${durationMs.toInt()} ms" else "—",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = if (durationMs > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    context: android.content.Context,
    timingBridge: BrowserTimingBridge,
    onProgressChanged: (Int) -> Unit,
    onLoadingStateChanged: (Boolean, String?) -> Unit,
): WebView {
    // 如果 VPN 处于连接状态，通过 AndroidX WebKit ProxyController 把 WebView 的流量
    // 显式桥接到本地 SOCKS5 代理端口（因为本 App 自身被排除在 VPN 外以防 VPN 回环，
    // WebView 默认走物理网卡直连，必须通过 ProxyOverride 接入隧道）
    val socksPort = ActiveSession.socksPort
    if (socksPort != null && socksPort > 0 && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
        runCatching {
            val proxyConfig = ProxyConfig.Builder()
                .addProxyRule("socks://127.0.0.1:$socksPort")
                .addDirect() // 当代理异常或未连接时回退直连
                .build()
            val executor = Executor { it.run() }
            ProxyController.getInstance().setProxyOverride(proxyConfig, executor) {
                timingBridge.postLog("INFO", "WebView proxy override set to socks://127.0.0.1:$socksPort")
            }
        }.onFailure {
            timingBridge.postLog("WARN", "Set proxy override failed: ${it.message}")
        }
    }

    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_NO_CACHE // 测速与诊断时避免使用本地强缓存，看真实网络耗时
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // 添加 JS 交互 Bridge
        addJavascriptInterface(timingBridge, BrowserTimingBridge.JS_INTERFACE_NAME)

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                onProgressChanged(newProgress)
                // 在进度达到 50% 以上时即尝试注入收集，尽早抓取导航时序
                if (newProgress > 50) {
                    view?.evaluateJavascript(BrowserTimingBridge.INJECTION_SCRIPT, null)
                }
            }
        }

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                onLoadingStateChanged(true, url)
                // 页面开始加载即注入脚本
                view?.evaluateJavascript(BrowserTimingBridge.INJECTION_SCRIPT, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                onLoadingStateChanged(false, url)
                // 页面完成时再次触发采集
                view?.evaluateJavascript(BrowserTimingBridge.INJECTION_SCRIPT, null)
                view?.evaluateJavascript("if (window.__collectTiming) window.__collectTiming();", null)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                val desc = error?.description?.toString() ?: "Unknown error"
                timingBridge.postLog("ERR", "Resource error (${request?.url}): $desc")
            }
        }
    }
}

private fun sanitizeUrl(input: String): String {
    val trimmed = input.trim()
    return when {
        trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        trimmed.contains(".") -> "https://$trimmed"
        else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
    }
}
