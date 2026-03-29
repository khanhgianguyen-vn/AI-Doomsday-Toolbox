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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType

/**
 * Image Generation Settings - Thread controls and output folder
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageGenSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }
    val db = remember { AppDatabase.getDatabase(context) }
    
    val sdTxt2imgThreads by settingsRepo.sdTxt2imgThreads.collectAsState()
    val sdImg2imgThreads by settingsRepo.sdImg2imgThreads.collectAsState()
    val sdUpscaleThreads by settingsRepo.sdUpscaleThreads.collectAsState()

    val sdVaeTiling by settingsRepo.sdVaeTiling.collectAsState()
    val sdVaeTileOverlap by settingsRepo.sdVaeTileOverlap.collectAsState()
    val sdVaeTileSize by settingsRepo.sdVaeTileSize.collectAsState()
    val sdVaeRelativeTileSize by settingsRepo.sdVaeRelativeTileSize.collectAsState()
    val sdTensorTypeRules by settingsRepo.sdTensorTypeRules.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.imagegen_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
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
            // txt2img Threads
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.imagegen_txt2img_threads), fontWeight = FontWeight.Bold)
                            Text("$sdTxt2imgThreads", color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = sdTxt2imgThreads.toFloat(),
                            onValueChange = { settingsRepo.setSdTxt2imgThreads(it.toInt()) },
                            valueRange = 1f..8f,
                            steps = 6
                        )
                        Text(
                            stringResource(R.string.imagegen_txt2img_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // img2img Threads
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.imagegen_img2img_threads), fontWeight = FontWeight.Bold)
                            Text("$sdImg2imgThreads", color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = sdImg2imgThreads.toFloat(),
                            onValueChange = { settingsRepo.setSdImg2imgThreads(it.toInt()) },
                            valueRange = 1f..8f,
                            steps = 6
                        )
                        Text(
                            stringResource(R.string.imagegen_img2img_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Upscale Threads
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.imagegen_upscale_threads), fontWeight = FontWeight.Bold)
                            Text("$sdUpscaleThreads", color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = sdUpscaleThreads.toFloat(),
                            onValueChange = { settingsRepo.setSdUpscaleThreads(it.toInt()) },
                            valueRange = 1f..8f,
                            steps = 6
                        )
                        Text(
                            stringResource(R.string.imagegen_upscale_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Memory Optimization
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.imagegen_memory_opt), fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // VAE Tiling
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.imagegen_vae_tiling))
                            Switch(
                                checked = sdVaeTiling,
                                onCheckedChange = { settingsRepo.setSdVaeTiling(it) }
                            )
                        }
                        
                        if (sdVaeTiling) {
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            // Tile Overlap
                            Text(
                                stringResource(R.string.imagegen_tile_overlap) + ": ${"%.2f".format(sdVaeTileOverlap)}",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Slider(
                                value = sdVaeTileOverlap,
                                onValueChange = { settingsRepo.setSdVaeTileOverlap(it) },
                                valueRange = 0f..1f,
                                steps = 10
                            )
                            
                            // Tile Size
                            OutlinedTextField(
                                value = sdVaeTileSize,
                                onValueChange = { settingsRepo.setSdVaeTileSize(it) },
                                label = { Text(stringResource(R.string.imagegen_tile_size)) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("32x32") },
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Relative Tile Size
                            OutlinedTextField(
                                value = sdVaeRelativeTileSize,
                                onValueChange = { settingsRepo.setSdVaeRelativeTileSize(it) },
                                label = { Text(stringResource(R.string.imagegen_relative_tile_size)) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("e.g. 0.5") },
                                singleLine = true
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Tensor Type Rules
                        OutlinedTextField(
                            value = sdTensorTypeRules,
                            onValueChange = { settingsRepo.setSdTensorTypeRules(it) },
                            label = { Text(stringResource(R.string.imagegen_tensor_type_rules)) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("e.g. *:q8_0,attn*:f16") },
                            supportingText = {
                                Column {
                                    Text(stringResource(R.string.imagegen_vae_gguf_note))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(stringResource(R.string.imagegen_presets), style = MaterialTheme.typography.labelSmall)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf("f16", "q8_0", "q4_0").forEach { quant ->
                                            AssistChip(
                                                onClick = { settingsRepo.setSdTensorTypeRules("*: $quant") },
                                                label = { Text(quant) }
                                            )
                                        }
                                        AssistChip(
                                            onClick = { settingsRepo.setSdTensorTypeRules("") },
                                            label = { Text(stringResource(R.string.imagegen_default)) }
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
