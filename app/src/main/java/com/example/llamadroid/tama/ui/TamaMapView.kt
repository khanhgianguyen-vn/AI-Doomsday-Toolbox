package com.example.llamadroid.tama.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.tama.data.TamaLocation
import com.example.llamadroid.tama.data.LocationType

/**
 * Map view showing locations in a city grid.
 */
@Composable
fun TamaMapView(
    cityName: String,
    locations: List<TamaLocation>,
    currentLocation: TamaLocation?,
    discoveredLocationIds: Set<String>,
    onLocationClick: (TamaLocation) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TamaLight)
            .padding(8.dp)
    ) {
        // City name header
        Text(
            text = cityName,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = TamaDark,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // 5x5 grid of locations
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (y in 0 until 5) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (x in 0 until 5) {
                        val location = locations.find { it.x == x && it.y == y }
                        // Home is always discovered
                        val isDiscovered = location != null && (
                            location.type == com.example.llamadroid.tama.data.LocationType.HOME ||
                            discoveredLocationIds.contains(location.id)
                        )
                        LocationTile(
                            location = location,
                            isCurrentLocation = location?.id == currentLocation?.id,
                            isDiscovered = isDiscovered,
                            onClick = { location?.let { onLocationClick(it) } },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        
        // Legend
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            LegendItem("🏠", "Home")
            LegendItem("🏪", "Shop")
            LegendItem("🌳", "Park")
            LegendItem("❓", "Unknown")
        }
    }
}

@Composable
fun LocationTile(
    location: TamaLocation?,
    isCurrentLocation: Boolean,
    isDiscovered: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = when {
        isCurrentLocation -> Color(0xFF8BC34A)  // Green for current
        isDiscovered -> TamaLight
        location != null -> Color(0xFFC0C0C0)  // Sillier gray for undiscovered
        else -> TamaBackground  // Empty tile
    }
    
    val borderColor = if (isCurrentLocation) TamaDark else Color.Transparent
    
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .border(2.dp, borderColor, RoundedCornerShape(4.dp))
            .clickable(enabled = location != null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (location != null) {
            Text(
                text = if (isDiscovered) location.type.emoji else "❓",
                fontSize = 16.sp,
                color = if (isDiscovered) TamaDark else Color.Gray
            )
        }
    }
}

@Composable
fun LegendItem(emoji: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 12.sp)
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = TamaAccent
        )
    }
}

/**
 * Dialog showing location details and actions.
 */
@Composable
fun LocationDetailsDialog(
    location: TamaLocation,
    isCurrentLocation: Boolean,
    petEnergy: Int,
    travelCost: Int,
    onTravel: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(location.type.emoji, fontSize = 24.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = location.name,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column {
                Text(
                    text = location.description,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                if (!isCurrentLocation) {
                    Text(
                        text = "⚡ Travel cost: $travelCost energy",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = if (petEnergy >= travelCost) TamaAccent else Color.Red
                    )
                } else {
                    Text(
                        text = "📍 You are here",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color(0xFF4CAF50)
                    )
                }
                
                // Show location-specific info
                if (location.type == LocationType.SHOP && location.shopInventory != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "🛍️ ${location.shopInventory.size} items available",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
                
                if (location.type == LocationType.WORKPLACE && location.jobs != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "💼 ${location.jobs.size} jobs available",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            if (!isCurrentLocation) {
                TextButton(
                    onClick = onTravel,
                    enabled = petEnergy >= travelCost
                ) {
                    Text("Travel Here")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
