package com.example.llamadroid.ui.kiwix

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ZimEntity
import com.example.llamadroid.service.KiwixService
import kotlinx.coroutines.launch

/**
 * WebView-based viewer for Kiwix content served by kiwix-serve.
 * 
 * @param zimPath Optional path to auto-load a specific ZIM file
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KiwixViewerScreen(navController: NavController, zimPath: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    
    // Service connection
    var kiwixService by remember { mutableStateOf<KiwixService?>(null) }
    val serviceConnection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                kiwixService = (binder as? KiwixService.LocalBinder)?.getService()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                kiwixService = null
            }
        }
    }
    
    // Bind to service
    DisposableEffect(context) {
        val intent = Intent(context, KiwixService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        onDispose {
            context.unbindService(serviceConnection)
        }
    }
    
    // State
    val isRunning by kiwixService?.isRunning?.collectAsState() ?: remember { mutableStateOf(false) }
    val serverUrl by kiwixService?.serverUrl?.collectAsState() ?: remember { mutableStateOf<String?>(null) }
    val installedZims by db.zimDao().getAllZims().collectAsState(initial = emptyList())
    
    var selectedZim by remember { mutableStateOf<ZimEntity?>(null) }
    var showZimPicker by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var currentTitle by remember { mutableStateOf("Kiwix") }
    
    // Auto-select ZIM if path provided
    LaunchedEffect(zimPath, installedZims) {
        if (zimPath != null && selectedZim == null) {
            // Decode path and find matching ZIM
            val decodedPath = java.net.URLDecoder.decode(zimPath, "UTF-8")
            val matchingZim = installedZims.find { it.path == decodedPath }
            if (matchingZim != null) {
                selectedZim = matchingZim
                com.example.llamadroid.util.DebugLog.log("[KIWIX] Auto-selected ZIM: ${matchingZim.title}")
            }
        }
    }
    
    // Start server with ALL installed ZIMs using library mode
    LaunchedEffect(installedZims, kiwixService) {
        if (installedZims.isNotEmpty() && kiwixService != null && !isRunning) {
            // Start the service
            val intent = Intent(context, KiwixService::class.java)
            context.startForegroundService(intent)
            
            // Wait a bit for service to start then start server with ALL ZIMs
            kotlinx.coroutines.delay(500)
            val allZimPaths = installedZims.map { it.path }
            kiwixService?.startServer(allZimPaths)
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentTitle, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { 
                        // Stop server before leaving
                        kiwixService?.stopServer()
                        navController.popBackStack() 
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Stop server and exit button
                    if (isRunning) {
                        IconButton(onClick = { 
                            kiwixService?.stopServer()
                            // Navigate to Dashboard Home
                            navController.navigate("dashboard") {
                                popUpTo(navController.graph.startDestinationId) { inclusive = false }
                            }
                        }) {
                            Icon(Icons.Default.Close, "Stop & Exit")
                        }
                    }
                }
            )
        },
        bottomBar = {
            // Navigation controls
            if (isRunning && serverUrl != null) {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") },
                        label = { Text("Back") },
                        selected = false,
                        enabled = canGoBack,
                        onClick = { webView?.goBack() }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward") },
                        label = { Text("Forward") },
                        selected = false,
                        enabled = canGoForward,
                        onClick = { webView?.goForward() }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Home, "Home") },
                        label = { Text("Home") },
                        selected = false,
                        onClick = { webView?.loadUrl(serverUrl!!) }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Refresh, "Reload") },
                        label = { Text("Reload") },
                        selected = false,
                        onClick = { webView?.reload() }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                installedZims.isEmpty() -> {
                    // No ZIMs installed
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.Info,
                            null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No ZIM files installed")
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { navController.navigate("zim_manager") }) {
                            Text("Browse Catalog")
                        }
                    }
                }
                !isRunning -> {
                    // Starting server
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Starting Kiwix server...")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Loading ${installedZims.size} ZIM file(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                serverUrl != null -> {
                    // WebView
                    KiwixWebView(
                        url = serverUrl!!,
                        onWebViewCreated = { webView = it },
                        onPageTitleChanged = { currentTitle = it },
                        onNavigationChanged = { back, forward ->
                            canGoBack = back
                            canGoForward = forward
                        }
                    )
                }
            }
        }
    }
    
    // ZIM picker dialog
    if (showZimPicker) {
        AlertDialog(
            onDismissRequest = { showZimPicker = false },
            title = { Text("Select ZIM") },
            text = {
                Column {
                    installedZims.forEach { zim ->
                        TextButton(
                            onClick = {
                                selectedZim = zim
                                showZimPicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(zim.title)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showZimPicker = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KiwixWebView(
    url: String,
    onWebViewCreated: (WebView) -> Unit,
    onPageTitleChanged: (String) -> Unit,
    onNavigationChanged: (canBack: Boolean, canForward: Boolean) -> Unit
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.title?.let { onPageTitleChanged(it) }
                        onNavigationChanged(view?.canGoBack() == true, view?.canGoForward() == true)
                    }
                    
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        // Keep all navigation within the WebView
                        return false
                    }
                }
                
                loadUrl(url)
                onWebViewCreated(this)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
