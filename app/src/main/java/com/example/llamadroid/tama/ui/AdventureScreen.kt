package com.example.llamadroid.tama.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.tama.adventure.AdventureService
import com.example.llamadroid.tama.adventure.AdventureStage
import com.example.llamadroid.tama.adventure.DungeonType
import com.example.llamadroid.tama.db.TamaDatabase
import kotlinx.coroutines.launch

// Dark fantasy colors
private val AdventureDark = Color(0xFF0D0D0D)
private val AdventureText = Color(0xFFE0E0E0)
private val AdventureAccent = Color(0xFFDAA520)
private val AdventureInput = Color(0xFF1A1A2E)
private val UserBubble = Color(0xFF2C5530)
private val StoryBubble = Color(0xFF1C1C2E)

/**
 * Text adventure gameplay screen.
 * Displays LLM-generated story as conversation with player responses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdventureScreen(
    navController: NavController,
    dungeonTypeName: String,
    database: TamaDatabase,
    settingsRepository: SettingsRepository
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Parse dungeon type
    val dungeonType = remember {
        try { DungeonType.valueOf(dungeonTypeName) } catch (e: Exception) { DungeonType.CHAOS_REALM }
    }
    
    // Adventure state - keep stages as list for conversation history
    var storyStages by remember { mutableStateOf<List<AdventureStage>>(emptyList()) }
    var playerInput by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var currentStage by remember { mutableIntStateOf(0) }
    var totalStages by remember { mutableIntStateOf(0) }
    var isCompleted by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // Adventure service with context for foreground service
    val adventureService = remember {
        AdventureService(database, settingsRepository, context)
    }
    
    // List state for auto-scrolling
    val listState = rememberLazyListState()
    
    // Initialize or continue adventure
    LaunchedEffect(dungeonType) {
        isLoading = true
        errorMessage = null
        
        val pet = database.tamaDao().getActivePet()
        if (pet == null) {
            errorMessage = "No pet found! Return to Tama screen."
            isLoading = false
            return@LaunchedEffect
        }
        
        try {
            val result = adventureService.initializeOrContinue(pet.id, dungeonType)
            
            when {
                result.isSuccess -> {
                    val session = result.getOrNull()
                    if (session != null) {
                        totalStages = session.schematic.totalStages
                        currentStage = session.currentStage
                        isCompleted = session.isCompleted
                        storyStages = session.stages
                    }
                }
                result.isFailure -> {
                    errorMessage = result.exceptionOrNull()?.message ?: "Failed to start adventure"
                }
            }
        } catch (e: Exception) {
            errorMessage = "Error: ${e.message}"
        }
        
        isLoading = false
    }
    
    // Auto-scroll when new stages added
    LaunchedEffect(storyStages.size) {
        if (storyStages.isNotEmpty()) {
            listState.animateScrollToItem(storyStages.size - 1)
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "${dungeonType.emoji} ${dungeonType.displayName}",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        if (totalStages > 0) {
                            Text(
                                "Stage $currentStage / $totalStages",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Color.Gray
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
                    // Reset button
                    IconButton(
                        onClick = {
                            scope.launch {
                                isLoading = true
                                val pet = database.tamaDao().getActivePet()
                                if (pet != null) {
                                    adventureService.resetAdventure(pet.id, dungeonType)
                                    storyStages = emptyList()
                                    currentStage = 0
                                    totalStages = 0
                                    
                                    // Re-initialize
                                    val result = adventureService.initializeOrContinue(pet.id, dungeonType)
                                    if (result.isSuccess) {
                                        val session = result.getOrNull()
                                        if (session != null) {
                                            totalStages = session.schematic.totalStages
                                            currentStage = session.currentStage
                                            isCompleted = session.isCompleted
                                            storyStages = session.stages
                                        }
                                    }
                                    Toast.makeText(context, "Adventure reset!", Toast.LENGTH_SHORT).show()
                                }
                                isLoading = false
                            }
                        },
                        enabled = !isLoading
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset", tint = Color.Red)
                    }
                    
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AdventureDark,
                    titleContentColor = AdventureAccent
                )
            )
        },
        containerColor = AdventureDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Story display area - conversation history
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                if (isLoading && storyStages.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = AdventureAccent)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "The story unfolds...",
                            color = AdventureText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp
                        )
                    }
                } else if (errorMessage != null) {
                    Text(
                        "⚠️ $errorMessage",
                        color = Color.Red,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(storyStages) { stage ->
                            StageItem(stage = stage)
                        }
                        
                        // Loading indicator for new stage
                        if (isLoading && storyStages.isNotEmpty()) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator(
                                        color = AdventureAccent,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Writing...",
                                        color = AdventureText,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                        
                        // Completion message
                        if (isCompleted) {
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        "🏆 ADVENTURE COMPLETE",
                                        color = AdventureAccent,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                    Text(
                                        "Continue exploring or reset to start fresh.",
                                        color = Color.Gray,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            // Input area
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AdventureInput)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = playerInput,
                    onValueChange = { playerInput = it },
                    placeholder = { 
                        Text(
                            if (isCompleted) "Continue your story..." else "What do you do?",
                            color = Color.Gray
                        )
                    },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AdventureText,
                        unfocusedTextColor = AdventureText,
                        focusedBorderColor = AdventureAccent,
                        unfocusedBorderColor = Color.Gray
                    ),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    enabled = !isLoading
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Button(
                    onClick = {
                        if (playerInput.isNotBlank()) {
                            val inputText = playerInput
                            playerInput = ""
                            
                            scope.launch {
                                isLoading = true
                                val pet = database.tamaDao().getActivePet()
                                if (pet != null) {
                                    val result = adventureService.submitChoice(pet.id, dungeonType, inputText)
                                    
                                    when {
                                        result.isSuccess -> {
                                            val session = result.getOrNull()
                                            if (session != null) {
                                                totalStages = session.schematic.totalStages
                                                currentStage = session.currentStage
                                                isCompleted = session.isCompleted
                                                storyStages = session.stages
                                            }
                                        }
                                        result.isFailure -> {
                                            Toast.makeText(
                                                context,
                                                "Error: ${result.exceptionOrNull()?.message}",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                }
                                isLoading = false
                            }
                        }
                    },
                    enabled = !isLoading && playerInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = AdventureAccent)
                ) {
                    Text("➡️", fontSize = 18.sp)
                }
            }
        }
    }
    
    // Settings dialog
    if (showSettingsDialog) {
        AdventureSettingsDialog(
            onDismiss = { showSettingsDialog = false },
            settingsRepository = settingsRepository
        )
    }
}

@Composable
fun StageItem(stage: AdventureStage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Story content bubble
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(StoryBubble)
                .padding(12.dp)
        ) {
            Column {
                Text(
                    "Stage ${stage.stageNumber}",
                    color = AdventureAccent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stage.storyContent,
                    color = AdventureText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    lineHeight = 22.sp
                )
            }
        }
        
        // User response bubble (if any)
        if (!stage.userResponse.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .align(Alignment.End)
                    .clip(RoundedCornerShape(12.dp))
                    .background(UserBubble)
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        "You",
                        color = Color.White.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stage.userResponse,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}
