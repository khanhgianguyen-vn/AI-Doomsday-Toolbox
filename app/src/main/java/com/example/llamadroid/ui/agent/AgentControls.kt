package com.example.llamadroid.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.service.AgentService
import com.example.llamadroid.service.PromptContextSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentTopBar(
    onShowConversations: () -> Unit,
    onShowAgentSettings: () -> Unit,
    onShowSettings: () -> Unit,
    onShowSetupInfo: () -> Unit,
    onShowProjectManagement: () -> Unit,
    onShowCustomTools: () -> Unit,
    onShowCustomAgents: () -> Unit,
    showAllOutput: Boolean,
    onToggleAllOutput: () -> Unit,
    showDebugPanel: Boolean,
    onToggleDebugPanel: () -> Unit,
    onStopAll: () -> Unit,
    onNavigateToWorkspace: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Text(
                stringResource(R.string.agent_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            IconButton(onClick = onShowConversations) {
                Icon(Icons.Default.Menu, stringResource(R.string.agent_conversations_title))
            }
        },
        actions = {
            // Priority Actions
            IconButton(onClick = onNavigateToWorkspace) {
                Icon(Icons.Default.Folder, stringResource(R.string.agent_workspace_title), tint = Color(0xFFFFC107))
            }

            IconButton(onClick = onStopAll) {
                Icon(Icons.Default.StopCircle, stringResource(R.string.agent_stop_all), tint = MaterialTheme.colorScheme.error)
            }

            // More Actions Menu
            Box {
                IconButton(onClick = { showMenu = !showMenu }) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.action_more))
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.agent_settings_title)) },
                        onClick = { showMenu = false; onShowAgentSettings() },
                        leadingIcon = { Icon(Icons.Default.Person, null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.agent_custom_tools_title)) },
                        onClick = { showMenu = false; onShowCustomTools() },
                        leadingIcon = { Icon(Icons.Default.Build, null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.agent_custom_agents_title)) },
                        onClick = { showMenu = false; onShowCustomAgents() },
                        leadingIcon = { Icon(Icons.Default.SmartToy, null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.agent_project_mgmt_title)) },
                        onClick = { showMenu = false; onShowProjectManagement() },
                        leadingIcon = { Icon(Icons.Default.Inventory, null) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    DropdownMenuItem(
                        text = { Text(if (showAllOutput) stringResource(R.string.agent_hide_output) else stringResource(R.string.agent_show_output)) },
                        onClick = { showMenu = false; onToggleAllOutput() },
                        leadingIcon = { Icon(if (showAllOutput) Icons.Default.Visibility else Icons.Default.VisibilityOff, null) }
                    )
                    DropdownMenuItem(
                        text = { Text(if (showDebugPanel) stringResource(R.string.agent_hide_debug) else stringResource(R.string.agent_show_debug)) },
                        onClick = { showMenu = false; onToggleDebugPanel() },
                        leadingIcon = { Icon(Icons.Default.Code, null) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_settings)) },
                        onClick = { showMenu = false; onShowSettings() },
                        leadingIcon = { Icon(Icons.Default.Settings, null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.agent_setup_title)) },
                        onClick = { showMenu = false; onShowSetupInfo() },
                        leadingIcon = { Icon(Icons.Default.Help, null) }
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
fun ConnectionStatusBar(
    isOllamaConnected: Boolean,
    ollamaIsRecovering: Boolean,
    ollamaHasChecked: Boolean,
    agentConnectionStatus: AgentService.Companion.ConnectionStatus,
    retryMessage: String?,
    onRetry: () -> Unit
) {
    // Don't show bar until we've actually checked AND confirmed a problem
    val agentHasIssue = agentConnectionStatus == AgentService.Companion.ConnectionStatus.DISCONNECTED ||
        agentConnectionStatus == AgentService.Companion.ConnectionStatus.RECONNECTING ||
        agentConnectionStatus == AgentService.Companion.ConnectionStatus.CONNECTING
    val ollamaHasIssue = ollamaIsRecovering || (ollamaHasChecked && !isOllamaConnected)
    
    if (ollamaHasIssue || agentHasIssue) {
        val message = when {
            ollamaIsRecovering -> stringResource(R.string.agent_ollama_reconnecting)
            ollamaHasIssue -> stringResource(R.string.agent_ollama_offline)
            agentConnectionStatus == AgentService.Companion.ConnectionStatus.RECONNECTING -> retryMessage ?: stringResource(R.string.agent_reconnecting)
            agentConnectionStatus == AgentService.Companion.ConnectionStatus.CONNECTING -> stringResource(R.string.agent_connecting)
            else -> stringResource(R.string.agent_disconnected)
        }

        Surface(
            color = if (ollamaIsRecovering || agentConnectionStatus == AgentService.Companion.ConnectionStatus.RECONNECTING || agentConnectionStatus == AgentService.Companion.ConnectionStatus.CONNECTING) 
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f)
            else 
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (ollamaIsRecovering || agentConnectionStatus == AgentService.Companion.ConnectionStatus.RECONNECTING || agentConnectionStatus == AgentService.Companion.ConnectionStatus.CONNECTING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Text(
                        text = message,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (ollamaIsRecovering || agentConnectionStatus == AgentService.Companion.ConnectionStatus.RECONNECTING || agentConnectionStatus == AgentService.Companion.ConnectionStatus.CONNECTING)
                            MaterialTheme.colorScheme.onTertiaryContainer
                        else
                            MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                // Show retry button only if disconnected and NOT retrying or if ollama is down
                if (!ollamaIsRecovering && (agentConnectionStatus == AgentService.Companion.ConnectionStatus.DISCONNECTED || !isOllamaConnected)) {
                    TextButton(
                        onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(stringResource(R.string.action_retry), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun DebugPanel(
    debugLog: List<String>,
    onClear: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.agent_debug_console), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Row {
                    TextButton(onClick = { clipboardManager.setText(AnnotatedString(debugLog.joinToString("\n"))) }) {
                        Text(stringResource(R.string.action_copy), fontSize = 10.sp)
                    }
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.action_clear), fontSize = 10.sp)
                    }
                }
            }
            if (debugLog.isEmpty()) {
                Text(stringResource(R.string.agent_no_logs), fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(8.dp))
            } else {
                Box(modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                    Column {
                        debugLog.forEach { entry ->
                            Text(entry, fontSize = 10.sp, color = Color(0xFF4CAF50), fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AgentActivityBanner(
    statusText: String,
    isVisible: Boolean,
    modifier: Modifier = Modifier
) {
    if (!isVisible || statusText.isBlank()) return

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun AgentContextWindowBanner(
    snapshot: PromptContextSnapshot?,
    modifier: Modifier = Modifier
) {
    if (snapshot == null) return

    var expanded by rememberSaveable { mutableStateOf(true) }
    val detailScrollState = rememberScrollState()
    val timeFormatter = remember {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    val progress = (snapshot.packedEstimatedTokens.toFloat() / snapshot.contextSize.toFloat()).coerceIn(0f, 1f)
    val containerColor = when {
        snapshot.percentUsed >= snapshot.thresholdPercent -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f)
        snapshot.percentUsed >= 75 -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.92f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
    }
    val contentColor = when {
        snapshot.percentUsed >= snapshot.thresholdPercent -> MaterialTheme.colorScheme.onErrorContainer
        snapshot.percentUsed >= 75 -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val detailText = when {
        snapshot.didCompactHistory || snapshot.omittedCount > 0 ->
            stringResource(
                R.string.agent_context_usage_compacted_detail,
                snapshot.rawEstimatedTokens,
                snapshot.packedEstimatedTokens,
                snapshot.omittedCount
            )
        else ->
            stringResource(
                R.string.agent_context_usage_normalized_detail,
                snapshot.rawEstimatedTokens,
                snapshot.thresholdPercent
            )
    }

    Surface(
        color = containerColor,
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.agent_context_usage_label,
                        snapshot.percentUsed,
                        snapshot.packedEstimatedTokens,
                        snapshot.contextSize
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = contentColor)
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = stringResource(
                            if (expanded) R.string.agent_context_usage_hide_details else R.string.agent_context_usage_show_details
                        ),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(
                            if (expanded) R.string.agent_context_usage_hide_details else R.string.agent_context_usage_show_details
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (!expanded) {
                return@Column
            }

            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = contentColor,
                trackColor = contentColor.copy(alpha = 0.22f)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 168.dp)
                    .verticalScroll(detailScrollState)
            ) {
                Text(
                    text = detailText,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.88f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.agent_context_usage_history_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (snapshot.recentCompactions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.agent_context_usage_history_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.82f)
                    )
                } else {
                    snapshot.recentCompactions.forEach { event ->
                        Text(
                            text = stringResource(
                                R.string.agent_context_usage_history_item,
                                timeFormatter.format(Date(event.timestamp)),
                                event.rawEstimatedTokens,
                                event.packedEstimatedTokens
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor.copy(alpha = 0.88f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AgentInputBar(
    inputText: String,
    onInputTextChange: (String) -> Unit,
    isLoading: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    canSend: Boolean
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputTextChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (canSend) stringResource(R.string.agent_type_msg) else stringResource(R.string.agent_thinking),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                enabled = canSend || isLoading,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                ),
                textStyle = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.width(12.dp))

            FilledIconButton(
                onClick = { if (isLoading) onStop() else onSend() },
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (isLoading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isLoading) {
                    Icon(Icons.Default.Stop, stringResource(R.string.action_stop), modifier = Modifier.size(24.dp))
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.action_send), modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}
