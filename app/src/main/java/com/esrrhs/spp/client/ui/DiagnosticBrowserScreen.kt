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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.Memory
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
import com.esrrhs.spp.client.proxy.InternalPipelineTiming
import com.esrrhs.spp.client.proxy.InternalProxyTrace
import com.esrrhs.spp.client.proxy.ProxyEventBus
import com.esrrhs.spp.client.util.BrowserTimingBridge
import com.esrrhs.spp.client.vpn.ActiveSession
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor
import kotlinx.coroutines.launch
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.mutableLongStateOf

// 浏览器网络时序色彩（现代冷色调体系：青/蓝/紫/翠绿）
private val ColorDns = Color(0xFF0288D1)       // 明蓝: DNS 查询
private val ColorConnect = Color(0xFF00ACC1)   // 湖蓝/青: TCP 建连
private val ColorSsl = Color(0xFF7E57C2)       // 浅紫: SSL/TLS
private val ColorTtfb = Color(0xFF26A69A)      // 翠绿/青绿: 等待首字节 (TTFB)
private val ColorDownload = Color(0xFF42A5F5)  // 浅天蓝: 内容下载
private val ColorOther = Color(0xFF9E9E9E)     // 灰色: 排队/其他

// 内部代理内核链路阶段色彩（温暖/琥珀/深橙/深紫/珊瑚色体系，与上层冷色调鲜明区分）
private val ColorInternalRoute = Color(0xFF673AB7)      // 浓郁深紫: 分流路由判定
private val ColorInternalTunnel = Color(0xFFFF9800)     // 琥珀橙: 上游隧道建连
private val ColorInternalHandshake = Color(0xFFE91E63)  // 玫红紫: SOCKS5 握手
private val ColorInternalWait = Color(0xFFFFB300)       // 金黄/琥珀: 等待首包 (TTFB)
private val ColorInternalPipe = Color(0xFFFF7043)       // 珊瑚橙红: 双向数据转发
private val ColorTrigger = Color(0xFFD81B60)            // 鲜亮洋红: 用户回车触发上网起点

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
    var userTriggerTimestampMs by remember { mutableLongStateOf(0L) }

    val timingBridge = remember { BrowserTimingBridge() }
    val records by timingBridge.records.collectAsState()
    val internalPipelines by ProxyEventBus.pipelines.collectAsState()

    var selectedRecord by remember { mutableStateOf<TimingRecord?>(null) }
    var selectedPipeline by remember { mutableStateOf<InternalPipelineTiming?>(null) }

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
                                userTriggerTimestampMs = System.currentTimeMillis()
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
                            userTriggerTimestampMs = System.currentTimeMillis()
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
                        userTriggerTimestampMs = System.currentTimeMillis()
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
                        // Header: 标题与操作栏
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.Timeline,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "${stringResource(R.string.diag_tab_unified)} (${records.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                if (internalPipelines.isNotEmpty()) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "• 内核连接: ${internalPipelines.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    timingBridge.clear()
                                    ProxyEventBus.clearTraces()
                                    userTriggerTimestampMs = 0L
                                    selectedRecord = null
                                    selectedPipeline = null
                                    webViewInstance?.evaluateJavascript("if (window.__clearTimingBuffer) window.__clearTimingBuffer();", null)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
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

                        // 阶段色彩图例（包含浏览器阶段与内核阶段）
                        TimingLegendBar(hasTrigger = userTriggerTimestampMs > 0L)

                        HorizontalDivider()

                        // 整合后的统一瀑布流时序列表
                        UnifiedWaterfallTab(
                            records = records,
                            pipelines = internalPipelines,
                            userTriggerTimestampMs = userTriggerTimestampMs,
                            selected = selectedRecord,
                            onSelect = { selectedRecord = it }
                        )
                    }
                }
            }
        }
    }

    // 统一资源与内核详情弹出框
    if (selectedRecord != null) {
        val rec = selectedRecord!!
        val matchedPipeline = remember(rec) {
            val host = runCatching { URI(rec.name).host }.getOrNull()
            ProxyEventBus.findPipeline(host, rec.absoluteStartMs)
        }
        RecordDetailDialog(
            record = rec,
            pipeline = matchedPipeline,
            triggerTimeMs = userTriggerTimestampMs,
            onDismiss = { selectedRecord = null }
        )
    }
}

@Composable
private fun TimingLegendBar(hasTrigger: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (hasTrigger) {
            LegendDot(ColorTrigger, stringResource(R.string.diag_trigger_legend))
        }
        LegendDot(ColorDns, stringResource(R.string.diag_phase_dns))
        LegendDot(ColorConnect, stringResource(R.string.diag_phase_connect))
        LegendDot(ColorSsl, stringResource(R.string.diag_phase_ssl))
        LegendDot(ColorTtfb, stringResource(R.string.diag_phase_ttfb))
        LegendDot(ColorDownload, stringResource(R.string.diag_phase_download))
        LegendDot(ColorInternalRoute, stringResource(R.string.diag_internal_route))
        LegendDot(ColorInternalTunnel, stringResource(R.string.diag_internal_tunnel))
        LegendDot(ColorInternalHandshake, stringResource(R.string.diag_internal_handshake))
        LegendDot(ColorInternalWait, stringResource(R.string.diag_internal_wait))
        LegendDot(ColorInternalPipe, stringResource(R.string.diag_internal_pipe))
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
private fun UnifiedWaterfallTab(
    records: List<TimingRecord>,
    pipelines: List<InternalPipelineTiming>,
    userTriggerTimestampMs: Long,
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

    // 计算全局统一时间基准轴:
    // 如果有绝对时间戳 (timeOrigin > 0)，以 triggerTimeMs 与各记录/连接的起始时间计算基准
    // 否则回退为以 record.startTime 为基准的相对时间
    val hasAbsoluteTiming = records.any { it.absoluteStartMs > 0L }

    val (timelineBaseMs, totalTimelineMs) = remember(records, pipelines, userTriggerTimestampMs, hasAbsoluteTiming) {
        if (hasAbsoluteTiming) {
            val recordStarts = records.filter { it.absoluteStartMs > 0L }.map { it.absoluteStartMs }
            val recordEnds = records.filter { it.absoluteStartMs > 0L }.map { it.absoluteStartMs + it.duration.toLong() }
            val pipelineStarts = pipelines.filter { it.startTimeMs > 0L }.map { it.startTimeMs }
            val pipelineEnds = pipelines.filter { it.startTimeMs > 0L }.map { it.startTimeMs + it.totalTime }

            val allStarts = mutableListOf<Long>()
            allStarts.addAll(recordStarts)
            allStarts.addAll(pipelineStarts)
            if (userTriggerTimestampMs > 0L) {
                // 如果 trigger 时间和记录在合理区间内 (例如前后 30 秒)，纳入基准
                val minEvent = allStarts.minOrNull() ?: userTriggerTimestampMs
                if (Math.abs(userTriggerTimestampMs - minEvent) < 30_000L) {
                    allStarts.add(userTriggerTimestampMs)
                }
            }

            val base = allStarts.minOrNull() ?: 0L
            val maxEnd = (recordEnds + pipelineEnds).maxOrNull() ?: (base + 1000L)
            val span = (maxEnd - base).coerceAtLeast(100L).toDouble()
            Pair(base, span)
        } else {
            val span = records.maxOfOrNull { it.startTime + it.duration }?.coerceAtLeast(10.0) ?: 100.0
            Pair(0L, span)
        }
    }

    val listState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxSize()) {
        // 如果有用户回车触发上网时间，且在时间轴基准内，展示出发时间提示指示
        if (userTriggerTimestampMs > 0L) {
            val timeStr = remember(userTriggerTimestampMs) {
                SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(userTriggerTimestampMs))
            }
            Surface(
                color = ColorTrigger.copy(alpha = 0.08f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(ColorTrigger)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.diag_trigger_origin, timeStr),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = ColorTrigger,
                        fontSize = 11.sp,
                    )
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = ColorTrigger.copy(alpha = 0.3f))
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(records) { r ->
                val host = remember(r.name) {
                    runCatching { URI(r.name).host }.getOrNull()
                }
                val matchedPipeline = remember(host, pipelines, r.absoluteStartMs) {
                    ProxyEventBus.findPipeline(host, r.absoluteStartMs)
                }

                UnifiedWaterfallRow(
                    record = r,
                    pipeline = matchedPipeline,
                    timelineBaseMs = timelineBaseMs,
                    totalTimelineMs = totalTimelineMs,
                    isSelected = r == selected,
                    onClick = { onSelect(r) }
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun UnifiedWaterfallRow(
    record: TimingRecord,
    pipeline: InternalPipelineTiming?,
    timelineBaseMs: Long,
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
        Column(modifier = Modifier.width(115.dp)) {
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
                if (pipeline != null) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (pipeline.isDirect) "DIRECT" else "TUNNEL",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        fontSize = 8.sp,
                        color = if (pipeline.isDirect) Color(0xFF2E7D32) else Color(0xFFEF6C00),
                    )
                } else if (record.nextHopProtocol.isNotBlank()) {
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

        // 双层可视化甘特条 (上条: 浏览器网络时序; 下条: 内部代理运转链路)
        // 关键改进: 基于统一时间原点计算浏览器条和内核条的起始偏移量，使二者真实时序严格对齐！
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // 计算浏览器请求在时间轴上的物理起始位置
            val browserStartOffsetMs = if (timelineBaseMs > 0L && record.absoluteStartMs > 0L) {
                (record.absoluteStartMs - timelineBaseMs).coerceAtLeast(0L).toDouble()
            } else {
                record.startTime
            }
            val browserStartWeight = (browserStartOffsetMs / totalTimelineMs).coerceIn(0.0, 0.95).toFloat()
            val browserDurationWeight = (record.duration / totalTimelineMs).coerceIn(0.01, 1.0).toFloat()

            // 浏览器视角网络瀑布流
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(11.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction = (browserStartWeight + browserDurationWeight).coerceAtMost(1f))
                        .padding(start = (browserStartWeight * 180).coerceAtLeast(0f).dp)
                ) {
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

                        if (dnsW <= 0 && connW <= 0 && sslW <= 0 && ttfbW <= 0 && downW <= 0) {
                            Box(Modifier.fillMaxSize().background(ColorOther))
                        }
                    } else {
                        Box(Modifier.fillMaxSize().background(ColorOther))
                    }
                }
            }

            // 内核代理链路剖析条 (按内核真实的 startTimeMs 偏移，精确对齐浏览器建立连接与等待首包阶段)
            if (pipeline != null && pipeline.totalTime > 0) {
                val pipeStartOffsetMs = if (timelineBaseMs > 0L && pipeline.startTimeMs > 0L) {
                    (pipeline.startTimeMs - timelineBaseMs).coerceAtLeast(0L).toDouble()
                } else {
                    browserStartOffsetMs
                }
                val pipeStartWeight = (pipeStartOffsetMs / totalTimelineMs).coerceIn(0.0, 0.95).toFloat()
                val pipeDurationWeight = (pipeline.totalTime.toDouble() / totalTimelineMs).coerceIn(0.01, 1.0).toFloat()
                val d = pipeline.totalTime.toDouble()

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction = (pipeStartWeight + pipeDurationWeight).coerceAtMost(1f))
                            .padding(start = (pipeStartWeight * 180).coerceAtLeast(0f).dp)
                    ) {
                        val routeW = (pipeline.routeMs / d).toFloat().coerceIn(0f, 1f)
                        val connW = (pipeline.dnsOrConnectMs / d).toFloat().coerceIn(0f, 1f)
                        val hsW = (pipeline.handshakeMs / d).toFloat().coerceIn(0f, 1f)
                        val waitW = (pipeline.waitFirstDataMs / d).toFloat().coerceIn(0f, 1f)
                        val pipeW = (pipeline.dataTransferMs / d).toFloat().coerceIn(0f, 1f)

                        if (routeW > 0) Box(Modifier.fillMaxHeight().weight(routeW).background(ColorInternalRoute))
                        if (connW > 0) Box(Modifier.fillMaxHeight().weight(connW).background(ColorInternalTunnel))
                        if (hsW > 0) Box(Modifier.fillMaxHeight().weight(hsW).background(ColorInternalHandshake))
                        if (waitW > 0) Box(Modifier.fillMaxHeight().weight(waitW).background(ColorInternalWait))
                        if (pipeW > 0) Box(Modifier.fillMaxHeight().weight(pipeW).background(ColorInternalPipe))

                        if (routeW <= 0 && connW <= 0 && hsW <= 0 && waitW <= 0 && pipeW <= 0) {
                            Box(Modifier.fillMaxSize().background(ColorOther))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        // 总耗时数字
        Column(modifier = Modifier.width(52.dp), horizontalAlignment = Alignment.End) {
            Text(
                text = "${record.duration.toInt()} ms",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                fontSize = 11.sp,
            )
            if (pipeline != null) {
                Text(
                    text = "${pipeline.totalTime} ms",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RecordDetailDialog(
    record: TimingRecord,
    pipeline: InternalPipelineTiming?,
    triggerTimeMs: Long,
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = record.name,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))

                // 回车触发时间相关分析
                if (triggerTimeMs > 0L && record.absoluteStartMs > 0L) {
                    val triggerDeltaMs = (record.absoluteStartMs - triggerTimeMs).coerceAtLeast(0L)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "从回车到本请求发起",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = ColorTrigger
                        )
                        Text(
                            text = "+$triggerDeltaMs ms",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                            color = ColorTrigger
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // 浏览器网络时序部分
                Text(
                    text = stringResource(R.string.diag_browser_section_title),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))

                // 浏览器时序阶段水平连续甘特图条
                DetailProgressBar(
                    items = listOf(
                        ProgressSegment(stringResource(R.string.diag_phase_dns), record.dnsDuration, ColorDns),
                        ProgressSegment(stringResource(R.string.diag_phase_connect), (record.connectDuration - record.sslDuration).coerceAtLeast(0.0), ColorConnect),
                        ProgressSegment(stringResource(R.string.diag_phase_ssl), record.sslDuration, ColorSsl),
                        ProgressSegment(stringResource(R.string.diag_phase_ttfb), record.ttfb, ColorTtfb),
                        ProgressSegment(stringResource(R.string.diag_phase_download), record.downloadDuration, ColorDownload),
                    ),
                    totalDuration = record.duration
                )
                Spacer(Modifier.height(8.dp))

                DetailTimingRow(ColorDns, stringResource(R.string.diag_phase_dns), record.dnsDuration)
                DetailTimingRow(ColorConnect, stringResource(R.string.diag_phase_connect), record.connectDuration)
                if (record.sslDuration > 0) {
                    DetailTimingRow(ColorSsl, stringResource(R.string.diag_phase_ssl), record.sslDuration, isSub = true)
                }
                DetailTimingRow(ColorTtfb, stringResource(R.string.diag_phase_ttfb), record.ttfb)
                DetailTimingRow(ColorDownload, stringResource(R.string.diag_phase_download), record.downloadDuration)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(text = stringResource(R.string.diag_total_duration), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    Text(text = "${record.duration.toInt()} ms", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }

                if (record.transferSize > 0) {
                    Spacer(Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = stringResource(R.string.diag_transfer_size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(text = "${record.transferSize} bytes", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }

                // 如果关联到了内核运转记录，剖析展示内核运作各阶段
                if (pipeline != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                    Text(
                        text = stringResource(R.string.diag_internal_section_title),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = ColorInternalTunnel,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (pipeline.isDirect) "路由决策: 本地直连 (DIRECT)" else "路由决策: SPP 代理隧道 (TUNNEL)",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = if (pipeline.isDirect) Color(0xFF2E7D32) else Color(0xFFEF6C00),
                    )
                    Spacer(Modifier.height(6.dp))

                    // 内核时序阶段水平连续甘特图条
                    DetailProgressBar(
                        items = listOf(
                            ProgressSegment(stringResource(R.string.diag_internal_route), pipeline.routeMs.toDouble(), ColorInternalRoute),
                            ProgressSegment(if (pipeline.isDirect) "本地建连" else stringResource(R.string.diag_internal_tunnel), pipeline.dnsOrConnectMs.toDouble(), ColorInternalTunnel),
                            ProgressSegment(stringResource(R.string.diag_internal_handshake), pipeline.handshakeMs.toDouble(), ColorInternalHandshake),
                            ProgressSegment(stringResource(R.string.diag_internal_wait), pipeline.waitFirstDataMs.toDouble(), ColorInternalWait),
                            ProgressSegment(stringResource(R.string.diag_internal_pipe), pipeline.dataTransferMs.toDouble(), ColorInternalPipe),
                        ),
                        totalDuration = pipeline.totalTime.toDouble()
                    )
                    Spacer(Modifier.height(8.dp))

                    DetailTimingRow(ColorInternalRoute, stringResource(R.string.diag_internal_route), pipeline.routeMs.toDouble())
                    DetailTimingRow(ColorInternalTunnel, if (pipeline.isDirect) "目标 DNS / 本地建连" else stringResource(R.string.diag_internal_tunnel), pipeline.dnsOrConnectMs.toDouble())
                    DetailTimingRow(ColorInternalHandshake, stringResource(R.string.diag_internal_handshake), pipeline.handshakeMs.toDouble())
                    DetailTimingRow(ColorInternalWait, stringResource(R.string.diag_internal_wait), pipeline.waitFirstDataMs.toDouble())
                    DetailTimingRow(ColorInternalPipe, stringResource(R.string.diag_internal_pipe), pipeline.dataTransferMs.toDouble())

                    Spacer(Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = "内核总耗时", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        Text(text = "${pipeline.totalTime} ms", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = "传输流量", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(text = "↑ ${pipeline.uploadBytes} B  ↓ ${pipeline.downloadBytes} B", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }

                    if (!pipeline.errorReason.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "错误原因: ${pipeline.errorReason}",
                            style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFFE53935)),
                        )
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

private data class ProgressSegment(
    val name: String,
    val durationMs: Double,
    val color: Color,
)

@Composable
private fun DetailProgressBar(
    items: List<ProgressSegment>,
    totalDuration: Double,
) {
    val validItems = items.filter { it.durationMs > 0.0 }
    val safeTotal = if (totalDuration > 0.0) totalDuration else validItems.sumOf { it.durationMs }.coerceAtLeast(1.0)

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            if (validItems.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxSize()) {
                    validItems.forEach { seg ->
                        val weight = (seg.durationMs / safeTotal).toFloat().coerceIn(0.01f, 1f)
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(weight)
                                .background(seg.color)
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ColorOther.copy(alpha = 0.4f))
                )
            }
        }
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
