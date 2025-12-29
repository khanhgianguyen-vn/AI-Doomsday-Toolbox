package com.example.llamadroid.ui.kiwix

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ZimEntity
import com.example.llamadroid.data.model.KiwixCatalogClient
import com.example.llamadroid.data.model.ZimMetadata
import com.example.llamadroid.data.model.ZimRepository
import com.example.llamadroid.util.FormatUtils
import com.example.llamadroid.service.DownloadService
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZimManagerScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val repo = remember { ZimRepository(context, db.zimDao()) }
    val settings = remember { SettingsRepository(context) }
    
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Installed", "Catalog")
    
    // ZIM folder state - removed folder picker, always use internal storage
    val zimFolderUri = null // Using internal app storage
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ZIM Library") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tab Row - 4 tabs (Settings moved to main Settings screen)
            val tabs = listOf("Installed", "Catalog", "Downloads", "Share")
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 0.dp
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }
            
            // Tab Content
            when (selectedTab) {
                0 -> InstalledZimsTab(repo, navController)
                1 -> CatalogTab(repo, zimFolderUri)
                2 -> DownloadingTab()
                3 -> ZimShareTab()
            }
        }
    }
}

@Composable
private fun FolderSetupScreen(
    onSelectFolder: () -> Unit,
    onSkip: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    Icons.Default.Home,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Text(
                    "Select ZIM Storage Folder",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                
                Text(
                    "Choose where to store downloaded ZIM files. " +
                    "ZIM files can be large (100MB - 90GB), so select a folder with enough space.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = onSelectFolder,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Select Folder")
                }
                
                TextButton(onClick = onSkip) {
                    Text("Skip for now (use internal storage)")
                }
            }
        }
    }
}

@Composable
fun InstalledZimsTab(repo: ZimRepository, navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installedZims by repo.getInstalledZims().collectAsState(initial = emptyList())
    
    // Rename dialog state
    var showRenameDialog by remember { mutableStateOf(false) }
    var zimToRename by remember { mutableStateOf<ZimEntity?>(null) }
    var newZimName by remember { mutableStateOf("") }
    
    // Share dialog state
    var showShareDialog by remember { mutableStateOf(false) }
    var zimToShare by remember { mutableStateOf<ZimEntity?>(null) }
    
    // Get ZIM storage directory
    val zimDir = remember { 
        File(context.getExternalFilesDir(null), "zim_downloads").also { it.mkdirs() }
    }
    
    // Import picker - tries to use direct path reference, only copies if necessary
    val importPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    // Take persistable permission for future access
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            it, 
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: Exception) {
                        com.example.llamadroid.util.DebugLog.log("[ZIM] Could not take persistable permission: ${e.message}")
                    }
                    
                    // Get filename from SAF
                    val cursor = context.contentResolver.query(it, null, null, null, null)
                    var filename = "imported_${System.currentTimeMillis()}.zim"
                    var fileSize = 0L
                    cursor?.use { c ->
                        val nameIndex = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (c.moveToFirst()) {
                            if (nameIndex >= 0) filename = c.getString(nameIndex)
                            if (sizeIndex >= 0) fileSize = c.getLong(sizeIndex)
                        }
                    }
                    
                    // Ensure .zim extension
                    if (!filename.endsWith(".zim")) {
                        filename += ".zim"
                    }
                    
                    // Try to resolve actual file path from URI (works for local files on external storage)
                    var resolvedPath: String? = null
                    try {
                        // Try file:// scheme
                        if (it.scheme == "file") {
                            resolvedPath = it.path
                        } else if (it.scheme == "content") {
                            // Try DocumentsContract for content:// URIs - external storage only
                            try {
                                val docId = android.provider.DocumentsContract.getDocumentId(it)
                                // Handle external storage provider (Downloads, Documents, etc)
                                if (it.authority == "com.android.externalstorage.documents") {
                                    val split = docId.split(":")
                                    if (split.size >= 2 && split[0] == "primary") {
                                        resolvedPath = android.os.Environment.getExternalStorageDirectory().absolutePath + "/" + split[1]
                                    }
                                }
                            } catch (e: Exception) {
                                // Not a documents URI, skip
                            }
                        }
                    } catch (e: Exception) {
                        com.example.llamadroid.util.DebugLog.log("[ZIM] Could not resolve path: ${e.message}")
                    }
                    
                    // Check if resolved path is accessible
                    val finalPath: String
                    val wasCopied: Boolean
                    
                    if (resolvedPath != null && File(resolvedPath).canRead()) {
                        // Use direct path reference - no copy needed!
                        finalPath = resolvedPath
                        wasCopied = false
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Using reference: $filename", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        com.example.llamadroid.util.DebugLog.log("[ZIM] Using direct path: $resolvedPath")
                    } else {
                        // Fallback: copy to app storage
                        val destFile = File(zimDir, filename)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Copying $filename...", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        context.contentResolver.openInputStream(it)?.use { input ->
                            java.io.FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        finalPath = destFile.absolutePath
                        fileSize = destFile.length()
                        wasCopied = true
                        com.example.llamadroid.util.DebugLog.log("[ZIM] Copied to: $finalPath")
                    }
                    
                    // Register in database
                    val db = com.example.llamadroid.data.db.AppDatabase.getDatabase(context)
                    val zimEntity = com.example.llamadroid.data.db.ZimEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        filename = filename,
                        path = finalPath,
                        title = filename.substringBeforeLast("."),
                        description = if (wasCopied) "Imported (copied)" else "Imported (reference)",
                        language = "unknown",
                        sizeBytes = fileSize,
                        articleCount = 0,
                        mediaCount = 0,
                        date = "",
                        creator = "",
                        publisher = "",
                        downloadUrl = "",
                        catalogEntryId = null,
                        sourceUri = it.toString()
                    )
                    db.zimDao().insertZim(zimEntity)
                    
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        val msg = if (wasCopied) "Imported (copied): $filename" else "Imported (reference): $filename"
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    com.example.llamadroid.util.DebugLog.log("[ZIM] Import failed: ${e.message}")
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Import failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
    
    // Export function - copies ZIM to Downloads folder
    fun exportZimToDownloads(zim: ZimEntity) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val sourceFile = File(zim.path)
                if (!sourceFile.exists()) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "File not found", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    // Use MediaStore for Android 10+
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, zim.filename)
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                        put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                    }
                    
                    val resolver = context.contentResolver
                    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    
                    if (uri != null) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Exporting to Downloads...", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        
                        resolver.openOutputStream(uri)?.use { output ->
                            java.io.FileInputStream(sourceFile).use { input ->
                                input.copyTo(output)
                            }
                        }
                        
                        // Mark as complete
                        contentValues.clear()
                        contentValues.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(uri, contentValues, null, null)
                        
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Exported to Downloads: ${zim.filename}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        
                        com.example.llamadroid.util.DebugLog.log("[ZIM] Exported: ${zim.filename} to Downloads")
                    }
                } else {
                    // Legacy fallback for API < 29
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    val destFile = File(downloadsDir, zim.filename)
                    
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Exporting to Downloads...", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    
                    sourceFile.copyTo(destFile, overwrite = true)
                    
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Exported to Downloads: ${zim.filename}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    
                    com.example.llamadroid.util.DebugLog.log("[ZIM] Exported: ${zim.filename} to Downloads (legacy)")
                }
            } catch (e: Exception) {
                com.example.llamadroid.util.DebugLog.log("[ZIM] Export failed: ${e.message}")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Export failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        if (installedZims.isEmpty()) {
            // Empty state
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.List,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "No ZIM files installed",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Browse the catalog to download offline content",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(installedZims) { zim ->
                    ZimCard(
                        zim = zim,
                        onDelete = {
                            scope.launch {
                                repo.deleteZim(zim)
                            }
                        },
                        onRename = {
                            zimToRename = zim
                            newZimName = zim.filename.substringBeforeLast(".")
                            showRenameDialog = true
                        },
                        onView = {
                            // Navigate to Kiwix viewer
                            navController.navigate("kiwix_viewer?zimPath=${java.net.URLEncoder.encode(zim.path, "UTF-8")}")
                        },
                        onExport = {
                            exportZimToDownloads(zim)
                        },
                        onShare = {
                            zimToShare = zim
                            showShareDialog = true
                        }
                    )
                }
            }
        }
        
        // FAB for import
        FloatingActionButton(
            onClick = { importPicker.launch(arrayOf("*/*")) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Default.Add, "Import ZIM")
        }
        
        // Rename Dialog
        if (showRenameDialog && zimToRename != null) {
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("Rename ZIM") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = newZimName,
                            onValueChange = { newZimName = it },
                            label = { Text("New name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "Extension .zim will be kept",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                repo.renameZim(zimToRename!!, newZimName)
                            }
                            showRenameDialog = false
                        }
                    ) { Text("Rename") }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
                }
            )
        }
        
        // Share Dialog with QR code
        if (showShareDialog && zimToShare != null) {
            ZimShareDialog(
                zim = zimToShare!!,
                onDismiss = { 
                    showShareDialog = false
                    zimToShare = null 
                }
            )
        }
    }
}


/**
 * Dialog for sharing ZIM files over the local network.
 * Shows instructions to open the Kiwix viewer for network sharing.
 */
@Composable
fun ZimShareDialog(
    zim: ZimEntity,
    onDismiss: () -> Unit,
    onOpenViewer: () -> Unit = {}
) {
    // Get device IP address in a safe way
    var ipAddress by remember { mutableStateOf<String?>(null) }
    
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces != null && interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (!iface.isUp || iface.isLoopback) continue
                    
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            ipAddress = addr.hostAddress
                            return@withContext
                        }
                    }
                }
            } catch (e: Exception) {
                com.example.llamadroid.util.DebugLog.log("[ZIM] Failed to get IP: ${e.message}")
            }
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share ${zim.title}") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "To share ZIM files over the network:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text("1. Open the Kiwix Viewer", style = MaterialTheme.typography.bodySmall)
                    Text("2. The server will start automatically", style = MaterialTheme.typography.bodySmall)
                    Text("3. Enable LAN access in Settings", style = MaterialTheme.typography.bodySmall)
                    Text("4. Other devices can connect to:", style = MaterialTheme.typography.bodySmall)
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (ipAddress != null) "http://$ipAddress:8888" else "Connect to WiFi",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(12.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}


@Composable
fun CatalogTab(repo: ZimRepository, zimFolderUri: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var selectedLanguage by remember { mutableStateOf("all") }
    var catalogEntries by remember { mutableStateOf<List<KiwixCatalogClient.CatalogEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var languageExpanded by remember { mutableStateOf(false) }
    
    // Fetch catalog on load or search/filter change
    LaunchedEffect(searchQuery, selectedLanguage) {
        isLoading = true
        errorMessage = null
        
        val result = KiwixCatalogClient.fetchCatalog(
            query = searchQuery.takeIf { it.isNotBlank() },
            language = selectedLanguage.takeIf { it != "all" },
            count = 50
        )
        
        result.fold(
            onSuccess = { entries ->
                catalogEntries = entries
                isLoading = false
            },
            onFailure = { e ->
                errorMessage = "Failed to load catalog: ${e.message}"
                isLoading = false
            }
        )
    }
    
    Column(modifier = Modifier.fillMaxSize()) {
        // Search bar and filters
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search ZIMs...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            
            // Language dropdown
            Box {
                OutlinedButton(onClick = { languageExpanded = true }) {
                    Text(selectedLanguage.uppercase())
                }
                DropdownMenu(
                    expanded = languageExpanded,
                    onDismissRequest = { languageExpanded = false }
                ) {
                    KiwixCatalogClient.LANGUAGE_OPTIONS.forEach { (code, name) ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                selectedLanguage = code
                                languageExpanded = false
                            }
                        )
                    }
                }
            }
        }
        
        // Content
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Loading catalog...")
                    }
                }
            }
            errorMessage != null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Warning,
                            null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Error: $errorMessage")
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { searchQuery = searchQuery }) {
                            Text("Retry")
                        }
                    }
                }
            }
            catalogEntries.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No results found")
                }
            }
            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(catalogEntries) { entry ->
                        CatalogEntryCard(
                            entry = entry,
                            onDownload = {
                                // Determine download destination
                                val destPath: String
                                val filename = "${entry.name.ifBlank { entry.title.replace(" ", "_") }}.zim"
                                
                                if (zimFolderUri != null) {
                                    // Use SAF folder - DownloadService handles SAF
                                    destPath = "$zimFolderUri/$filename"
                                } else {
                                    // Fallback to internal storage
                                    val zimDir = File(context.filesDir, "zim_files")
                                    zimDir.mkdirs()
                                    destPath = File(zimDir, filename).absolutePath
                                }
                                
                                // Use Android DownloadManager for reliable background downloads
                                val downloadManager = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                                
                                val request = android.app.DownloadManager.Request(android.net.Uri.parse(entry.url))
                                    .setTitle(entry.title)
                                    .setDescription("Downloading ZIM file")
                                    .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                    .setAllowedOverMetered(true)
                                    .setAllowedOverRoaming(false)
                                
                                // Set download destination - DownloadManager requires external storage
                                // Always use external files dir which is accessible by DownloadManager
                                val zimDir = File(context.getExternalFilesDir(null), "zim_downloads")
                                zimDir.mkdirs()
                                request.setDestinationInExternalFilesDir(context, "zim_downloads", filename)
                                
                                try {
                                    val downloadId = downloadManager.enqueue(request)
                                    
                                    // Register pending download for completion handler
                                    com.example.llamadroid.service.ZimDownloadReceiver.registerPendingDownload(
                                        downloadId,
                                        com.example.llamadroid.service.ZimDownloadReceiver.Companion.PendingZimDownload(
                                            id = entry.id,
                                            title = entry.title,
                                            description = entry.description,
                                            language = entry.language,
                                            sizeBytes = entry.size,
                                            articleCount = entry.articleCount,
                                            mediaCount = entry.mediaCount,
                                            date = entry.date,
                                            creator = entry.creator,
                                            publisher = entry.publisher,
                                            downloadUrl = entry.url,
                                            filename = filename
                                        )
                                    )
                                    
                                    android.widget.Toast.makeText(
                                        context,
                                        "Downloading: ${entry.title}",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                    
                                    com.example.llamadroid.util.DebugLog.log("[KIWIX] Started download ID=$downloadId for ${entry.title}, size=${entry.size}")
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Download failed: ${e.message}",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                    com.example.llamadroid.util.DebugLog.log("[KIWIX] Download failed: ${e.message}")
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ZimCard(
    zim: ZimEntity,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onView: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        onClick = onView
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.Star,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
                Column {
                    Text(
                        zim.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${zim.language.uppercase()} • ${FormatUtils.formatFileSize(zim.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (zim.articleCount > 0) {
                        Text(
                            "${zim.articleCount} articles",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            Row {
                // More options menu
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, "More options")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { showMenu = false; onRename() }
                        )
                        DropdownMenuItem(
                            text = { Text("Export to Downloads") },
                            leadingIcon = { Icon(Icons.Default.Share, null) },
                            onClick = { showMenu = false; onExport() }
                        )
                        DropdownMenuItem(
                            text = { Text("Share over Network") },
                            leadingIcon = { Icon(Icons.Default.Share, null) },
                            onClick = { showMenu = false; onShare() }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { showMenu = false; onDelete() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CatalogEntryCard(
    entry: KiwixCatalogClient.CatalogEntry,
    onDownload: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                // Show size and language inline
                Text(
                    "${entry.language.uppercase()} • ${if (entry.size > 0) FormatUtils.formatFileSize(entry.size) else "Size unknown"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    entry.description.take(80) + if (entry.description.length > 80) "..." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            FilledTonalButton(onClick = onDownload) {
                Icon(Icons.Default.KeyboardArrowDown, null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Get")
            }
        }
    }
}

@Composable
fun DownloadingTab() {
    val context = LocalContext.current
    val downloadManager = remember { 
        context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager 
    }
    var activeDownloads by remember { mutableStateOf<List<DownloadInfo>>(emptyList()) }
    
    // Poll for active downloads
    LaunchedEffect(Unit) {
        while (true) {
            activeDownloads = getActiveDownloads(downloadManager)
            kotlinx.coroutines.delay(1000) // Update every second
        }
    }
    
    if (activeDownloads.isEmpty()) {
        // Empty state
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
                Text(
                    "No active downloads",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Download ZIMs from the Catalog tab",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(activeDownloads, key = { it.id }) { download ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            download.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { download.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${(download.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "${FormatUtils.formatFileSize(download.downloadedBytes)} / ${FormatUtils.formatFileSize(download.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                // Cancel the download
                                downloadManager.remove(download.id)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Cancel Download")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Share tab - serves raw ZIM files for download via HTTP web UI.
 * Similar to model sharing - other devices can download the actual ZIM files.
 */
@Composable
fun ZimShareTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val installedZims by db.zimDao().getAllZims().collectAsState(initial = emptyList())
    
    // Service connection for ZimShareService
    var shareService by remember { mutableStateOf<com.example.llamadroid.service.ZimShareService?>(null) }
    val serviceConnection = remember {
        object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
                shareService = (binder as? com.example.llamadroid.service.ZimShareService.LocalBinder)?.getService()
            }
            override fun onServiceDisconnected(name: android.content.ComponentName?) {
                shareService = null
            }
        }
    }
    
    DisposableEffect(context) {
        val intent = android.content.Intent(context, com.example.llamadroid.service.ZimShareService::class.java)
        context.bindService(intent, serviceConnection, android.content.Context.BIND_AUTO_CREATE)
        onDispose {
            context.unbindService(serviceConnection)
        }
    }
    
    val isRunning by shareService?.isRunning?.collectAsState() ?: remember { mutableStateOf(false) }
    val serverUrls by shareService?.serverUrls?.collectAsState() ?: remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    val activeDownloads by shareService?.activeDownloads?.collectAsState() ?: remember { mutableStateOf(0) }
    
    // QR code generation
    var qrBitmaps by remember { mutableStateOf<Map<String, android.graphics.Bitmap?>>(emptyMap()) }
    
    LaunchedEffect(serverUrls) {
        if (serverUrls.isNotEmpty()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val bitmaps = serverUrls.associate { (_, url) ->
                    val ip = url.removePrefix("http://").substringBefore(":")
                    ip to try {
                        val writer = com.google.zxing.qrcode.QRCodeWriter()
                        val bitMatrix = writer.encode(url, com.google.zxing.BarcodeFormat.QR_CODE, 200, 200)
                        val bitmap = android.graphics.Bitmap.createBitmap(200, 200, android.graphics.Bitmap.Config.RGB_565)
                        for (x in 0 until 200) {
                            for (y in 0 until 200) {
                                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                            }
                        }
                        bitmap
                    } catch (e: Exception) { null }
                }
                qrBitmaps = bitmaps
            }
        }
    }
    
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        if (isRunning) Icons.Default.CheckCircle else Icons.Default.Share,
                        null,
                        modifier = Modifier.size(48.dp),
                        tint = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (isRunning) "Sharing ZIM Files" else "ZIM File Server",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${installedZims.size} ZIM file(s) available for download",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isRunning && activeDownloads > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "📥 $activeDownloads active download(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Button(
                        onClick = {
                            if (isRunning) {
                                shareService?.stopServer()
                            } else {
                                val intent = android.content.Intent(context, com.example.llamadroid.service.ZimShareService::class.java)
                                context.startForegroundService(intent)
                                scope.launch {
                                    kotlinx.coroutines.delay(500)
                                    shareService?.startServer()
                                }
                            }
                        },
                        enabled = installedZims.isNotEmpty() || isRunning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            if (isRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                            null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isRunning) "Stop Server" else "Start File Server (Port 8087)")
                    }
                }
            }
        }
        
        // QR Codes and URLs when running
        if (isRunning && serverUrls.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "📲 Scan to Download ZIM Files",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            serverUrls.forEach { (ifName, url) ->
                                val ip = url.removePrefix("http://").substringBefore(":")
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.width(140.dp)
                                ) {
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surface
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        qrBitmaps[ip]?.let { bitmap ->
                                            Image(
                                                bitmap = bitmap.asImageBitmap(),
                                                contentDescription = "QR for $url",
                                                modifier = Modifier
                                                    .size(120.dp)
                                                    .padding(8.dp)
                                            )
                                        } ?: Box(
                                            modifier = Modifier.size(120.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(ifName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                                    Text(url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Open any URL in a browser to download ZIM files",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
        
        // Info
        item {
            Text(
                "Share ZIM files with other devices on your network. They can download files directly from this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private data class DownloadInfo(
    val id: Long,
    val title: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val progress: Float
)

private fun getActiveDownloads(downloadManager: android.app.DownloadManager): List<DownloadInfo> {
    val downloads = mutableListOf<DownloadInfo>()
    val query = android.app.DownloadManager.Query().setFilterByStatus(
        android.app.DownloadManager.STATUS_RUNNING or android.app.DownloadManager.STATUS_PENDING
    )
    
    try {
        downloadManager.query(query)?.use { cursor ->
            val idCol = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_ID)
            val titleCol = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_TITLE)
            val downloadedCol = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalCol = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "Unknown"
                val downloaded = cursor.getLong(downloadedCol)
                val total = cursor.getLong(totalCol)
                val progress = if (total > 0) downloaded.toFloat() / total else 0f
                
                downloads.add(DownloadInfo(id, title, downloaded, total, progress))
            }
        }
    } catch (e: Exception) {
        // Ignore
    }
    
    return downloads
}

@Composable
fun KiwixSettingsTab(
    settings: SettingsRepository
) {
    val kiwixRemoteAccess by settings.kiwixRemoteAccess.collectAsState()
    val context = LocalContext.current
    
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Storage Info Section
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Storage",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        "ZIM files are stored in the app's internal storage for fast access by the Kiwix server.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val zimDir = File(context.getExternalFilesDir(null), "zim_downloads")
                    Text(
                        "Path: ${zimDir.absolutePath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
        
        // Server Section
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Kiwix Server",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "LAN Access",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                if (kiwixRemoteAccess) 
                                    "Server accessible from other devices (0.0.0.0)" 
                                else 
                                    "Local only (127.0.0.1)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = kiwixRemoteAccess,
                            onCheckedChange = { settings.setKiwixRemoteAccess(it) }
                        )
                    }
                }
            }
        }
        
        // Info Section
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "ZIM files can be very large (100MB - 90GB). " +
                        "Make sure you have enough storage space.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
