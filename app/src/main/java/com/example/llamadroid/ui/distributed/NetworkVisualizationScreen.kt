package com.example.llamadroid.ui.distributed

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.llamadroid.service.DistributedMode
import com.example.llamadroid.service.DistributedService
import com.example.llamadroid.service.LlamaService
import com.example.llamadroid.service.ServerState
import com.example.llamadroid.service.WorkerInfo

// Matrix-style color palette
private val MatrixGreen = Color(0xFF00FF41)
private val MatrixDarkGreen = Color(0xFF008F11)
private val MatrixBg = Color(0xFF0D0D0D)
private val MatrixBgSecondary = Color(0xFF1A1A1A)
private val MatrixBorder = Color(0xFF003B00)
private val MatrixRed = Color(0xFFFF3333)
private val MatrixCyan = Color(0xFF00FFFF)
private val MatrixYellow = Color(0xFFFFFF00)

/**
 * Network Visualization Screen - Hacker/Matrix style
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkVisualizationScreen(navController: NavController) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    
    // Collect distributed service states
    val workers by DistributedService.workers.collectAsState()
    val isRunning by DistributedService.isRunning.collectAsState()
    val mode by DistributedService.mode.collectAsState()
    val masterRamMB by DistributedService.masterRamMB.collectAsState()
    val modelLayerCount by DistributedService.modelLayerCount.collectAsState()
    val rpcLayerCount by DistributedService.rpcLayerCount.collectAsState()
    val modelSizeMB by DistributedService.modelSizeMB.collectAsState()
    val inferenceRunning by DistributedService.inferenceRunning.collectAsState()
    val transferProgress by DistributedService.transferProgress.collectAsState()
    
    // LlamaService state
    val serverState by LlamaService.state.collectAsState()
    
    val masterLayers = modelLayerCount - rpcLayerCount
    val totalRam = masterRamMB + workers.sumOf { it.availableRamMB }
    
    // Calculate proportions for display
    val totalWorkerProportion = workers.mapNotNull { it.assignedProportion }.sum()
    val masterProportion = if (totalWorkerProportion > 0f) {
        ((1f - totalWorkerProportion) * 100).toInt()
    } else if (totalRam > 0) {
        (masterRamMB.toFloat() / totalRam * 100).toInt()
    } else {
        100
    }
    
    // Blinking cursor animation
    val infiniteTransition = rememberInfiniteTransition(label = "blink")
    val cursorVisible by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor"
    )
    
    Scaffold(
        containerColor = MatrixBg,
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "> NETWORK_STATUS",
                        fontFamily = FontFamily.Monospace,
                        color = MatrixGreen
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack, 
                            contentDescription = "Back",
                            tint = MatrixGreen
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MatrixBgSecondary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MatrixBg)
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Terminal Header
            TerminalBox(title = "SYSTEM STATUS") {
                val (statusText, statusColor) = when (serverState) {
                    is ServerState.Running -> "[ONLINE]" to MatrixGreen
                    is ServerState.Loading -> "[LOADING]" to MatrixYellow
                    is ServerState.Starting -> "[INIT...]" to MatrixYellow
                    is ServerState.Error -> "[ERROR]" to MatrixRed
                    ServerState.Stopped -> "[OFFLINE]" to MatrixRed
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "INFERENCE_SERVER: ",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MatrixDarkGreen
                    )
                    Text(
                        text = statusText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }
                
                Text(
                    text = "MODE: ${mode.name}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MatrixDarkGreen
                )
                
                if (inferenceRunning) {
                    Text(
                        text = "▓▓▓ PROCESSING ▓▓▓" + if (cursorVisible > 0.5f) "_" else " ",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MatrixGreen
                    )
                }
            }
            
            // Network Topology
            TerminalBox(title = "NETWORK TOPOLOGY") {
                // Master node
                Text(
                    text = "┌─────────────────────────────────────┐",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MatrixBorder
                )
                Row {
                    Text("│ ", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MatrixBorder)
                    Text(
                        text = "[MASTER] 👑 LOCAL_NODE",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MatrixCyan,
                        modifier = Modifier.weight(1f)
                    )
                    Text(" │", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MatrixBorder)
                }
                Row {
                    Text("│ ", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MatrixBorder)
                    Text(
                        text = "RAM: ${masterRamMB}MB | LAYERS: $masterLayers | LOAD: $masterProportion%",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MatrixGreen,
                        modifier = Modifier.weight(1f)
                    )
                    Text(" │", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MatrixBorder)
                }
                Text(
                    text = "└─────────────────────────────────────┘",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MatrixBorder
                )
                
                if (workers.isNotEmpty()) {
                    // Connection lines
                    Text(
                        text = "         │",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = if (inferenceRunning) MatrixGreen else MatrixDarkGreen
                    )
                    Text(
                        text = "    ─────┼─────" + "─────┬─────".repeat((workers.size - 1).coerceAtLeast(0)),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = if (inferenceRunning) MatrixGreen else MatrixDarkGreen
                    )
                    
                    // Worker nodes
                    workers.forEachIndexed { index, worker ->
                        val layersPerWorker = if (workers.isNotEmpty()) rpcLayerCount / workers.size else 0
                        
                        Text(
                            text = "┌───────────────────────────────┐",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = MatrixBorder
                        )
                        Row {
                            Text("│ ", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MatrixBorder)
                            Text(
                                text = "[WORKER_${index}] 📱 ${worker.deviceName}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = MatrixYellow,
                                modifier = Modifier.weight(1f)
                            )
                            Text(" │", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MatrixBorder)
                        }
                        Row {
                            Text("│ ", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MatrixBorder)
                            Text(
                                text = "${worker.ip}:${worker.port}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = MatrixDarkGreen,
                                modifier = Modifier.weight(1f)
                            )
                            Text(" │", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MatrixBorder)
                        }
                        Row {
                            Text("│ ", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MatrixBorder)
                            val workerProp = worker.assignedProportion?.let { "${(it * 100).toInt()}%" } ?: "auto"
                            Text(
                                text = "RAM: ${worker.availableRamMB}MB | LAYERS: ~$layersPerWorker | LOAD: $workerProp",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = MatrixGreen,
                                modifier = Modifier.weight(1f)
                            )
                            Text(" │", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MatrixBorder)
                        }
                        Text(
                            text = "└───────────────────────────────┘",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = MatrixBorder
                        )
                    }
                } else {
                    Text(
                        text = "\n[NO_WORKERS_CONNECTED]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MatrixRed
                    )
                }
            }
            
            // Model Info
            if (modelLayerCount > 0) {
                TerminalBox(title = "MODEL METRICS") {
                    Text(
                        text = "TOTAL_LAYERS: $modelLayerCount",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MatrixGreen
                    )
                    Text(
                        text = "MODEL_SIZE:   ${modelSizeMB}MB",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MatrixGreen
                    )
                    Text(
                        text = "LOCAL_LAYERS: $masterLayers",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MatrixCyan
                    )
                    Text(
                        text = "RPC_LAYERS:   $rpcLayerCount",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MatrixYellow
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // ASCII progress bar
                    val masterRatio = if (modelLayerCount > 0) masterLayers.toFloat() / modelLayerCount else 0f
                    val barWidth = 30
                    val masterBlocks = (masterRatio * barWidth).toInt()
                    val workerBlocks = barWidth - masterBlocks
                    
                    Text(
                        text = "LAYER_DISTRIBUTION:",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MatrixDarkGreen
                    )
                    Text(
                        text = "[" + "█".repeat(masterBlocks) + "░".repeat(workerBlocks) + "]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MatrixGreen
                    )
                    Row {
                        Text(
                            text = " LOCAL",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = MatrixCyan
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "RPC ",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = MatrixYellow
                        )
                    }
                }
            }
            
            // Transfer progress
            if (transferProgress > 0 && transferProgress < 100) {
                TerminalBox(title = "TRANSFER") {
                    val progressBlocks = (transferProgress / 100f * 30).toInt()
                    Text(
                        text = "SYNCING: [${"▓".repeat(progressBlocks)}${"░".repeat(30 - progressBlocks)}] $transferProgress%",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MatrixYellow
                    )
                }
            }
            
            // Memory stats
            TerminalBox(title = "MEMORY ALLOCATION") {
                Text(
                    text = "TOTAL_CLUSTER_RAM: ${totalRam}MB",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MatrixGreen
                )
                Text(
                    text = "MASTER_ALLOCATION: ${masterRamMB}MB",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MatrixDarkGreen
                )
                workers.forEachIndexed { i, w ->
                    Text(
                        text = "WORKER_${i}_ALLOC:   ${w.availableRamMB}MB",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MatrixDarkGreen
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Control buttons
            TerminalBox(title = "CONTROLS") {
                Button(
                    onClick = {
                        val intent = Intent(context, LlamaService::class.java).apply {
                            action = LlamaService.ACTION_STOP
                        }
                        context.startService(intent)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MatrixRed.copy(alpha = 0.3f),
                        contentColor = MatrixRed
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "> TERMINATE_SERVER",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                if (mode == DistributedMode.WORKER && isRunning) {
                    Button(
                        onClick = {
                            DistributedService.stopWorker(context)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MatrixYellow.copy(alpha = 0.3f),
                            contentColor = MatrixYellow
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "> STOP_RPC_WORKER",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }
                
                OutlinedButton(
                    onClick = {
                        DistributedService.clearWorkers()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MatrixDarkGreen
                    ),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(MatrixDarkGreen)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "> CLEAR_WORKERS",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TerminalBox(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, MatrixBorder, RoundedCornerShape(4.dp))
            .background(MatrixBgSecondary)
    ) {
        // Title bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MatrixBorder.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Fake terminal buttons
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MatrixRed.copy(alpha = 0.7f))
                )
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MatrixYellow.copy(alpha = 0.7f))
                )
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MatrixGreen.copy(alpha = 0.7f))
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "// $title",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MatrixDarkGreen
            )
        }
        
        // Content
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content
        )
    }
}
