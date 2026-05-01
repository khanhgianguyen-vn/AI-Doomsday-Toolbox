package com.example.llamadroid.tama.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import coil.compose.AsyncImage
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.*
import com.example.llamadroid.tama.db.FarmLivestockEntity
import com.example.llamadroid.tama.db.FarmUpgradeEntity
import com.example.llamadroid.tama.game.FARM_WELL_COST
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.data.FarmShopCatalog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

private const val FARM_STORE_ASSET_SCALE = 1f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreScreen(
    pet: TamaPet,
    farmRepository: FarmRepository,
    upgrades: List<FarmUpgradeEntity>,
    livestock: List<FarmLivestockEntity>,
    onBuy: suspend (InventoryItem, Int) -> TamaGameEngine.ActionResult,
    onSell: suspend (InventoryItem, Int) -> TamaGameEngine.ActionResult,
    onBuyUpgrade: suspend (String, Int) -> TamaGameEngine.ActionResult,
    onBuyLivestock: suspend (FarmLivestockType) -> TamaGameEngine.ActionResult,
    onBack: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.tama_farm_store_tab_seeds),
        stringResource(R.string.tama_farm_store_tab_tools),
        stringResource(R.string.tama_farm_store_tab_materials),
        stringResource(R.string.tama_farm_store_tab_upgrades),
        stringResource(R.string.tama_farm_store_tab_livestock),
        stringResource(R.string.tama_farm_store_tab_sell)
    )

    LaunchedEffect(pet.id) {
        while (true) {
            farmRepository.refreshFarmState(pet.id)
            delay(30_000L)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tama_farm_store_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    Text(
                        "${pet.money} 🪙",
                        modifier = Modifier.padding(end = 16.dp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = "file:///android_asset/tama/backgrounds/farm.png",
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.None
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.34f))
            )
            Column(modifier = Modifier.padding(padding)) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .clip(MaterialTheme.shapes.medium),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                ) {
                    TabRow(selectedTabIndex = selectedTab) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                text = { Text(title) }
                            )
                        }
                    }
                }

                when (selectedTab) {
                    0 -> SeedList(onBuy)
                    1 -> ToolList(onBuy)
                    2 -> MaterialList(onBuy)
                    3 -> UpgradeList(pet.money, upgrades, onBuyUpgrade)
                    4 -> LivestockList(livestock, onBuyLivestock)
                    5 -> SellList(pet.inventory, onSell)
                }
            }
        }
    }
}

@Composable
fun SeedList(onBuy: suspend (InventoryItem, Int) -> TamaGameEngine.ActionResult) {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    val seeds = CropDefinitions.CROPS.entries.toList()
    LazyColumn {
        items(seeds) { seed ->
            val cropId = seed.key
            val cropInfo = seed.value
            val seedLabel = seedDisplayText(cropId).resolve(locale)
            StoreItemRow(
                name = seedLabel,
                price = cropInfo.seedPrice,
                icon = "Crops/seed/$cropId.png",
                description = stringResource(R.string.tama_farm_store_growth, formatTime(cropInfo.stageTimes.sum())),
                onAction = { qty ->
                    onBuy(InventoryItem(
                        id = "seed_$cropId",
                        name = seedLabel,
                        type = ItemType.SEED
                    ), qty)
                }
            )
        }
    }
}

@Composable
fun ToolList(onBuy: suspend (InventoryItem, Int) -> TamaGameEngine.ActionResult) {
    val tools = listOf(
        Triple("Hoe", 100, "Others/hoe.png"),
        Triple("Watering Can", 150, "Others/watering_can.png")
    )
    LazyColumn {
        items(tools) { (name, price, icon) ->
            StoreItemRow(
                name = name,
                price = price,
                icon = icon,
                description = stringResource(R.string.tama_farm_store_durability_repairable),
                onAction = { qty ->
                    onBuy(InventoryItem(
                        id = name.lowercase().replace(" ", "_"),
                        name = name,
                        type = ItemType.TOOL,
                        durability = 100,
                        maxDurability = 100
                    ), qty)
                }
            )
        }
    }
}

@Composable
fun MaterialList(onBuy: suspend (InventoryItem, Int) -> TamaGameEngine.ActionResult) {
    val materials = listOf(
        Triple("Fertilizer", FarmShopCatalog.FERTILIZER_BUY_PRICE, "Others/fertilizer.png"),
        Triple("Water", 5, "Others/water.png")
    )
    LazyColumn {
        items(materials) { (name, price, icon) ->
            StoreItemRow(
                name = name,
                price = price,
                icon = icon,
                description = stringResource(R.string.tama_farm_store_material_desc),
                onAction = { qty ->
                    onBuy(InventoryItem(
                        id = name.lowercase(),
                        name = name,
                        type = ItemType.MATERIAL
                    ), qty)
                }
            )
        }
    }
}

@Composable
fun UpgradeList(
    petMoney: Long,
    upgrades: List<FarmUpgradeEntity>,
    onBuyUpgrade: suspend (String, Int) -> TamaGameEngine.ActionResult
) {
    val upgradeItems = listOf(
        Triple("well", stringResource(R.string.tama_farm_store_upgrade_well_desc), FARM_WELL_COST),
        Triple("composter", stringResource(R.string.tama_farm_store_upgrade_composter_desc), 800)
    )
    LazyColumn {
        items(upgradeItems) { (type, desc, price) ->
            val existingUpgrade = upgrades.firstOrNull { it.type.equals(type, ignoreCase = true) }
            val canBuy = petMoney >= price && existingUpgrade?.isPurchased != true
            StoreItemRow(
                name = if (type == "well") stringResource(R.string.tama_farm_upgrade_well) else stringResource(R.string.tama_farm_upgrade_composter),
                price = price,
                icon = "Others/$type.png",
                description = desc,
                showQuantityControls = false,
                actionEnabled = canBuy,
                onAction = { onBuyUpgrade(type, price) }
            )
        }
    }
}

@Composable
fun LivestockList(
    livestock: List<FarmLivestockEntity>,
    onBuyLivestock: suspend (FarmLivestockType) -> TamaGameEngine.ActionResult
) {
    LazyColumn {
        items(FarmLivestockType.entries) { type ->
            val entity = livestock.firstOrNull { it.type == type.id }
            val slots = remember(entity?.slotsJson) { runCatching { entity?.let { Json.decodeFromString<List<FarmLivestockSlot>>(it.slotsJson) } }.getOrNull() ?: emptyLivestockSlots(type) }
            val occupied = occupiedLivestockCount(slots)
            val remaining = (type.maxAnimals - occupied).coerceAtLeast(0)
            StoreItemRow(
                name = if (type == FarmLivestockType.BARN) stringResource(R.string.tama_farm_livestock_cow) else stringResource(R.string.tama_farm_livestock_chicken),
                price = type.buyPrice,
                icon = type.animalAssetPath.removePrefix("farm/"),
                description = stringResource(
                    if (type == FarmLivestockType.BARN) R.string.tama_farm_livestock_cow_desc else R.string.tama_farm_livestock_chicken_desc,
                    occupied,
                    type.maxAnimals
                ),
                showQuantityControls = false,
                actionEnabled = remaining > 0,
                onAction = { onBuyLivestock(type) }
            )
        }
    }
}

@Composable
fun SellList(inventory: List<InventoryItem>, onSell: suspend (InventoryItem, Int) -> TamaGameEngine.ActionResult) {
    val context = LocalContext.current
    val sellable = inventory.filter { it.type == ItemType.CROP && FarmTradeItemCatalog.isTradeItem(it.id) }
    if (sellable.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.tama_farm_store_no_trade_items), color = Color.Gray)
        }
    } else {
        LazyColumn {
            items(sellable) { item ->
                val sellPrice = FarmTradeItemCatalog.sellPrice(item.id).coerceAtLeast(5)
                
                StoreItemRow(
                    name = inventoryItemDisplayName(context, item),
                    price = sellPrice,
                    icon = FarmTradeItemCatalog.assetPath(item.id)?.removePrefix("farm/") ?: "Others/soil.png",
                    description = stringResource(R.string.tama_farm_store_owned, item.quantity),
                    isSelling = true,
                    maxQty = item.quantity,
                    onAction = { qty -> onSell(item, qty) }
                )
            }
        }
    }
}

@Composable
fun StoreItemRow(
    name: String,
    price: Int,
    icon: String,
    description: String,
    isSelling: Boolean = false,
    maxQty: Int = 99,
    showQuantityControls: Boolean = true,
    actionEnabled: Boolean = true,
    onAction: suspend (Int) -> TamaGameEngine.ActionResult
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var qty by remember { mutableIntStateOf(1) }

    ListItem(
        headlineContent = { Text(name, fontWeight = FontWeight.Bold) },
        supportingContent = { Text(description, fontSize = 12.sp) },
        leadingContent = {
            val assetUri = remember(icon) { "file:///android_asset/farm/$icon" }
            AsyncImage(
                model = rememberFarmAssetModel(assetUri),
                contentDescription = name,
                modifier = Modifier
                    .size(56.dp)
                    .scale(FARM_STORE_ASSET_SCALE),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None
            )
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text("${price * qty} 🪙", fontWeight = FontWeight.Bold, color = if (isSelling) Color(0xFF43A047) else Color(0xFFE65100))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showQuantityControls) {
                        IconButton(onClick = { if (qty > 1) qty-- }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Remove, null)
                        }
                        Text(qty.toString(), modifier = Modifier.padding(horizontal = 4.dp))
                        IconButton(onClick = { if (qty < maxQty) qty++ }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Add, null)
                        }
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                val result = onAction(qty)
                                if (result.success) {
                                    qty = 1
                                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = actionEnabled,
                        modifier = Modifier.height(32.dp).padding(start = if (showQuantityControls) 8.dp else 0.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelling) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            if (isSelling) stringResource(R.string.tama_farm_store_sell) else stringResource(R.string.tama_farm_store_buy),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    )
}

fun formatTime(ms: Long): String {
    val hours = ms / 3600000
    return "${hours}h"
}
