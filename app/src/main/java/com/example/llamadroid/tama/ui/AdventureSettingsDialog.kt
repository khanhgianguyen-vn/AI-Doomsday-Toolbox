package com.example.llamadroid.tama.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.tama.adventure.DungeonType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Settings dialog for adventure system - Ollama config, prompts, model selection.
 */
@Composable
fun AdventureSettingsDialog(
    onDismiss: () -> Unit,
    settingsRepository: SettingsRepository
) {
    val scope = rememberCoroutineScope()
    
    // Collect settings
    val adventureModel by settingsRepository.adventureModel.collectAsState()
    val adventureSummarizerModel by settingsRepository.adventureSummarizerModel.collectAsState()
    val adventureOllamaUrl by settingsRepository.adventureOllamaUrl.collectAsState()
    val adventureOllamaThreads by settingsRepository.adventureOllamaThreads.collectAsState()
    val adventureOllamaNumCtx by settingsRepository.adventureOllamaNumCtx.collectAsState()
    val adventureOllamaMmap by settingsRepository.adventureOllamaMmap.collectAsState()
    val adventureSystemPrompt by settingsRepository.adventureSystemPrompt.collectAsState()
    val adventureSummarizerPrompt by settingsRepository.adventureSummarizerPrompt.collectAsState()
    
    // Local state for editing
    var localUrl by remember { mutableStateOf(adventureOllamaUrl) }
    var localCtxText by remember { mutableStateOf(adventureOllamaNumCtx.toString()) }
    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoadingModels by remember { mutableStateOf(false) }
    var showStoryModelDropdown by remember { mutableStateOf(false) }
    var showSummarizerModelDropdown by remember { mutableStateOf(false) }
    var showSystemPromptEditor by remember { mutableStateOf(false) }
    var showSummarizerPromptEditor by remember { mutableStateOf(false) }
    var showDungeonPromptsEditor by remember { mutableStateOf(false) }
    
    // Fetch models on URL change
    LaunchedEffect(localUrl) {
        if (localUrl.isNotBlank()) {
            isLoadingModels = true
            availableModels = fetchOllamaModels(localUrl)
            isLoadingModels = false
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                "⚔️ Adventure Settings",
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Ollama URL
                Text("Ollama URL", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                OutlinedTextField(
                    value = localUrl,
                    onValueChange = { newUrl ->
                        localUrl = newUrl
                        settingsRepository.setAdventureOllamaUrl(newUrl)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                // Refresh models button
                TextButton(
                    onClick = {
                        scope.launch {
                            isLoadingModels = true
                            availableModels = fetchOllamaModels(localUrl)
                            isLoadingModels = false
                        }
                    },
                    enabled = !isLoadingModels
                ) {
                    Text(if (isLoadingModels) "Loading..." else "🔄 Refresh Models (${availableModels.size})")
                }
                
                // Story Model Dropdown
                Text("Story Model", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                ModelDropdown(
                    selectedModel = adventureModel,
                    availableModels = availableModels,
                    expanded = showStoryModelDropdown,
                    onExpandChange = { showStoryModelDropdown = it },
                    onModelSelect = { 
                        settingsRepository.setAdventureModel(it)
                        showStoryModelDropdown = false
                    }
                )
                
                // Summarizer Model Dropdown
                Text("Summarizer Model", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                ModelDropdown(
                    selectedModel = adventureSummarizerModel,
                    availableModels = availableModels,
                    expanded = showSummarizerModelDropdown,
                    onExpandChange = { showSummarizerModelDropdown = it },
                    onModelSelect = { 
                        settingsRepository.setAdventureSummarizerModel(it)
                        showSummarizerModelDropdown = false
                    }
                )
                
                // MMAP Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("MMAP (Memory Map)", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Text("Enable for faster loading", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    Switch(
                        checked = adventureOllamaMmap,
                        onCheckedChange = { settingsRepository.setAdventureOllamaMmap(it) }
                    )
                }
                
                // Language Picker
                val adventureLanguage by settingsRepository.adventureLanguage.collectAsState()
                var showLanguageDropdown by remember { mutableStateOf(false) }
                val languages = listOf(
                    "English", "Spanish", "French", "German", "Italian", 
                    "Portuguese", "Russian", "Chinese", "Japanese", "Korean"
                )
                
                Text("Story Language", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Box {
                    OutlinedTextField(
                        value = adventureLanguage,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { showLanguageDropdown = !showLanguageDropdown }) {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "Select language")
                            }
                        }
                    )
                    
                    DropdownMenu(
                        expanded = showLanguageDropdown,
                        onDismissRequest = { showLanguageDropdown = false }
                    ) {
                        languages.forEach { lang ->
                            DropdownMenuItem(
                                text = { Text(lang, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                                onClick = { 
                                    settingsRepository.setAdventureLanguage(lang)
                                    showLanguageDropdown = false
                                }
                            )
                        }
                    }
                }
                
                // Threads
                Text("Threads: $adventureOllamaThreads", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Slider(
                    value = adventureOllamaThreads.toFloat(),
                    onValueChange = { settingsRepository.setAdventureOllamaThreads(it.toInt()) },
                    valueRange = 1f..16f,
                    steps = 15
                )
                
                // Context Size - Slider + Input
                Text("Context Size", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = adventureOllamaNumCtx.toFloat(),
                        onValueChange = { 
                            val newVal = it.toInt()
                            settingsRepository.setAdventureOllamaNumCtx(newVal)
                            localCtxText = newVal.toString()
                        },
                        valueRange = 1024f..32768f,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = localCtxText,
                        onValueChange = { input ->
                            localCtxText = input
                            input.toIntOrNull()?.let { value ->
                                if (value in 1024..65536) {
                                    settingsRepository.setAdventureOllamaNumCtx(value)
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.width(80.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                    )
                }
                
                HorizontalDivider()
                
                // Prompt editors
                Text("Prompts", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                
                TextButton(
                    onClick = { showSystemPromptEditor = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("✏️ Edit System Prompt")
                }
                
                TextButton(
                    onClick = { showSummarizerPromptEditor = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("✏️ Edit Summarizer Prompt")
                }
                
                TextButton(
                    onClick = { showDungeonPromptsEditor = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🏰 View Dungeon Themes")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
    
    // System prompt editor
    if (showSystemPromptEditor) {
        PromptEditorDialog(
            title = "System Prompt",
            prompt = adventureSystemPrompt,
            onSave = { 
                settingsRepository.setAdventureSystemPrompt(it)
                showSystemPromptEditor = false
            },
            onDismiss = { showSystemPromptEditor = false }
        )
    }
    
    // Summarizer prompt editor
    if (showSummarizerPromptEditor) {
        PromptEditorDialog(
            title = "Summarizer Prompt",
            prompt = adventureSummarizerPrompt,
            onSave = { 
                settingsRepository.setAdventureSummarizerPrompt(it)
                showSummarizerPromptEditor = false
            },
            onDismiss = { showSummarizerPromptEditor = false }
        )
    }
    
    // Dungeon themes viewer
    if (showDungeonPromptsEditor) {
        DungeonThemesDialog(
            onDismiss = { showDungeonPromptsEditor = false }
        )
    }
}

@Composable
fun ModelDropdown(
    selectedModel: String,
    availableModels: List<String>,
    expanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    onModelSelect: (String) -> Unit
) {
    Box {
        OutlinedTextField(
            value = selectedModel,
            onValueChange = { onModelSelect(it) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { onExpandChange(!expanded) }) {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Select model")
                }
            }
        )
        
        DropdownMenu(
            expanded = expanded && availableModels.isNotEmpty(),
            onDismissRequest = { onExpandChange(false) }
        ) {
            availableModels.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                    onClick = { onModelSelect(model) }
                )
            }
        }
    }
}

@Composable
fun DungeonThemesDialog(
    onDismiss: () -> Unit
) {
    var selectedDungeon by remember { mutableStateOf(DungeonType.entries.first()) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🏰 Dungeon Themes", fontFamily = FontFamily.Monospace) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
            ) {
                // Dungeon selector - horizontal scroll
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    DungeonType.entries.forEach { dungeon ->
                        TextButton(
                            onClick = { selectedDungeon = dungeon },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (dungeon == selectedDungeon) 
                                    MaterialTheme.colorScheme.primary 
                                else 
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        ) {
                            Text(dungeon.emoji, fontSize = 18.sp)
                        }
                    }
                }
                
                // Selected dungeon info
                Text(
                    selectedDungeon.displayName,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                
                // Theme prompt (read-only, scrollable)
                OutlinedTextField(
                    value = selectedDungeon.stylePrompt,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace, 
                        fontSize = 11.sp
                    )
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun PromptEditorDialog(
    title: String,
    prompt: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var editedPrompt by remember { mutableStateOf(prompt) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontFamily = FontFamily.Monospace) },
        text = {
            OutlinedTextField(
                value = editedPrompt,
                onValueChange = { editedPrompt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(editedPrompt) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Fetch available models from Ollama server.
 */
suspend fun fetchOllamaModels(baseUrl: String): List<String> = withContext(Dispatchers.IO) {
    try {
        val url = URL("${baseUrl.trimEnd('/')}/api/tags")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        
        if (connection.responseCode == 200) {
            val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            
            // Parse JSON response to extract model names
            val modelNames = mutableListOf<String>()
            val regex = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
            regex.findAll(response).forEach { match ->
                modelNames.add(match.groupValues[1])
            }
            modelNames
        } else {
            emptyList()
        }
    } catch (e: Exception) {
        emptyList()
    }
}
