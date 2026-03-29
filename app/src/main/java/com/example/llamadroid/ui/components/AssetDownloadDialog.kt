package com.example.llamadroid.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.util.AssetPackManagerUtil
import com.example.llamadroid.util.AssetPackManagerUtil.AssetPack
import com.example.llamadroid.util.AssetPackManagerUtil.InstallState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.ui.window.DialogProperties

/**
 * Dialog shown on first launch to prompt user to download all asset packs
 */
@Composable
fun AssetDownloadDialog(
    onDismiss: () -> Unit,
    onDownloadAll: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var currentState by remember { mutableStateOf<InstallState>(InstallState.Pending) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    val allPacks = remember { AssetPack.ALL }
    val missingPacks = remember(isDownloading) {
        if (!isDownloading) {
            allPacks.filter { !AssetPackManagerUtil.isReady(context, it) }
        } else emptyList()
    }
    
    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        title = {
            Text(
                text = stringResource(R.string.feature_download_title),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (isDownloading) {
                    // Download progress view
                    val statusText = when (currentState) {
                        is InstallState.Pending -> stringResource(R.string.feature_status_pending)
                        is InstallState.Downloading -> stringResource(R.string.feature_downloading)
                        is InstallState.Extracting -> stringResource(R.string.feature_status_extracting)
                        is InstallState.Completed -> stringResource(R.string.feature_status_completed)
                        is InstallState.Failed -> stringResource(R.string.feature_status_failed)
                    }
                    
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    if (currentState is InstallState.Downloading) {
                        LinearProgressIndicator(
                            progress = { downloadProgress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = "$downloadProgress%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (currentState is InstallState.Extracting) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = stringResource(R.string.feature_status_extracting_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                } else if (errorMessage != null) {
                    // Error view
                    Text(
                        text = errorMessage ?: "",
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    // Initial prompt
                    Text(
                        text = stringResource(R.string.feature_download_description),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // List packs
                    allPacks.forEach { pack ->
                        val isInstalled = AssetPackManagerUtil.isReady(context, pack)
                        AssetPackItem(
                            pack = pack,
                            isInstalled = isInstalled
                        )
                    }
                    
                    if (missingPacks.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(
                            text = stringResource(
                                R.string.feature_download_size_warning,
                                AssetPackManagerUtil.getTotalSizeMB()
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.feature_download_required_force))
                    }
                }
            }
        },
        confirmButton = {
            if (!isDownloading) {
                // Mandatory download - no skip option
                Button(
                    onClick = {
                        isDownloading = true
                        errorMessage = null
                        scope.launch {
                            AssetPackManagerUtil.downloadAllPacks(context).collectLatest { state ->
                                currentState = state
                                when (state) {
                                    is InstallState.Downloading -> {
                                        downloadProgress = state.progress
                                    }
                                    is InstallState.Completed -> {
                                        isDownloading = false
                                        onDownloadAll()
                                    }
                                    is InstallState.Failed -> {
                                        errorMessage = state.error
                                        isDownloading = false
                                    }
                                    else -> {}
                                }
                            }
                        }
                    }
                ) {
                    Text("Download")
                }
            }
        },
        dismissButton = {}, // No dismiss allowed
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    )
}

@Composable
private fun AssetPackItem(
    pack: AssetPack,
    isInstalled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = getPackIcon(pack),
            contentDescription = null,
            tint = if (isInstalled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(pack.displayNameRes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = getPackDescription(pack),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        if (isInstalled) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun getPackDescription(pack: AssetPack): String {
    return when (pack) {
        AssetPack.UPSCALER -> stringResource(R.string.feature_upscaler_desc)
        else -> "" // Should be exhaustive if enum is small
    }
}

private fun getPackIcon(pack: AssetPack): ImageVector {
    return when (pack) {
        AssetPack.UPSCALER -> Icons.Default.HighQuality
        else -> Icons.Default.Extension
    }
}
