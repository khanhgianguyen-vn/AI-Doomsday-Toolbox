package com.example.llamadroid.tama.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.llamadroid.tama.data.*
import com.example.llamadroid.tama.game.FarmRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreScreen(
    pet: TamaPet,
    onBuy: (InventoryItem, Int) -> Unit,
    onSell: (InventoryItem, Int) -> Unit,
    onBuyUpgrade: (String, Int) -> Unit,
    onBack: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Seeds", "Tools", "Materials", "Upgrades", "Sell")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Farm Store") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
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
        Column(modifier = Modifier.padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            when (selectedTab) {
                0 -> SeedList(onBuy)
                1 -> ToolList(onBuy)
                2 -> MaterialList(onBuy)
                3 -> UpgradeList(onBuyUpgrade)
                4 -> SellList(pet.inventory, onSell)
            }
        }
    }
}

@Composable
fun SeedList(onBuy: (InventoryItem, Int) -> Unit) {
    val seeds = CropDefinitions.CROPS.values.toList()
    LazyColumn {
        items(seeds) { seed ->
            StoreItemRow(
                name = "${seed.name} Seeds",
                price = seed.seedPrice,
                icon = "Crops/seed/${seed.name.lowercase()}.png",
                description = "Growth: ${formatTime(seed.stageTimes.sum())}",
                onAction = { qty ->
                    onBuy(InventoryItem(
                        id = "seed_${seed.name.lowercase()}",
                        name = "${seed.name} Seeds",
                        type = ItemType.SEED
                    ), qty)
                }
            )
        }
    }
}

@Composable
fun ToolList(onBuy: (InventoryItem, Int) -> Unit) {
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
                description = "Durability: 100/100 (Repairable)",
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
fun MaterialList(onBuy: (InventoryItem, Int) -> Unit) {
    val materials = listOf(
        Triple("Fertilizer", 20, "Others/fertilizer.png"),
        Triple("Water", 5, "Others/water.png")
    )
    LazyColumn {
        items(materials) { (name, price, icon) ->
            StoreItemRow(
                name = name,
                price = price,
                icon = icon,
                description = "Enhances growth or hydration",
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
fun UpgradeList(onBuyUpgrade: (String, Int) -> Unit) {
    val upgrades = listOf(
        Triple("Well", "Automatically collects water. Max 10 units.", 500),
        Triple("Composter", "Produces fertilizer over time.", 800)
    )
    LazyColumn {
        items(upgrades) { (name, desc, price) ->
            StoreItemRow(
                name = name,
                price = price,
                icon = "Others/${name.lowercase()}.png",
                description = desc,
                onAction = { onBuyUpgrade(name.lowercase(), price) }
            )
        }
    }
}

@Composable
fun SellList(inventory: List<InventoryItem>, onSell: (InventoryItem, Int) -> Unit) {
    val sellable = inventory.filter { it.type == ItemType.CROP }
    if (sellable.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No crops to sell", color = Color.Gray)
        }
    } else {
        LazyColumn {
            items(sellable) { item ->
                val baseCrop = CropDefinitions.CROPS[item.id.replace("crop_", "")]
                val sellPrice = baseCrop?.sellPrice ?: 5
                
                StoreItemRow(
                    name = item.name,
                    price = sellPrice,
                    icon = "Crops/stage_final/${item.id.replace("crop_", "")}.png",
                    description = "Owned: ${item.quantity}",
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
    onAction: (Int) -> Unit
) {
    var qty by remember { mutableIntStateOf(1) }

    ListItem(
        headlineContent = { Text(name, fontWeight = FontWeight.Bold) },
        supportingContent = { Text(description, fontSize = 12.sp) },
        leadingContent = {
            AsyncImage(
                model = "file:///android_asset/farm/$icon",
                contentDescription = name,
                modifier = Modifier.size(48.dp)
            )
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text("${price * qty} 🪙", fontWeight = FontWeight.Bold, color = if (isSelling) Color(0xFF43A047) else Color(0xFFE65100))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (qty > 1) qty-- }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Remove, null)
                    }
                    Text(qty.toString(), modifier = Modifier.padding(horizontal = 4.dp))
                    IconButton(onClick = { if (qty < maxQty) qty++ }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Add, null)
                    }
                    Button(
                        onClick = { onAction(qty) },
                        modifier = Modifier.height(32.dp).padding(start = 8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelling) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(if (isSelling) "Sell" else "Buy", fontSize = 12.sp)
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
