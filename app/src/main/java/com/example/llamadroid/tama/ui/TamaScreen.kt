package com.example.llamadroid.tama.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.tama.data.*
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.ui.navigation.Screen
import androidx.navigation.NavController
import kotlinx.coroutines.launch

// Retro color palette (like classic Tamagotchi)
val TamaBackground = Color(0xFFD4D4AA)  // Cream/greenish LCD background
val TamaDark = Color(0xFF2C2C2C)         // Dark for pixels
val TamaLight = Color(0xFFE8E8D0)        // Light for highlights
val TamaAccent = Color(0xFF5A5A5A)       // Mid-gray

/**
 * Main Tama Tab screen.
 */
@Composable
fun TamaScreen(
    navController: NavController,
    gameEngine: TamaGameEngine,
    onChat: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val pet by gameEngine.pet.collectAsState()
    val events by gameEngine.events.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    
    var showNameDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var pendingExportJson by remember { mutableStateOf<String?>(null) }
    
    // File picker for export
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null && pendingExportJson != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(pendingExportJson!!.toByteArray())
                }
                Toast.makeText(context, context.getString(R.string.tama_export_success), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.tama_export_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
        pendingExportJson = null
    }
    
    // File picker for import
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                if (json != null) {
                    scope.launch {
                        val success = gameEngine.importFromJson(json)
                        if (success) {
                            Toast.makeText(context, context.getString(R.string.tama_import_success), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, context.getString(R.string.tama_import_invalid), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.tama_import_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    // Load pet on first composition
    LaunchedEffect(Unit) {
        val loadedPet = gameEngine.loadPet()
        if (loadedPet == null) {
            showNameDialog = true
        }
    }
    
    // Periodic decay update - use pet?.id as key so it doesn't restart on every pet change
    LaunchedEffect(pet?.id) {
        while (true) {
            kotlinx.coroutines.delay(5000)  // Every 5 seconds for decay visibility
            gameEngine.updateForTimePassed()
        }
    }
    
    // UI time refresh - update every second to show current age/time
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)  // Update every second
            currentTime = System.currentTimeMillis()
        }
    }
    
    // Animation cooldown state (for care actions)
    var currentAction by remember { mutableStateOf<String?>(null) }
    var actionCooldown by remember { mutableStateOf(false) }
    
    // Computed action for display
    val displayAction = remember(currentAction, pet?.currentActivity) {
        currentAction ?: when (pet?.currentActivity) {
            ActivityType.WORKING -> "working"
            ActivityType.STUDYING -> "studying"
            ActivityType.RELAXING -> "sunbathing"
            else -> "idle"
        }
    }
    
    // Dialogs
    var showFeedDialog by remember { mutableStateOf(false) }
    var showShopDialog by remember { mutableStateOf(false) }
    var showStatusDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showSecondResetDialog by remember { mutableStateOf(false) }
    
    // View mode: Pet or Map
    var showMap by remember { mutableStateOf(false) }
    
    // Fixed city and location state (no city generation)
    val cityName = "Hometown"
    val cityLocations = remember { 
        val coreLocations = listOf(
            Triple(0, 0, com.example.llamadroid.tama.data.LocationType.HOME),
            Triple(1, 0, com.example.llamadroid.tama.data.LocationType.SHOP),
            Triple(2, 0, com.example.llamadroid.tama.data.LocationType.PARK),
            Triple(3, 0, com.example.llamadroid.tama.data.LocationType.HOSPITAL),
            Triple(1, 1, com.example.llamadroid.tama.data.LocationType.SCHOOL),
            Triple(2, 1, com.example.llamadroid.tama.data.LocationType.WORKPLACE),
            Triple(3, 1, com.example.llamadroid.tama.data.LocationType.FARM),
            Triple(0, 2, com.example.llamadroid.tama.data.LocationType.DUNGEON),
            Triple(4, 2, com.example.llamadroid.tama.data.LocationType.DUNGEON),
        )
        
        coreLocations.map { (x, y, type) ->
            com.example.llamadroid.tama.data.TamaLocation(
                id = "fixed_${x}_${y}",
                name = type.displayName,
                type = type,
                description = type.description,
                cityId = "hometown",
                x = x, y = y,
                isDiscovered = type == com.example.llamadroid.tama.data.LocationType.HOME
            )
        }
    }
    val currentLocation by gameEngine.currentLocation.collectAsState()
    var selectedLocation by remember { mutableStateOf<TamaLocation?>(null) }
    
    // Helper to perform action with cooldown and Toast feedback
    fun performAction(action: suspend () -> TamaGameEngine.ActionResult) {
        if (actionCooldown) return
        actionCooldown = true
        scope.launch {
            val result = action()
            Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
            if (result.success) {
                currentAction = result.action
                kotlinx.coroutines.delay(1500)  // Animation time
                currentAction = null
            }
            actionCooldown = false
        }
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TamaBackground)
    ) {
        // Header with view toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TamaDark)
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pet name and info
            if (pet != null) {
                Text(
                    text = "♥ ${pet!!.name}",
                    color = TamaLight,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // View toggle buttons
                    TextButton(onClick = { showMap = false }) {
                        Text(
                            if (showMap) "🐾" else "🐾✓",
                            fontSize = 16.sp
                        )
                    }
                    TextButton(onClick = { showMap = true }) {
                        Text(
                            if (showMap) "🗺️✓" else "🗺️",
                            fontSize = 16.sp
                        )
                    }
                }
            } else {
                Text(
                    text = "♥ Tama",
                    color = TamaLight,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        }
        
        // Main display area (LCD screen style)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(TamaLight)
                .border(4.dp, TamaDark, RoundedCornerShape(8.dp))
        ) {
            val currentPet = pet
            if (currentPet != null) {
                if (showMap) {
                    // Show map view
                    TamaMapView(
                        cityName = cityName,
                        locations = cityLocations,
                        currentLocation = currentLocation ?: cityLocations.firstOrNull(),
                        discoveredLocationIds = currentPet.discoveredLocationIds,
                        onLocationClick = { loc -> selectedLocation = loc }
                    )
                } else {
                    // Show pet view
                    TamaPetDisplay(pet = currentPet, currentAction = displayAction, locationTypeName = currentLocation?.type?.name?.lowercase(), currentTime = currentTime)
                }
            } else {
                // No pet yet
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "🥚",
                        fontSize = 48.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.tama_no_pet_yet),
                        fontFamily = FontFamily.Monospace,
                        color = TamaDark
                    )
                }
            }
        }
        
        // Stats display
        if (pet != null) {
            TamaStatsBar(pet = pet!!)
        }
        
        // Control buttons - location-aware
        TamaControls(
            pet = pet,
            isSleeping = pet?.isSleeping == true,
            isBusy = actionCooldown,
            currentLocationId = currentLocation?.name?.lowercase() ?: "home",
            onFeed = { 
                if ((pet?.stats?.hunger ?: 0f) >= 100f) {
                    Toast.makeText(context, context.getString(R.string.tama_full_toast, pet?.name ?: ""), Toast.LENGTH_SHORT).show()
                } else {
                    showFeedDialog = true
                }
            },
            onClean = { performAction { gameEngine.clean() } },
            onPlay = { performAction { gameEngine.play() } },
            onSleepOrWake = { 
                if (pet?.isSleeping == true) {
                    scope.launch { 
                        gameEngine.wakeUp()
                        Toast.makeText(context, context.getString(R.string.tama_woke_up, pet?.name ?: ""), Toast.LENGTH_SHORT).show()
                    }
                } else {
                    performAction { gameEngine.goToBed() }
                }
            },
            onGoHome = {
                // Free travel home
                scope.launch {
                    val homeLocation = cityLocations.find { it.name == "Home" }
                    if (homeLocation != null) {
                        gameEngine.setCurrentLocation(homeLocation)
                        Toast.makeText(context, context.getString(R.string.tama_returned_home), Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onWork = { 
                scope.launch {
                    val result = gameEngine.startActivity(ActivityType.WORKING)
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                }
            },
            onStudy = {
                scope.launch {
                    val result = gameEngine.startActivity(ActivityType.STUDYING)
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                }
            },
            onRelax = {
                scope.launch {
                    val result = gameEngine.startActivity(ActivityType.RELAXING)
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                }
            },
            onFarm = { navController.navigate(Screen.Farm.route) },
            onStore = { navController.navigate(Screen.Store.route) },
            onStopActivity = {
                scope.launch {
                    val result = gameEngine.stopActivity()
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                }
            },
            onBuy = { showShopDialog = true },
            onChat = onChat,
            onDungeon = { navController.navigate(Screen.Dungeon.route) },
            onMenu = { showMenu = true }
        )
        
        // Event log
        TamaEventLog(events = events.take(5))
    }
    
    // Name dialog for new pet
    if (showNameDialog) {
        NewPetDialog(
            onConfirm = { name ->
                scope.launch {
                    try {
                        gameEngine.createPet(name)
                        Toast.makeText(context, context.getString(R.string.tama_welcome_new, name), Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, context.getString(R.string.tama_hatch_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                    }
                }
                showNameDialog = false
            },
            onDismiss = { showNameDialog = false }
        )
    }
    
    // Menu dialog
    if (showMenu) {
        TamaMenuDialog(
            onDismiss = { showMenu = false },
            onStatus = {
                showMenu = false
                showStatusDialog = true
            },
            onExport = {
                scope.launch {
                    try {
                        val json = gameEngine.exportToJson()
                        pendingExportJson = json
                        val petName = pet?.name ?: "tama"
                        val fileName = "tama_${petName}.json"
                        exportLauncher.launch(fileName)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                showMenu = false
            },
            onImport = {
                showMenu = false
                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            },
            onReset = {
                showMenu = false
                showResetDialog = true
            }
        )
    }

    // Reset Confirmations
    if (showResetDialog) {
        ResetConfirmationDialog(
            title = "🚨 DANGER ZONE",
            message = "Are you sure you want to delete ${pet?.name}? This action CANNOT be undone.",
            onConfirm = {
                showResetDialog = false
                showSecondResetDialog = true
            },
            onDismiss = { showResetDialog = false }
        )
    }

    if (showSecondResetDialog) {
        ResetConfirmationDialog(
            title = "🛑 FINAL WARNING",
            message = "REALLY delete ${pet?.name}? All stats, money, and items will be lost forever.",
            onConfirm = {
                scope.launch {
                    gameEngine.resetPet()
                    Toast.makeText(context, context.getString(R.string.tama_deleted), Toast.LENGTH_SHORT).show()
                }
                showSecondResetDialog = false
            },
            onDismiss = { showSecondResetDialog = false }
        )
    }

    // Status Dialog
    if (showStatusDialog && pet != null) {
        PetStatusDialog(
            pet = pet!!,
            onDismiss = { showStatusDialog = false }
        )
    }
    
    // Location details dialog
    if (selectedLocation != null && pet != null) {
        val loc = selectedLocation!!
        val isDiscovered = pet!!.discoveredLocationIds.contains(loc.id) || loc.type == LocationType.HOME
        val isHere = currentLocation?.id == loc.id || (currentLocation == null && loc.x == 0 && loc.y == 0)
        val travelCost = if (isHere) 0 else ((kotlin.math.abs(loc.x - (currentLocation?.x ?: 0)) + kotlin.math.abs(loc.y - (currentLocation?.y ?: 0))) * 3).coerceAtLeast(3)
        
        AlertDialog(
            onDismissRequest = { selectedLocation = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isDiscovered) loc.type.emoji else "❓", fontSize = 24.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isDiscovered) loc.name else stringResource(R.string.tama_unknown_place), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    if (isDiscovered) {
                        Text(loc.description, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    } else {
                        Text(stringResource(R.string.tama_unknown_warning), 
                            color = Color(0xFFD32F2F),
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace, 
                            fontSize = 14.sp)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    if (isHere) {
                        Text(stringResource(R.string.tama_you_are_here), color = Color(0xFF4CAF50), fontFamily = FontFamily.Monospace)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Location-specific actions
                        when (loc.type) {
                            LocationType.HOME -> {
                                Text(stringResource(R.string.tama_rest_home), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            }
                            LocationType.SHOP -> {
                                Text(stringResource(R.string.tama_items_available), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                Text(stringResource(R.string.tama_apple_price), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                Text(stringResource(R.string.tama_bread_price), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                Text(stringResource(R.string.tama_cake_price), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            }
                            LocationType.SCHOOL -> {
                                Text(stringResource(R.string.tama_study_gain), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                Text(stringResource(R.string.tama_current_edu, pet!!.educationLevel.toInt()), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            }
                            LocationType.WORKPLACE -> {
                                Text(stringResource(R.string.tama_avail_jobs), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                Text(stringResource(R.string.tama_job_clerk), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                Text(stringResource(R.string.tama_job_teacher), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            }
                            LocationType.PARK -> {
                                Text(stringResource(R.string.tama_park_relax), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            }
                            LocationType.HOSPITAL -> {
                                Text(stringResource(R.string.tama_hospital_heal), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            }
                            else -> {}
                        }
                    } else {
                        Text(
                            stringResource(R.string.tama_travel_cost, travelCost),
                            color = if (pet!!.stats.energy >= travelCost) TamaAccent else Color.Red,
                            fontFamily = FontFamily.Monospace
                        )
                        if (pet!!.stats.energy < travelCost) {
                            Text(stringResource(R.string.tama_not_enough_energy), color = Color.Red, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                if (!isHere) {
                    TextButton(
                        onClick = {
                            val alreadyDiscovered = pet!!.discoveredLocationIds.contains(loc.id)
                            scope.launch {
                                val result = gameEngine.travelTo(loc)
                                if (result.success) {
                                    if (!alreadyDiscovered) {
                                        Toast.makeText(context, context.getString(R.string.tama_discovered, loc.name, loc.description), Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                                    }
                                    showMap = false  // Switch to pet view
                                } else {
                                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                                }
                            }
                            selectedLocation = null
                        },
                        enabled = pet!!.stats.energy >= travelCost
                    ) {
                        Text(if (isDiscovered) stringResource(R.string.tama_btn_travel) else stringResource(R.string.tama_btn_explore))
                    }
                } else {
                    // Location actions
                    when (loc.type) {
                        LocationType.SHOP -> {
                            TextButton(onClick = {
                                // Buy cheapest item (apple)
                                if (pet!!.money >= 10) {
                                    scope.launch {
                                        Toast.makeText(context, context.getString(R.string.tama_bought_apple), Toast.LENGTH_SHORT).show()
                                    }
                                }
                                selectedLocation = null
                            }) { Text(stringResource(R.string.tama_btn_buy_apple)) }
                        }
                        LocationType.SCHOOL -> {
                            TextButton(onClick = {
                                scope.launch {
                                    // Study action (increase education)
                                    Toast.makeText(context, context.getString(R.string.tama_is_studying, pet!!.name), Toast.LENGTH_SHORT).show()
                                }
                                selectedLocation = null
                            }) { Text(stringResource(R.string.tama_btn_study)) }
                        }
                        LocationType.WORKPLACE -> {
                            TextButton(onClick = {
                                scope.launch {
                                    gameEngine.startWork("Clerk", 10)
                                    Toast.makeText(context, context.getString(R.string.tama_is_working, pet!!.name), Toast.LENGTH_SHORT).show()
                                }
                                selectedLocation = null
                                showMap = false
                            }) { Text(stringResource(R.string.tama_btn_work)) }
                        }
                        else -> {
                            TextButton(onClick = { selectedLocation = null }) { Text(stringResource(R.string.action_ok)) }
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedLocation = null }) { Text(stringResource(R.string.action_close)) }
            }
        )
    }

    // Feeding dialog with food selection
    if (showFeedDialog && pet != null) {
        FeedingDialog(
            pet = pet!!,
            onFeed = { foodType, hungerGain, happinessGain ->
                scope.launch {
                    gameEngine.feedWithFood(foodType, hungerGain, happinessGain)
                    Toast.makeText(context, context.getString(R.string.tama_ate_food, pet?.name ?: "", foodType), Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showFeedDialog = false }
        )
    }
    
    // Shop dialog for buying items
    if (showShopDialog && pet != null) {
        ShopDialog(
            pet = pet!!,
            onBuy = { itemName, cost ->
                scope.launch {
                    val result = gameEngine.buyItem(itemName, cost)
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showShopDialog = false }
        )
    }
}

// Food items data
data class FoodItem(
    val emoji: String,
    val name: String,
    val hungerGain: Int,
    val happinessGain: Int,
    val cost: Int?,  // null = infinite/free
    val isShopItem: Boolean = false
)

@Composable
fun ShopDialog(
    pet: TamaPet,
    onBuy: (String, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val shopItems = listOf(
        FoodItem("🍎", "Apple", 15, 5, 10, true),
        FoodItem("🍞", "Bread", 25, 3, 15, true),
        FoodItem("🎂", "Cake", 10, 25, 25, true),
        FoodItem("🍕", "Pizza", 30, 10, 30, true),
        FoodItem("🍔", "Burger", 35, 8, 35, true),
        FoodItem("🍣", "Sushi", 20, 15, 40, true),
        FoodItem("🍩", "Donut", 5, 20, 20, true),
        FoodItem("🥗", "Salad", 20, 2, 12, true)
    )
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tama_shop_title), fontFamily = FontFamily.Monospace) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.tama_money_label, pet.money), fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                
                shopItems.forEach { item ->
                    val canAfford = pet.money >= (item.cost ?: 0)
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(enabled = canAfford) {
                                item.cost?.let { cost ->
                                    onBuy(item.name, cost)
                                }
                            }
                            .padding(8.dp)
                            .then(if (!canAfford) Modifier.background(Color.Gray.copy(alpha = 0.2f)) else Modifier),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.emoji, fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(item.name, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                                Text(
                                    "+${item.hungerGain}🍖 +${item.happinessGain}😊",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                        Text(
                            "${item.cost}💰",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = if (canAfford) TamaAccent else Color.Red
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        dismissButton = null
    )
}

@Composable
fun FeedingDialog(
    pet: TamaPet,
    onFeed: (String, Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    // Free foods always available
    val freeFoods = listOf(
        FoodItem("🥬", "Lettuce", 5, 0, null),
        FoodItem("🍬", "Candy", 0, 1, null)
    )
    
    // Foods from inventory
    val allShopFoods = listOf(
        FoodItem("🍎", "Apple", 15, 5, 10, true),
        FoodItem("🍞", "Bread", 25, 3, 15, true),
        FoodItem("🎂", "Cake", 10, 25, 25, true),
        FoodItem("🍕", "Pizza", 30, 10, 30, true),
        FoodItem("🍔", "Burger", 35, 8, 35, true),
        FoodItem("🍣", "Sushi", 20, 15, 40, true),
        FoodItem("🍩", "Donut", 5, 20, 20, true),
        FoodItem("🥗", "Salad", 20, 2, 12, true)
    )
    
    // Count items in inventory
    val inventoryCount = pet.inventory.groupingBy { it.name.lowercase() }.eachCount()
    val ownedFoods = allShopFoods.filter { food -> 
        inventoryCount.containsKey(food.name.lowercase())
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🍖 Feed ${pet.name}", fontFamily = FontFamily.Monospace) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Hunger: ${pet.stats.hunger.toInt()}/100", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                
                Text("Always Available:", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.Gray)
                freeFoods.forEach { food ->
                    FoodItemRow(food, null) {
                        onFeed(food.name, food.hungerGain, food.happinessGain)
                        onDismiss()
                    }
                }
                
                if (ownedFoods.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("From Inventory:", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.Gray)
                    ownedFoods.forEach { food ->
                        val count = inventoryCount[food.name.lowercase()] ?: 0
                        FoodItemRow(food, count) {
                            onFeed(food.name, food.hungerGain, food.happinessGain)
                            onDismiss()
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("💡 Visit a Shop to buy more food!", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.Gray)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        dismissButton = null
    )
}

@Composable
fun FoodItemRow(food: FoodItem, count: Int?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(food.emoji, fontSize = 20.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(food.name, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                Text(
                    "+${food.hungerGain}🍖 +${food.happinessGain}😊",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color.Gray
                )
            }
        }
        Text(
            if (count != null) "x$count" else "∞",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }
}

@Composable
fun TamaHeader(pet: TamaPet?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TamaDark)
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "🐣 TAMA",
            color = TamaLight,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            fontSize = 18.sp
        )
        
        if (pet != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "💰 ${pet.money}",
                    color = TamaLight,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = pet.stage.displayName,
                    color = TamaLight,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun TamaPetDisplay(pet: TamaPet, currentAction: String? = null, locationTypeName: String? = null, currentTime: Long = System.currentTimeMillis()) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        // Background scene - larger pixels to cover more area
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            PixelScene(locationId = pet.currentLocationId, locationType = locationTypeName, pixelSize = 8.dp)
        }
        
        // Activity timer overlay at top-right
        if (pet.currentActivity != com.example.llamadroid.tama.data.ActivityType.NONE && pet.activityStartTime != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(TamaDark.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                horizontalAlignment = Alignment.End
            ) {
                val activityEmoji = when (pet.currentActivity) {
                    com.example.llamadroid.tama.data.ActivityType.WORKING -> "💼"
                    com.example.llamadroid.tama.data.ActivityType.STUDYING -> "📚"
                    com.example.llamadroid.tama.data.ActivityType.RELAXING -> "🌳"
                    else -> "✨"
                }
                
                var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        currentTime = System.currentTimeMillis()
                        kotlinx.coroutines.delay(1000)
                    }
                }
                
                val durationMs = currentTime - pet.activityStartTime
                val durationSec = (durationMs / 1000).toInt()
                val displayHours = durationSec / 3600
                val displayMin = (durationSec % 3600) / 60
                val displaySecRem = durationSec % 60
                
                val timeText = if (displayHours > 0) {
                    String.format("%d:%02d:%02d", displayHours, displayMin, displaySecRem)
                } else {
                    String.format("%02d:%02d", displayMin, displaySecRem)
                }
                
                Text("$activityEmoji $timeText", color = TamaLight, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                
                val hoursPassed = durationMs / (1000 * 60 * 60f)
                val gainText = when (pet.currentActivity) {
                    com.example.llamadroid.tama.data.ActivityType.WORKING -> "+${(hoursPassed * 10).toInt()}💰"
                    com.example.llamadroid.tama.data.ActivityType.STUDYING -> "+${(hoursPassed * 5).toInt()} edu"
                    com.example.llamadroid.tama.data.ActivityType.RELAXING -> "+${(hoursPassed * 40).toInt()} 😊"
                    else -> ""
                }
                Text(gainText, color = TamaLight.copy(alpha = 0.8f), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                
                val maxDurationMs = 8 * 60 * 60 * 1000L
                LinearProgressIndicator(
                    progress = { (durationMs.toFloat() / maxDurationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.width(60.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = TamaAccent,
                    trackColor = TamaLight.copy(alpha = 0.3f)
                )
            }
        }
        
        // Pet sprite area centered
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Animation overlays
                if (currentAction == "playing") {
                    val infiniteTransition = rememberInfiniteTransition(label = "ball_anim")
                    val ballX by infiniteTransition.animateFloat(
                        initialValue = -40f,
                        targetValue = 40f,
                        animationSpec = infiniteRepeatable(animation = tween(1000), repeatMode = RepeatMode.Reverse),
                        label = "ball_x"
                    )
                    Text("⚽", modifier = Modifier.offset(x = ballX.dp, y = 15.dp), fontSize = 16.sp)
                } else if (currentAction == "sunbathing") {
                    Text("☀️", modifier = Modifier.offset(x = 40.dp, y = (-40).dp), fontSize = 24.sp)
                    Text("✨", modifier = Modifier.offset(x = (-30).dp, y = (-20).dp), fontSize = 12.sp)
                    Text("✨", modifier = Modifier.offset(x = 20.dp, y = (-10).dp), fontSize = 10.sp)
                } else if (currentAction == "studying") {
                    Text("📖", modifier = Modifier.offset(x = 30.dp, y = 20.dp), fontSize = 18.sp)
                } else if (currentAction == "working") {
                    Text("📝", modifier = Modifier.offset(x = (-30).dp, y = 20.dp), fontSize = 18.sp)
                }

                // Pixel art pet sprite
                PixelPetSprite(
                    stage = pet.stage,
                    mood = pet.mood,
                    action = currentAction ?: "idle",
                    isSleeping = pet.isSleeping,
                    genetics = pet.genetics,
                    isMad = pet.isMad, // Pass isMad to PixelPetSprite
                    pixelSize = 10.dp
                )
            }
        }
        
        // Pet info at bottom (name, mood, age) - separate from centered sprite to avoid overlap
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(8.dp)
                .background(TamaLight.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Pet name and mood
            Text(
                text = "${pet.mood.emoji} ${pet.name}",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = TamaDark
            )
            
            // Age display
            val ageMinutes = ((currentTime - pet.birthTimestamp) / (1000 * 60)).toInt()
            Text(
                text = "${pet.stage.displayName} • ${if (ageMinutes < 60) "${ageMinutes}m" else "${ageMinutes / 60}h ${ageMinutes % 60}m"}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = TamaAccent
            )
            
            // Sleeping indicator
            if (pet.isSleeping) {
                Text(
                    text = "💤 Sleeping...",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TamaAccent
                )
            }
        }
    }
}

@Composable
fun TamaStatsBar(pet: TamaPet) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TamaDark)
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatIndicator("🍖", pet.stats.hunger)
            StatIndicator("😊", pet.stats.happiness)
            StatIndicator("❤️", pet.stats.health)
            StatIndicator("⚡", pet.stats.energy)
            StatIndicator("🛁", pet.stats.hygiene)
        }
    }
}

@Composable
fun StatIndicator(emoji: String, value: Float) {
    val intValue = value.toInt()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 16.sp)
        // ASCII-style bar
        val filled = (intValue / 20).coerceIn(0, 5)
        val bar = "▓".repeat(filled) + "░".repeat(5 - filled)
        Text(
            text = bar,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = when {
                intValue < 20 -> Color.Red
                intValue < 50 -> Color.Yellow
                else -> TamaLight
            }
        )
    }
}

@Composable
fun TamaControls(
    pet: TamaPet?,
    isSleeping: Boolean,
    isBusy: Boolean = false,
    currentLocationId: String = "home",
    onFeed: () -> Unit,
    onClean: () -> Unit,
    onPlay: () -> Unit,
    onSleepOrWake: () -> Unit,
    onGoHome: () -> Unit = {},
    onWork: () -> Unit = {},
    onStudy: () -> Unit = {},
    onRelax: () -> Unit = {},
    onStopActivity: () -> Unit = {},
    onBuy: () -> Unit = {},
    onFarm: () -> Unit = {},
    onStore: () -> Unit = {},
    onChat: () -> Unit = {},
    onDungeon: () -> Unit = {},
    onMenu: () -> Unit
) {
    val canAct = pet != null && pet.stage != GrowthStage.EGG && !isSleeping && !isBusy
    val isDoingActivity = pet?.currentActivity != ActivityType.NONE
    val isHome = currentLocationId == "home" || currentLocationId.startsWith("home")
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // Show buttons based on current location
        if (isDoingActivity) {
            // During activity: only show Stop button
            TamaButton("🛑", "Stop", enabled = !isBusy) { onStopActivity() }
        } else if (isHome) {
            // At Home: Feed, Clean, Play, Sleep
            TamaButton("🍖", "Feed", enabled = canAct) { onFeed() }
            TamaButton("🛁", "Clean", enabled = canAct) { onClean() }
            TamaButton("🎮", "Play", enabled = canAct) { onPlay() }
            TamaButton("💬", "Chat", enabled = canAct) { onChat() }
            
            if (isSleeping) {
                TamaButton("☀️", "Wake", enabled = pet != null && !isBusy) { onSleepOrWake() }
            } else {
                TamaButton("😴", "Sleep", enabled = canAct) { onSleepOrWake() }
            }
        } else {
            // Not at home: show Go Home button (always free)
            TamaButton("🏠", "Home", enabled = canAct) { onGoHome() }
            
            // Location-specific actions
            when {
                currentLocationId.contains("shop", ignoreCase = true) -> {
                    TamaButton("🛍️", "Buy", enabled = canAct) { onBuy() }
                }
                currentLocationId.contains("school", ignoreCase = true) -> {
                    TamaButton("📚", "Study", enabled = canAct && (pet?.stage == GrowthStage.CHILD || pet?.stage == GrowthStage.TEEN)) { onStudy() }
                }
                currentLocationId.contains("office", ignoreCase = true) || currentLocationId.contains("work", ignoreCase = true) -> {
                    TamaButton("💼", "Work", enabled = canAct && (pet?.stage == GrowthStage.TEEN || pet?.stage == GrowthStage.ADULT)) { onWork() }
                }
                currentLocationId.contains("park", ignoreCase = true) -> {
                    TamaButton("🌳", "Relax", enabled = canAct) { onRelax() }
                }
                currentLocationId.contains("farm", ignoreCase = true) -> {
                    TamaButton("🌾", "Farm", enabled = canAct) { onFarm() }
                    TamaButton("🏬", "Store", enabled = canAct) { onStore() }
                }
                currentLocationId.contains("hospital", ignoreCase = true) -> {
                    TamaButton("💊", "Heal", enabled = canAct && (pet?.stats?.health ?: 100f) < 100f) { /* TODO: heal */ }
                }
                currentLocationId.contains("dungeon", ignoreCase = true) -> {
                    TamaButton("⚔️", "Dungeon", enabled = canAct) { onDungeon() }
                }
            }
        }
        
        TamaButton("⚙️", "Menu", enabled = true) { onMenu() }
    }
}

@Composable
fun TamaButton(
    emoji: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) TamaDark else TamaAccent)
            .clickable(enabled = enabled) { onClick() }
            .padding(8.dp)
    ) {
        Text(emoji, fontSize = 24.sp)
        Text(
            label,
            color = TamaLight,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
    }
}

@Composable
fun TamaEventLog(events: List<TamaEvent>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .background(TamaDark.copy(alpha = 0.8f))
            .padding(8.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "📜 Recent Events",
            color = TamaLight,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
        
        events.forEach { event ->
            Text(
                text = event.toLogString(),
                color = TamaLight.copy(alpha = 0.8f),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }
        
        if (events.isEmpty()) {
            Text(
                "No events yet...",
                color = TamaAccent,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
fun NewPetDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🥚 " + stringResource(R.string.tama_new_pet_title), fontFamily = FontFamily.Monospace) },
        text = {
            Column {
                Text(stringResource(R.string.tama_egg_appeared), fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.tama_name_label)) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name) },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.tama_hatch_btn) + " 🐣")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun TamaMenuDialog(
    onDismiss: () -> Unit,
    onStatus: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onReset: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("⚙️ Menu", fontFamily = FontFamily.Monospace) },
        text = {
            Column {
                TextButton(onClick = onStatus, modifier = Modifier.fillMaxWidth()) {
                    Text("📊 Pet Status")
                }
                TextButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                    Text("📤 Export Pet (JSON)")
                }
                TextButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                    Text("📥 Import Pet (JSON)")
                }
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)) {
                    Text("🚨 Reset Pet")
                }
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
fun PetStatusDialog(
    pet: TamaPet,
    onDismiss: () -> Unit
) {
    val ageMinutes = ((System.currentTimeMillis() - pet.birthTimestamp) / (1000 * 60)).toInt()
    val ageText = if (ageMinutes < 60) "${ageMinutes} min" else "${ageMinutes / 60}h ${ageMinutes % 60}m"
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pet.mood.emoji, fontSize = 24.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(pet.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        if (pet.isMad) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("(Grumpy 💢)", color = Color.Red, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text("${pet.stage.displayName} • $ageText", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.Gray)
                }
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Core Stats Section
                Text("📊 Stats", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = TamaAccent)
                Spacer(modifier = Modifier.height(4.dp))
                StatBarRow("🍖 Hunger", pet.stats.hunger)
                StatBarRow("😊 Happy", pet.stats.happiness)
                StatBarRow("🛁 Hygiene", pet.stats.hygiene)
                StatBarRow("⚡ Energy", pet.stats.energy)
                StatBarRow("❤️ Health", pet.stats.health)
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Info Section
                Text("📋 Info", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = TamaAccent)
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    Text("💰 ", fontSize = 12.sp)
                    Text("${pet.money}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("📚 ", fontSize = 12.sp)
                    Text("Edu: ${pet.educationLevel.toInt()}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                Text("${pet.personality.name}: ${pet.personality.description}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.Gray)
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Inventory Section
                Text("🎒 Inventory (${pet.inventory.size})", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = TamaAccent)
                if (pet.inventory.isEmpty()) {
                    Text("Empty - buy food at the Shop!", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.Gray)
                } else {
                    val grouped = pet.inventory.groupingBy { it.name }.eachCount()
                    Text(grouped.entries.joinToString { "${it.key} x${it.value}" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Genetics Section
                Text("🧬 Genetics", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = TamaAccent)
                Text("Eyes: ${pet.genetics.eyeStyle} | Ears: ${pet.genetics.earStyle} | Body: ${pet.genetics.bodyStyle}", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color.Gray)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun StatBarRow(label: String, value: Float) {
    val intValue = value.toInt()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.width(80.dp))
        LinearProgressIndicator(
            progress = { intValue / 100f },
            modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = when {
                intValue >= 70 -> Color(0xFF4CAF50)
                intValue >= 40 -> Color(0xFFFF9800)
                else -> Color(0xFFF44336)
            },
            trackColor = Color.Gray.copy(alpha = 0.2f)
        )
        Text("${intValue}", fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.width(30.dp), textAlign = TextAlign.End)
    }
}

@Composable
fun ResetConfirmationDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = Color.Red, fontFamily = FontFamily.Monospace) },
        text = { Text(message, fontFamily = FontFamily.Monospace) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
            ) {
                Text("Yes, Delete", color = Color.White)
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
 * Generate ASCII art for pet based on genetics and stage.
 */
fun getPetAscii(stage: GrowthStage, genetics: GeneticTraits, mood: Mood, isMad: Boolean = false): String {
    // Eye variations based on genetics
    val eyes = when (genetics.eyeStyle % 5) {
        0 -> "o.o"
        1 -> "^.^"
        2 -> ">.>"
        3 -> "0.0"
        else -> "-.-"
    }
    
    // Mood overlay
    val displayEyes = when {
        mood == Mood.SLEEPING -> "-.-"
        mood == Mood.SICK -> "x.x"
        isMad && mood != Mood.SLEEPING -> ">.o"
        mood == Mood.ANGRY -> ">.o"
        mood == Mood.SAD -> "T.T"
        else -> eyes
    }
    
    // final override for isMad
    val finalEyes = displayEyes
    
    return when (stage) {
        GrowthStage.EGG -> """
            .---.
           /     \
          |  ?  |
           \     /
            '---'
        """.trimIndent()
        
        GrowthStage.BABY -> """
             oo
            ($finalEyes)
             ~~
        """.trimIndent()
        
        GrowthStage.CHILD -> """
            /\_/\
           ( $finalEyes )
            > ^ <
        """.trimIndent()
        
        GrowthStage.TEEN -> """
            /\_/\
           ( $finalEyes )
           /> ~ <\
            |   |
        """.trimIndent()
        
        GrowthStage.ADULT -> """
            /\_/\
           ( $finalEyes )
           /> ~ <\
           /|   |\
          (_|   |_)
        """.trimIndent()
        
        GrowthStage.SENIOR -> """
           ~~~/\_/\~~~
             ( $finalEyes )
             /> ~ <\
            /|     |\
           (_|     |_)
        """.trimIndent()
    }
}
