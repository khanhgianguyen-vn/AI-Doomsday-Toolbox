package com.example.llamadroid.ui.ai.llama

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Square
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.repository.LlamaRepository
import com.example.llamadroid.service.LlamaClientService

import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlamaChatScreen(
    navController: NavController,
    chatId: Long,
    initialServerId: Long
) {
    val context = LocalContext.current
    val database = AppDatabase.getDatabase(context)
    val checkDao = remember { database.llamaServerDao() }
    val repository = remember {
        LlamaRepository(
            database.llamaServerDao(),
            database.llamaChatDao(),
            database.llamaMessageDao()
        )
    }
    val viewModel: LlamaChatViewModel = viewModel(factory = LlamaChatViewModelFactory(repository))
    
    // UI State
    val messages by viewModel.messages.collectAsState()
    val generationState by LlamaClientService.generationState.collectAsState()
    
    var inputMessage by remember { mutableStateOf("") }
    var serverName by remember { mutableStateOf<String?>(null) }
    var activeServerId by remember { mutableLongStateOf(initialServerId) }
    
    // Search state
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentMatchIndex by remember { mutableIntStateOf(0) }
    
    // Export menu state
    var showOverflowMenu by remember { mutableStateOf(false) }
    
    // Coroutine Scope for UI events
    val scope = rememberCoroutineScope()
    
    // Error Handling (Toasts)
    LaunchedEffect(generationState) {
        if (generationState is LlamaClientService.GenerationState.Error) {
             val errorMsg = (generationState as LlamaClientService.GenerationState.Error).message
             Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
        }
    }
    
    // Server & Chat details
    val chats by viewModel.chats.collectAsState()
    val currentChat = chats.find { it.id == chatId }
    
    // Export launcher (declared after scope and currentChat are available)
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val chat = currentChat ?: return@launch
                    val msgs = viewModel.getMessagesOnce(chatId)
                    val exportData = mapOf(
                        "title" to chat.title,
                        "systemPrompt" to (chat.systemPrompt ?: ""),
                        "apiParams" to (chat.apiParams ?: ""),
                        "messages" to msgs.map { mapOf("role" to it.role, "content" to it.content) }
                    )
                    val json = Gson().toJson(exportData)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    Toast.makeText(context, context.getString(R.string.llama_export_success), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    var showParams by remember { mutableStateOf(false) }
    var supportsVision by remember { mutableStateOf(false) }
    var attachedImageUri by remember { mutableStateOf<Uri?>(null) }
    
    // Parameter States
    var temperature by remember(currentChat?.apiParams) { 
        mutableFloatStateOf(parseParam(currentChat?.apiParams, "temperature", 0.8f)) 
    }
    var topP by remember(currentChat?.apiParams) { 
        mutableFloatStateOf(parseParam(currentChat?.apiParams, "top_p", 0.95f)) 
    }
    var topK by remember(currentChat?.apiParams) { 
        mutableFloatStateOf(parseParam(currentChat?.apiParams, "top_k", 40f)) 
    }
    var minP by remember(currentChat?.apiParams) { 
        mutableFloatStateOf(parseParam(currentChat?.apiParams, "min_p", 0.05f)) 
    }
    var repPen by remember(currentChat?.apiParams) { 
        mutableFloatStateOf(parseParam(currentChat?.apiParams, "repeat_penalty", 1.1f)) 
    }
    var enableThinking by remember(currentChat?.apiParams) {
        mutableStateOf(parseParam(currentChat?.apiParams, "enable_thinking", true))
    }
    
    fun saveParams() {
        val map = mapOf(
            "temperature" to temperature,
            "top_p" to topP,
            "top_k" to topK.toInt(),
            "min_p" to minP,
            "repeat_penalty" to repPen,
            "enable_thinking" to enableThinking
        )
        val json = Gson().toJson(map)
        viewModel.updateChatApiParams(chatId, json)
    }
    
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> attachedImageUri = uri }
    )

    // Load messages
    LaunchedEffect(chatId) {
        viewModel.loadMessages(chatId)
    }

    // Determine Active Server
    LaunchedEffect(activeServerId) {
        if (activeServerId == -1L) {
            // Find last used
            val last = checkDao.getLastUsedServer()
            if (last != null) {
                activeServerId = last.id
                serverName = last.name
                supportsVision = last.supportsVision
            } else {
                // No server found
                serverName = context.getString(R.string.llama_no_servers)
            }
        } else {
             val s = checkDao.getServerById(activeServerId)
             serverName = s?.name
             if (s != null) supportsVision = s.supportsVision
        }
    }
    
    val listState = rememberLazyListState()
    
    // Smart auto-scroll: only scroll when user is at (or near) bottom
    // We use a stable flag that the user can "break out of" by scrolling up
    var userWantsAutoScroll by remember { mutableStateOf(true) }
    
    // Detect user manual scroll: if user scrolls up, disable auto-scroll
    // If user scrolls back to bottom, re-enable it
    LaunchedEffect(listState) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total == 0 || lastVisible >= total - 2
        }.collect { atBottom ->
            userWantsAutoScroll = atBottom
        }
    }
    
    // Only auto-scroll when new content arrives AND user hasn't scrolled away
    LaunchedEffect(messages.size) {
        if (userWantsAutoScroll && listState.layoutInfo.totalItemsCount > 0) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(
                            text = currentChat?.title ?: stringResource(R.string.llama_client_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        serverName?.let {
                            Text(
                                text = stringResource(R.string.llama_active_server, it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Search toggle
                    IconButton(onClick = { 
                        isSearching = !isSearching 
                        if (!isSearching) searchQuery = ""
                    }) {
                        Icon(
                            if (isSearching) Icons.Default.Close else Icons.Default.Search, 
                            contentDescription = stringResource(R.string.llama_search_chat)
                        )
                    }
                    IconButton(onClick = { showParams = !showParams }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.llama_parameters))
                    }
                    // Overflow menu
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.llama_export_chat)) },
                                onClick = {
                                    showOverflowMenu = false
                                    val fileName = (currentChat?.title ?: "chat") + ".json"
                                    exportLauncher.launch(fileName)
                                }
                            )
                        }
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
            // Parameters Panel
            AnimatedVisibility(visible = showParams) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(stringResource(R.string.llama_parameters), style = MaterialTheme.typography.titleSmall)
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Temp (${"%.1f".format(temperature)})", modifier = Modifier.width(80.dp), style = MaterialTheme.typography.bodySmall)
                            Slider(value = temperature, onValueChange = { temperature = it }, valueRange = 0f..2f, modifier = Modifier.weight(1f))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Top P (${"%.2f".format(topP)})", modifier = Modifier.width(80.dp), style = MaterialTheme.typography.bodySmall)
                            Slider(value = topP, onValueChange = { topP = it }, valueRange = 0f..1f, modifier = Modifier.weight(1f))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Top K (${topK.toInt()})", modifier = Modifier.width(80.dp), style = MaterialTheme.typography.bodySmall)
                            Slider(value = topK, onValueChange = { topK = it }, valueRange = 1f..100f, modifier = Modifier.weight(1f))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Min P (${"%.2f".format(minP)})", modifier = Modifier.width(80.dp), style = MaterialTheme.typography.bodySmall)
                            Slider(value = minP, onValueChange = { minP = it }, valueRange = 0f..1f, modifier = Modifier.weight(1f))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Rep Pen (${"%.2f".format(repPen)})", modifier = Modifier.width(80.dp), style = MaterialTheme.typography.bodySmall)
                            Slider(value = repPen, onValueChange = { repPen = it }, valueRange = 1f..2f, modifier = Modifier.weight(1f))
                        }
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.llama_thinking_process), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Switch(checked = enableThinking, onCheckedChange = { enableThinking = it })
                        }
                        
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TextButton(onClick = { 
                                // Reset to saved values on cancel
                                temperature = parseParam(currentChat?.apiParams, "temperature", 0.8f)
                                topP = parseParam(currentChat?.apiParams, "top_p", 0.95f)
                                topK = parseParam(currentChat?.apiParams, "top_k", 40f)
                                minP = parseParam(currentChat?.apiParams, "min_p", 0.05f)
                                repPen = parseParam(currentChat?.apiParams, "repeat_penalty", 1.1f)
                                showParams = false 
                            }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                            TextButton(onClick = { 
                                saveParams()
                                showParams = false 
                            }) {
                                Text(stringResource(R.string.action_save))
                            }
                        }
                    }
                }
            }

            // Search bar
            AnimatedVisibility(visible = isSearching) {
                val matchIndices = remember(searchQuery, messages) {
                    if (searchQuery.isBlank()) emptyList()
                    else messages.mapIndexedNotNull { idx, msg ->
                        if (msg.content.contains(searchQuery, ignoreCase = true)) idx else null
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { 
                            searchQuery = it
                            currentMatchIndex = 0 
                        },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.llama_search_chat)) },
                        singleLine = true,
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        shape = RoundedCornerShape(24.dp)
                    )
                    if (matchIndices.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${currentMatchIndex + 1}/${matchIndices.size}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        IconButton(
                            onClick = {
                                currentMatchIndex = if (currentMatchIndex > 0) currentMatchIndex - 1 else matchIndices.size - 1
                                scope.launch { listState.animateScrollToItem(matchIndices[currentMatchIndex]) }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.ExpandLess, contentDescription = "Previous", modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = {
                                currentMatchIndex = if (currentMatchIndex < matchIndices.size - 1) currentMatchIndex + 1 else 0
                                scope.launch { listState.animateScrollToItem(matchIndices[currentMatchIndex]) }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.ExpandMore, contentDescription = "Next", modifier = Modifier.size(20.dp))
                        }
                    }
                }
                // Auto-scroll to first match
                LaunchedEffect(searchQuery) {
                    if (matchIndices.isNotEmpty()) {
                        listState.animateScrollToItem(matchIndices[0])
                    }
                }
            }

            // Messages List + Scroll-to-bottom FAB
            Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                // Filter out the assistant message from the DB if it is CURRENTLY being generated in the UI indicator
                val filteredMessages = if (generationState is LlamaClientService.GenerationState.Generating && (generationState as LlamaClientService.GenerationState.Generating).chatId == chatId) {
                    messages.filterIndexed { index, msg -> 
                        // Hide it if it's the very last message AND it's the assistant role
                        !(index == messages.lastIndex && msg.role == "assistant")
                    }
                } else {
                    messages
                }

                items(filteredMessages) { msg ->
                    LlamaMessageItem(
                        message = msg,
                        onRegenerate = {
                            if (msg.role == "assistant") {
                                // Delete current AI message
                                viewModel.deleteMessage(msg)
                                // Trigger generation with updated history
                                val intent = Intent(context, LlamaClientService::class.java).apply {
                                    action = LlamaClientService.ACTION_GENERATE
                                    putExtra(LlamaClientService.EXTRA_CHAT_ID, chatId)
                                    putExtra(LlamaClientService.EXTRA_SERVER_ID, activeServerId)
                                }
                                context.startForegroundService(intent)
                            }
                        },
                        onEdit = { newContent ->
                            viewModel.updateMessage(msg.id, newContent)
                        },
                        onDelete = { viewModel.deleteMessage(msg) }
                    )
                }
                                // Active Generation Indicator
                 if (generationState is LlamaClientService.GenerationState.Generating && (generationState as LlamaClientService.GenerationState.Generating).chatId == chatId) {
                      val genState = generationState as LlamaClientService.GenerationState.Generating
                      item {
                          Column(modifier = Modifier.padding(8.dp)) {
                              if (!genState.thinking.isNullOrBlank()) {
                                  ThinkingMessageContent(genState.thinking, genState.content, forceExpand = true)
                              } else if (genState.content.isNotBlank()) {
                                  MarkdownText(text = genState.content, textColor = MaterialTheme.colorScheme.onSurfaceVariant)
                              }
                              
                              Row(
                                  modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                  verticalAlignment = Alignment.CenterVertically
                              ) {
                                  CircularProgressIndicator(
                                      modifier = Modifier.size(14.dp),
                                      strokeWidth = 2.dp
                                  )
                                  Spacer(modifier = Modifier.width(8.dp))
                                  Text(
                                      text = if (genState.tokenCount > 0) {
                                          "${genState.tokenCount} tokens · ${"%.1f".format(genState.tokensPerSecond)} t/s"
                                      } else {
                                          stringResource(R.string.llama_thinking)
                                      },
                                      style = MaterialTheme.typography.labelSmall,
                                      color = MaterialTheme.colorScheme.secondary
                                  )
                              }
                          }
                      }
                 }
                
                // Completed stats
                if (generationState is LlamaClientService.GenerationState.Completed && (generationState as LlamaClientService.GenerationState.Completed).chatId == chatId) {
                    val compState = generationState as LlamaClientService.GenerationState.Completed
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "✓ ",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 14.sp
                            )
                            Text(
                                text = buildString {
                                    append("${compState.completionTokens} tokens · ${ "%.1f".format(compState.tokensPerSecond)} t/s")
                                    if (compState.promptTokens > 0) {
                                        append(" · ${compState.promptTokens} prompt")
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
                
                // Continue button – shown when NOT generating and last message is from assistant
                if (generationState !is LlamaClientService.GenerationState.Generating && messages.isNotEmpty() && messages.last().role == "assistant") {
                    item {
                        TextButton(
                            onClick = {
                                if (activeServerId != -1L) {
                                    val intent = Intent(context, LlamaClientService::class.java).apply {
                                        action = LlamaClientService.ACTION_GENERATE
                                        putExtra(LlamaClientService.EXTRA_CHAT_ID, chatId)
                                        putExtra(LlamaClientService.EXTRA_SERVER_ID, activeServerId)
                                        // No EXTRA_USER_MESSAGE → service continues from existing history
                                    }
                                    context.startForegroundService(intent)
                                }
                            },
                            modifier = Modifier.padding(start = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.llama_continue))
                        }
                    }
                }
                }

            // Scroll-to-bottom FAB
            if (!userWantsAutoScroll) {
                SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            if (listState.layoutInfo.totalItemsCount > 0) {
                                listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                            }
                            userWantsAutoScroll = true
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.llama_scroll_to_bottom))
                }
            }
            } // end Box

            // Input Area
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Image attachment preview
                    if (attachedImageUri != null) {
                        Surface(
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Image, contentDescription = "Image Attached", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Image attached", style = MaterialTheme.typography.bodySmall)
                                IconButton(
                                    onClick = { attachedImageUri = null },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove file", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .padding(8.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        if (supportsVision) {
                            IconButton(
                                onClick = {
                                    photoPickerLauncher.launch(
                                        androidx.activity.result.PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                },
                                modifier = Modifier.padding(end = 4.dp, bottom = 4.dp)
                            ) {
                                Icon(Icons.Default.AttachFile, contentDescription = stringResource(R.string.llama_attach_image), tint = MaterialTheme.colorScheme.primary)
                            }
                        }

                        OutlinedTextField(
                        value = inputMessage,
                        onValueChange = { inputMessage = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        placeholder = { Text("Message...") },
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp),
                        enabled = generationState !is LlamaClientService.GenerationState.Generating
                    )
                    
                    if (generationState is LlamaClientService.GenerationState.Generating) {
                        IconButton(
                             onClick = {
                                  val intent = Intent(context, LlamaClientService::class.java).apply {
                                      action = LlamaClientService.ACTION_STOP
                                  }
                                  context.startForegroundService(intent)
                             },
                             modifier = Modifier
                                 .background(MaterialTheme.colorScheme.errorContainer, CircleShape)
                                 .size(48.dp)
                        ) {
                             Icon(
                                 painter = painterResource(android.R.drawable.ic_media_pause), // Standard stop icon
                                 contentDescription = "Stop",
                                 tint = MaterialTheme.colorScheme.onErrorContainer
                             )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                if (inputMessage.isNotBlank()) {
                                    if (activeServerId != -1L) {
                                        val text = inputMessage
                                        inputMessage = ""
                                        
                                        // Save URI path before clearing it
                                        val intentImagePath = try {
                                            if (attachedImageUri != null) {
                                                // Create a cache file since we cannot reliably pass content URI access across Service process
                                                val inputStream = context.contentResolver.openInputStream(attachedImageUri!!)
                                                val cacheFile = java.io.File(context.cacheDir, "vision_upload.jpg")
                                                inputStream?.use { input ->
                                                    cacheFile.outputStream().use { output ->
                                                        input.copyTo(output)
                                                    }
                                                }
                                                cacheFile.absolutePath
                                            } else null
                                        } catch (e: Exception) {
                                            null
                                        }
                                        attachedImageUri = null

                                        // Service handles saving the user message to DB
                                        val intent = Intent(context, LlamaClientService::class.java).apply {
                                            action = LlamaClientService.ACTION_GENERATE
                                            putExtra(LlamaClientService.EXTRA_CHAT_ID, chatId)
                                            putExtra(LlamaClientService.EXTRA_SERVER_ID, activeServerId)
                                            putExtra(LlamaClientService.EXTRA_USER_MESSAGE, text)
                                            if (intentImagePath != null) {
                                                putExtra(LlamaClientService.EXTRA_IMAGE_PATH, intentImagePath)
                                            }
                                        }
                                        context.startForegroundService(intent)
                                    } else {
                                        Toast.makeText(context, "No server selected", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier
                                .padding(bottom = 4.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                                .size(48.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send, 
                                contentDescription = "Send", 
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
            // Token estimate
            if (inputMessage.isNotBlank()) {
                val approxTokens = (inputMessage.trim().split("\\s+".toRegex()).size * 1.3).toInt()
                Text(
                    text = stringResource(R.string.llama_token_estimate, approxTokens),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                )
            }
        }
        }
    }
}

private fun parseParam(jsonStr: String?, key: String, default: Float): Float {
    if (jsonStr.isNullOrBlank()) return default
    return try {
        val mapType = object : TypeToken<Map<String, Any>>() {}.type
        val map: Map<String, Any> = Gson().fromJson(jsonStr, mapType)
        (map[key] as? Number)?.toFloat() ?: default
    } catch (e: Exception) {
        default
    }
}

private fun parseParam(jsonStr: String?, key: String, default: Boolean): Boolean {
    if (jsonStr.isNullOrBlank()) return default
    return try {
        val mapType = object : TypeToken<Map<String, Any>>() {}.type
        val map: Map<String, Any> = Gson().fromJson(jsonStr, mapType)
        (map[key] as? Boolean) ?: default
    } catch (e: Exception) {
        default
    }
}

@Composable
fun LlamaMessageItem(
    message: LlamaMessageEntity,
    onRegenerate: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit
) {
    val isUser = message.role == "user"
    val clipboardManager: ClipboardManager = LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    var isEditing by remember { mutableStateOf(false) }
    var editContent by remember { mutableStateOf(message.content) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (isEditing) {
                    OutlinedTextField(
                        value = editContent,
                        onValueChange = { editContent = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        TextButton(onClick = { isEditing = false }) { Text("Cancel") }
                        TextButton(onClick = { 
                            onEdit(editContent)
                            isEditing = false
                        }) { Text("Save") }
                    }
                } else {
                    SelectionContainer {
                        val thinkStartRegex = Regex("<[^>]*?(?:think|thought|Thought|Think)[^>]*?>")
                        val hasThinkingTags = thinkStartRegex.containsMatchIn(message.content)
                        
                        if (!isUser && (!message.thinking.isNullOrBlank() || hasThinkingTags)) {
                            if (!message.thinking.isNullOrBlank()) {
                                ThinkingMessageContent(message.thinking, message.content)
                            } else {
                                // Fallback: extract using regex if DB field is empty but tags exist
                                val combinedRegex = Regex("(<[^>]*?(?:think|thought|Thought|Think)[^>]*?>)(.*?)(<[^>]*?/(?:think|thought|Thought|Think)[^>]*?>|$)", setOf(RegexOption.DOT_MATCHES_ALL))
                                val match = combinedRegex.find(message.content)
                                val thinking = match?.groupValues?.get(2)?.trim() ?: ""
                                val content = message.content.replace(combinedRegex, "").trim()
                                ThinkingMessageContent(thinking, content)
                            }
                        } else {
                            MarkdownText(
                                text = message.content,
                                textColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        
        // Message Actions Row
        Row(
            modifier = Modifier.padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message.role.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            if (!isUser && message.completionTokens > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.llama_message_stats, "%.1fs".format(message.generationTimeMs / 1000.0), message.completionTokens, message.tps, message.promptTokens),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            
            // Copy
            Icon(
                Icons.Default.ContentCopy, 
                "Copy", 
                modifier = Modifier.size(14.dp).clickable {
                    val clip = android.content.ClipData.newPlainText("Message", message.content)
                    clipboardManager.setPrimaryClip(clip)
                },
                tint = MaterialTheme.colorScheme.outline
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            // Edit
            Icon(
                Icons.Default.Edit, 
                "Edit", 
                modifier = Modifier.size(14.dp).clickable { 
                    isEditing = true 
                    editContent = message.content
                },
                tint = MaterialTheme.colorScheme.outline
            )
             Spacer(modifier = Modifier.width(8.dp))
            
             // Regenerate (Assistant only)
            if (!isUser) {
                Icon(
                    Icons.Default.Refresh, 
                    "Regenerate", 
                    modifier = Modifier.size(14.dp).clickable { onRegenerate() },
                    tint = MaterialTheme.colorScheme.outline
                )
                 Spacer(modifier = Modifier.width(8.dp))
            }
            
            // Delete
             Icon(
                Icons.Default.Delete, 
                "Delete", 
                modifier = Modifier.size(14.dp).clickable { onDelete() },
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
fun ThinkingMessageContent(thinkingContent: String, finalResponse: String, forceExpand: Boolean = false) {
    var isExpanded by remember { mutableStateOf(forceExpand) }
    
    // Auto-update expansion state when forceExpand changes (e.g. at start of generation)
    LaunchedEffect(forceExpand) {
        if (forceExpand) isExpanded = true
    }
    
    // Auto-expand if the block is actively generating and we haven't seen the final response yet
    val isThinkingFinished = finalResponse.isNotBlank()
    LaunchedEffect(isThinkingFinished) {
        if (!isThinkingFinished) {
            isExpanded = true
        }
    }
    
    Column {
        if (thinkingContent.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(bottom = 8.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Square,
                            contentDescription = "Thinking",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.llama_thinking_process),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    
                    if (isExpanded && thinkingContent.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = thinkingContent,
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
        
        if (finalResponse.isNotEmpty()) {
            MarkdownText(
                text = finalResponse,
                textColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
