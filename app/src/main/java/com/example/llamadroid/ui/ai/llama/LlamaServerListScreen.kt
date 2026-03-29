package com.example.llamadroid.ui.ai.llama

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.repository.LlamaRepository
import com.example.llamadroid.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlamaServerListScreen(
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
    val viewModel: LlamaServerViewModel = viewModel(factory = LlamaServerViewModelFactory(repository))
    val servers by viewModel.servers.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var serverToEdit by remember { mutableStateOf<LlamaServerEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.llama_servers_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Server")
                    }
                }
            )
        }
    ) { padding ->
        if (servers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.llama_no_servers),
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(servers) { server ->
                    LlamaServerCard(
                        server = server,
                        onConnect = {
                            viewModel.selectServer(server)
                            navController.navigate(Screen.LlamaChatList.route)
                        },
                        onEdit = { serverToEdit = server },
                        onDelete = { viewModel.deleteServer(server) },
                        onReload = { viewModel.fetchModelName(server) }
                    )
                }
            }
        }
    }

    if (showAddDialog || serverToEdit != null) {
        val server = serverToEdit
        var name by remember { mutableStateOf(server?.name ?: "") }
        var host by remember { mutableStateOf(server?.host ?: "") }
        var port by remember { mutableStateOf(server?.port?.toString() ?: "8080") }
        var supportsVision by remember { mutableStateOf(server?.supportsVision ?: false) }

        AlertDialog(
            onDismissRequest = { 
                showAddDialog = false
                serverToEdit = null
            },
            title = { Text(if (server == null) stringResource(R.string.llama_add_server) else stringResource(R.string.llama_edit_server)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.llama_server_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text(stringResource(R.string.llama_server_host_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.llama_server_port_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.llama_supports_vision),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = supportsVision,
                            onCheckedChange = { supportsVision = it }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val portInt = port.toIntOrNull() ?: 8080
                        if (name.isNotBlank() && host.isNotBlank()) {
                            if (server == null) {
                                viewModel.addServer(name, host, portInt, supportsVision)
                            } else {
                                viewModel.updateServer(server.copy(name = name, host = host, port = portInt, supportsVision = supportsVision))
                            }
                            showAddDialog = false
                            serverToEdit = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showAddDialog = false
                    serverToEdit = null
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
fun LlamaServerCard(
    server: LlamaServerEntity,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReload: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onConnect
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${server.host}:${server.port}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!server.modelName.isNullOrBlank()) {
                        Text(
                            text = server.modelName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (server.supportsVision) {
                        Text(
                            text = "\uD83D\uDC41 Vision",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                
                Row {
                    IconButton(onClick = onReload) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.llama_reload_model), tint = MaterialTheme.colorScheme.secondary)
                    }
                    IconButton(onClick = onConnect) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Connect", tint = MaterialTheme.colorScheme.primary)
                    }
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
}
