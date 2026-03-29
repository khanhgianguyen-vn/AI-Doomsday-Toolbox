package com.example.llamadroid.ui.ai.llama

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.repository.LlamaRepository
import com.example.llamadroid.ui.navigation.Screen
import java.text.SimpleDateFormat
import java.util.*
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlamaChatListScreen(
    navController: NavController
) {
    val context = LocalContext.current
    val database = AppDatabase.getDatabase(context)
    val repository = remember { 
         LlamaRepository(
            database.llamaServerDao(),
            database.llamaChatDao(),
            database.llamaMessageDao()
        ) 
    }
    val viewModel: LlamaChatViewModel = viewModel(factory = LlamaChatViewModelFactory(repository))
    val chats by viewModel.chats.collectAsState()

    var showNewChatDialog by remember { mutableStateOf(false) }
    var chatToEdit by remember { mutableStateOf<LlamaChatEntity?>(null) }
    var showFabMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    
    // Import launcher
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                    val type = object : TypeToken<Map<String, Any>>() {}.type
                    val data: Map<String, Any> = Gson().fromJson(json, type)
                    val title = data["title"] as? String ?: "Imported Chat"
                    val systemPrompt = (data["systemPrompt"] as? String)?.ifBlank { null }
                    val msgs = (data["messages"] as? List<*>)?.mapNotNull { entry ->
                        val map = entry as? Map<*, *> ?: return@mapNotNull null
                        val role = map["role"] as? String ?: return@mapNotNull null
                        val content = map["content"] as? String ?: return@mapNotNull null
                        role to content
                    } ?: emptyList()
                    viewModel.importChat(title, systemPrompt, msgs) { newId ->
                        navController.navigate(Screen.LlamaChat.createRoute(newId, -1))
                    }
                    Toast.makeText(context, context.getString(R.string.llama_import_success), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.llama_import_error) + ": ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Ideally we should pass the selected server ID from previous screen
    // But since paths in navigation are strings, we'd need to parse arguments.
    // For simplicity, let's pick the LAST USED server automatically in the Chat Screen, 
    // or ask user to select if ambiguous. 
    // Wait, the route says `llama_chat/{chatId}/{serverId}`.
    // So we need a server ID to navigate to Chat Screen. 
    // We can get the last used server from Repo.
    
    // Let's Fetch last used server ID
    var lastUsedServerId by remember { mutableLongStateOf(-1L) }
    
    LaunchedEffect(Unit) {
        // A bit of a hack to get it in Composable, ideally ViewModel provides this
        kotlinx.coroutines.Dispatchers.IO.let {
             // In a real app, use a proper scope/VM
        }
        // Actually, let's just use the server list VM or add a method to Chat VM
        // For now, let's assume we pass -1 and Chat Screen handles "Active Server" resolution 
        // OR we just use the most recent one.
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.llama_chats_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showFabMenu = !showFabMenu }) {
                            Icon(Icons.Default.Add, contentDescription = "New Chat")
                        }
                        DropdownMenu(
                            expanded = showFabMenu,
                            onDismissRequest = { showFabMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.llama_new_chat)) },
                                onClick = {
                                    showFabMenu = false
                                    showNewChatDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.llama_import_chat)) },
                                onClick = {
                                    showFabMenu = false
                                    importLauncher.launch(arrayOf("application/json"))
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (chats.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.llama_no_chats),
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(chats) { chat ->
                    LlamaChatCard(
                        chat = chat,
                        onClick = {
                            // TODO: proper server ID
                            // For MVP, passing -1 and letting loading screen figure it out or prompt
                            // Or better: Server Selection is GLOBAL. 
                            // But user requirement: "allow user to change the active server"
                            // If we pass -1, LlamaChatScreen can look up "Last Used Server"
                            navController.navigate(Screen.LlamaChat.createRoute(chat.id, -1))
                        },
                        onEdit = { chatToEdit = chat },
                        onDelete = { viewModel.deleteChat(chat) }
                    )
                }
            }
        }
    }

    // Edit Chat Dialog
    if (chatToEdit != null) {
        val chat = chatToEdit!!
        var title by remember { mutableStateOf(chat.title) }
        var systemPrompt by remember { mutableStateOf(chat.systemPrompt ?: "") }

        AlertDialog(
            onDismissRequest = { chatToEdit = null },
            title = { Text(stringResource(R.string.llama_edit_chat)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(stringResource(R.string.llama_chat_title_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { systemPrompt = it },
                        label = { Text(stringResource(R.string.llama_system_prompt)) },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (title.isNotBlank()) {
                            viewModel.updateChat(chat, title, chat.contextSize, systemPrompt.ifBlank { null })
                            chatToEdit = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { chatToEdit = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // New Chat Dialog
    if (showNewChatDialog) {
        var title by remember { mutableStateOf("") }
        var systemPrompt by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showNewChatDialog = false },
            title = { Text(stringResource(R.string.llama_new_chat)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(stringResource(R.string.llama_chat_title_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { systemPrompt = it },
                        label = { Text(stringResource(R.string.llama_system_prompt)) },
                        placeholder = { Text(stringResource(R.string.llama_system_prompt_placeholder)) },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(R.string.llama_context_legacy_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (title.isNotBlank()) {
                            viewModel.createChat(title, 0, systemPrompt.ifBlank { null }) { newId ->
                                showNewChatDialog = false
                                navController.navigate(Screen.LlamaChat.createRoute(newId, -1))
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewChatDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
fun LlamaChatCard(
    chat: LlamaChatEntity,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chat.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = dateFormat.format(Date(chat.lastModified)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
