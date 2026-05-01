package com.example.llamadroid.tama.ui

import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import com.example.llamadroid.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.llamadroid.tama.data.*
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.game.FARM_COMPOSTER_MAX_LEVEL
import com.example.llamadroid.tama.game.FARM_COMPOSTER_PROCESS_MS
import com.example.llamadroid.tama.game.FARM_WELL_MAX_LEVEL
import com.example.llamadroid.tama.game.FARM_WELL_MAX_SPEED_LEVEL
import com.example.llamadroid.tama.game.FARM_WELL_COST
import com.example.llamadroid.tama.game.composterCapacityUpgradeCostForLevel
import com.example.llamadroid.tama.game.composterSlotCapacityForLevel
import com.example.llamadroid.tama.game.wellCapacityUpgradeCostForLevel
import com.example.llamadroid.tama.game.wellIntervalHoursForSpeedLevel
import com.example.llamadroid.tama.game.wellProductionIntervalForSpeedLevel
import com.example.llamadroid.tama.game.wellSpeedUpgradeCostForLevel
import com.example.llamadroid.tama.db.FarmUpgradeEntity
import com.example.llamadroid.tama.game.wellCapacityForLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val FARM_FIELD_BACKGROUND_ASSET = "file:///android_asset/farm/Others/farm_field_background.png"
private const val WELL_TILE_EMPTY_ASSET = "file:///android_asset/farm/Others/well_tile_empty.png"
private const val WELL_TILE_FULL_ASSET = "file:///android_asset/farm/Others/well_tile_full.png"
private const val COMPOSTER_TILE_ASSET = "file:///android_asset/farm/Others/composter_tile.png"
private const val FARM_SEED_TILE_SCALE = 0.68f
private const val FARM_STAGE_1_TILE_SCALE = 0.80f
private const val FARM_STAGE_2_TILE_SCALE = 0.94f
private const val FARM_STAGE_FINAL_TILE_SCALE = 1.04f
private const val FARM_DECAY_TILE_SCALE = 1.02f
private const val FARM_MATURE_PREVIEW_SCALE = 1.04f
private const val FARM_UPGRADE_ICON_SCALE = 1.08f

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
    var showWellDialog by remember { mutableStateOf(false) }
    var showComposterDialog by remember { mutableStateOf(false) }
    var showComposterCropPickerForSlot by remember { mutableStateOf<Int?>(null) }
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val context = LocalContext.current
    val wellUpgrade = upgrades.find { u -> u.type == "well" }
    val composterUpgrade = upgrades.find { u -> u.type == "composter" }
    val wellState = remember(wellUpgrade?.extraDataJson, wellUpgrade?.storedOutput, wellUpgrade?.level, currentTime) {
        farmRepository.decodeWellState(wellUpgrade, currentTime)
    }
    val composterSlots = remember(composterUpgrade?.extraDataJson, composterUpgrade?.level) {
        farmRepository.decodeComposterSlots(composterUpgrade)
    }
    val canBuyWell = pet.money >= FARM_WELL_COST
    val canBuyComposter = pet.money >= 800
    val compostableItems = remember(pet.inventory, context) {
        pet.inventory
            .filter { it.quantity > 0 && FarmTradeItemCatalog.isCompostableCropItem(it.id) }
            .sortedBy { inventoryItemDisplayName(context, it) }
    }
    LaunchedEffect(pet.id) {
        while (true) {
            farmRepository.refreshFarmState(pet.id)
            delay(30_000L)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            currentTime = System.currentTimeMillis()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tama_farm_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AsyncImage(
                modifier = Modifier
                    .fillMaxSize(),
                model = FARM_FIELD_BACKGROUND_ASSET,
                contentDescription = null,
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.08f))
            ) {
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
            ) {
                // Well and Composter area
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    UpgradeItem(
                        type = "well",
                        upgrade = wellUpgrade,
                        canAffordPurchase = canBuyWell,
                        onOpen = { showWellDialog = true },
                        onPurchase = {
                            scope.launch {
                                if (gameEngine.spendMoney(FARM_WELL_COST.toLong())) {
                                    farmRepository.buyUpgrade(pet.id, "well", FARM_WELL_COST)
                                    gameEngine.logEvent(pet.id, EventType.OTHER, context.getString(R.string.tama_event_well_upgrade))
                                }
                            }
                        }
                    )
                    UpgradeItem(
                        type = "composter",
                        upgrade = composterUpgrade,
                        canAffordPurchase = canBuyComposter,
                        onOpen = { showComposterDialog = true },
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

                // 3x3 Grid
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val gridSide = if (maxWidth < maxHeight) maxWidth else maxHeight
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.size(gridSide),
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
                                        }
                                    )
                                }
                            )
                        }
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
                        gameEngine.logEvent(pet.id, EventType.PLANTED, context.getString(R.string.tama_event_planted, inventoryItemDisplayName(context, seed)))
                        // Remove 1 seed from inventory
                        gameEngine.consumeItem(seed, 1)
                    }
                    showSeedPicker = null
                }
            )
        }

        if (showWellDialog) {
            WellDialog(
                upgrade = wellUpgrade,
                state = wellState,
                now = currentTime,
                money = pet.money,
                onDismiss = { showWellDialog = false },
                onCollectTile = { slotIndex ->
                    scope.launch {
                        val collected = farmRepository.collectWellTileOutput(
                            petId = pet.id,
                            slotIndex = slotIndex,
                            collectedAt = System.currentTimeMillis()
                        )
                        if (collected > 0) {
                            gameEngine.grantItem(
                                InventoryItem(
                                    id = "water",
                                    name = context.getString(R.string.tama_item_water),
                                    type = ItemType.MATERIAL
                                ),
                                collected
                            )
                            gameEngine.logEvent(pet.id, EventType.OTHER, context.getString(R.string.tama_event_collected_well_water, collected))
                        }
                    }
                },
                onUpgradeCapacity = {
                    scope.launch {
                        val currentLevel = wellUpgrade?.level ?: 1
                        val upgradeCost = wellCapacityUpgradeCostForLevel(currentLevel).toLong()
                        if (currentLevel >= FARM_WELL_MAX_LEVEL) return@launch
                        if (gameEngine.spendMoney(upgradeCost) && farmRepository.upgradeWellCapacity(pet.id)) {
                            gameEngine.logEvent(
                                pet.id,
                                EventType.OTHER,
                                context.getString(R.string.tama_event_well_capacity_upgrade, currentLevel + 1)
                            )
                        }
                    }
                },
                onUpgradeSpeed = {
                    scope.launch {
                        val speedLevel = wellState.speedLevel
                        val upgradeCost = wellSpeedUpgradeCostForLevel(speedLevel)?.toLong() ?: return@launch
                        if (speedLevel >= FARM_WELL_MAX_SPEED_LEVEL) return@launch
                        if (gameEngine.spendMoney(upgradeCost) && farmRepository.upgradeWellSpeed(pet.id)) {
                            gameEngine.logEvent(
                                pet.id,
                                EventType.OTHER,
                                context.getString(
                                    R.string.tama_event_well_speed_upgrade,
                                    wellIntervalHoursForSpeedLevel(speedLevel + 1)
                                )
                            )
                        }
                    }
                }
            )
        }

        if (showComposterDialog) {
            ComposterDialog(
                upgrade = composterUpgrade,
                money = pet.money,
                availableCrops = compostableItems,
                slots = composterSlots,
                now = currentTime,
                onDismiss = { showComposterDialog = false },
                onTileClick = { slotIndex, slot ->
                    when (slot.state) {
                        ComposterSlotState.EMPTY -> {
                            if (compostableItems.isNotEmpty()) {
                                showComposterCropPickerForSlot = slotIndex
                            }
                        }
                        ComposterSlotState.READY -> {
                            scope.launch {
                                val collected = farmRepository.collectComposterTileOutput(pet.id, slotIndex)
                                if (collected > 0) {
                                    gameEngine.grantItem(
                                        InventoryItem(
                                            id = "fertilizer",
                                            name = context.getString(R.string.tama_item_fertilizer),
                                            type = ItemType.MATERIAL
                                        ),
                                        collected
                                    )
                                    gameEngine.logEvent(
                                        pet.id,
                                        EventType.OTHER,
                                        context.getString(R.string.tama_event_collected_fertilizer, collected)
                                    )
                                }
                            }
                        }
                        ComposterSlotState.PROCESSING -> Unit
                    }
                },
                onUpgrade = {
                    scope.launch {
                        val currentLevel = composterUpgrade?.level ?: 1
                        val upgradeCost = composterCapacityUpgradeCostForLevel(currentLevel).toLong()
                        if (currentLevel >= FARM_COMPOSTER_MAX_LEVEL) return@launch
                        if (gameEngine.spendMoney(upgradeCost) && farmRepository.upgradeComposterCapacity(pet.id)) {
                            gameEngine.logEvent(
                                pet.id,
                                EventType.OTHER,
                                context.getString(R.string.tama_event_composter_capacity_upgrade, currentLevel + 1)
                            )
                        }
                    }
                }
            )
        }

        if (showComposterCropPickerForSlot != null) {
            ComposterCropPickerDialog(
                crops = compostableItems,
                onDismiss = { showComposterCropPickerForSlot = null },
                onCropSelected = { item ->
                    scope.launch {
                        if (gameEngine.consumeItem(item, 1)) {
                            if (farmRepository.addComposterInput(pet.id, item.id, showComposterCropPickerForSlot)) {
                                gameEngine.logEvent(
                                    pet.id,
                                    EventType.OTHER,
                                    context.getString(
                                        R.string.tama_event_started_composting_crop,
                                        inventoryItemDisplayName(context, item)
                                    )
                                )
                            } else {
                                gameEngine.grantItem(item, 1)
                            }
                        }
                        showComposterCropPickerForSlot = null
                    }
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
    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            FarmTileBackdrop(status = tile.status)

            tile.crop?.let { crop ->
                val cropPath = when (crop.stage) {
                    0 -> "seed/${crop.type}.png"
                    1 -> "stage_1/${crop.type}.png"
                    2 -> "stage_2/${crop.type}.png"
                    3 -> if (crop.isDecayed) null else "stage_final/${crop.type}.png"
                    else -> ""
                }

                if (crop.isDecayed) {
                    AsyncImage(
                        model = "file:///android_asset/farm/Others/rotten_crop.png",
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(1.dp)
                            .scale(FARM_DECAY_TILE_SCALE),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    val cropAssetUri = remember(cropPath) { "file:///android_asset/farm/Crops/$cropPath" }
                    AsyncImage(
                        model = rememberFarmAssetModel(cropAssetUri),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(1.dp)
                            .scale(farmTileScaleForCrop(crop)),
                        contentScale = ContentScale.Fit,
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.None
                    )
                }

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
                                modifier = Modifier
                                    .size(30.dp)
                                    .padding(end = 4.dp)
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
private fun FarmTileBackdrop(status: TileStatus) {
    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(
            model = "file:///android_asset/farm/Others/soil.png",
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val tileDark = Color(0xFF6C4A2F)
            val tileLight = Color(0xFFD6A16C)
            val wetTint = Color(0xFF2C1308).copy(alpha = 0.20f)
            val strokeWidth = size.minDimension * 0.035f
            val verticalFractions = listOf(0.26f, 0.5f, 0.74f)
            val horizontalFractions = listOf(0.3f, 0.56f, 0.82f)

            if (status == TileStatus.WET_FARMLAND) {
                drawRect(wetTint)
            }

            if (status != TileStatus.SOIL) {
                verticalFractions.forEach { fraction ->
                    val x = size.width * fraction
                    drawLine(
                        color = tileDark.copy(alpha = if (status == TileStatus.WET_FARMLAND) 0.58f else 0.46f),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = strokeWidth
                    )
                    drawLine(
                        color = tileLight.copy(alpha = if (status == TileStatus.WET_FARMLAND) 0.16f else 0.22f),
                        start = Offset(x + strokeWidth * 0.45f, 0f),
                        end = Offset(x + strokeWidth * 0.45f, size.height),
                        strokeWidth = strokeWidth * 0.45f
                    )
                }
                horizontalFractions.forEach { fraction ->
                    val y = size.height * fraction
                    drawLine(
                        color = tileDark.copy(alpha = if (status == TileStatus.WET_FARMLAND) 0.22f else 0.18f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = strokeWidth * 0.45f
                    )
                }
                drawRect(
                    color = tileDark.copy(alpha = 0.12f),
                    style = Stroke(width = strokeWidth * 0.7f)
                )
            }
        }
    }
}

internal fun farmTileScaleForCrop(crop: PlantedCrop): Float {
    if (crop.isDecayed) return FARM_DECAY_TILE_SCALE
    return when (crop.stage) {
        0 -> FARM_SEED_TILE_SCALE
        1 -> FARM_STAGE_1_TILE_SCALE
        2 -> FARM_STAGE_2_TILE_SCALE
        else -> FARM_STAGE_FINAL_TILE_SCALE
    }
}

internal fun canWaterFarmTile(tile: FarmTile): Boolean {
    return tile.status == TileStatus.FARMLAND
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
            .height(82.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(tools) { item ->
            val isSelected = selectedTool?.id == item.id
            Surface(
                modifier = Modifier
                    .size(68.dp)
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
                        modifier = Modifier
                            .size(50.dp)
                            .scale(1.7f),
                        contentScale = ContentScale.Fit
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
    canAffordPurchase: Boolean,
    onOpen: () -> Unit,
    onPurchase: () -> Unit
) {
    val icon = if (type == "well") "well.png" else "composter.png"
    val isBought = upgrade?.isPurchased == true
    val stored = upgrade?.storedOutput ?: 0
    val title = if (type == "well") {
        stringResource(R.string.tama_farm_upgrade_well)
    } else {
        stringResource(R.string.tama_farm_upgrade_composter)
    }
    val buyPrice = if (type == "well") FARM_WELL_COST else 800
    
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .clickable(enabled = isBought || canAffordPurchase) {
                    if (!isBought) onPurchase() else onOpen()
                },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = "file:///android_asset/farm/Others/$icon",
                contentDescription = title,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(0.dp)
                    .scale(FARM_UPGRADE_ICON_SCALE),
                contentScale = ContentScale.Fit,
                alpha = if (isBought || canAffordPurchase) 1f else 0.35f
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
                    contentDescription = stringResource(R.string.tama_farm_locked_desc),
                    tint = Color.Gray,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Text(
            if (isBought) title else stringResource(R.string.tama_farm_buy_upgrade, buyPrice),
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = if (isBought) FontWeight.Bold else FontWeight.Normal,
            color = if (isBought || canAffordPurchase) TamaDark else TamaMutedText,
            modifier = Modifier
                .widthIn(max = 114.dp)
                .offset(y = (-6).dp)
        )
    }
}

@Composable
private fun WellDialog(
    upgrade: FarmUpgradeEntity?,
    state: WellUpgradeState,
    now: Long,
    money: Long,
    onDismiss: () -> Unit,
    onCollectTile: (Int) -> Unit,
    onUpgradeCapacity: () -> Unit,
    onUpgradeSpeed: () -> Unit
) {
    val level = upgrade?.level ?: 1
    val capacity = state.slots.size.coerceAtLeast(wellCapacityForLevel(level))
    val readyTiles = state.slots.count { wellTileIsReady(it, state.speedLevel, now) }
    val maxedCapacity = level >= FARM_WELL_MAX_LEVEL
    val capacityUpgradeCost = wellCapacityUpgradeCostForLevel(level)
    val canUpgradeCapacity = !maxedCapacity && money >= capacityUpgradeCost.toLong()
    val speedLevel = state.speedLevel
    val speedUpgradeCost = wellSpeedUpgradeCostForLevel(speedLevel)
    val speedMaxed = speedUpgradeCost == null || speedLevel >= FARM_WELL_MAX_SPEED_LEVEL
    val canUpgradeSpeed = !speedMaxed && money >= (speedUpgradeCost?.toLong() ?: Long.MAX_VALUE)
    val context = LocalContext.current
    FarmUpgradeDialogFrame(
        title = stringResource(R.string.tama_farm_well_menu_title),
        backgroundAssetPath = "file:///android_asset/farm/Others/well_dialog_background.png",
        onDismiss = onDismiss,
        actions = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onUpgradeCapacity,
                    enabled = canUpgradeCapacity,
                    modifier = Modifier.weight(1f),
                    colors = farmPopupButtonColors()
                ) {
                    Text(
                        if (maxedCapacity) {
                            stringResource(R.string.tama_farm_upgrade_maxed)
                        } else {
                            stringResource(R.string.tama_farm_well_capacity_upgrade_action, capacityUpgradeCost)
                        }
                    )
                }
                Button(
                    onClick = onUpgradeSpeed,
                    enabled = canUpgradeSpeed,
                    modifier = Modifier.weight(1f),
                    colors = farmPopupButtonColors()
                ) {
                    Text(
                        if (speedMaxed) {
                            stringResource(R.string.tama_farm_upgrade_maxed)
                        } else {
                            stringResource(R.string.tama_farm_well_speed_upgrade_action, speedUpgradeCost ?: 0)
                        }
                    )
                }
            }
        }
    ) {
        FarmInfoSection(title = stringResource(R.string.tama_farm_well_status_section)) {
            Text(
                stringResource(R.string.tama_farm_well_storage_summary, readyTiles, capacity),
                fontWeight = FontWeight.Bold,
                color = TamaDark
            )
            Text(
                stringResource(R.string.tama_farm_well_speed_summary, wellIntervalHoursForSpeedLevel(speedLevel)),
                color = TamaDark
            )
            Text(stringResource(R.string.tama_farm_well_level, level), color = TamaDark)
            Text(overallWellStatusText(context, state, capacity, now), color = TamaMutedText)
            Text(stringResource(R.string.tama_farm_well_direct_use_hint), color = TamaMutedText)
        }
        FarmInfoSection(title = stringResource(R.string.tama_farm_well_tiles_section, capacity)) {
            FarmUtilityTileGrid(count = capacity) { index ->
                WellTileCard(
                    slot = state.slots.getOrElse(index) { WellSlot() },
                    speedLevel = speedLevel,
                    now = now,
                    onClick = { onCollectTile(index) }
                )
            }
        }
    }
}

@Composable
private fun ComposterDialog(
    upgrade: FarmUpgradeEntity?,
    money: Long,
    availableCrops: List<InventoryItem>,
    slots: List<ComposterSlot>,
    now: Long,
    onDismiss: () -> Unit,
    onTileClick: (Int, ComposterSlot) -> Unit,
    onUpgrade: () -> Unit
) {
    val stored = upgrade?.storedOutput ?: 0
    val level = upgrade?.level ?: 1
    val slotCapacity = composterSlotCapacityForLevel(level)
    val availableSlots = slots.count { it.state == ComposterSlotState.EMPTY }
    val processingSlots = slots.count { it.state == ComposterSlotState.PROCESSING }
    val availableCropCount = availableCrops.sumOf { it.quantity }
    val upgradeCost = composterCapacityUpgradeCostForLevel(level)
    val maxed = level >= FARM_COMPOSTER_MAX_LEVEL
    val canUpgrade = !maxed && money >= upgradeCost.toLong()
    FarmUpgradeDialogFrame(
        title = stringResource(R.string.tama_farm_composter_menu_title),
        backgroundAssetPath = "file:///android_asset/farm/Others/composter_dialog_background.png",
        onDismiss = onDismiss,
        actions = {
            Button(
                onClick = onUpgrade,
                enabled = canUpgrade,
                modifier = Modifier.fillMaxWidth(),
                colors = farmPopupButtonColors()
            ) {
                Text(
                    if (maxed) {
                        stringResource(R.string.tama_farm_upgrade_maxed)
                    } else {
                        stringResource(R.string.tama_farm_composter_upgrade_action, upgradeCost)
                    }
                )
            }
        }
    ) {
        FarmInfoSection(title = stringResource(R.string.tama_farm_composter_status_section)) {
            Text(stringResource(R.string.tama_farm_composter_available_crops, availableCropCount), color = TamaDark)
            Text(
                stringResource(R.string.tama_farm_composter_ready_fertilizer, stored),
                fontWeight = FontWeight.Bold,
                color = TamaDark
            )
            Text(stringResource(R.string.tama_farm_composter_slots, availableSlots, slotCapacity), color = TamaDark)
            Text(stringResource(R.string.tama_farm_composter_processing, processingSlots), color = TamaMutedText)
            Text(stringResource(R.string.tama_farm_composter_level, level), color = TamaMutedText)
            Text(stringResource(R.string.tama_farm_composter_tile_hint), color = TamaMutedText)
        }
        FarmInfoSection(title = stringResource(R.string.tama_farm_composter_slots_section)) {
            FarmUtilityTileGrid(count = slotCapacity) { index ->
                val slot = slots.getOrElse(index) { ComposterSlot() }
                ComposterTileCard(
                    slot = slot,
                    now = now,
                    hasAvailableCrops = availableCropCount > 0,
                    onClick = { onTileClick(index, slot) }
                )
            }
        }
    }
}

@Composable
private fun FarmUtilityTileGrid(
    count: Int,
    content: @Composable (Int) -> Unit
) {
    val rows = remember(count) { (0 until count).toList().chunked(2) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                row.forEach { index ->
                    Box(modifier = Modifier.weight(1f)) {
                        content(index)
                    }
                }
                repeat(2 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FarmUpgradeDialogFrame(
    title: String,
    backgroundAssetPath: String,
    onDismiss: () -> Unit,
    actions: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.74f)),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.82f),
                shape = RoundedCornerShape(18.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = backgroundAssetPath,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alpha = 0.9f
                    )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF101010).copy(alpha = 0.74f))
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.24f))
                )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Text(
                            title,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 18.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            content = content
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        actions()
                        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                            Text(stringResource(R.string.action_close), color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FarmInfoSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
            color = Color(0xFFF9F8F3).copy(alpha = 0.99f),
            shape = RoundedCornerShape(14.dp)
        ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides TamaDark
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(title, fontWeight = FontWeight.Bold, color = TamaDark, fontSize = 15.sp)
                content()
            }
        }
    }
}

@Composable
private fun WellTileCard(
    slot: WellSlot,
    speedLevel: Int,
    now: Long,
    onClick: () -> Unit
) {
    val ready = wellTileIsReady(slot, speedLevel, now)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(enabled = ready, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EEE0).copy(alpha = 0.94f))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = if (ready) WELL_TILE_FULL_ASSET else WELL_TILE_EMPTY_ASSET,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Surface(
                color = Color.Black.copy(alpha = 0.64f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
            ) {
                Text(
                    text = wellTileStatusText(slot, speedLevel, now),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
            if (ready) {
                Surface(
                    color = Color(0xFF1E88E5).copy(alpha = 0.90f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.tama_farm_tile_collect),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposterTileCard(
    slot: ComposterSlot,
    now: Long,
    hasAvailableCrops: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    val processingItemName = slot.inputItemId?.let { FarmTradeItemCatalog.displayName(it, locale) }
        ?: context.getString(R.string.tama_item_rotten_crop)
    val processingAsset = FarmTradeItemCatalog.compostableAssetPath(slot.inputItemId ?: "rotten_crop")
        ?.let { "file:///android_asset/$it" }
        ?: "file:///android_asset/farm/Others/rotten_crop.png"
    val timerText = when (slot.state) {
        ComposterSlotState.PROCESSING -> formatComposterRemaining(slot.startedAt ?: now, now)
        ComposterSlotState.READY -> stringResource(R.string.tama_farm_composter_slot_ready)
        ComposterSlotState.EMPTY -> stringResource(R.string.tama_farm_composter_slot_empty_short)
    }
    val clickable = slot.state == ComposterSlotState.READY || (slot.state == ComposterSlotState.EMPTY && hasAvailableCrops)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(enabled = clickable, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EEE0).copy(alpha = 0.94f))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = COMPOSTER_TILE_ASSET,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            when (slot.state) {
                ComposterSlotState.EMPTY -> {
                    Text(
                        text = if (hasAvailableCrops) {
                            stringResource(R.string.tama_farm_tile_add_crop)
                        } else {
                            stringResource(R.string.tama_farm_composter_picker_empty)
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        color = TamaDark,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                ComposterSlotState.PROCESSING -> {
                    AsyncImage(
                        model = processingAsset,
                        contentDescription = processingItemName,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(top = 20.dp)
                            .size(76.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                ComposterSlotState.READY -> {
                    AsyncImage(
                        model = "file:///android_asset/farm/Others/fertilizer.png",
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(84.dp),
                        contentScale = ContentScale.Fit
                    )
                    Surface(
                        color = Color(0xFF7CB342).copy(alpha = 0.92f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.tama_farm_tile_collect),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            Surface(
                color = TamaDark.copy(alpha = 0.92f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
            ) {
                Text(
                    timerText,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun ComposterCropPickerDialog(
    crops: List<InventoryItem>,
    onDismiss: () -> Unit,
    onCropSelected: (InventoryItem) -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tama_farm_composter_picker_title)) },
        text = {
            if (crops.isEmpty()) {
                Text(stringResource(R.string.tama_farm_composter_picker_empty))
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(crops) { item ->
                        val assetPath = FarmTradeItemCatalog.compostableAssetPath(item.id)
                        ListItem(
                            modifier = Modifier.clickable { onCropSelected(item) },
                            leadingContent = {
                                if (assetPath != null) {
                                    AsyncImage(
                                        model = rememberFarmAssetModel("file:///android_asset/$assetPath"),
                                        contentDescription = inventoryItemDisplayName(context, item),
                                        modifier = Modifier
                                            .size(52.dp)
                                            .scale(FARM_MATURE_PREVIEW_SCALE),
                                        contentScale = ContentScale.Fit,
                                        filterQuality = androidx.compose.ui.graphics.FilterQuality.None
                                    )
                                }
                            },
                            headlineContent = { Text(inventoryItemDisplayName(context, item)) },
                            supportingContent = {
                                Text(stringResource(R.string.tama_farm_available_quantity, item.quantity))
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun farmPopupButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Color(0xFFF4F2E7),
    contentColor = TamaDark,
    disabledContainerColor = Color(0xFFCECAB9).copy(alpha = 0.9f),
    disabledContentColor = TamaDark.copy(alpha = 0.72f)
)

fun calculateRemaining(crop: PlantedCrop): String {
    val definitions = CropDefinitions.CROPS[crop.type] ?: return "???"
    val elapsed = System.currentTimeMillis() - crop.lastStageUpdateTime
    val remaining = definitions.stageTimes
        .mapIndexedNotNull { stageIndex, baseTime ->
            if (stageIndex < crop.stage || stageIndex >= 3) {
                null
            } else {
                val adjusted = if (crop.isFertilized) baseTime / 2 else baseTime
                if (stageIndex == crop.stage) maxOf(0L, adjusted - elapsed) else adjusted
            }
        }
        .sum()
    
    val hours = remaining / 3600000
    val minutes = (remaining % 3600000) / 60000
    return String.format("%02dh %02dm", hours, minutes)
}

private fun overallWellStatusText(
    context: android.content.Context,
    state: WellUpgradeState,
    capacity: Int,
    now: Long
): String {
    val readyCount = state.slots.count { wellTileIsReady(it, state.speedLevel, now) }
    return when {
        readyCount >= capacity -> context.getString(R.string.tama_farm_well_storage_full)
        readyCount > 0 -> context.getString(R.string.tama_farm_well_ready_tiles, readyCount, capacity)
        else -> {
            val nextReadyIn = state.slots
                .filterNot { wellTileIsReady(it, state.speedLevel, now) }
                .mapNotNull { slot ->
                    slot.cycleStartedAt?.let { startedAt ->
                        (startedAt + wellProductionIntervalForSpeedLevel(state.speedLevel) - now).coerceAtLeast(0L)
                    }
                }
                .minOrNull()
            if (nextReadyIn == null) {
                context.getString(R.string.tama_farm_well_ready_now)
            } else {
                context.getString(
                    R.string.tama_farm_well_next_water,
                    formatDurationToHoursMinutes(nextReadyIn)
                )
            }
        }
    }
}

private fun wellTileIsReady(
    slot: WellSlot,
    speedLevel: Int,
    now: Long
): Boolean {
    if (slot.hasWater) return true
    val startedAt = slot.cycleStartedAt ?: return false
    return now >= startedAt + wellProductionIntervalForSpeedLevel(speedLevel)
}

@Composable
private fun wellTileStatusText(
    slot: WellSlot,
    speedLevel: Int,
    now: Long
): String {
    return if (wellTileIsReady(slot, speedLevel, now)) {
        stringResource(R.string.tama_farm_well_ready_now)
    } else {
        val startedAt = slot.cycleStartedAt ?: now
        formatDurationToHoursMinutes(
            (startedAt + wellProductionIntervalForSpeedLevel(speedLevel) - now).coerceAtLeast(0L)
        )
    }
}

fun handleTileClick(
    tile: FarmTile,
    tool: InventoryItem?,
    gameEngine: TamaGameEngine,
    pet: TamaPet,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    onSeedPlantRequest: () -> Unit,
    onAction: (FarmTile) -> Unit
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
            if (canWaterFarmTile(tile)) {
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
        "water" -> {
            // Direct use of water item
            if (canWaterFarmTile(tile)) {
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
                onAction(tile.copy(crop = null, status = TileStatus.SOIL))
                scope.launch {
                    val result = gameEngine.harvestCrop(crop)
                    if (result.success && result.message.isNotBlank()) {
                        Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
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
    val context = LocalContext.current
    val seeds = inventory.filter { it.type == ItemType.SEED }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tama_farm_select_seed)) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 320.dp)
            ) {
                items(seeds) { seed ->
                    val cropType = seed.id.removePrefix("seed_")
                    ListItem(
                        leadingContent = {
                            AsyncImage(
                                model = rememberFarmAssetModel("file:///android_asset/farm/Crops/stage_final/$cropType.png"),
                                contentDescription = stringResource(R.string.tama_farm_mature_preview_description, inventoryItemDisplayName(context, seed)),
                                modifier = Modifier
                                    .size(58.dp)
                                    .scale(FARM_MATURE_PREVIEW_SCALE),
                                contentScale = ContentScale.Fit,
                                filterQuality = androidx.compose.ui.graphics.FilterQuality.None
                            )
                        },
                        headlineContent = { Text(inventoryItemDisplayName(context, seed)) },
                        supportingContent = { Text(stringResource(R.string.tama_farm_available_quantity, seed.quantity)) },
                        modifier = Modifier.clickable { onSeedSelected(seed) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

private fun formatComposterRemaining(startTime: Long, now: Long): String {
    val remaining = maxOf(0L, FARM_COMPOSTER_PROCESS_MS - (now - startTime))
    return formatDurationToHoursMinutes(remaining)
}

private fun formatDurationToHoursMinutes(durationMs: Long): String {
    val remaining = durationMs.coerceAtLeast(0L)
    val hours = remaining / 3_600_000L
    val minutes = (remaining % 3_600_000L) / 60_000L
    return String.format("%02dh %02dm", hours, minutes)
}
