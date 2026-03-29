package com.example.llamadroid.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.BenchmarkResult
import com.example.llamadroid.service.BenchmarkService
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import kotlinx.coroutines.launch

/**
 * Benchmark screen to test optimal thread count for LLM inference.
 * Shows saved results and allows running new benchmarks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(navController: NavController) {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }
    val db = remember { AppDatabase.getDatabase(context) }
    val benchmarkService = remember { BenchmarkService(context) }
    
    val selectedModelPath by settingsRepo.selectedModelPath.collectAsState()
    
    // Load saved results for current model
    val savedResults by selectedModelPath?.let { path ->
        db.benchmarkDao().getResultsForModel(path).collectAsState(initial = emptyList())
    } ?: remember { mutableStateOf(emptyList<BenchmarkResult>()) }
    
    var maxThreads by remember { mutableIntStateOf(8) }
    var promptTokens by remember { mutableIntStateOf(512) }
    var genTokens by remember { mutableIntStateOf(128) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    
    // Use global state from BenchmarkService (persists across navigation)
    val isRunning by BenchmarkService.isRunning.collectAsState()
    val progressText by BenchmarkService.progressText.collectAsState()
    val progress by BenchmarkService.progress.collectAsState()
    
    // Display results from database (auto-updates as benchmark runs)
    val displayResults = savedResults
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.benchmark_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (displayResults.isNotEmpty() && !isRunning) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.benchmark_delete_title))
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Model Selection
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.benchmark_model_label), fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            selectedModelPath?.substringAfterLast("/") ?: stringResource(R.string.benchmark_no_model),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (selectedModelPath == null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.benchmark_select_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
            
            // Results Table (shows live or saved)
            if (displayResults.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                if (isRunning) stringResource(R.string.benchmark_live_results) else stringResource(R.string.benchmark_saved_results), 
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            // Header
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.benchmark_threads), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Text(stringResource(R.string.benchmark_prompt_ts), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Text(stringResource(R.string.benchmark_gen_ts), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            }
                            
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            
                            // Find best results
                            val bestGen = displayResults.maxByOrNull { it.genTokensPerSecond }
                            
                            displayResults.forEach { result ->
                                val isBest = result == bestGen
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "${result.threads}${if (isBest) " ⭐" else ""}",
                                        modifier = Modifier.weight(1f),
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = if (isBest) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isBest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        String.format("%.1f t/s", result.promptTokensPerSecond),
                                        modifier = Modifier.weight(1f),
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        String.format("%.1f t/s", result.genTokensPerSecond),
                                        modifier = Modifier.weight(1f),
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = if (isBest) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isBest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            
                            if (bestGen != null && !isRunning) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            stringResource(R.string.benchmark_optimal, bestGen.threads),
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            stringResource(R.string.benchmark_gen_speed, String.format("%.1f", bestGen.genTokensPerSecond)),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // Configuration
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.benchmark_new_title), fontWeight = FontWeight.Bold)
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Max Threads
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.benchmark_max_threads))
                            Text("$maxThreads", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = maxThreads.toFloat(),
                            onValueChange = { maxThreads = it.toInt() },
                            valueRange = 1f..12f,
                            steps = 10,
                            enabled = !isRunning
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Prompt tokens
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.benchmark_prompt_tokens))
                            Text("$promptTokens", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = promptTokens.toFloat(),
                            onValueChange = { promptTokens = it.toInt() },
                            valueRange = 128f..2048f,
                            steps = 14,
                            enabled = !isRunning
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Gen tokens
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.benchmark_gen_tokens))
                            Text("$genTokens", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = genTokens.toFloat(),
                            onValueChange = { genTokens = it.toInt() },
                            valueRange = 32f..512f,
                            steps = 14,
                            enabled = !isRunning
                        )
                    }
                }
            }
            
            // Progress
            if (isRunning) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(progressText, fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
            
            // Run/Stop Buttons
            item {
                if (isRunning) {
                    // Stop Button
                    Button(
                        onClick = {
                            BenchmarkService.cancel()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Close, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.benchmark_stop))
                    }
                } else {
                    // Run Button
                    Button(
                        onClick = {
                            val modelPath = selectedModelPath ?: return@Button
                            benchmarkService.startBenchmark(
                                modelPath = modelPath,
                                minThreads = 1,
                                maxThreads = maxThreads,
                                promptTokens = promptTokens,
                                genTokens = genTokens
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selectedModelPath != null,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.benchmark_run, maxThreads))
                    }
                }
            }
            
            // Info
            item {
                Text(
                    stringResource(R.string.benchmark_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    
    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.benchmark_delete_title)) },
            text = { Text(stringResource(R.string.benchmark_delete_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            selectedModelPath?.let { path ->
                                db.benchmarkDao().deleteResultsForModel(path)
                            }
                        }
                        showDeleteDialog = false
                    }
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
