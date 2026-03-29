package com.example.llamadroid.ui.models

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.data.model.PendingDownloadHolder
import com.example.llamadroid.service.DownloadService
import com.example.llamadroid.service.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import java.io.File

/**
 * Whisper Models management screen
 * Allows downloading/deleting WhisperCPP models
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhisperModelsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    
    // Downloaded models from database
    val downloadedModels by db.modelDao().getModelsByType(ModelType.WHISPER).collectAsState(initial = emptyList())
    val downloadedFilenames = downloadedModels.map { it.filename }.toSet()
    
    // Download progress from DownloadProgressHolder (survives tab switches)
    val downloadProgressMap by DownloadProgressHolder.progress.collectAsState()
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // Models directory
    val modelsDir = remember { File(context.filesDir, "whisper_models").apply { mkdirs() } }
    
    // Helper to start download via service
    fun startModelDownload(model: WhisperModel) {
        val destPath = File(modelsDir, model.filename).absolutePath
        val repoId = "ggerganov/whisper.cpp"
        // Register pending download for DB tracking
        PendingDownloadHolder.addPending(model.filename, repoId, ModelType.WHISPER, destPath)
        // Track progress
        DownloadProgressHolder.updateProgress("whisper_${model.filename}", model.filename, 0f)
        // Start service
        DownloadService.startDownload(context, model.downloadUrl, destPath, model.filename)
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.whisper_models_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.kiwix_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.whisper_models_title), fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.whisper_models_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Error message
            errorMessage?.let {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(modifier = Modifier.padding(12.dp)) {
                        Text(it, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            // Model categories
            WhisperModelCategory(
                title = "🚀 Tiny (Fast, ~75MB)",
                models = listOf(WhisperModel.TINY, WhisperModel.TINY_EN, WhisperModel.TINY_Q5_1, WhisperModel.TINY_EN_Q5_1),
                downloadedFilenames = downloadedFilenames,
                downloadProgressMap = downloadProgressMap,
                onDownload = { model -> startModelDownload(model) },
                onDelete = { model ->
                    scope.launch {
                        deleteModel(model, modelsDir, db)
                    }
                }
            )
            
            WhisperModelCategory(
                title = "⚡ Base (~142MB)",
                models = listOf(WhisperModel.BASE, WhisperModel.BASE_EN, WhisperModel.BASE_Q5_1, WhisperModel.BASE_EN_Q5_1),
                downloadedFilenames = downloadedFilenames,
                downloadProgressMap = downloadProgressMap,
                onDownload = { model -> startModelDownload(model) },
                onDelete = { model -> scope.launch { deleteModel(model, modelsDir, db) } }
            )
            
            WhisperModelCategory(
                title = "🎯 Small (~466MB)",
                models = listOf(WhisperModel.SMALL, WhisperModel.SMALL_EN, WhisperModel.SMALL_Q5_1, WhisperModel.SMALL_EN_Q5_1),
                downloadedFilenames = downloadedFilenames,
                downloadProgressMap = downloadProgressMap,
                onDownload = { model -> startModelDownload(model) },
                onDelete = { model -> scope.launch { deleteModel(model, modelsDir, db) } }
            )
            
            WhisperModelCategory(
                title = "💪 Medium (~1.5GB)",
                models = listOf(WhisperModel.MEDIUM, WhisperModel.MEDIUM_EN, WhisperModel.MEDIUM_Q5_0, WhisperModel.MEDIUM_EN_Q5_0),
                downloadedFilenames = downloadedFilenames,
                downloadProgressMap = downloadProgressMap,
                onDownload = { model -> startModelDownload(model) },
                onDelete = { model -> scope.launch { deleteModel(model, modelsDir, db) } }
            )
            
            WhisperModelCategory(
                title = "🏆 Large (~3GB)",
                models = listOf(WhisperModel.LARGE_V3, WhisperModel.LARGE_V3_TURBO, WhisperModel.LARGE_V3_Q5_0, WhisperModel.LARGE_V3_TURBO_Q5_0),
                downloadedFilenames = downloadedFilenames,
                downloadProgressMap = downloadProgressMap,
                onDownload = { model -> startModelDownload(model) },
                onDelete = { model -> scope.launch { deleteModel(model, modelsDir, db) } }
            )
        }
    }
}

@Composable
private fun WhisperModelCategory(
    title: String,
    models: List<WhisperModel>,
    downloadedFilenames: Set<String>,
    downloadProgressMap: Map<String, Float>,
    onDownload: (WhisperModel) -> Unit,
    onDelete: (WhisperModel) -> Unit
) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(8.dp))
    
    models.forEach { model ->
        val isDownloaded = model.filename in downloadedFilenames
        // Check if any progress entry contains this filename
        val progressEntry = downloadProgressMap.entries.find { it.key.contains(model.filename) }
        val isDownloading = progressEntry != null && progressEntry.value in 0f..0.99f
        val downloadProgress = progressEntry?.value ?: 0f
        
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(model.displayName, fontWeight = FontWeight.Medium)
                        if (model.isEnglishOnly) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Badge(containerColor = MaterialTheme.colorScheme.secondary) { 
                                Text(stringResource(R.string.whisper_lang_en), style = MaterialTheme.typography.labelSmall) 
                            }
                        }
                        if (model.isQuantized) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Badge(containerColor = MaterialTheme.colorScheme.tertiary) { 
                                Text(stringResource(R.string.whisper_quant_q), style = MaterialTheme.typography.labelSmall) 
                            }
                        }
                    }
                    Text(
                        model.sizeDisplay,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                when {
                    isDownloading -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 3.dp
                            )
                            Text(
                                "${(downloadProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    isDownloaded -> {
                        Row {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = stringResource(R.string.desc_downloaded),
                                tint = Color(0xFF4CAF50)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(onClick = { onDelete(model) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.desc_delete),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                    else -> {
                        IconButton(onClick = { onDownload(model) }) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.desc_download))
                        }
                    }
                }
            }
        }
    }
    
    Spacer(modifier = Modifier.height(16.dp))
}



private suspend fun deleteModel(
    model: WhisperModel,
    modelsDir: File,
    db: AppDatabase
) = withContext(Dispatchers.IO) {
    val file = File(modelsDir, model.filename)
    file.delete()
    db.modelDao().deleteByFilename(model.filename)
}
