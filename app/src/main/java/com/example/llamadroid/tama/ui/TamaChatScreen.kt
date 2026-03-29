package com.example.llamadroid.tama.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.tama.game.TamaAgentService
import com.example.llamadroid.tama.game.TamaGameEngine
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * TamaChatScreen - AI Chat interface for the virtual pet.
 * Uses the retro LCD aesthetic from the main Tama screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TamaChatScreen(
    navController: NavController,
    gameEngine: TamaGameEngine,
    agentService: TamaAgentService,
    settingsRepo: SettingsRepository
) {
    val scope = rememberCoroutineScope()
    val pet by gameEngine.pet.collectAsState()
    val messages by agentService.messages.collectAsState()
    val isLoading by agentService.isLoading.collectAsState()
    val isOllamaConnected by OllamaService.isConnected.collectAsState()
    val availableModels by OllamaService.availableModels.collectAsState()
    val modelNames = remember(availableModels) { availableModels.map { it.name } }
    
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }
    var showSummaryDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var currentSummary by remember { mutableStateOf("") }
    
    // Auto-scroll to bottom
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    
    // Load history once when pet ID is available
    LaunchedEffect(pet?.id) {
        pet?.id?.let { agentService.loadHistory(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TamaBackground)
    ) {
        // App Bar / Header
        TopAppBar(
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${pet?.name ?: "Tama"} (${pet?.mood?.name ?: "Unknown"})",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                modifier = Modifier.size(6.dp),
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = if (isOllamaConnected) Color(0xFF4CAF50) else Color(0xFFF44336)
                            ) {}
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isOllamaConnected) "Ollama" else "Offline",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = if (isOllamaConnected) Color(0xFF4CAF50) else Color(0xFFF44336)
                            )
                        }
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                // Retry Connection
                IconButton(onClick = { 
                    agentService.retryConnection()
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Retry Connection")
                }
                
                // View/Edit Summary (Brain)
                IconButton(onClick = { 
                    scope.launch {
                        currentSummary = agentService.getLatestSummary(pet!!.id) ?: "No memory yet."
                        showSummaryDialog = true
                    }
                }) {
                    Icon(Icons.Default.Psychology, contentDescription = "Memory")
                }
                
                
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = TamaDark,
                titleContentColor = TamaLight,
                navigationIconContentColor = TamaLight,
                actionIconContentColor = TamaLight
            )
        )

        // Chat Messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp)
        ) {
            items(messages, key = { it.id ?: UUID.randomUUID().toString() }) { message ->
                TamaChatBubble(message) {
                    agentService.deleteMessage(message.id!!)
                }
            }
            
            if (isLoading) {
                item {
                    ThinkingIndicator()
                }
            }
        }

        // Input Field
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = TamaLight,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .padding(8.dp)
                    .imePadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Say something...", color = TamaAccent, fontSize = 14.sp) },
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = TamaDark,
                        unfocusedBorderColor = TamaAccent,
                        cursorColor = TamaDark,
                        containerColor = Color.Transparent
                    ),
                    maxLines = 4,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        color = TamaDark,
                        fontSize = 14.sp
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            val content = inputText
                            inputText = ""
                            // Launch in background (Service scope handles persistence)
                            agentService.sendMessage(pet!!, content)
                        }
                    },
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (inputText.isNotBlank() && !isLoading) TamaDark else TamaAccent),
                    enabled = inputText.isNotBlank() && !isLoading
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send",
                        tint = TamaLight
                    )
                }
            }
        }
    }

    if (showSettings) {
        TamaChatSettingsDialog(
            settingsRepo = settingsRepo,
            agentService = agentService,
            onDismiss = { showSettings = false }
        )
    }
    
    if (showSummaryDialog) {
        SummaryEditDialog(
            currentSummary = currentSummary,
            isLoading = isLoading,
            onSave = { 
                scope.launch {
                    agentService.updateSummary(pet!!, it)
                }
                showSummaryDialog = false
            },
            onSummarize = {
                scope.launch {
                    agentService.summarize(pet!!)
                    currentSummary = agentService.getLatestSummary(pet!!.id) ?: "No memory yet."
                }
            },
            onDismiss = { showSummaryDialog = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryEditDialog(
    currentSummary: String,
    isLoading: Boolean,
    onSave: (String) -> Unit,
    onSummarize: () -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(currentSummary) }
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.7f),
            colors = CardDefaults.cardColors(containerColor = TamaBackground),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(2.dp, TamaDark)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "🧠 Tama Memory", 
                        fontWeight = FontWeight.Bold, 
                        fontFamily = FontFamily.Monospace,
                        fontSize = 18.sp,
                        color = TamaDark
                    )
                    
                    IconButton(
                        onClick = onSummarize,
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = TamaDark)
                        } else {
                            Icon(Icons.Default.Sync, contentDescription = "Regenerate Summary", tint = TamaDark)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "This summary is used by the AI as long-term context.",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TamaAccent
                )
                Spacer(modifier = Modifier.height(12.dp))
                
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TamaDark),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = TamaDark, 
                        unfocusedBorderColor = TamaAccent
                    )
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { 
                        Text("Cancel", color = TamaDark, fontFamily = FontFamily.Monospace) 
                    }
                    Button(
                        onClick = { onSave(text) }, 
                        colors = ButtonDefaults.buttonColors(containerColor = TamaDark),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Save Memory", color = TamaLight, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TamaChatBubble(
    message: OllamaService.ChatMessage,
    onDelete: () -> Unit
) {
    if (message.role == "system") return
    
    val isUser = message.role == "user"
    var showMenu by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Text(
            text = if (isUser) "You" else "Tama",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = TamaAccent,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
        
        Box {
            Surface(
                color = if (isUser) TamaDark else (if (message.content.startsWith("⚠️")) Color(0xFFB71C1C) else TamaLight),
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                ),
                border = if (!isUser && !message.content.startsWith("⚠️")) androidx.compose.foundation.BorderStroke(2.dp, TamaDark) else null,
                shadowElevation = 2.dp,
                modifier = Modifier.combinedClickable(
                    onClick = { },
                    onLongClick = { showMenu = true }
                )
            ) {
                Text(
                    text = message.content,
                    modifier = Modifier.padding(12.dp),
                    color = if (isUser || message.content.startsWith("⚠️")) TamaLight else TamaDark,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                )
            }
            
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier.background(TamaBackground)
            ) {
                DropdownMenuItem(
                    text = { Text("Remove Message", color = Color.Red, fontFamily = FontFamily.Monospace) },
                    onClick = {
                        onDelete()
                        showMenu = false
                    },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red) }
                )
            }
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    Row(
        modifier = Modifier.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = TamaDark
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            "Thinking...",
            color = TamaDark,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TamaChatSettingsDialog(
    settingsRepo: SettingsRepository,
    agentService: TamaAgentService,
    onDismiss: () -> Unit
) {
    val petModel by settingsRepo.tamaPetModel.collectAsState()
    val summarizerModel by settingsRepo.tamaSummarizerModel.collectAsState()
    val petPrompt by settingsRepo.tamaPetPrompt.collectAsState()
    val summarizerPrompt by settingsRepo.tamaSummarizerPrompt.collectAsState()
    
    // New isolated Ollama settings
    val ollamaUrl by settingsRepo.tamaOllamaUrl.collectAsState()
    val ollamaMmap by settingsRepo.tamaOllamaMmap.collectAsState()
    val ollamaThreads by settingsRepo.tamaOllamaThreads.collectAsState()
    val ollamaNumCtx by settingsRepo.tamaOllamaNumCtx.collectAsState()
    val availableModels by OllamaService.availableModels.collectAsState()
    val modelNames = remember(availableModels) { availableModels.map { it.name } }

    var tempPetPrompt by remember { mutableStateOf(petPrompt) }
    var tempSummarizerPrompt by remember { mutableStateOf(summarizerPrompt) }
    var tempOllamaUrl by remember { mutableStateOf(ollamaUrl) }
    var tempNumCtx by remember { mutableStateOf(ollamaNumCtx.toString()) }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = TamaBackground)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "⚙️ Tama AI Settings", 
                    fontFamily = FontFamily.Monospace, 
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = TamaDark
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Ollama Connection Section
                Text("Connection", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TamaDark)
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = tempOllamaUrl,
                    onValueChange = { tempOllamaUrl = it },
                    label = { Text("Ollama URL", fontSize = 10.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    colors = TextFieldDefaults.outlinedTextFieldColors(focusedBorderColor = TamaDark, unfocusedBorderColor = TamaAccent, cursorColor = TamaDark)
                )

                Spacer(modifier = Modifier.height(12.dp))
                
                // Model Selectors
                Text("Models", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TamaDark)
                Spacer(modifier = Modifier.height(8.dp))
                
                TamaModelSelector(
                    label = "Pet Model",
                    selectedModel = petModel,
                    availableModels = modelNames,
                    onModelChange = { settingsRepo.setTamaPetModel(it) }
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                TamaModelSelector(
                    label = "Summarizer Model",
                    selectedModel = summarizerModel,
                    availableModels = modelNames,
                    onModelChange = { settingsRepo.setTamaSummarizerModel(it) }
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use MMAP", modifier = Modifier.weight(1f), fontSize = 12.sp, color = TamaDark)
                    Switch(
                        checked = ollamaMmap,
                        onCheckedChange = { 
                            settingsRepo.setTamaOllamaMmap(it)
                            agentService.ollamaService.setUseMmap(it)
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = TamaDark, checkedTrackColor = TamaAccent)
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text("Threads: $ollamaThreads", fontSize = 12.sp, color = TamaDark)
                Slider(
                    value = ollamaThreads.toFloat(),
                    onValueChange = { 
                        val newVal = it.toInt()
                        settingsRepo.setTamaOllamaThreads(newVal)
                        agentService.ollamaService.setNumThreads(newVal)
                    },
                    valueRange = 1f..16f,
                    steps = 14,
                    colors = SliderDefaults.colors(thumbColor = TamaDark, activeTrackColor = TamaDark)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = tempNumCtx,
                    onValueChange = { tempNumCtx = it },
                    label = { Text("Context Size", fontSize = 10.sp) },
                    modifier = Modifier.width(120.dp),
                    textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    colors = TextFieldDefaults.outlinedTextFieldColors(focusedBorderColor = TamaDark, unfocusedBorderColor = TamaAccent)
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = TamaAccent.copy(alpha = 0.3f))
                
                // Prompts Section
                Text("Identity & Memory", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TamaDark)
                Spacer(modifier = Modifier.height(12.dp))

                Text("Pet Behavior Prompt", fontSize = 12.sp, color = TamaDark)
                OutlinedTextField(
                    value = tempPetPrompt,
                    onValueChange = { tempPetPrompt = it },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    textStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                    colors = TextFieldDefaults.outlinedTextFieldColors(focusedBorderColor = TamaDark, unfocusedBorderColor = TamaAccent)
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text("Summarizer Prompt", fontSize = 12.sp, color = TamaDark)
                OutlinedTextField(
                    value = tempSummarizerPrompt,
                    onValueChange = { tempSummarizerPrompt = it },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    textStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                    colors = TextFieldDefaults.outlinedTextFieldColors(focusedBorderColor = TamaDark, unfocusedBorderColor = TamaAccent)
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Cancel", color = TamaDark)
                    }
                    Button(
                        onClick = {
                            settingsRepo.setTamaPetPrompt(tempPetPrompt)
                            settingsRepo.setTamaSummarizerPrompt(tempSummarizerPrompt)
                            settingsRepo.setTamaOllamaUrl(tempOllamaUrl)
                            agentService.ollamaService.setBaseUrl(tempOllamaUrl)
                            tempNumCtx.toIntOrNull()?.let { 
                                settingsRepo.setTamaOllamaNumCtx(it)
                                agentService.ollamaService.setNumCtx(it)
                            }
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = TamaDark),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save", color = TamaLight)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TamaModelSelector(
    label: String,
    selectedModel: String,
    availableModels: List<String>,
    onModelChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedModel,
            onValueChange = { },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            label = { Text(label, fontSize = 10.sp) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
            readOnly = true,
            colors = TextFieldDefaults.outlinedTextFieldColors(
                focusedBorderColor = TamaDark,
                unfocusedBorderColor = TamaAccent,
                cursorColor = TamaDark
            )
        )
        
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(TamaBackground)
        ) {
            availableModels.forEach { model ->
                DropdownMenuItem(
                    text = { 
                        Text(
                            model, 
                            fontSize = 12.sp, 
                            fontFamily = FontFamily.Monospace,
                            color = TamaDark
                        ) 
                    },
                    onClick = {
                        onModelChange(model)
                        expanded = false
                    }
                )
            }
        }
    }
}
