package com.example.llamadroid.tama.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import com.example.llamadroid.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.llamadroid.tama.data.*
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.db.FarmUpgradeEntity
import com.example.llamadroid.tama.db.FarmTileEntity
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarmScreen(
    pet: TamaPet,
    gameEngine: TamaGameEngine,
    farmRepository: FarmRepository,
    onBack: () -> Unit
) {
    val tiles by farmRepository.observeTiles(pet.id).collectAsState(initial = emptyList())
    val upgrades by farmRepository.observeUpgrades(pet.id).collectAsState(initial = emptyList())
    
    val scope = rememberCoroutineScope()
    var selectedTool by remember { mutableStateOf<InventoryItem?>(null) }
    var showSeedPicker by remember { mutableStateOf<Int?>(null) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tama_farm_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    Text(
                        "${pet.money} 🪙",
                        modifier = Modifier.padding(end = 16.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
            )
        },
        bottomBar = {
            ToolSidebar(
                inventory = pet.inventory,
                selectedTool = selectedTool,
                onToolSelect = { selectedTool = it }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFFE1F5FE))
        ) {
            // Well and Composter area
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                UpgradeItem(
                    type = "well",
                    upgrade = upgrades.find { u -> u.type == "well" },
                    onCollect = { 
                        // Water is automatically available for the watering can if well is owned
                    },
                    onPurchase = { 
                         scope.launch {
                            if (gameEngine.spendMoney(500)) {
                                farmRepository.buyUpgrade(pet.id, "well", 500)
                                gameEngine.logEvent(pet.id, EventType.OTHER, context.getString(R.string.tama_event_well_upgrade))
                            }
                         }
                    }
                )
                UpgradeItem(
                    type = "composter",
                    upgrade = upgrades.find { u -> u.type == "composter" },
                    onCollect = {
                         val composter = upgrades.find { u -> u.type == "composter" }
                         if (composter != null && composter.storedOutput > 0) {
                             scope.launch {
                                 // Collect fertilizer units into inventory
                                 gameEngine.buyItem(InventoryItem("fertilizer", "Fertilizer", type = ItemType.MATERIAL), composter.storedOutput, 0)
                                 farmRepository.saveUpgrade(composter.copy(storedOutput = 0))
                                 gameEngine.logEvent(pet.id, EventType.OTHER, context.getString(R.string.tama_event_collected_fertilizer, composter.storedOutput))
                             }
                         }
                    },
                    onPurchase = {
                         scope.launch {
                            if (gameEngine.spendMoney(800)) {
                                farmRepository.buyUpgrade(pet.id, "composter", 800)
                                gameEngine.logEvent(pet.id, EventType.OTHER, context.getString(R.string.tama_event_composter_upgrade))
                            }
                         }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3x3 Grid
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.size(320.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(9) { index ->
                        val tile = tiles.find { it.id == index } ?: FarmTile(id = index)
                        FarmTileItem(
                            tile = tile,
                            onClick = {
                                    handleTileClick(
                                        tile = tile,
                                        tool = selectedTool,
                                        gameEngine = gameEngine,
                                        pet = pet,
                                        scope = scope,
                                        context = context,
                                        onSeedPlantRequest = { 
                                            showSeedPicker = index 
                                        },
                                        onAction = { updated ->
                                            scope.launch { farmRepository.saveTile(pet.id, updated) }
                                        },
                                        wellUpgrade = upgrades.find { u -> u.type == "well" },
                                        onConsumeWater = {
                                            scope.launch { farmRepository.consumeWater(pet.id) }
                                        }
                                    )
                            }
                        )
                    }
                }
            }
        }

        if (showSeedPicker != null) {
            SeedPickerDialog(
                inventory = pet.inventory,
                onDismiss = { showSeedPicker = null },
                onSeedSelected = { seed ->
                    val tile = tiles.find { it.id == showSeedPicker } ?: FarmTile(id = showSeedPicker!!)
                    val updatedTile = tile.copy(
                        crop = PlantedCrop(
                            type = seed.id.replace("seed_", ""),
                            plantedTime = System.currentTimeMillis(),
                            lastStageUpdateTime = System.currentTimeMillis()
                        )
                    )
                    scope.launch { 
                        farmRepository.saveTile(pet.id, updatedTile)
                        gameEngine.logEvent(pet.id, EventType.PLANTED, context.getString(R.string.tama_event_planted, seed.name))
                        // Remove 1 seed from inventory
                        gameEngine.consumeItem(seed, 1)
                    }
                    showSeedPicker = null
                }
            )
        }
    }
}

@Composable
fun FarmTileItem(
    tile: FarmTile,
    onClick: () -> Unit
) {
    val bgImage = when (tile.status) {
        TileStatus.SOIL -> "soil.png"
        TileStatus.FARMLAND -> "farmland.png"
        TileStatus.WET_FARMLAND -> "farmland_wet.png"
    }

    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = "file:///android_asset/farm/Others/$bgImage",
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            tile.crop?.let { crop ->
                val cropPath = when (crop.stage) {
                    0 -> "seed/${crop.type}.png"
                    1 -> "stage_1/${crop.type}.png"
                    2 -> "stage_2/${crop.type}.png"
                    3 -> if (crop.isDecayed) "../../Others/fertilizer.png" else "stage_final/${crop.type}.png"
                    else -> ""
                }
                
                AsyncImage(
                    model = "file:///android_asset/farm/Crops/$cropPath",
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(8.dp)
                )

                // Timer
                if (crop.stage < 3 && !crop.isDecayed) {
                    val remaining = calculateRemaining(crop)
                    Row(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (crop.isFertilized) {
                            AsyncImage(
                                model = "file:///android_asset/farm/Others/fertilizer.png",
                                contentDescription = null,
                                modifier = Modifier.size(12.dp).padding(end = 2.dp)
                            )
                        }
                        Surface(
                            color = Color.Black.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                remaining,
                                color = Color.White,
                                fontSize = 8.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ToolSidebar(
    inventory: List<InventoryItem>,
    selectedTool: InventoryItem?,
    onToolSelect: (InventoryItem?) -> Unit
) {
    val tools = inventory.filter { it.type == ItemType.TOOL || it.id == "fertilizer" || it.id == "water" }
    
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(tools) { item ->
            val isSelected = selectedTool?.id == item.id
            Surface(
                modifier = Modifier
                    .size(64.dp)
                    .clickable { if (isSelected) onToolSelect(null) else onToolSelect(item) },
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val iconSource = when(item.id) {
                        "hoe_starter", "hoe" -> "Others/hoe.png"
                        "watering_can_starter", "watering_can" -> "Others/watering_can.png"
                        "fertilizer" -> "Others/fertilizer.png"
                        "water" -> "Others/water.png"
                        else -> "Others/soil.png"
                    }
                    AsyncImage(
                        model = "file:///android_asset/farm/$iconSource",
                        contentDescription = item.name,
                        modifier = Modifier.size(40.dp)
                    )
                    
                    // Show quantity for resources
                    // Show quantity for resources or durability for tools
                    if (item.type == ItemType.TOOL) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiary,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.align(Alignment.BottomCenter).padding(2.dp)
                        ) {
                             Text(
                                "D:${item.durability ?: 100}",
                                modifier = Modifier.padding(horizontal = 4.dp),
                                fontSize = 8.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.secondary,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp)
                        ) {
                             Text(
                                item.quantity.toString(),
                                modifier = Modifier.padding(horizontal = 4.dp),
                                fontSize = 10.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UpgradeItem(
    type: String,
    upgrade: FarmUpgradeEntity?,
    onCollect: () -> Unit,
    onPurchase: () -> Unit
) {
    val icon = if (type == "well") "well.png" else "composter.png"
    val isBought = upgrade?.isPurchased == true
    val stored = upgrade?.storedOutput ?: 0
    
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clickable { 
                    if (!isBought) onPurchase()
                    else if (stored > 0) onCollect()
                },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = "file:///android_asset/farm/Others/$icon",
                contentDescription = type,
                modifier = Modifier.fillMaxSize(),
                alpha = if (isBought) 1f else 0.4f
            )
            
            if (isBought && stored > 0) {
                Surface(
                    color = Color.Red,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.align(Alignment.TopEnd).size(20.dp)
                ) {
                    Text(
                        stored.toString(),
                        color = Color.White,
                        fontSize = 10.sp,
                        modifier = Modifier.wrapContentSize(),
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (!isBought) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "Locked",
                    tint = Color.Gray,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Text(
            if (isBought) type.replaceFirstChar { it.uppercase() } else "Buy ${if (type == "well") "500" else "800"}", 
            fontSize = 10.sp,
            fontWeight = if (isBought) FontWeight.Bold else FontWeight.Normal
        )
    }
}

fun calculateRemaining(crop: PlantedCrop): String {
    val definitions = CropDefinitions.CROPS[crop.type] ?: return "???"
    val baseTime = definitions.stageTimes[crop.stage]
    val totalNeeded = if (crop.isFertilized) baseTime / 2 else baseTime
    val passed = System.currentTimeMillis() - crop.lastStageUpdateTime
    val remaining = maxOf(0L, totalNeeded - passed)
    
    val hours = remaining / 3600000
    val minutes = (remaining % 3600000) / 60000
    return String.format("%02dh %02dm", hours, minutes)
}

fun handleTileClick(
    tile: FarmTile,
    tool: InventoryItem?,
    gameEngine: TamaGameEngine,
    pet: TamaPet,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    onSeedPlantRequest: () -> Unit,
    onAction: (FarmTile) -> Unit,
    wellUpgrade: FarmUpgradeEntity? = null,
    onConsumeWater: () -> Unit = {}
) {
    when (tool?.id) {
        "hoe_starter", "hoe" -> {
            if (tile.status == TileStatus.SOIL) {
                onAction(tile.copy(status = TileStatus.FARMLAND))
                scope.launch { 
                    gameEngine.reduceToolDurability(tool, 1)
                    gameEngine.logEvent(pet.id, EventType.OTHER, context.getString(R.string.tama_event_tilled))
                }
            }
        }
        "watering_can_starter", "watering_can" -> {
            if (tile.status == TileStatus.FARMLAND || tile.status == TileStatus.WET_FARMLAND) {
                // Priority: 1. Well Water, 2. Inventory Water
                if (wellUpgrade?.isPurchased == true && wellUpgrade.storedOutput > 0) {
                    onAction(tile.copy(status = TileStatus.WET_FARMLAND, lastWateredTime = System.currentTimeMillis()))
                    onConsumeWater()
                    scope.launch {
                        gameEngine.reduceToolDurability(tool, 1)
                        gameEngine.logEvent(pet.id, EventType.WATERED, context.getString(R.string.tama_event_used_well_water))
                    }
                } else {
                    // Try inventory water
                    scope.launch {
                        val waterItem = pet.inventory.find { it.id == "water" && it.quantity > 0 }
                        if (waterItem != null) {
                            if (gameEngine.consumeItem(waterItem, 1)) {
                                onAction(tile.copy(status = TileStatus.WET_FARMLAND, lastWateredTime = System.currentTimeMillis()))
                                gameEngine.reduceToolDurability(tool, 1)
                                gameEngine.logEvent(pet.id, EventType.WATERED, context.getString(R.string.tama_event_used_bottled_water))
                            }
                        }
                    }
                }
            }
        }
        "water" -> {
            // Direct use of water item
            if (tile.status == TileStatus.FARMLAND || tile.status == TileStatus.WET_FARMLAND) {
                scope.launch {
                    if (gameEngine.consumeItem(tool, 1)) {
                        onAction(tile.copy(status = TileStatus.WET_FARMLAND, lastWateredTime = System.currentTimeMillis()))
                        gameEngine.logEvent(pet.id, EventType.WATERED, context.getString(R.string.tama_event_poured_water))
                    }
                }
            }
        }
        "fertilizer" -> {
            if (tile.crop != null && tile.crop.stage < 3 && !tile.crop.isFertilized) {
                 scope.launch {
                    if (gameEngine.consumeItem(tool, 1)) {
                        onAction(tile.copy(crop = tile.crop.copy(isFertilized = true)))
                        gameEngine.logEvent(pet.id, EventType.OTHER, context.getString(R.string.tama_event_applied_fertilizer))
                    }
                 }
            } else if (tile.crop?.isDecayed == true) {
                 scope.launch {
                    if (gameEngine.consumeItem(tool, 1)) {
                         onAction(tile.copy(crop = tile.crop.copy(isDecayed = false, lastStageUpdateTime = System.currentTimeMillis())))
                         gameEngine.logEvent(pet.id, EventType.OTHER, context.getString(R.string.tama_event_revived_plant))
                    }
                 }
            }
        }
        null -> {
            // Hand interaction
            if (tile.status == TileStatus.WET_FARMLAND && tile.crop == null) {
                onSeedPlantRequest()
            } else if (tile.crop?.stage == 3) {
                val crop = tile.crop!!
                if (crop.isDecayed) {
                    // Collect fertilizer
                    onAction(tile.copy(crop = null, status = TileStatus.SOIL))
                    scope.launch {
                        gameEngine.buyItem(InventoryItem("fertilizer", "Fertilizer", type = ItemType.MATERIAL), 1, 0)
                        gameEngine.logEvent(pet.id, EventType.OTHER, context.getString(R.string.tama_event_cleared_decayed, crop.type))
                    }
                } else {
                    // Harvest
                    onAction(tile.copy(crop = null, status = TileStatus.SOIL))
                    scope.launch {
                        gameEngine.buyItem(InventoryItem("crop_${crop.type}", "${crop.type.replaceFirstChar { it.uppercase() }} crop", type = ItemType.CROP), 1, 0)
                        gameEngine.logEvent(pet.id, EventType.HARVESTED, context.getString(R.string.tama_event_harvested, crop.type))
                    }
                }
            }
        }
    }
}

@Composable
fun SeedPickerDialog(
    inventory: List<InventoryItem>,
    onDismiss: () -> Unit,
    onSeedSelected: (InventoryItem) -> Unit
) {
    val seeds = inventory.filter { it.type == ItemType.SEED }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Seed") },
        text = {
            LazyColumn {
                items(seeds) { seed ->
                    ListItem(
                        headlineContent = { Text(seed.name) },
                        supportingContent = { Text("Available: ${seed.quantity}") },
                        modifier = Modifier.clickable { onSeedSelected(seed) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
