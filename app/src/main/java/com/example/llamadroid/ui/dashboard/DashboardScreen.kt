package com.example.llamadroid.ui.dashboard

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.llamadroid.util.SystemMonitor
import com.example.llamadroid.service.ServerState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.llamadroid.service.FileServerService
import kotlinx.coroutines.launch


@Composable
fun DashboardScreen(
    navController: NavController,
) {
    val context = LocalContext.current
    val systemMonitor = remember { SystemMonitor(context) }
    val viewModel = remember { DashboardViewModel(systemMonitor) }
    val settingsRepo = remember { com.example.llamadroid.data.SettingsRepository(context) }
    
    val stats by viewModel.stats.collectAsState()
    val selectedModelPath by settingsRepo.selectedModelPath.collectAsState()
    val contextSize by settingsRepo.contextSize.collectAsState()
    val serverState by viewModel.serverState.collectAsState()
    
    val isRunning = serverState is ServerState.Running
    val isStarting = serverState is ServerState.Starting
    val isLoading = serverState is ServerState.Loading
    
    
    val scrollState = rememberScrollState()
    
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
            .verticalScroll(scrollState)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Header
        Column {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                stringResource(R.string.ai_hub_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // Server Status Card (Hero)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isRunning) 
                    MaterialTheme.colorScheme.primaryContainer 
                else 
                    MaterialTheme.colorScheme.surfaceVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Status indicator
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isRunning -> Color(0xFF4CAF50)
                                    isLoading -> Color(0xFF2196F3)
                                    isStarting -> Color(0xFFFFC107)
                                    serverState is ServerState.Error -> Color(0xFFF44336)
                                    else -> Color(0xFF9E9E9E)
                                }
                            )
                    )
                    Text(
                        when (serverState) {
                            is ServerState.Stopped -> stringResource(R.string.status_stopped)
                            is ServerState.Starting -> stringResource(R.string.dashboard_starting)
                            is ServerState.Loading -> stringResource(R.string.status_loading)
                            is ServerState.Running -> stringResource(R.string.status_running)
                            is ServerState.Error -> stringResource(R.string.status_error)
                        },
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
                
                if (isRunning) {
                    val remoteAccess by settingsRepo.remoteAccess.collectAsState()
                    val ips = remember { getDeviceIPs() }
                    val port = (serverState as ServerState.Running).port
                    
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Running on port $port",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                        if (remoteAccess && ips.isNotEmpty()) {
                            Text(
                                "Connect from:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                            )
                            ips.forEach { ip ->
                                Text(
                                    "http://$ip:$port",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        } else if (!remoteAccess) {
                            Text(
                                "Local only (enable remote access in Settings)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
                
                // Loading progress bar
                if (isLoading) {
                    val loadingState = serverState as ServerState.Loading
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            loadingState.status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (loadingState.progress >= 0f) {
                            LinearProgressIndicator(
                                progress = { loadingState.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = Color(0xFF2196F3),
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = Color(0xFF2196F3),
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                }
                
                if (serverState is ServerState.Error) {
                    Text(
                        (serverState as ServerState.Error).message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                
                // Model info
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.llm_model),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                selectedModelPath?.substringAfterLast("/") ?: stringResource(R.string.dashboard_no_model),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                maxLines = 1
                            )
                            // RAM Estimation
                            if (selectedModelPath != null) {
                                val modelFile = java.io.File(selectedModelPath)
                                if (modelFile.exists()) {
                                    val modelSizeGb = modelFile.length() / (1024.0 * 1024.0 * 1024.0)
                                    // Context memory: ~2 bytes per token per layer (rough estimate)
                                    // Model weights + 20% overhead + context buffer
                                    val estimatedRam = modelSizeGb * 1.2 + (contextSize / 1024.0) * 0.1
                                    Text(
                                        "~${String.format("%.1f", estimatedRam)} GB RAM needed",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (estimatedRam > stats.freeRamGb) 
                                            MaterialTheme.colorScheme.error 
                                        else 
                                            MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
                
                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!isRunning && !isStarting) {
                        Button(
                            onClick = { viewModel.startServer(context, selectedModelPath) },
                            enabled = selectedModelPath != null,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.dashboard_start_llamacpp))
                        }
                    } else if (isStarting) {
                        OutlinedButton(
                            onClick = { },
                            enabled = false,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.dashboard_starting))
                        }
                    } else {
                        Button(
                            onClick = { viewModel.stopServer(context) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.dashboard_stop_server))
                        }
                    }
                }
            }
        }
        
        // QR Code Section (when server is running with LAN access enabled)
        val remoteAccess by settingsRepo.remoteAccess.collectAsState()
        if (isRunning && remoteAccess) {
            val interfaces = remember { getDeviceIPs() }
            if (interfaces.isNotEmpty()) {
                val port = (serverState as ServerState.Running).port
                var expanded by remember { mutableStateOf(true) }  // Show QR codes by default
                var qrBitmaps by remember { mutableStateOf<Map<String, Bitmap?>>(emptyMap()) }
                
                LaunchedEffect(interfaces, port) {
                    withContext(Dispatchers.Default) {
                        val bitmaps = interfaces.associate { (ifName, ip) ->
                            val url = "http://$ip:$port"
                            ip to generateQrCode(url, 200)
                        }
                        qrBitmaps = bitmaps
                    }
                }
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Share, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("📲 Connect via QR", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            }
                            IconButton(onClick = { expanded = !expanded }) {
                                Icon(
                                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (expanded) "Collapse" else "Expand"
                                )
                            }
                        }
                        
                        AnimatedVisibility(visible = expanded) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                interfaces.forEach { (ifName, ip) ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.width(140.dp)
                                    ) {
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = Color.White),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            qrBitmaps[ip]?.let { bitmap ->
                                                Image(
                                                    bitmap = bitmap.asImageBitmap(),
                                                    contentDescription = "QR for $ip",
                                                    modifier = Modifier.size(120.dp).padding(8.dp)
                                                )
                                            } ?: Box(
                                                modifier = Modifier.size(120.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(ifName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                                        Text(ip, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // File Server Card
        val scope = rememberCoroutineScope()
        var fileServerRunning by remember { mutableStateOf(false) }
        var fileServerUrls by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        var fileServerFolderUri by remember { mutableStateOf<android.net.Uri?>(null) }
        var fileServerService by remember { mutableStateOf<FileServerService?>(null) }
        var fileServerBound by remember { mutableStateOf(false) }
        var fileServerQrExpanded by remember { mutableStateOf(false) }
        var fileServerQrBitmaps by remember { mutableStateOf<Map<String, Bitmap?>>(emptyMap()) }
        
        val fileServerConnection = remember {
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val binder = service as FileServerService.LocalBinder
                    fileServerService = binder.getService()
                    fileServerBound = true
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    fileServerService = null
                    fileServerBound = false
                }
            }
        }
        
        LaunchedEffect(Unit) {
            val intent = Intent(context, FileServerService::class.java)
            context.bindService(intent, fileServerConnection, Context.BIND_AUTO_CREATE)
        }
        
        LaunchedEffect(fileServerService) {
            fileServerService?.let { service ->
                launch {
                    service.isRunning.collect { fileServerRunning = it }
                }
                launch {
                    service.serverUrls.collect { urls ->
                        fileServerUrls = urls
                        // Generate QR codes
                        withContext(Dispatchers.Default) {
                            val bitmaps = urls.associate { (ifName, url) ->
                                val ip = url.substringAfter("://").substringBefore(":")
                                ip to generateQrCode(url, 200)
                            }
                            fileServerQrBitmaps = bitmaps
                        }
                    }
                }
            }
        }
        
        val folderPicker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            uri?.let {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                fileServerFolderUri = it
            }
        }
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (fileServerRunning) 
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
                else 
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("📂 File Server", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (fileServerRunning) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4CAF50))
                        )
                    }
                }
                
                // Folder selection
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Shared Folder", style = MaterialTheme.typography.labelMedium)
                        Text(
                            fileServerFolderUri?.lastPathSegment ?: "Not selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    OutlinedButton(
                        onClick = { folderPicker.launch(null) },
                        enabled = !fileServerRunning
                    ) {
                        Text("Browse")
                    }
                }
                
                // Start/Stop Button
                Button(
                    onClick = {
                        if (fileServerRunning) {
                            fileServerService?.stopServer()
                        } else {
                            fileServerFolderUri?.let { uri ->
                                context.startForegroundService(Intent(context, FileServerService::class.java))
                                fileServerService?.startServer(uri, FileServerService.DEFAULT_PORT)
                            }
                        }
                    },
                    enabled = fileServerFolderUri != null || fileServerRunning,
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (fileServerRunning) 
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    else 
                        ButtonDefaults.buttonColors()
                ) {
                    Icon(
                        if (fileServerRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (fileServerRunning) "Stop Server" else "Start Server (Port 9111)")
                }
                
                // QR codes when running
                if (fileServerRunning && fileServerUrls.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("📲 Scan to access files", fontWeight = FontWeight.Medium)
                            Text(
                                fileServerUrls.firstOrNull()?.second ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = { fileServerQrExpanded = !fileServerQrExpanded }) {
                            Icon(
                                if (fileServerQrExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null
                            )
                        }
                    }
                    
                    AnimatedVisibility(visible = fileServerQrExpanded) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            fileServerUrls.forEach { (ifName, url) ->
                                val ip = url.substringAfter("://").substringBefore(":")
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.width(140.dp)
                                ) {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color.White),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        fileServerQrBitmaps[ip]?.let { bitmap ->
                                            Image(
                                                bitmap = bitmap.asImageBitmap(),
                                                contentDescription = "QR for $ip",
                                                modifier = Modifier.size(120.dp).padding(8.dp)
                                            )
                                        } ?: Box(
                                            modifier = Modifier.size(120.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(ifName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                                    Text(ip, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Kiwix Server Card
        var kiwixService by remember { mutableStateOf<com.example.llamadroid.service.KiwixService?>(null) }
        val kiwixConnection = remember {
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    kiwixService = (binder as? com.example.llamadroid.service.KiwixService.LocalBinder)?.getService()
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    kiwixService = null
                }
            }
        }
        
        DisposableEffect(context) {
            val intent = Intent(context, com.example.llamadroid.service.KiwixService::class.java)
            context.bindService(intent, kiwixConnection, Context.BIND_AUTO_CREATE)
            onDispose {
                context.unbindService(kiwixConnection)
            }
        }
        
        val kiwixRunning by kiwixService?.isRunning?.collectAsState() ?: remember { mutableStateOf(false) }
        val db = remember { com.example.llamadroid.data.db.AppDatabase.getDatabase(context) }
        val installedZims by db.zimDao().getAllZims().collectAsState(initial = emptyList())
        var kiwixQrExpanded by remember { mutableStateOf(false) }
        var kiwixQrBitmaps by remember { mutableStateOf<Map<String, Bitmap?>>(emptyMap()) }
        val kiwixInterfaces = remember { getDeviceIPs() }
        
        LaunchedEffect(kiwixRunning) {
            if (kiwixRunning) {
                withContext(Dispatchers.Default) {
                    val bitmaps = kiwixInterfaces.associate { (ifName, ip) ->
                        val url = "http://$ip:8888"
                        ip to generateQrCode(url, 200)
                    }
                    kiwixQrBitmaps = bitmaps
                }
            }
        }
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        "📚 Kiwix Server",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (kiwixRunning) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4CAF50))
                        )
                    }
                }
                
                Text(
                    "${installedZims.size} ZIM file(s) installed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // Start/Stop Button
                Button(
                    onClick = {
                        if (kiwixRunning) {
                            kiwixService?.stopServer()
                        } else {
                            context.startForegroundService(Intent(context, com.example.llamadroid.service.KiwixService::class.java))
                            scope.launch {
                                kotlinx.coroutines.delay(500)
                                kiwixService?.startServer(installedZims.map { it.path })
                            }
                        }
                    },
                    enabled = installedZims.isNotEmpty() || kiwixRunning,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = if (kiwixRunning) 
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    else 
                        ButtonDefaults.buttonColors()
                ) {
                    Icon(
                        if (kiwixRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (kiwixRunning) "Stop Kiwix" else "Start Kiwix (Port 8888)")
                }
                
                // QR codes when running (always show - LAN always enabled)
                if (kiwixRunning && kiwixInterfaces.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📲 Connect via QR", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                        IconButton(onClick = { kiwixQrExpanded = !kiwixQrExpanded }) {
                            Icon(
                                if (kiwixQrExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (kiwixQrExpanded) "Collapse" else "Expand"
                            )
                        }
                    }
                    
                    AnimatedVisibility(visible = kiwixQrExpanded) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            kiwixInterfaces.forEach { (ifName, ip) ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.width(140.dp)
                                ) {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color.White),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        kiwixQrBitmaps[ip]?.let { bitmap ->
                                            Image(
                                                bitmap = bitmap.asImageBitmap(),
                                                contentDescription = "QR for $ip",
                                                modifier = Modifier.size(120.dp).padding(8.dp)
                                            )
                                        } ?: Box(
                                            modifier = Modifier.size(120.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(ifName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                                    Text("$ip:8888", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Distributed Inference Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "🌐 Distributed Inference",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
                
                Text(
                    "Connect multiple phones to run large models together",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Button(
                    onClick = { navController.navigate(com.example.llamadroid.ui.navigation.Screen.DistributedHub.route) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Setup Distributed")
                }
            }
        }
        
        // Memory Stats Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        stringResource(R.string.dashboard_memory),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
                
                LinearProgressIndicator(
                    progress = { stats.ramUsagePercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = when {
                        stats.ramUsagePercent > 80 -> MaterialTheme.colorScheme.error
                        stats.ramUsagePercent > 60 -> Color(0xFFFFA726)
                        else -> MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "${String.format("%.1f", stats.totalRamGb - stats.freeRamGb)} GB",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            "Used",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "${String.format("%.1f", stats.freeRamGb)} GB",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Free",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "${String.format("%.1f", stats.totalRamGb)} GB",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            "Total",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        
        // Kiwix Offline Library Card
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            onClick = { navController.navigate("zim_manager") }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Icon
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("📚", style = MaterialTheme.typography.headlineSmall)
                }
                
                // Content
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Kiwix Library",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        if (installedZims.isEmpty()) "Offline Wikipedia & more"
                        else "${installedZims.size} ZIM files installed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                }
                
                // Action icons
                Row {
                    if (installedZims.isNotEmpty()) {
                        IconButton(onClick = { navController.navigate("kiwix_viewer") }) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "View",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Icon(
                        Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Quick tip
        if (selectedModelPath == null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Column {
                        Text(
                            stringResource(R.string.dashboard_no_model),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            stringResource(R.string.dashboard_select_model),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
    }
}


/**
 * Get all IPv4 addresses from all network interfaces.
 * Returns list of (friendlyName, ipAddress) pairs.
 */
private fun getDeviceIPs(): List<Pair<String, String>> {
    val ips = mutableListOf<Pair<String, String>>()
    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (!iface.isUp || iface.isLoopback) continue
            
            val addresses = iface.inetAddresses
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                    addr.hostAddress?.let { ip ->
                        // Skip link-local addresses (169.254.x.x)
                        if (!ip.startsWith("169.254")) {
                            val friendlyName = when {
                                iface.name.startsWith("wlan") -> "WiFi"
                                iface.name.startsWith("eth") -> "Ethernet"
                                iface.name.startsWith("tun") -> "VPN"
                                iface.name.startsWith("rmnet") -> "Mobile"
                                else -> iface.name
                            }
                            ips.add(Pair(friendlyName, ip))
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        // Ignore network enumeration errors
    }
    return ips
}

/**
 * Generate a QR code bitmap from a URL string.
 */
private fun generateQrCode(content: String, size: Int): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}
