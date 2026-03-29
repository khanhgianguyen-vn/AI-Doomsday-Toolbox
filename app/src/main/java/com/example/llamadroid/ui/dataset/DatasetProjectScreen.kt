package com.example.llamadroid.ui.dataset

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.llamadroid.data.db.*
import com.example.llamadroid.data.model.DatasetExporter
import com.example.llamadroid.data.model.DatasetFormat
import com.example.llamadroid.service.DatasetProcessor
import com.example.llamadroid.service.PDFService
import com.example.llamadroid.ui.components.SliderWithInput
import com.example.llamadroid.ui.components.IntSliderWithInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ViewModel to preserve state across navigation
 */
class DatasetProjectViewModel : ViewModel() {
    private val _selectedTab = MutableStateFlow(0)
    val selectedTab = _selectedTab.asStateFlow()
    
    fun setTab(index: Int) {
        _selectedTab.value = index
    }
    
    // Processing state preservation
    var processor: DatasetProcessor? = null
}

// Tab data class for icons
data class TabItem(val icon: ImageVector, val label: String)

/**
 * Dataset Project Detail Screen with 5 tabs (icon-based for better fit)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatasetProjectScreen(
    navController: NavController,
    projectId: Long
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val dao = db.datasetDao()
    val pdfService = remember { PDFService(context) }
    
    // ViewModel for state persistence
    val viewModel: DatasetProjectViewModel = viewModel()
    val processor = remember { 
        viewModel.processor ?: DatasetProcessor(context, dao).also { viewModel.processor = it }
    }
    
    // State from ViewModel (survives navigation)
    val selectedTab by viewModel.selectedTab.collectAsState()
    
    // State from database - using Flow for project so settings changes auto-refresh
    val project by dao.getProjectFlow(projectId).collectAsState(initial = null)
    val sources by dao.getSourcesForProject(projectId).collectAsState(initial = emptyList())
    val chunks by dao.getChunksForProject(projectId).collectAsState(initial = emptyList())
    val qaList by dao.getQAForProject(projectId).collectAsState(initial = emptyList())
    val prompts by dao.getAllPrompts().collectAsState(initial = emptyList())
    
    // Get available LLM models (downloaded only)
    val models by db.modelDao().getModelsByType(ModelType.LLM).collectAsState(initial = emptyList())
    
    
    // Processing state from static companion object (survives navigation)
    val processingProgress by DatasetProcessor.progress.collectAsState()
    val isProcessing by DatasetProcessor.isProcessing.collectAsState()
    val jobQueue by DatasetProcessor.jobQueue.collectAsState()
    
    // Set context and processor for global notifications and job queue
    LaunchedEffect(Unit) {
        DatasetProcessor.setApplicationContext(context)
        DatasetProcessor.setProcessor(processor)
    }
    
    // Icon-based tabs (no text cutting)
    val tabs = listOf(
        TabItem(Icons.Default.Home, stringResource(R.string.dataset_tab_sources)),
        TabItem(Icons.Default.Menu, stringResource(R.string.dataset_tab_chunks)),
        TabItem(Icons.Default.PlayArrow, stringResource(R.string.dataset_tab_process)),
        TabItem(Icons.Default.Star, stringResource(R.string.dataset_tab_review)),
        TabItem(Icons.Default.List, stringResource(R.string.dataset_tab_queue)),
        TabItem(Icons.Default.Settings, stringResource(R.string.dataset_tab_settings))
    )
    
    // File pickers
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { pdfUri ->
            scope.launch {
                val name = uri.lastPathSegment?.substringAfterLast("/") ?: context.getString(R.string.file_type_pdf)
                val textResult = pdfService.extractText(pdfUri)
                textResult.onSuccess { text ->
                    val sourceId = dao.insertSource(DatasetSourceEntity(
                        projectId = projectId,
                        type = SourceType.PDF,
                        uri = pdfUri.toString(),
                        name = name,
                        extractedText = text
                    ))
                    val proj = project ?: return@launch
                    val chunkTexts = processor.chunkText(text, proj.chunkSize)
                    val chunkEntities = chunkTexts.mapIndexed { index, chunkText ->
                        DatasetChunkEntity(
                            projectId = projectId,
                            sourceId = sourceId,
                            chunkIndex = index,
                            originalText = chunkText
                        )
                    }
                    dao.insertChunks(chunkEntities)
                }
            }
        }
    }
    
    val txtPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { txtUri ->
            scope.launch {
                val name = uri.lastPathSegment?.substringAfterLast("/") ?: context.getString(R.string.file_type_text)
                context.contentResolver.openInputStream(txtUri)?.use { stream ->
                    val text = stream.bufferedReader().readText()
                    val sourceId = dao.insertSource(DatasetSourceEntity(
                        projectId = projectId,
                        type = SourceType.TXT,
                        uri = txtUri.toString(),
                        name = name,
                        extractedText = text
                    ))
                    val proj = project ?: return@launch
                    val chunkTexts = processor.chunkText(text, proj.chunkSize)
                    val chunkEntities = chunkTexts.mapIndexed { index, chunkText ->
                        DatasetChunkEntity(
                            projectId = projectId,
                            sourceId = sourceId,
                            chunkIndex = index,
                            originalText = chunkText
                        )
                    }
                    dao.insertChunks(chunkEntities)
                }
            }
        }
    }
    
    // State for selected IDs during export
    var selectedIdsForExport by remember { mutableStateOf(setOf<Long>()) }
    
    // Export launcher  
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { exportUri ->
            scope.launch {
                exportQAToFile(context, exportUri, qaList, chunks, selectedIdsForExport)
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project?.name ?: stringResource(R.string.dataset_tab_sources), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
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
            // Scrollable Tab Row with icons and short labels
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { viewModel.setTab(index) },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        text = { Text(tab.label, fontSize = 11.sp, maxLines = 1) }
                    )
                }
            }
            
            // Tab Content
            when (selectedTab) {
                0 -> SourcesTab(
                    sources = sources,
                    chunks = chunks,
                    onAddPdf = { pdfPicker.launch(arrayOf("application/pdf")) },
                    onAddTxt = { txtPicker.launch(arrayOf("text/*")) },
                    onDeleteSource = { source ->
                        scope.launch {
                            dao.deleteChunksForSource(source.id)
                            dao.deleteSource(source)
                        }
                    }
                )
                1 -> ChunksTab(
                    chunks = chunks,
                    sources = sources,
                    onUpdateChunk = { chunk ->
                        scope.launch { dao.updateChunk(chunk) }
                    },
                    onRegenQuestions = { chunkIds ->
                        project?.let { proj ->
                            val questionPrompt = prompts.find { it.type == PromptType.QUESTION && it.isDefault }?.content ?: DEFAULT_QUESTION_PROMPT
                            DatasetProcessor.queueJob(DatasetProcessor.Job.RegenQuestions(chunkIds, proj.id, questionPrompt, context.getString(R.string.dataset_regen_questions)))
                        }
                    },
                    isProcessing = isProcessing
                )
                2 -> ProcessTab(
                    project = project,
                    chunks = chunks,
                    qaList = qaList,
                    prompts = prompts,
                    progress = processingProgress,
                    isProcessing = isProcessing,
                    onRunClean = { prompt ->
                        project?.let { proj ->
                            DatasetProcessor.queueJob(DatasetProcessor.Job.Clean(proj.id, prompt, context.getString(R.string.dataset_job_clean)))
                        }
                    },
                    onRunQuestions = { prompt ->
                        project?.let { proj ->
                            DatasetProcessor.queueJob(DatasetProcessor.Job.Questions(proj.id, prompt, context.getString(R.string.dataset_job_questions)))
                        }
                    },
                    onRunAnswers = { prompt ->
                        project?.let { proj ->
                            DatasetProcessor.queueJob(DatasetProcessor.Job.Answers(proj.id, prompt, context.getString(R.string.dataset_job_answers)))
                        }
                    },
                    onRunRating = { prompt ->
                        project?.let { proj ->
                            DatasetProcessor.queueJob(DatasetProcessor.Job.Rating(proj.id, prompt, context.getString(R.string.dataset_job_rating)))
                        }
                    },
                    onCancel = { DatasetProcessor.cancel() }
                )
                3 -> ReviewTab(
                    qaList = qaList,
                    chunks = chunks,
                    onUpdateQA = { qa ->
                        scope.launch { dao.updateQA(qa) }
                    },
                    onDeleteQA = { qa ->
                        scope.launch { dao.deleteQA(qa) }
                    },
                    onDeleteSelected = { selectedIds ->
                        scope.launch {
                            selectedIds.forEach { id ->
                                qaList.find { it.id == id }?.let { dao.deleteQA(it) }
                            }
                        }
                    },
                    onRegenerateAnswer = { qa ->
                        project?.let { proj ->
                            val answerPrompt = prompts.find { it.type == PromptType.ANSWER && it.isDefault }?.content ?: DEFAULT_ANSWER_PROMPT
                            DatasetProcessor.queueJob(DatasetProcessor.Job.RegenAnswer(qa.id, proj.id, answerPrompt, context.getString(R.string.dataset_job_regen_answer)))
                        }
                    },
                    onRegenerateRating = { qa ->
                        project?.let { proj ->
                            val reviewPrompt = prompts.find { it.type == PromptType.REVIEW && it.isDefault }?.content ?: DEFAULT_REVIEW_PROMPT
                            DatasetProcessor.queueJob(DatasetProcessor.Job.RegenRating(qa.id, proj.id, reviewPrompt, context.getString(R.string.dataset_job_regen_rating)))
                        }
                    },
                    onRegenerateSelectedAnswers = { selectedIds ->
                        project?.let { proj ->
                            val answerPrompt = prompts.find { it.type == PromptType.ANSWER && it.isDefault }?.content ?: DEFAULT_ANSWER_PROMPT
                            DatasetProcessor.queueJob(DatasetProcessor.Job.RegenAnswers(selectedIds, proj.id, answerPrompt, context.getString(R.string.dataset_job_regen_answers_param, selectedIds.size)))
                        }
                    },
                    onRegenerateSelectedRatings = { selectedIds ->
                        project?.let { proj ->
                            val reviewPrompt = prompts.find { it.type == PromptType.REVIEW && it.isDefault }?.content ?: DEFAULT_REVIEW_PROMPT
                            DatasetProcessor.queueJob(DatasetProcessor.Job.RegenRatings(selectedIds, proj.id, reviewPrompt, context.getString(R.string.dataset_job_regen_ratings_param, selectedIds.size)))
                        }
                    },
                    onExport = { selectedIds ->
                        selectedIdsForExport = selectedIds
                        val filename = "dataset_${System.currentTimeMillis()}.json"
                        exportLauncher.launch(filename)
                    },
                    isProcessing = isProcessing,
                    progress = processingProgress,
                    jobQueue = jobQueue,
                    onCancel = { DatasetProcessor.cancel() },
                    onCancelAll = { DatasetProcessor.cancelAll() }
                )
                4 -> QueueTab(
                    jobQueue = jobQueue,
                    isProcessing = isProcessing,
                    progress = processingProgress,
                    onCancel = { DatasetProcessor.cancel() },
                    onCancelAll = { DatasetProcessor.cancelAll() },
                    onRemoveJob = { index -> DatasetProcessor.removeJob(index) }
                )
                5 -> SettingsTab(
                    project = project,
                    prompts = prompts,
                    models = models,
                    dao = dao,
                    onUpdateProject = { updated ->
                        scope.launch {
                            dao.updateProject(updated)
                            // Flow will auto-refresh project
                        }
                    },
                    onSavePrompt = { prompt ->
                        scope.launch { dao.insertPrompt(prompt) }
                    },
                    onUpdatePrompt = { prompt ->
                        scope.launch { dao.updatePrompt(prompt) }
                    },
                    onDeletePrompt = { prompt ->
                        scope.launch { dao.deletePrompt(prompt) }
                    }
                )
            }
        }
    }
}

// Notification helpers
private const val CHANNEL_ID = "dataset_progress"
private const val NOTIFICATION_ID = 9001

private fun showProgressNotification(context: Context, progress: DatasetProcessor.Progress) {
    // Create channel (required for Android 8+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.dataset_processing_notif),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
    
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_sort_by_size)
        .setContentTitle("${progress.stage}: ${progress.current}/${progress.total}")
        .setContentText(progress.currentItem)
        .setProgress(progress.total, progress.current, false)
        .setOngoing(true)
        .setSilent(true)
        .build()
    
    try {
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    } catch (_: SecurityException) {
        // Permission not granted
    }
}

private fun cancelProgressNotification(context: Context) {
    NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
}

// Export helper
private suspend fun exportQAToFile(
    context: Context,
    uri: Uri,
    qaList: List<DatasetQAEntity>,
    chunks: List<DatasetChunkEntity>,
    selectedIds: Set<Long> = emptySet()
) {
    try {
        com.example.llamadroid.util.DebugLog.log("[Export] Starting export, selected=${selectedIds.size}, total=${qaList.size}")
        
        // If specific items selected, export those; otherwise export all with score >= 3
        val toExport = if (selectedIds.isNotEmpty()) {
            qaList.filter { it.id in selectedIds && it.answer != null }
        } else {
            qaList.filter { (it.score ?: 0) >= 3 && it.answer != null }
        }
        
        com.example.llamadroid.util.DebugLog.log("[Export] Items to export: ${toExport.size}")
        
        val entries = toExport.map { qa ->
            com.example.llamadroid.data.model.DatasetEntry(
                source = com.example.llamadroid.data.model.DatasetSource.MANUAL,
                sourceName = "Generated",
                instruction = qa.question,
                input = "",
                output = qa.answer ?: ""
            )
        }
        
        val output = DatasetExporter.toAlpaca(entries)
        com.example.llamadroid.util.DebugLog.log("[Export] JSON size: ${output.length} chars")
        
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(output.toByteArray())
            }
        }
        
        com.example.llamadroid.util.DebugLog.log("[Export] Export completed successfully")
        android.widget.Toast.makeText(context, context.getString(R.string.dataset_export_success, toExport.size), android.widget.Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        com.example.llamadroid.util.DebugLog.log("[Export] ERROR: ${e.message}")
        e.printStackTrace()
        android.widget.Toast.makeText(context, context.getString(R.string.dataset_export_failed, e.message), android.widget.Toast.LENGTH_LONG).show()
    }
}

// ========== SOURCES TAB ==========
@Composable
fun SourcesTab(
    sources: List<DatasetSourceEntity>,
    chunks: List<DatasetChunkEntity>,
    onAddPdf: () -> Unit,
    onAddTxt: () -> Unit,
    onDeleteSource: (DatasetSourceEntity) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Stats row
        item {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row {
                        Text("${sources.size} ", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(stringResource(R.string.dataset_sources_count), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row {
                        Text("${chunks.size} ", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(stringResource(R.string.dataset_chunks_count), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        
        // Add buttons row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(onClick = onAddPdf, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dataset_add_pdf))
                }
                FilledTonalButton(onClick = onAddTxt, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dataset_add_txt))
                }
            }
        }
        
        // Source items
        items(sources) { source ->
            val chunkCount = chunks.count { it.sourceId == source.id }
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Text(
                            when (source.type) {
                                SourceType.PDF -> "📄"
                                SourceType.TXT -> "📝"
                                SourceType.NOTE -> "🗒️"
                            },
                            fontSize = 20.sp
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(source.name, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                             Text(stringResource(R.string.dataset_chunks_count_param, chunkCount), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = { onDeleteSource(source) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, stringResource(R.string.action_remove), modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        
        // Empty state
        if (sources.isEmpty()) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("📂", fontSize = 40.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.dataset_no_sources), fontWeight = FontWeight.Medium, fontSize = 16.sp)
                        Text(stringResource(R.string.dataset_add_sources_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ========== CHUNKS TAB ==========
@Composable
fun ChunksTab(
    chunks: List<DatasetChunkEntity>,
    sources: List<DatasetSourceEntity>,
    onUpdateChunk: (DatasetChunkEntity) -> Unit,
    onRegenQuestions: (Set<Long>) -> Unit = {},
    isProcessing: Boolean = false
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var editingChunk by remember { mutableStateOf<DatasetChunkEntity?>(null) }
    var selectedChunkIds by remember { mutableStateOf(setOf<Long>()) }
    
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Compact action bar
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(
                        onClick = { selectedChunkIds = if (selectedChunkIds.size == chunks.size) emptySet() else chunks.map { it.id }.toSet() }
                    ) {
                        Text(if (selectedChunkIds.isEmpty()) stringResource(R.string.dataset_select_all) else stringResource(R.string.dataset_selected, selectedChunkIds.size), fontSize = 13.sp)
                    }
                    if (selectedChunkIds.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { selectedChunkIds = emptySet() }) {
                            Text(stringResource(R.string.action_clear), fontSize = 12.sp)
                        }
                    }
                }
                
                Button(
                    onClick = { if (selectedChunkIds.isNotEmpty()) onRegenQuestions(selectedChunkIds) },
                    enabled = selectedChunkIds.isNotEmpty()
                ) {
                    Text(stringResource(R.string.dataset_regen_questions), fontSize = 13.sp)
                }
            }
        }
        
        // Chunk items
        items(chunks) { chunk ->
            val source = sources.find { it.id == chunk.sourceId }
            val isSelected = chunk.id in selectedChunkIds
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface,
                tonalElevation = if (isSelected) 0.dp else 1.dp,
                modifier = Modifier.fillMaxWidth().clickable { 
                    selectedChunkIds = if (isSelected) selectedChunkIds - chunk.id else selectedChunkIds + chunk.id 
                }
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { selectedChunkIds = if (it) selectedChunkIds + chunk.id else selectedChunkIds - chunk.id },
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("${source?.name?.take(15) ?: "?"} #${chunk.chunkIndex}", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Text(if (chunk.status == ChunkStatus.CLEANED) "✓" else "○", fontSize = 12.sp, color = if (chunk.status == ChunkStatus.CLEANED) Color(0xFF4CAF50) else Color.Gray)
                        }
                        // Original text
                        Text(stringResource(R.string.dataset_original_label), fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
                        Text(
                            chunk.originalText.take(80) + if (chunk.originalText.length > 80) "…" else "",
                            fontSize = 11.sp, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 14.sp
                        )
                        // Cleaned text (if available)
                        if (chunk.cleanedText != null) {
                            Text(stringResource(R.string.dataset_cleaned_label), fontSize = 10.sp, color = Color(0xFF4CAF50), modifier = Modifier.padding(top = 4.dp))
                            Text(
                                chunk.cleanedText.take(80) + if (chunk.cleanedText.length > 80) "…" else "",
                                fontSize = 11.sp, maxLines = 2, color = MaterialTheme.colorScheme.onSurface,
                                lineHeight = 14.sp
                            )
                        }
                    }
                    IconButton(onClick = { editingChunk = chunk }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Edit, stringResource(R.string.action_edit), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    
    editingChunk?.let { chunk ->
        var originalText by remember { mutableStateOf(chunk.originalText) }
        var cleanedText by remember { mutableStateOf(chunk.cleanedText ?: "") }
        
        AlertDialog(
            onDismissRequest = { editingChunk = null },
            title = { 
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.dataset_edit_chunk, chunk.chunkIndex))
                    // Regen cleaning button
                    TextButton(
                        onClick = {
                            // Queue regen clean for this chunk
                            DatasetProcessor.queueJob(DatasetProcessor.Job.RegenClean(chunk.id, chunk.projectId, "", context.getString(R.string.dataset_job_regen_clean)))
                            editingChunk = null
                        }
                    ) {
                        Text(stringResource(R.string.dataset_regen_clean_btn), fontSize = 12.sp)
                    }
                }
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    // Original text
                    Text(stringResource(R.string.dataset_context_original), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.Gray)
                    OutlinedTextField(
                        value = originalText,
                        onValueChange = { originalText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        maxLines = 8,
                        textStyle = TextStyle(fontSize = 12.sp)
                    )
                    
                    Spacer(Modifier.height(12.dp))
                    
                    // Cleaned text
                    Text(stringResource(R.string.dataset_cleaned_label), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF4CAF50))
                    OutlinedTextField(
                        value = cleanedText,
                        onValueChange = { cleanedText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        maxLines = 8,
                        textStyle = TextStyle(fontSize = 12.sp),
                        placeholder = { Text(stringResource(R.string.dataset_not_cleaned), fontSize = 12.sp) }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val updated = chunk.copy(
                        originalText = originalText,
                        cleanedText = if (cleanedText.isNotBlank()) cleanedText else null
                    )
                    onUpdateChunk(updated)
                    editingChunk = null
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editingChunk = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

// ========== PROCESS TAB ==========
@Composable
fun ProcessTab(
    project: DatasetProjectEntity?,
    chunks: List<DatasetChunkEntity>,
    qaList: List<DatasetQAEntity>,
    prompts: List<DatasetPromptEntity>,
    progress: DatasetProcessor.Progress?,
    isProcessing: Boolean,
    onRunClean: (String) -> Unit,
    onRunQuestions: (String) -> Unit,
    onRunAnswers: (String) -> Unit,
    onRunRating: (String) -> Unit,
    onCancel: () -> Unit
) {
    val pendingChunks = chunks.count { it.status == ChunkStatus.PENDING }
    val cleanedChunks = chunks.count { it.status == ChunkStatus.CLEANED }
    val questionsGenerated = qaList.count { it.status >= QAStatus.QUESTIONED }
    val answersGenerated = qaList.count { it.status >= QAStatus.ANSWERED }
    val rated = qaList.count { it.status == QAStatus.REVIEWED }
    
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isProcessing && progress != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("${progress.stage}: ${progress.current}/${progress.total}", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress.current.toFloat() / progress.total.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(progress.currentItem, fontSize = 12.sp, color = Color.Gray, maxLines = 1)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                }
            }
        }
        
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.dataset_pipeline_status), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$pendingChunks", fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.dataset_status_pending), fontSize = 10.sp)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$cleanedChunks", fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.dataset_status_cleaned), fontSize = 10.sp)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$questionsGenerated", fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.dataset_status_questions), fontSize = 10.sp)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$answersGenerated", fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.dataset_status_answers), fontSize = 10.sp)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$rated", fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.dataset_status_rated), fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        
        item {
            val cleanPrompt = prompts.find { it.type == PromptType.CLEAN && it.isDefault }?.content ?: DEFAULT_CLEAN_PROMPT
            val questionPrompt = prompts.find { it.type == PromptType.QUESTION && it.isDefault }?.content ?: DEFAULT_QUESTION_PROMPT
            val answerPrompt = prompts.find { it.type == PromptType.ANSWER && it.isDefault }?.content ?: DEFAULT_ANSWER_PROMPT
            val reviewPrompt = prompts.find { it.type == PromptType.REVIEW && it.isDefault }?.content ?: DEFAULT_REVIEW_PROMPT
            
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onRunClean(cleanPrompt) }, enabled = pendingChunks > 0 && !isProcessing, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.dataset_run_clean, pendingChunks))
                }
                Button(onClick = { onRunQuestions(questionPrompt) }, enabled = cleanedChunks > 0 && !isProcessing, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.dataset_run_questions, cleanedChunks))
                }
                Button(onClick = { onRunAnswers(answerPrompt) }, enabled = questionsGenerated > answersGenerated && !isProcessing, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.dataset_run_answers, questionsGenerated - answersGenerated))
                }
                Button(onClick = { onRunRating(reviewPrompt) }, enabled = answersGenerated > rated && !isProcessing, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.dataset_run_rating, answersGenerated - rated))
                }
            }
        }
    }
}

// ========== REVIEW TAB ==========
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewTab(
    qaList: List<DatasetQAEntity>,
    chunks: List<DatasetChunkEntity>,
    onUpdateQA: (DatasetQAEntity) -> Unit,
    onDeleteQA: (DatasetQAEntity) -> Unit,
    onDeleteSelected: (Set<Long>) -> Unit,
    onRegenerateAnswer: (DatasetQAEntity) -> Unit,
    onRegenerateRating: (DatasetQAEntity) -> Unit,
    onRegenerateSelectedAnswers: (Set<Long>) -> Unit,
    onRegenerateSelectedRatings: (Set<Long>) -> Unit,
    onExport: (Set<Long>) -> Unit,
    isProcessing: Boolean = false,
    progress: DatasetProcessor.Progress? = null,
    jobQueue: List<DatasetProcessor.Job> = emptyList(),
    onCancel: () -> Unit = {},
    onCancelAll: () -> Unit = {}
) {
    var filter by remember { mutableStateOf("all") }
    var editingQA by remember { mutableStateOf<DatasetQAEntity?>(null) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showJustification by remember { mutableStateOf<DatasetQAEntity?>(null) }
    var showContext by remember { mutableStateOf<DatasetQAEntity?>(null) }
    var filterDropdownExpanded by remember { mutableStateOf(false) }
    var actionsExpanded by remember { mutableStateOf(false) }
    
    val filterOptions = listOf("all", "answered", "unrated", "1", "2", "3", "4", "5", ">3")
    
    val filteredList = when {
        filter == "all" -> qaList
        filter == "answered" -> qaList.filter { it.answer != null }
        filter == "unrated" -> qaList.filter { it.score == null }
        filter.startsWith(">") -> {
            val threshold = filter.drop(1).toIntOrNull() ?: 0
            qaList.filter { (it.score ?: 0) > threshold }
        }
        else -> {
            val exact = filter.toIntOrNull() ?: 0
            qaList.filter { it.score == exact }
        }
    }
    
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Progress card (compact)
        if (isProcessing && progress != null) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${progress.stage}: ${progress.current}/${progress.total}", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            TextButton(onClick = onCancel, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                Text(stringResource(R.string.action_stop), fontSize = 12.sp)
                            }
                        }
                        LinearProgressIndicator(
                            progress = { progress.current.toFloat() / progress.total.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
        
        // Job queue (compact)
        if (jobQueue.isNotEmpty()) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.dataset_tab_queue) + ": ", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text(jobQueue.take(3).joinToString(" → ") { it.name }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
                        if (jobQueue.size > 3) Text("+${jobQueue.size - 3}", fontSize = 11.sp, color = Color.Gray)
                        TextButton(onClick = onCancelAll, modifier = Modifier.height(28.dp)) { Text(stringResource(R.string.action_clear), fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        
        // Combined filter + actions bar
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Filter dropdown (compact)
                ExposedDropdownMenuBox(
                    expanded = filterDropdownExpanded,
                    onExpandedChange = { filterDropdownExpanded = it },
                    modifier = Modifier.width(100.dp)
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(if (filter == "all") stringResource(R.string.dataset_filter_all) else if (filter == "unrated") "○" else filter, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(18.dp))
                        }
                    }
                    ExposedDropdownMenu(expanded = filterDropdownExpanded, onDismissRequest = { filterDropdownExpanded = false }) {
                        filterOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(when (option) { "all" -> stringResource(R.string.dataset_filter_all); "unrated" -> stringResource(R.string.dataset_filter_unrated); else -> stringResource(R.string.dataset_filter_score, option) }, fontSize = 13.sp) },
                                onClick = { filter = option; filterDropdownExpanded = false; selectedIds = emptySet() }
                            )
                        }
                    }
                }
                
                // Selection badge
                Surface(shape = MaterialTheme.shapes.small, color = if (selectedIds.isNotEmpty()) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent) {
                    Text(
                        if (selectedIds.isEmpty()) "${filteredList.size}" else "✓${selectedIds.size}",
                        fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = if (selectedIds.isNotEmpty()) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                }
                
                Spacer(Modifier.weight(1f))
                
                // Select all icon
                IconButton(onClick = { selectedIds = if (selectedIds.size == filteredList.size) emptySet() else filteredList.map { it.id }.toSet() }, modifier = Modifier.size(32.dp)) {
                    Icon(if (selectedIds.size == filteredList.size && filteredList.isNotEmpty()) Icons.Default.Check else Icons.Default.Add, stringResource(R.string.action_select), modifier = Modifier.size(20.dp))
                }
                
                // Export button (icon only)
                IconButton(
                    onClick = { onExport(selectedIds) },
                    enabled = selectedIds.isNotEmpty() || filteredList.any { (it.score ?: 0) >= 3 },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Share, stringResource(R.string.action_export), modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
                
                // Actions dropdown
                Box {
                    FilledTonalButton(onClick = { actionsExpanded = true }, enabled = selectedIds.isNotEmpty()) {
                        Text(stringResource(R.string.dataset_actions), fontSize = 12.sp)
                    }
                    DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.dataset_action_regen_answers)) }, onClick = { actionsExpanded = false; onRegenerateSelectedAnswers(selectedIds) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.dataset_action_regen_ratings)) }, onClick = { actionsExpanded = false; onRegenerateSelectedRatings(selectedIds) })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }, onClick = { actionsExpanded = false; onDeleteSelected(selectedIds); selectedIds = emptySet() })
                    }
                }
            }
        }
        
        // Q&A items
        items(filteredList) { qa ->
            val isSelected = qa.id in selectedIds
            
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth().clickable { selectedIds = if (isSelected) selectedIds - qa.id else selectedIds + qa.id }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Question row
                    Row(verticalAlignment = Alignment.Top) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { selectedIds = if (it) selectedIds + qa.id else selectedIds - qa.id },
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(qa.question, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.weight(1f), lineHeight = 16.sp)
                        // Score badge
                        Surface(shape = MaterialTheme.shapes.small, color = when(qa.score) { 5 -> Color(0xFF4CAF50); 4 -> Color(0xFF8BC34A); 3 -> Color(0xFFFFC107); 2 -> Color(0xFFFF9800); 1 -> Color(0xFFF44336); else -> Color.Gray }.copy(alpha = 0.2f)) {
                            Text("${qa.score ?: "?"}", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                    
                    // Answer
                    if (qa.answer != null) {
                        Text(qa.answer.take(150) + if (qa.answer.length > 150) "…" else "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 28.dp, top = 4.dp), lineHeight = 15.sp, maxLines = 3)
                    }
                    
                    // Actions row (compact)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Context view button (shows text sent to LLM)
                         IconButton(onClick = { showContext = qa }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Info, stringResource(R.string.dataset_context_sent), modifier = Modifier.size(16.dp), tint = Color.Gray)
                        }
                        if (qa.scoreJustification != null) {
                            IconButton(onClick = { showJustification = qa }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Star, stringResource(R.string.dataset_score_justification), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        IconButton(onClick = { onRegenerateAnswer(qa) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Refresh, stringResource(R.string.dataset_job_regen_answer), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                        }
                        if (qa.answer != null) {
                            IconButton(onClick = { onRegenerateRating(qa) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Star, stringResource(R.string.dataset_job_regen_rating), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                        IconButton(onClick = { editingQA = qa }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Edit, stringResource(R.string.action_edit), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onDeleteQA(qa) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, stringResource(R.string.action_delete), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
    
    // Edit dialog
    editingQA?.let { qa ->
        var question by remember { mutableStateOf(qa.question) }
        var answer by remember { mutableStateOf(qa.answer ?: "") }
        
        AlertDialog(
            onDismissRequest = { editingQA = null },
            title = { Text(stringResource(R.string.dataset_edit_qa)) },
            text = {
                Column {
                    OutlinedTextField(value = question, onValueChange = { question = it }, label = { Text(stringResource(R.string.dataset_qa_question)) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = answer, onValueChange = { answer = it }, label = { Text(stringResource(R.string.dataset_qa_answer)) }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdateQA(qa.copy(question = question, answer = answer))
                    editingQA = null
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editingQA = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
    
    // Justification dialog
    showJustification?.let { qa ->
        AlertDialog(
            onDismissRequest = { showJustification = null },
            title = { Text(stringResource(R.string.dataset_score_justification)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.dataset_status_rated) + ": ${qa.score}/5", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(qa.scoreJustification ?: stringResource(R.string.dataset_no_justification), fontSize = 14.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { showJustification = null }) { Text(stringResource(R.string.action_close)) }
            }
        )
    }
    
    // Context dialog - shows the text sent to LLM
    showContext?.let { qa ->
        val chunk = chunks.find { it.id == qa.chunkId }
        AlertDialog(
            onDismissRequest = { showContext = null },
            title = { Text(stringResource(R.string.dataset_context_sent)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("📝 " + stringResource(R.string.dataset_qa_question) + ":", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(qa.question, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                    
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    
                    Text(stringResource(R.string.dataset_context_raw), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF4CAF50))
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Text(
                            chunk?.cleanedText ?: chunk?.originalText ?: stringResource(R.string.dataset_no_context),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    
                    if (chunk?.cleanedText != null && chunk.originalText != chunk.cleanedText) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.dataset_context_original), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.Gray)
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Text(
                                chunk.originalText,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(8.dp),
                                color = Color.Gray
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showContext = null }) { Text(stringResource(R.string.action_close)) }
            }
        )
    }
}

// ========== QUEUE TAB ==========
@Composable
fun QueueTab(
    jobQueue: List<DatasetProcessor.Job>,
    isProcessing: Boolean,
    progress: DatasetProcessor.Progress?,
    onCancel: () -> Unit,
    onCancelAll: () -> Unit,
    onRemoveJob: (Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Current job (if processing)
        if (isProcessing && progress != null) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.dataset_running), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            TextButton(onClick = onCancel, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                Text(stringResource(R.string.action_stop))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(progress.stage, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                        Text("${progress.current} / ${progress.total}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(
                            progress = { progress.current.toFloat() / progress.total.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                        Text(progress.currentItem, fontSize = 11.sp, color = Color.Gray, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        
        // Queue header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.dataset_pending_jobs, jobQueue.size), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (jobQueue.isNotEmpty()) {
                    TextButton(onClick = onCancelAll, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text(stringResource(R.string.dataset_clear_all))
                    }
                }
            }
        }
        
        // Empty state
        if (jobQueue.isEmpty() && !isProcessing) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("✓", fontSize = 40.sp, color = Color(0xFF4CAF50))
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.dataset_no_jobs), fontWeight = FontWeight.Medium, fontSize = 16.sp)
                        Text(stringResource(R.string.dataset_no_jobs_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        
        // Pending jobs list
        itemsIndexed(jobQueue) { index, job ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Index badge
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text("${index + 1}", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    
                    // Job name
                    Column(modifier = Modifier.weight(1f)) {
                        Text(job.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    }
                    
                    // Move up
                    IconButton(
                        onClick = { DatasetProcessor.moveJobUp(index) },
                        enabled = index > 0,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, "Move Up", modifier = Modifier.size(20.dp))
                    }
                    
                    // Move down
                    IconButton(
                        onClick = { DatasetProcessor.moveJobDown(index) },
                        enabled = index < jobQueue.size - 1,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, "Move Down", modifier = Modifier.size(20.dp))
                    }
                    
                    // Remove
                    IconButton(
                        onClick = { onRemoveJob(index) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Close, stringResource(R.string.action_remove), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

// ========== SETTINGS TAB ==========
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(
    project: DatasetProjectEntity?,
    prompts: List<DatasetPromptEntity>,
    models: List<com.example.llamadroid.data.db.ModelEntity>,
    dao: DatasetDao,
    onUpdateProject: (DatasetProjectEntity) -> Unit,
    onSavePrompt: (DatasetPromptEntity) -> Unit,
    onUpdatePrompt: (DatasetPromptEntity) -> Unit,
    onDeletePrompt: (DatasetPromptEntity) -> Unit
) {
    val scope = rememberCoroutineScope()
    
    // State - only per-request API settings (server is started from Dashboard)
    var serverUrl by remember(project) { mutableStateOf(project?.serverUrl ?: "http://127.0.0.1:8080") }
    var temperature by remember(project) { mutableStateOf(project?.temperature ?: 0.7f) }
    var maxTokens by remember(project) { mutableStateOf(project?.maxTokens ?: 512) }
    var useCoT by remember(project) { mutableStateOf(project?.useCoT ?: false) }
    
    // Prompt editor state
    var editingPromptType by remember { mutableStateOf<PromptType?>(null) }
    var editingPromptContent by remember { mutableStateOf("") }
    var projectName by remember(project) { mutableStateOf(project?.name ?: "") }
    
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        
        // Project name
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.dataset_project_name), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = projectName,
                        onValueChange = { projectName = it },
                        label = { Text(stringResource(R.string.dataset_name_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            if (projectName != project?.name) {
                                IconButton(onClick = {
                                    project?.let { onUpdateProject(it.copy(name = projectName)) }
                                }) {
                                    Icon(Icons.Default.Check, stringResource(R.string.action_save), tint = Color(0xFF4CAF50))
                                }
                            }
                        }
                    )
                }
            }
        }
        
        // Server settings
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.dataset_server_settings), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.dataset_server_desc), fontSize = 11.sp, color = Color.Gray)
                    Spacer(Modifier.height(12.dp))
                    
                    OutlinedTextField(
                        value = serverUrl, 
                        onValueChange = { serverUrl = it }, 
                        label = { Text(stringResource(R.string.dataset_server_url)) },
                        placeholder = { Text(stringResource(R.string.dataset_server_default_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Text(
                        "Default: http://127.0.0.1:8080 (or custom external URL)",
                        fontSize = 11.sp, 
                        color = Color.Gray
                    )
                    
                    Spacer(Modifier.height(12.dp))
                    
                    IntSliderWithInput(
                        value = maxTokens,
                        onValueChange = { maxTokens = it },
                        valueRange = 64..4096,
                        label = stringResource(R.string.dataset_max_tokens)
                    )
                    
                    SliderWithInput(
                        value = temperature,
                        onValueChange = { temperature = it },
                        valueRange = 0f..2f,
                        label = stringResource(R.string.action_temperature)
                    )
                    
                    Spacer(Modifier.height(8.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.dataset_cot_label))
                        Spacer(Modifier.weight(1f))
                        Switch(checked = useCoT, onCheckedChange = { useCoT = it })
                    }
                    
                    Spacer(Modifier.height(12.dp))
                    
                    Button(
                        onClick = {
                            project?.let {
                                onUpdateProject(it.copy(
                                    serverUrl = serverUrl,
                                    temperature = temperature,
                                    maxTokens = maxTokens,
                                    useCoT = useCoT
                                ))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_save_settings))
                    }
                }
            }
        }
        
        // Prompts Editor
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.dataset_prompts_title), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    
                    PromptType.entries.forEach { type ->
                        val savedPrompt = prompts.find { it.type == type && it.isDefault }
                        val defaultPrompt = when (type) {
                            PromptType.CLEAN -> DEFAULT_CLEAN_PROMPT
                            PromptType.QUESTION -> DEFAULT_QUESTION_PROMPT
                            PromptType.ANSWER -> DEFAULT_ANSWER_PROMPT
                            PromptType.REVIEW -> DEFAULT_REVIEW_PROMPT
                        }
                        val currentPrompt = savedPrompt?.content ?: defaultPrompt
                        
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(type.name, fontWeight = FontWeight.Medium)
                                    Row {
                                        TextButton(onClick = {
                                            editingPromptType = type
                                            editingPromptContent = currentPrompt
                                        }) {
                                            Text(stringResource(R.string.action_edit), fontSize = 12.sp)
                                        }
                                        if (savedPrompt != null) {
                                            TextButton(onClick = {
                                                scope.launch { dao.deletePrompt(savedPrompt) }
                                            }) {
                                                Text(stringResource(R.string.action_reset), fontSize = 12.sp, color = Color.Red)
                                            }
                                        }
                                    }
                                }
                                Text(
                                    currentPrompt.take(100) + if (currentPrompt.length > 100) "..." else "",
                                    fontSize = 11.sp,
                                    color = Color.Gray,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 3
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Prompt edit dialog
    editingPromptType?.let { type ->
        AlertDialog(
            onDismissRequest = { editingPromptType = null },
            title = { Text(stringResource(R.string.dataset_edit_prompt, type.name)) },
            text = {
                OutlinedTextField(
                    value = editingPromptContent,
                    onValueChange = { editingPromptContent = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 250.dp),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    maxLines = 20
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val existing = prompts.find { it.type == type && it.isDefault }
                    if (existing != null) {
                        scope.launch { dao.updatePrompt(existing.copy(content = editingPromptContent)) }
                    } else {
                        scope.launch {
                            dao.insertPrompt(DatasetPromptEntity(
                                name = "${type.name} Custom",
                                type = type,
                                content = editingPromptContent,
                                isDefault = true
                            ))
                        }
                    }
                    editingPromptType = null
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editingPromptType = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

// Default prompts
const val DEFAULT_CLEAN_PROMPT = """Clean this text by removing formatting artifacts, OCR errors, and noise.
Keep the meaning intact. Do not summarize or change content.

Previous context: {prev_chunk_50%}
Text to clean: {chunk}
Next context: {next_chunk_50%}

Cleaned text:"""

const val DEFAULT_QUESTION_PROMPT = """Generate 1 unique question from this text.

Previous: {prev_chunk_50%}
Text: {chunk}
Next: {next_chunk_50%}

Question:"""

const val DEFAULT_ANSWER_PROMPT = """You are an expert assistant. Answer the question accurately and completely using only the provided context.
{if CoT: Think step by step before answering.}

Context:
{context}

Question: {question}

Provide a clear, informative answer. If the context does not contain enough information to fully answer the question, say so.

Answer:"""

const val DEFAULT_REVIEW_PROMPT = """You are a strict Q&A quality evaluator. Rate the following Q&A pair.

RUBRIC (score each criterion):
- Relevance (0-2): Does the question directly relate to the context? Is it answerable from the given context?
- Accuracy (0-2): Is the answer factually correct and complete based on the context?
- Clarity (0-1): Are both the question and answer clear, well-formed, and unambiguous?

IMPORTANT: Your response MUST end with "FINAL_SCORE: X" where X is the sum (0-5).

Question: {question}
Answer: {answer}
Context: {context}

Evaluate each criterion, then provide your FINAL_SCORE.
Example output format:
Relevance: 2/2 - [your reasoning]
Accuracy: 2/2 - [your reasoning]
Clarity: 1/1 - [your reasoning]
FINAL_SCORE: 5"""
