package com.example.llamadroid.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.LogEntry
import com.example.llamadroid.service.DistributedService
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LogTab { APP, RPC }

@Composable
fun LogsScreen(navController: NavController) {
    val appLogs by DebugLog.logs.collectAsState()
    val rpcLogs by DistributedService.rpcLogs.collectAsState()
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val context = LocalContext.current
    val appListState = rememberLazyListState()
    val rpcListState = rememberLazyListState()
    
    var selectedTab by remember { mutableStateOf(LogTab.APP) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                )
            )
    ) {
        // Header
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                stringResource(R.string.logs_title),
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp
                )
            )
            Text(
                stringResource(R.string.settings_debug_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // Tab Row
        TabRow(
            selectedTabIndex = selectedTab.ordinal,
            modifier = Modifier.padding(horizontal = 20.dp),
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            indicator = { _ -> }
        ) {
            Tab(
                selected = selectedTab == LogTab.APP,
                onClick = { selectedTab = LogTab.APP },
                text = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📱 App Logs")
                        if (appLogs.isNotEmpty()) {
                            Spacer(Modifier.width(4.dp))
                            Badge { Text("${appLogs.size}") }
                        }
                    }
                }
            )
            Tab(
                selected = selectedTab == LogTab.RPC,
                onClick = { selectedTab = LogTab.RPC },
                text = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🌐 RPC Logs")
                        if (rpcLogs.isNotEmpty()) {
                            Spacer(Modifier.width(4.dp))
                            Badge(
                                containerColor = Color(0xFF00FF41),
                                contentColor = Color.Black
                            ) { Text("${rpcLogs.size}") }
                        }
                    }
                }
            )
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val currentLogs = if (selectedTab == LogTab.APP) appLogs else rpcLogs
            val logText = if (selectedTab == LogTab.APP) {
                appLogs.joinToString("\n") { "[${dateFormat.format(Date(it.timestamp))}] ${it.message}" }
            } else {
                rpcLogs.joinToString("\n")
            }
            
            FilledTonalButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("LlamaDroid Logs", logText))
                    Toast.makeText(context, "Logs copied!", Toast.LENGTH_SHORT).show()
                },
                enabled = currentLogs.isNotEmpty(),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.logs_copy))
            }
            
            FilledTonalButton(
                onClick = {
                    if (selectedTab == LogTab.APP) {
                        DebugLog.clear()
                    } else {
                        DistributedService.clearRpcLogs()
                    }
                },
                enabled = currentLogs.isNotEmpty(),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.logs_clear))
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Log content based on selected tab
        when (selectedTab) {
            LogTab.APP -> AppLogsContent(appLogs, appListState, dateFormat)
            LogTab.RPC -> RpcLogsContent(rpcLogs, rpcListState)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun AppLogsContent(
    logs: List<LogEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    dateFormat: SimpleDateFormat
) {
    if (logs.isEmpty()) {
        EmptyLogsPlaceholder("No app logs yet", "Perform an action to see logs here")
    } else {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                reverseLayout = true
            ) {
                items(logs.reversed()) { entry ->
                    val isError = entry.message.contains("ERROR", ignoreCase = true) || 
                                  entry.message.contains("FAILED", ignoreCase = true) ||
                                  entry.message.contains("CRASH", ignoreCase = true)
                    val isServer = entry.message.startsWith("Server:")
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isError) Color(0xFF3D2020) else Color.Transparent)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = dateFormat.format(Date(entry.timestamp)),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            ),
                            color = Color(0xFF6A9955),
                            modifier = Modifier.width(50.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = entry.message,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            ),
                            color = when {
                                isError -> Color(0xFFF48771)
                                isServer -> Color(0xFF9CDCFE)
                                else -> Color(0xFFD4D4D4)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RpcLogsContent(
    logs: List<String>,
    listState: androidx.compose.foundation.lazy.LazyListState
) {
    if (logs.isEmpty()) {
        EmptyLogsPlaceholder(
            "No RPC logs yet",
            "Start a worker to see RPC server output"
        )
    } else {
        // Hacker-style terminal card
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0D0D)),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column {
                // Terminal header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1A1A1A))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Fake window buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(Color(0xFFFF5F56)))
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(Color(0xFFFFBD2E)))
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(Color(0xFF27CA40)))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "rpc-server -- /dev/tty",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = Color(0xFF888888)
                    )
                }
                
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    reverseLayout = true
                ) {
                    items(logs.reversed()) { line ->
                        val isTimestamp = line.startsWith("[")
                        val isError = line.contains("error", ignoreCase = true) || 
                                      line.contains("failed", ignoreCase = true)
                        val isConnection = line.contains("connection", ignoreCase = true) ||
                                           line.contains("accepted", ignoreCase = true)
                        val isHeader = line.startsWith("===")
                        
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            ),
                            color = when {
                                isHeader -> Color(0xFF00FFFF) // Cyan for headers
                                isError -> Color(0xFFFF4444)  // Red for errors
                                isConnection -> Color(0xFF00FF41) // Matrix green
                                isTimestamp && line.contains("]") -> Color(0xFF00FF41).copy(alpha = 0.8f)
                                else -> Color(0xFF00FF41).copy(alpha = 0.7f)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLogsPlaceholder(title: String, subtitle: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}
