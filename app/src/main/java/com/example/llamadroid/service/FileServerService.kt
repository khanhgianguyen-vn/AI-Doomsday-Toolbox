package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import com.example.llamadroid.util.FormatUtils
import com.example.llamadroid.util.WakeLockManager

/**
 * Service that hosts an HTTP file server for sharing files from a user-selected folder.
 * Uses NanoHTTPD for lightweight HTTP serving.
 */
class FileServerService : Service() {
    
    companion object {
        private const val TAG = "FileServerService"
        const val DEFAULT_PORT = 9111
    }
    
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var server: FileServer? = null
    private var currentPort = DEFAULT_PORT
    private var folderUri: Uri? = null
    
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning
    private var notificationTaskId: Int? = null
    
    private val _serverUrls = MutableStateFlow<List<Pair<String, String>>>(emptyList()) // (interfaceName, url)
    val serverUrls: StateFlow<List<Pair<String, String>>> = _serverUrls
    
    inner class LocalBinder : Binder() {
        fun getService(): FileServerService = this@FileServerService
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.FILE_SERVER,
            "File Server"
        )
        notificationTaskId = taskId
        startForeground(taskId, notification)
        return START_STICKY
    }
    
    override fun onDestroy() {
        stopServer()
        serviceScope.cancel()
        super.onDestroy()
    }
    
    /**
     * Start the file server with the specified folder and port.
     */
    fun startServer(folderUri: Uri, port: Int = DEFAULT_PORT) {
        Log.i(TAG, "startServer called with folder: $folderUri")
        
        if (_isRunning.value) {
            Log.w(TAG, "Server already running")
            return
        }
        
        this.folderUri = folderUri
        
        serviceScope.launch {
            try {
                currentPort = port
                val allIps = getAllLocalIpAddresses()
                
                if (allIps.isEmpty()) {
                    Log.e(TAG, "No network interfaces found")
                    withContext(Dispatchers.Main) {
                        updateNotification("Error: No network connection")
                    }
                    return@launch
                }
                
                server = FileServer(this@FileServerService, folderUri, port).apply {
                    start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                }
                
                _isRunning.value = true
                _serverUrls.value = allIps.map { (ifName, ip) -> Pair(ifName, "http://$ip:$port") }
                WakeLockManager.acquire(this@FileServerService, "FileServerService")
                
                Log.i(TAG, "File server started on ${allIps.size} interfaces")
                
                withContext(Dispatchers.Main) {
                    val primaryUrl = _serverUrls.value.firstOrNull()?.second ?: "unknown"
                    updateNotification("Sharing files at $primaryUrl")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server: ${e.message}", e)
                _isRunning.value = false
                _serverUrls.value = emptyList()
                withContext(Dispatchers.Main) {
                    updateNotification("Error: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Stop the file server.
     */
    fun stopServer() {
        Log.i(TAG, "Stopping file server")
        server?.stop()
        server = null
        _isRunning.value = false
        _serverUrls.value = emptyList()
        folderUri = null
        WakeLockManager.release("FileServerService")
    }
    
    private fun getAllLocalIpAddresses(): List<Pair<String, String>> {
        val ips = mutableListOf<Pair<String, String>>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ips
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
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
            Log.e(TAG, "Error getting IP addresses", e)
        }
        return ips
    }
    
    private fun updateNotification(message: String) {
        notificationTaskId?.let {
            UnifiedNotificationManager.updateProgress(it, 1f, message)
        }
    }
    
    /**
     * Internal HTTP server for serving files.
     */
    private inner class FileServer(
        private val context: Context,
        private val folderUri: Uri,
        port: Int
    ) : NanoHTTPD(port) {
        
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri.trimStart('/')
            val params = session.parms ?: emptyMap()
            Log.d(TAG, "Request: $uri, params: $params")
            
            return try {
                // Handle ZIP download request
                if (params["download"] == "zip") {
                    return serveZipDownload(uri)
                }
                
                if (uri.isEmpty() || uri == "/") {
                    serveDirectoryListing(folderUri, "")
                } else {
                    serveFileOrDirectory(uri)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error serving: $uri", e)
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
            }
        }
        
        private fun serveDirectoryListing(dirUri: Uri, path: String): Response {
            val docFile = DocumentFile.fromTreeUri(context, dirUri) ?: 
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Folder not found")
            
            val targetDir = if (path.isEmpty()) {
                docFile
            } else {
                navigateToPath(docFile, path) ?: 
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Path not found: $path")
            }
            
            val html = buildString {
                append("<!DOCTYPE html><html><head>")
                append("<meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'>")
                append("<title>📂 ${targetDir.name ?: "Files"}</title>")
                append("<style>")
                append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; padding: 20px; max-width: 800px; margin: 0 auto; background: #1a1a2e; color: #eee; }")
                append("h1 { color: #4cc9f0; }")
                append("a { color: #7209b7; text-decoration: none; padding: 10px; display: block; border-radius: 8px; margin: 4px 0; background: #16213e; }")
                append("a:hover { background: #0f3460; }")
                append(".file { color: #f72585; }")
                append(".folder { color: #4cc9f0; }")
                append(".size { color: #888; font-size: 0.9em; float: right; }")
                append(".back { background: #3a0ca3; color: white; }")
                append("</style></head><body>")
                append("<h1>📂 ${targetDir.name ?: "Files"}</h1>")
                
                // Back link
                if (path.isNotEmpty()) {
                    val parentPath = path.substringBeforeLast("/", "")
                    append("<a class='back' href='/$parentPath'>⬅️ Back</a>")
                }
                
                // Download folder as ZIP button
                val downloadPath = if (path.isEmpty()) "" else URLEncoder.encode(path, "UTF-8").replace("+", "%20")
                append("<a class='download' href='/$downloadPath?download=zip' style='background: #2a9d8f; color: white; text-align: center;'>📦 Download as ZIP</a>")
                
                // List files and folders
                targetDir.listFiles().sortedWith(compareBy({ !it.isDirectory }, { it.name?.lowercase() })).forEach { file ->
                    val name = file.name ?: "unknown"
                    val filePath = if (path.isEmpty()) name else "$path/$name"
                    val encodedPath = URLEncoder.encode(filePath, "UTF-8").replace("+", "%20")
                    
                    if (file.isDirectory) {
                        append("<a class='folder' href='/$encodedPath'>📁 $name</a>")
                    } else {
                        val sizeStr = FormatUtils.formatFileSize(file.length())
                        append("<a class='file' href='/$encodedPath'>📄 $name <span class='size'>$sizeStr</span></a>")
                    }
                }
                
                append("</body></html>")
            }
            
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }
        
        private fun serveFileOrDirectory(path: String): Response {
            val docFile = DocumentFile.fromTreeUri(context, folderUri) ?: 
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Folder not found")
            
            val decodedPath = java.net.URLDecoder.decode(path, "UTF-8")
            val target = navigateToPath(docFile, decodedPath) ?: 
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found: $path")
            
            return if (target.isDirectory) {
                serveDirectoryListing(folderUri, decodedPath)
            } else {
                serveFile(target)
            }
        }
        
        private fun navigateToPath(root: DocumentFile, path: String): DocumentFile? {
            if (path.isEmpty()) return root
            
            var current = root
            for (segment in path.split("/")) {
                if (segment.isEmpty()) continue
                current = current.listFiles().find { it.name == segment } ?: return null
            }
            return current
        }
        
        private fun serveFile(file: DocumentFile): Response {
            val uri = file.uri
            val mimeType = file.type ?: "application/octet-stream"
            val length = file.length()
            
            val inputStream: InputStream = context.contentResolver.openInputStream(uri)
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Cannot open file")
            
            return newFixedLengthResponse(Response.Status.OK, mimeType, inputStream, length)
        }

        
        private fun serveZipDownload(path: String): Response {
            val docFile = DocumentFile.fromTreeUri(context, folderUri) ?: 
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Folder not found")
            
            val decodedPath = if (path.isNotEmpty()) java.net.URLDecoder.decode(path, "UTF-8") else ""
            val targetDir = if (decodedPath.isEmpty()) docFile else navigateToPath(docFile, decodedPath)
            
            if (targetDir == null || !targetDir.isDirectory) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Directory not found")
            }
            
            val zipName = (targetDir.name ?: "files") + ".zip"
            
            // Use piped streams to stream ZIP on the fly
            val pipedIn = PipedInputStream()
            val pipedOut = PipedOutputStream(pipedIn)
            
            thread {
                try {
                    ZipOutputStream(pipedOut).use { zos ->
                        addFolderToZip(zos, targetDir, "")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating ZIP", e)
                } finally {
                    try { pipedOut.close() } catch (e: Exception) {}
                }
            }
            
            val response = newChunkedResponse(Response.Status.OK, "application/zip", pipedIn)
            response.addHeader("Content-Disposition", "attachment; filename=\"$zipName\"")
            return response
        }
        
        private fun addFolderToZip(zos: ZipOutputStream, folder: DocumentFile, basePath: String) {
            folder.listFiles().forEach { file ->
                val entryPath = if (basePath.isEmpty()) (file.name ?: "unknown") else "$basePath/${file.name ?: "unknown"}"
                
                if (file.isDirectory) {
                    addFolderToZip(zos, file, entryPath)
                } else {
                    try {
                        zos.putNextEntry(ZipEntry(entryPath))
                        context.contentResolver.openInputStream(file.uri)?.use { input ->
                            input.copyTo(zos)
                        }
                        zos.closeEntry()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error adding file to ZIP: $entryPath", e)
                    }
                }
            }
        }
    }
}
