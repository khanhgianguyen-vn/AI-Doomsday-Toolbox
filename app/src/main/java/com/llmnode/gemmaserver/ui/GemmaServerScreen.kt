package com.llmnode.gemmaserver.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sin
import kotlin.math.cos
import kotlin.random.Random

// Colix Node color palette (from mockup)
private val BgDark = Color(0xFF05080C)
private val CardGlass = Color(0xFF0E1116)
private val Emerald400 = Color(0xFF34D399)
private val Emerald500 = Color(0xFF10B981)
private val Amber500 = Color(0xFFF59E0B)
private val Purple400 = Color(0xFFA78BFA)
private val Purple500 = Color(0xFF8B5CF6)
private val Cyan400 = Color(0xFF22D3EE)
private val Red500 = Color(0xFFEF4444)
private val SlateText = Color(0xFFE2E8F0)
private val SlateSecondary = Color(0xFF94A3B8)
private val SlateMuted = Color(0xFF64748B)
private val SlateSubtle = Color(0xFF334155)
private val BorderSubtle = Color(0x14FFFFFF) // white/8%

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GemmaServerScreen(viewModel: GemmaServerViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val modelFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.onModelFileSelected(it) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshModelStatus()
            delay(3000)
        }
    }

    if (state.showModelMissing) {
        ModelMissingDialog(
            onDismiss = { viewModel.dismissModelMissing() },
            onSelectModel = {
                viewModel.dismissModelMissing()
                modelFilePicker.launch(arrayOf("*/*"))
            }
        )
    }

    if (state.showBatteryDialog) {
        BatteryDialog(
            onDismiss = { viewModel.dismissBatteryDialog() },
            onOpenSettings = {
                viewModel.dismissBatteryDialog()
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    )
                } catch (_: Exception) { }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF1B2735), Color(0xFF090A0F)),
                    radius = 1200f
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .padding(top = 40.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header
            WorkerHeader(state)

            // Global Network + Earnings (WebView from mockup HTML)
            StatsCardsWebView(state)

            // World Map
            WorldMapSection()

            // Recent Activities
            RecentActivitiesCard(state)
        }

        // Bottom Navigation Bar
        var showTaskOverlay by remember { mutableStateOf(false) }
        var overlayFlow by remember { mutableStateOf("") }
        var overlaySimData by remember { mutableStateOf("") }
        // Track the last task ID we showed the overlay for (to avoid re-showing)
        var lastTriggeredTaskId by remember { mutableStateOf("") }

        // Auto-trigger persona overlay when a simulation task arrives
        LaunchedEffect(state.simulationTask?.taskId) {
            val simTask = state.simulationTask ?: return@LaunchedEffect
            if (simTask.taskId != lastTriggeredTaskId && simTask.taskId.isNotEmpty()) {
                lastTriggeredTaskId = simTask.taskId
                overlayFlow = "persona"
                // Encode task data as JSON for the WebView
                val simJson = """{"name":"${simTask.personaName}","title":"${simTask.personaTitle}","event":"${simTask.eventName}","eventDesc":"${simTask.eventDescription}","nodes":[${simTask.nodes.joinToString(",") { """{"name":"${it.name}","relation":"${it.relation}"}""" }}]}"""
                overlaySimData = android.util.Base64.encodeToString(simJson.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
                showTaskOverlay = true
            }
        }

        // Fallback: auto-trigger on regular tasks (non-simulation)
        LaunchedEffect(state.currentTask?.id) {
            val taskId = state.currentTask?.id ?: return@LaunchedEffect
            if (taskId != lastTriggeredTaskId && taskId.isNotEmpty() && !taskId.startsWith("sim-")) {
                lastTriggeredTaskId = taskId
                overlayFlow = "persona"
                overlaySimData = ""
                showTaskOverlay = true
            }
        }

        // Task history overlay
        var showTaskHistory by remember { mutableStateOf(false) }

        BottomNavBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            onTasksClick = {
                if (state.completedTasks.isNotEmpty()) {
                    showTaskHistory = true
                } else {
                    showTaskOverlay = true; overlayFlow = "persona"; overlaySimData = ""
                }
            },
            onEarnClick = { showTaskOverlay = true; overlayFlow = "reaction"; overlaySimData = "" }
        )

        // Task History List
        if (showTaskHistory) {
            TaskHistoryOverlay(
                tasks = state.completedTasks,
                onTaskClick = { task ->
                    showTaskHistory = false
                    overlayFlow = "review"
                    // Encode saved result for the WebView
                    val reviewJson = """{"name":"${task.personaName}","title":"${task.personaTitle}","event":"${task.eventName}","result":${task.resultJson}}"""
                    overlaySimData = android.util.Base64.encodeToString(reviewJson.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
                    showTaskOverlay = true
                },
                onClose = { showTaskHistory = false }
            )
        }

        // Task/Earn Overlay
        if (showTaskOverlay) {
            TaskOverlayWebView(
                flow = overlayFlow,
                simData = overlaySimData,
                simulationResult = state.simulationResult,
                onClose = { showTaskOverlay = false }
            )
        }
    }
}

// ====== HEADER ======
@Composable
private fun WorkerHeader(state: GemmaUiState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Avatar with amber glow border
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, Amber500.copy(alpha = 0.5f), CircleShape)
                    .background(Color.Black)
                    .padding(2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("🤖", fontSize = 22.sp)
            }

            Spacer(Modifier.width(10.dp))

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Colix_Node",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(6.dp))
                    // Tier badge
                    Text(
                        " Tier: Gold 🥇 ",
                        color = Amber500,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(Amber500.copy(alpha = 0.15f))
                            .border(0.5.dp, Amber500.copy(alpha = 0.3f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Pulsing green dot
                    val statusColor = if (state.isServerRunning) Emerald500 else Red500
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (state.isServerRunning) "Contributing · Online" else "Offline",
                        color = if (state.isServerRunning) Emerald400 else Red500,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Notification bell
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(CardGlass.copy(alpha = 0.6f))
                .border(0.5.dp, BorderSubtle, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Notifications, "alerts", tint = SlateSecondary, modifier = Modifier.size(16.dp))
            // Red dot
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-6).dp, y = 6.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(Red500)
            )
        }
    }
}

// ====== STATS CARDS (WebView — pixel-perfect from mockup HTML) ======
@Composable
private fun StatsCardsWebView(state: GemmaUiState) {
    var webViewRef by remember { mutableStateOf<android.webkit.WebView?>(null) }

    // Push data updates only when there's real activity
    LaunchedEffect(state.totalRequests, state.isServerRunning, state.tokensPerSec) {
        if (state.totalRequests > 0 || state.isServerRunning) {
            webViewRef?.let { wv ->
                val clx = if (state.totalRequests > 0) "${state.totalRequests * 3}" else "47"
                val clxToday = if (state.totalRequests > 0) "+${state.totalRequests % 20}" else "+12"
                val usdc = if (state.totalRequests > 0) String.format("$%.2f", state.totalRequests * 0.01) else "$4.20"
                val usdcToday = if (state.totalRequests > 0) String.format("+$%.3f", (state.totalRequests % 10) * 0.008) else "+$1.2"
                val tps = if (state.tokensPerSec > 0) String.format("%.1f", state.tokensPerSec) else "800K"
                val tasks = if (state.totalRequests > 0) "${state.totalRequests}" else "47"
                val processed = if (state.totalRequests > 0) "${state.totalRequests}" else "283"
                val online = state.isServerRunning
                wv.post {
                    wv.evaluateJavascript(
                        "if(typeof updateData==='function')updateData('1.2M','$tps','$tasks','$clx','$clxToday','$usdc','$usdcToday','$processed',${online})",
                        null
                    )
                }
            }
        }
    }

    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            android.webkit.WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                settings.allowUniversalAccessFromFileURLs = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                loadUrl("file:///android_asset/stats_cards.html")
                webViewRef = this
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
    )
}

// ====== GLOBAL NETWORK STRIP ======
@Composable
private fun GlobalNetworkStrip(state: GemmaUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        CardGlass.copy(alpha = 0.8f),
                        Emerald500.copy(alpha = 0.05f)
                    )
                )
            )
            .border(1.dp, Emerald500.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Title
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Language, "network", tint = Emerald400, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "GLOBAL NETWORK",
                color = Emerald400,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        }

        // Stats row with animated charts
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            // Nodes with animated bar chart
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("1.2M", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("NODES", color = SlateMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                Spacer(Modifier.height(4.dp))
                AnimatedBarChart(
                    barCount = 20,
                    barColor = Emerald500,
                    modifier = Modifier.fillMaxWidth(0.85f).height(14.dp)
                )
            }

            VerticalDivider()

            // TPS with dot equalizer
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp), horizontalAlignment = Alignment.Start) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (state.tokensPerSec > 0) "%.1f".format(state.tokensPerSec) else "0",
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("TOK/S", color = SlateMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                Spacer(Modifier.height(4.dp))
                AnimatedDotEqualizer(
                    cols = 16, rows = 4,
                    modifier = Modifier.fillMaxWidth(0.85f).height(14.dp)
                )
            }

            VerticalDivider()

            // Tasks with utilization
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp), horizontalAlignment = Alignment.Start) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${state.totalRequests}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("TASKS", color = SlateMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("85%", color = Cyan400, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("UTIL", color = SlateMuted, fontSize = 8.sp, letterSpacing = 1.sp)
                }
            }
        }

        // My Stats sub-card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.3f))
                .border(0.5.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BarChart, "stats", tint = Emerald400, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(6.dp))
                Text("My Stats", color = SlateMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MiniStat("Uptime", if (state.isServerRunning) "●" else "○", Color.White)
                MiniStat("RAM", "${state.ramUsageMb}MB", Emerald400)
                MiniStat("Model", if (state.isModelLoaded) "✓" else "✗",
                    if (state.isModelLoaded) Emerald400 else Red500)
            }
        }
    }
}

// ====== ANIMATED BAR CHART (scrolling bars like the mockup) ======
@Composable
private fun AnimatedBarChart(barCount: Int, barColor: Color, modifier: Modifier = Modifier) {
    var barData by remember { mutableStateOf(List(barCount) { 10f + Random.nextFloat() * 80f }) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            barData = barData.drop(1) + listOf(10f + Random.nextFloat() * 90f)
        }
    }

    Canvas(modifier = modifier) {
        val barWidth = size.width / barCount
        val gap = 1f
        barData.forEachIndexed { i, value ->
            val barH = (value / 100f) * size.height
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(barColor, barColor.copy(alpha = 0.1f)),
                    startY = size.height - barH,
                    endY = size.height
                ),
                topLeft = Offset(i * barWidth + gap, size.height - barH),
                size = Size(barWidth - gap * 2, barH),
                cornerRadius = CornerRadius(1f)
            )
        }
    }
}

// ====== ANIMATED DOT EQUALIZER ======
@Composable
private fun AnimatedDotEqualizer(cols: Int, rows: Int, modifier: Modifier = Modifier) {
    var colData by remember { mutableStateOf(List(cols) { Random.nextInt(rows + 1) }) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            colData = colData.drop(1) + listOf(Random.nextInt(rows + 1))
        }
    }

    Canvas(modifier = modifier) {
        val dotSize = 3f
        val gapX = (size.width - cols * dotSize) / (cols - 1).coerceAtLeast(1)
        val gapY = (size.height - rows * dotSize) / (rows - 1).coerceAtLeast(1)

        for (col in 0 until cols) {
            val activeCount = colData.getOrElse(col) { 0 }
            for (row in 0 until rows) {
                val invRow = rows - 1 - row
                val isActive = invRow < activeCount
                val color = if (isActive) {
                    when {
                        invRow == rows - 1 -> Amber500
                        invRow == rows - 2 -> Emerald400
                        else -> Color(0xFF059669)
                    }
                } else Color(0x66334155)

                drawRoundRect(
                    color = color,
                    topLeft = Offset(col * (dotSize + gapX), row * (dotSize + gapY)),
                    size = Size(dotSize, dotSize),
                    cornerRadius = CornerRadius(1f)
                )
            }
        }
    }
}

// ====== WORLD MAP (WebView — bundled D3.js + TopoJSON assets) ======
@Composable
private fun WorldMapSection() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(20.dp))
            .background(CardGlass.copy(alpha = 0.3f))
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
    ) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                android.webkit.WebView(ctx).apply {
                    setBackgroundColor(android.graphics.Color.parseColor("#0d1117"))
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = true
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                    loadUrl("file:///android_asset/world_map.html")
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Floating legend card
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xCC0D1117))
                .border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("NODE DENSITY", color = SlateMuted, fontSize = 6.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            LegendDot(Amber500, "50K+")
            LegendDot(Color(0xFFFDE68A), "10K-50K")
            LegendDot(Emerald400, "1K-10K")
            LegendDot(Emerald500, "10-100")
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(color))
        Text(label, color = SlateMuted, fontSize = 7.sp)
    }
}

@Composable
private fun VerticalDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(32.dp)
            .background(Color.White.copy(alpha = 0.1f))
    )
}

@Composable
private fun MiniStat(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.End) {
        Text(label, color = SlateMuted, fontSize = 8.sp)
        Text(value, color = valueColor, fontWeight = FontWeight.Bold, fontSize = 10.sp)
    }
}

// ====== EARNINGS STRIP ======
@Composable
private fun EarningsStrip(state: GemmaUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardGlass.copy(alpha = 0.4f))
            .border(1.dp, BorderSubtle, RoundedCornerShape(20.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Circle, "earnings", tint = Purple400, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("EARNINGS", color = SlateMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }
            Text(
                " Claim ",
                color = Color.White,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // CLX card
            EarningsCard(
                modifier = Modifier.weight(1f),
                amount = "${state.totalRequests * 3}",
                unit = "CLX",
                unitColor = Purple400,
                todayChange = "+${(state.totalRequests % 20)} today",
                accentColor = Purple500
            )
            // USDC card
            EarningsCard(
                modifier = Modifier.weight(1f),
                amount = "$${String.format("%.2f", state.totalRequests * 0.01)}",
                unit = "",
                unitColor = Emerald400,
                todayChange = "+$${String.format("%.3f", (state.totalRequests % 10) * 0.008)} today",
                accentColor = Emerald500
            )
        }
    }
}

@Composable
private fun EarningsCard(
    modifier: Modifier,
    amount: String,
    unit: String,
    unitColor: Color,
    todayChange: String,
    accentColor: Color
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.2f))
            .border(0.5.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(amount, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(unit, color = unitColor, fontWeight = FontWeight.Bold, fontSize = 8.sp)
                }
            }
            Text(
                todayChange,
                color = Emerald400,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Emerald400.copy(alpha = 0.1f))
                    .border(0.5.dp, Emerald400.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        // 7-day bar chart
        EarningsBarChart(
            barColor = accentColor,
            modifier = Modifier.fillMaxWidth().height(28.dp)
        )
    }
}

@Composable
private fun EarningsBarChart(barColor: Color, modifier: Modifier = Modifier) {
    val barData = remember {
        listOf(25f, 40f, 15f, 65f, 75f, 45f, 90f)
    }

    Canvas(modifier = modifier) {
        val barCount = barData.size
        val gap = 4f
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        barData.forEachIndexed { i, value ->
            val barH = (value / 100f) * size.height
            drawRoundRect(
                color = barColor.copy(alpha = 0.7f + (value / 100f) * 0.3f),
                topLeft = Offset(i * (barWidth + gap), size.height - barH),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(2f)
            )
        }
    }
}

// ====== NODE STATUS BAR (replaces manual ServerControlCard) ======
@Composable
private fun NodeStatusBar(
    state: GemmaUiState,
    viewModel: GemmaServerViewModel,
    filePicker: androidx.activity.result.ActivityResultLauncher<Array<String>>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardGlass.copy(alpha = 0.4f))
            .border(1.dp, BorderSubtle, RoundedCornerShape(20.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Memory, "model", tint = Cyan400, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(6.dp))
                Text("NODE CONTROL", color = SlateMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }

            // Status chip
            val (statusText, statusColor) = when {
                state.isModelLoaded && state.isServerRunning -> "● ONLINE" to Emerald400
                state.isModelLoading -> "◐ LOADING" to Amber500
                state.modelFileExists -> "○ OFFLINE" to SlateMuted
                else -> "⚠ NO MODEL" to Red500
            }
            Text(
                statusText,
                color = statusColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(statusColor.copy(alpha = 0.1f))
                    .border(0.5.dp, statusColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }

        // Loading progress
        if (state.isModelLoading) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = Amber500,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.loadProgress.isNotEmpty()) state.loadProgress else "Initializing LLM engine...",
                        color = Amber500,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                // Animated progress bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                ) {
                    val infiniteTransition = rememberInfiniteTransition(label = "loading")
                    val offset by infiniteTransition.animateFloat(
                        initialValue = -1f,
                        targetValue = 2f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1500, easing = LinearEasing)
                        ),
                        label = "shimmer"
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.4f)
                            .fillMaxHeight()
                            .offset(x = (offset * 200).dp)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(Color.Transparent, Amber500, Color.Transparent)
                                )
                            )
                    )
                }
            }
        }

        // Model info row
        if (state.modelFileName.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.3f))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🧠", fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                Text(state.modelFileName, color = SlateText, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (state.isModelLoaded) {
                    Text("✓", color = Emerald400, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Error
        state.error?.let { error ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Red500.copy(alpha = 0.1f))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Warning, "error", tint = Red500, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(error, color = Red500, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }

        // Only show Select Model button when no model exists
        if (!state.modelFileExists) {
            OutlinedButton(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth().height(40.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Cyan400.copy(alpha = 0.3f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan400),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Icon(Icons.Default.FolderOpen, "select", modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Select Model", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ====== CURRENT TASK / OUTPUT ======
@Composable
private fun CurrentTaskCard(state: GemmaUiState) {
    val context = LocalContext.current
    val outputScroll = rememberScrollState()

    LaunchedEffect(state.streamingOutput) {
        outputScroll.animateScrollTo(outputScroll.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardGlass.copy(alpha = 0.4f))
            .border(1.dp, BorderSubtle, RoundedCornerShape(20.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📡", fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (state.streamingOutput.isNotEmpty()) "AI ANALYSIS ONGOING" else "LIVE OUTPUT",
                    color = if (state.streamingOutput.isNotEmpty()) Emerald400 else SlateMuted,
                    fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp
                )
            }
            if (state.tokensPerSec > 0) {
                Text(
                    "%.1f tok/s".format(state.tokensPerSec),
                    color = Emerald400,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Emerald400.copy(alpha = 0.1f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        // Incoming task info
        if (state.currentTask != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.3f))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📥", fontSize = 10.sp)
                Spacer(Modifier.width(4.dp))
                Text(
                    state.currentTask.prompt.take(80) + if (state.currentTask.prompt.length > 80) "..." else "",
                    color = SlateSecondary,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                val ts = SimpleDateFormat("HH:mm", Locale.US).format(Date(state.currentTask.timestamp))
                Text(ts, color = SlateMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
        }

        // Output area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp, max = 200.dp)
                .verticalScroll(outputScroll)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.3f))
                .padding(10.dp)
        ) {
            if (state.streamingOutput.isNotEmpty()) {
                Text(state.streamingOutput, color = Emerald400.copy(alpha = 0.9f), fontSize = 11.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace)
            } else {
                Text("Node idle · Waiting for simulation tasks...", color = SlateMuted.copy(alpha = 0.4f), fontSize = 11.sp)
            }
        }

        // API Key row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Text("🔑", fontSize = 10.sp)
                Spacer(Modifier.width(4.dp))
                Text(
                    if (state.showApiKey) state.apiKey else "••••••••••••••",
                    color = SlateMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { copyToClipboard(context, state.apiKey) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.ContentCopy, "copy", tint = SlateMuted, modifier = Modifier.size(13.dp))
                }
            }
        }

        // Endpoint
        if (state.tailscale.lanIp != null && state.isServerRunning) {
            val endpoint = "http://${state.tailscale.lanIp}:8080"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Emerald500.copy(alpha = 0.08f))
                    .clickable { copyToClipboard(context, endpoint) }
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🔗", fontSize = 10.sp)
                Spacer(Modifier.width(4.dp))
                Text(endpoint, color = Emerald400, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ContentCopy, "copy", tint = SlateMuted, modifier = Modifier.size(12.dp))
            }
        }
    }
}

// ====== RECENT ACTIVITIES ======
@Composable
private fun RecentActivitiesCard(state: GemmaUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardGlass.copy(alpha = 0.4f))
            .border(1.dp, BorderSubtle, RoundedCornerShape(20.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Schedule, "recent", tint = Cyan400, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(6.dp))
            Text("RECENT ACTIVITIES", color = SlateMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        }

        // Show placeholder activities (in a real app, you'd track these)
        val activities = listOf(
            ActivityItem("#3eh16", "Task Alpha", "${state.totalRequests * 600}", "+$0.01", "+3 CLX"),
            ActivityItem("#9fk21", "Task Beta", "${state.totalRequests * 420}", "+$0.008", "+2 CLX"),
            ActivityItem("#1az99", "Task Gamma", "${state.totalRequests * 850}", "+$0.015", "+5 CLX")
        )

        activities.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.2f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.id, color = SlateMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(6.dp))
                    Text(item.name, color = SlateText, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${item.tokens} tok", color = SlateMuted.copy(alpha = 0.6f), fontSize = 9.sp)
                    Spacer(Modifier.width(6.dp))
                    Box(modifier = Modifier.width(1.dp).height(12.dp).background(Color.White.copy(alpha = 0.1f)))
                    Spacer(Modifier.width(6.dp))
                    Text(item.usdEarned, color = Emerald400, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(4.dp))
                    Text(item.clxEarned, color = Purple400, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private data class ActivityItem(
    val id: String,
    val name: String,
    val tokens: String,
    val usdEarned: String,
    val clxEarned: String
)

// ====== TASK HISTORY OVERLAY ======
@Composable
private fun TaskHistoryOverlay(
    tasks: List<CompletedTask>,
    onTaskClick: (CompletedTask) -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xEE090A0F))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClose() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 60.dp, bottom = 100.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⚡", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("COMPLETED TASKS", color = Cyan400, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                }
                Text(
                    "${tasks.size}",
                    color = Emerald400,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Emerald400.copy(alpha = 0.1f))
                        .border(0.5.dp, Emerald400.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            // Task list
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tasks.forEach { task ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(CardGlass.copy(alpha = 0.5f))
                            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                            .clickable { onTaskClick(task) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Avatar circle
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(listOf(Cyan400, Emerald500))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                task.personaName.take(1).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        Spacer(Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                task.personaName,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (task.eventName.isNotEmpty()) {
                                Text(
                                    task.eventName,
                                    color = SlateMuted,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.US)
                                    .format(java.util.Date(task.timestamp)),
                                color = SlateMuted.copy(alpha = 0.6f),
                                fontSize = 9.sp
                            )
                        }

                        // Status chip
                        Text(
                            "COMPLETED",
                            color = Emerald400,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Emerald400.copy(alpha = 0.1f))
                                .border(0.5.dp, Emerald400.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

// ====== TASK OVERLAY (WebView — full-screen overlay for Tasks/Earn flows) ======
@Composable
private fun TaskOverlayWebView(
    flow: String,
    simData: String = "",
    simulationResult: com.llmnode.gemmaserver.server.SimulationResult? = null,
    onClose: () -> Unit
) {
    var webViewRef by remember { mutableStateOf<android.webkit.WebView?>(null) }

    // Inject simulation result into WebView when it arrives
    LaunchedEffect(simulationResult?.taskId) {
        simulationResult?.let { result ->
            webViewRef?.let { wv ->
                val escapedJson = result.rawJson
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\n", "\\n")
                    .replace("\r", "")
                wv.post {
                    wv.evaluateJavascript(
                        "if(typeof window.onSimulationResult==='function')window.onSimulationResult('$escapedJson')",
                        null
                    )
                }
            }
        }
    }

    // Stream AI tokens to console in real-time
    var lastStreamLen by remember { mutableStateOf(0) }
    LaunchedEffect(simulationResult) {
        // Reset stream tracking when a new simulation starts
        lastStreamLen = 0
    }
    // Forward new streaming tokens to WebView
    val streamOutput = remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val server = com.llmnode.gemmaserver.service.GemmaServerService.apiServer ?: return@LaunchedEffect
        server.tokenFlow.collect { token ->
            if (!token.done && token.token.isNotEmpty()) {
                val escaped = token.token
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\n", "\\n")
                    .replace("\r", "")
                webViewRef?.post {
                    webViewRef?.evaluateJavascript(
                        "if(typeof window.onStreamToken==='function')window.onStreamToken('$escaped')",
                        null
                    )
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        val flowHash = if (flow == "persona") "#persona" else "#reaction"
        val fullHash = if (simData.isNotEmpty()) "$flowHash:$simData" else flowHash
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                android.webkit.WebView(ctx).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = true
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                    // JS bridge: Android.closeOverlay()
                    addJavascriptInterface(object {
                        @android.webkit.JavascriptInterface
                        fun closeOverlay() {
                            post { onClose() }
                        }
                    }, "Android")
                    // Flow type + sim data passed via URL
                    loadUrl("file:///android_asset/task_overlay.html$fullHash")
                    webViewRef = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ====== BOTTOM NAV BAR ======
@Composable
private fun BottomNavBar(
    modifier: Modifier = Modifier,
    onTasksClick: () -> Unit = {},
    onEarnClick: () -> Unit = {}
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 28.dp, bottomEnd = 28.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xF00C121A), Color(0xF50C121A))
                    )
                )
                .border(
                    1.dp,
                    Color.White.copy(alpha = 0.1f),
                    RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 28.dp, bottomEnd = 28.dp)
                )
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavItem(Icons.Default.Home, "Home", isActive = true)
            NavItem(Icons.Default.Assignment, "Tasks", isActive = false, onClick = onTasksClick)
            NavItem(Icons.Default.MonetizationOn, "Earn", isActive = false, onClick = onEarnClick)
            NavItem(Icons.Default.Language, "Network", isActive = false)
            NavItem(Icons.Default.Settings, "Settings", isActive = false)
        }
    }
}

@Composable
private fun NavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit = {}
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Icon(
            icon, label,
            tint = if (isActive) Emerald400 else SlateMuted,
            modifier = Modifier.size(20.dp)
        )
        Text(
            label,
            color = if (isActive) Emerald400 else SlateMuted,
            fontSize = 9.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
        )
    }
}

// ====== DIALOGS ======
@Composable
private fun ModelMissingDialog(onDismiss: () -> Unit, onSelectModel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardGlass,
        titleContentColor = SlateText,
        textContentColor = SlateSecondary,
        title = { Text("No Model Selected", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Select a LiteRT-LM model file to get started.", fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text("Download models from:", fontSize = 12.sp)
                Text(
                    "huggingface.co/litert-community",
                    color = Cyan400,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.3f))
                        .padding(6.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSelectModel) { Text("Select Model", color = Emerald400) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = SlateMuted) }
        }
    )
}

@Composable
private fun BatteryDialog(onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardGlass,
        titleContentColor = SlateText,
        textContentColor = SlateSecondary,
        title = { Text("Disable Battery Optimization", fontWeight = FontWeight.Bold) },
        text = { Text("To keep the node running, please disable battery optimization.", fontSize = 13.sp) },
        confirmButton = {
            TextButton(onClick = onOpenSettings) { Text("Open Settings", color = Emerald400) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Later", color = SlateMuted) }
        }
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("colix", text))
    Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
}
