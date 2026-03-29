package com.example.llamadroid.tama.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.llamadroid.tama.data.GeneticTraits
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.Mood
import com.example.llamadroid.tama.sprites.SpritePatterns

/**
 * Retro pixel art renderer.
 * Draws sprites from text patterns where each character is a pixel.
 */

// Pixel colors
val PixelBlack = Color(0xFF1A1A1A)       // Main sprite color
val PixelWhite = Color(0xFFF5F5F0)       // Highlights
val PixelBlush = Color(0xFFE8B4B4)       // Cheeks
val PixelTransparent = Color.Transparent

@Composable
fun PixelPetSprite(
    stage: GrowthStage,
    mood: Mood,
    action: String = "idle",
    isSleeping: Boolean = false,
    genetics: GeneticTraits = GeneticTraits.random(),
    isMad: Boolean = false,
    pixelSize: Dp = 8.dp,
    modifier: Modifier = Modifier
) {
    // Animation frame for bouncing/walking
    val infiniteTransition = rememberInfiniteTransition(label = "sprite_anim")
    val frame by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "frame"
    )
    
    // Bounce animation
    val bounceOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bounce"
    )
    
    val currentAction = if (isSleeping) "sleeping" else action
    
    // Use genetic sprite generator
    val sprite = SpritePatterns.generateSprite(
        stage = stage.name,
        genetics = genetics,
        mood = mood.name,
        action = currentAction,
        isMad = isMad
    )
    
    val lines = sprite.lines()
    val width = lines.maxOfOrNull { it.length } ?: 8
    val height = lines.size
    
    Canvas(
        modifier = modifier.size(
            width = pixelSize * width,
            height = pixelSize * height
        )
    ) {
        val pxSize = pixelSize.toPx()
        val bounceY = if (!isSleeping && action == "idle") bounceOffset * pxSize * 0.5f else 0f
        
        lines.forEachIndexed { y, line ->
            line.forEachIndexed { x, char ->
                val color = when (char) {
                    '#' -> PixelBlack
                    'O' -> PixelWhite
                    '@' -> PixelBlush
                    // Expression characters
                    '^', 'v', 'w', 'U', 'n', 'o' -> PixelBlack
                    '-', 'T', 'x', '>', '<', '*', '!' -> PixelBlack
                    '~', '_' -> PixelBlack
                    'Z' -> PixelBlack  // Sleep Z
                    else -> PixelTransparent
                }
                
                if (color != PixelTransparent) {
                    drawRect(
                        color = color,
                        topLeft = Offset(x * pxSize, y * pxSize - bounceY),
                        size = Size(pxSize, pxSize)
                    )
                }
            }
        }
    }
}

/**
 * Draw the pet's room background when sleeping.
 */
@Composable
fun PixelScene(
    locationId: String,
    locationType: String? = null,  // Optional: pass type directly
    pixelSize: Dp = 6.dp,
    modifier: Modifier = Modifier
) {
    val sceneKey = locationType?.lowercase() ?: when {
        locationId.contains("home", ignoreCase = true) -> "home"
        locationId.contains("shop", ignoreCase = true) -> "shop"
        locationId.contains("park", ignoreCase = true) -> "park"
        locationId.contains("school", ignoreCase = true) -> "school"
        locationId.contains("office", ignoreCase = true) || locationId.contains("work", ignoreCase = true) -> "office"
        locationId.contains("hospital", ignoreCase = true) -> "hospital"
        else -> "home"
    }
    
    val scene = SpritePatterns.LOCATION_SCENES[sceneKey] ?: SpritePatterns.LOCATION_SCENES["home"]!!
    val patternWidth = scene.maxOfOrNull { it.length } ?: 16
    val patternHeight = scene.size
    
    // Fill the available space and repeat/scale the pattern
    Canvas(
        modifier = modifier.fillMaxSize()
    ) {
        val availableWidth = size.width
        val availableHeight = size.height
        
        // Calculate pixel size to fill the space nicely (at least 12dp equivalent)
        val targetPixelSize = maxOf(availableWidth / patternWidth, availableHeight / patternHeight, 36f)
        
        // Center the pattern
        val totalPatternWidth = patternWidth * targetPixelSize
        val totalPatternHeight = patternHeight * targetPixelSize
        val offsetX = (availableWidth - totalPatternWidth) / 2
        val offsetY = (availableHeight - totalPatternHeight) / 2
        
        scene.forEachIndexed { y, line ->
            line.forEachIndexed { x, char ->
                val color = when (char) {
                    '#' -> PixelBlack.copy(alpha = 0.5f)
                    '|', '=', '-', '_', '[', ']' -> PixelBlack.copy(alpha = 0.35f)
                    '/', '\\' -> PixelBlack.copy(alpha = 0.35f)
                    '+' -> Color.Red.copy(alpha = 0.25f)
                    'O' -> Color.Green.copy(alpha = 0.25f)
                    ' ' -> Color.White.copy(alpha = 0.05f)
                    else -> PixelTransparent
                }
                
                if (color != PixelTransparent) {
                    drawRect(
                        color = color,
                        topLeft = Offset(offsetX + x * targetPixelSize, offsetY + y * targetPixelSize),
                        size = Size(targetPixelSize, targetPixelSize)
                    )
                }
            }
        }
    }
}

@Composable
fun PixelRoom(
    type: String = "bedroom",
    pixelSize: Dp = 6.dp,
    modifier: Modifier = Modifier
) {
    val pattern = SpritePatterns.ROOM_BED
    val lines = pattern.lines()
    val width = lines.maxOfOrNull { it.length } ?: 16
    val height = lines.size
    
    Canvas(
        modifier = modifier.size(
            width = pixelSize * width,
            height = pixelSize * height
        )
    ) {
        val pxSize = pixelSize.toPx()
        
        lines.forEachIndexed { y, line ->
            line.forEachIndexed { x, char ->
                val color = when (char) {
                    '#' -> PixelBlack
                    'Z' -> PixelBlack
                    else -> PixelTransparent
                }
                
                if (color != PixelTransparent) {
                    drawRect(
                        color = color,
                        topLeft = Offset(x * pxSize, y * pxSize),
                        size = Size(pxSize, pxSize)
                    )
                }
            }
        }
    }
}

/**
 * Animated eating sprite.
 */
@Composable
fun PixelEatingAnimation(
    stage: GrowthStage,
    mood: Mood,
    pixelSize: Dp = 8.dp,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var frame by remember { mutableStateOf(0) }
    
    LaunchedEffect(Unit) {
        repeat(4) {
            kotlinx.coroutines.delay(300)
            frame++
        }
        onComplete()
    }
    
    PixelPetSprite(
        stage = stage,
        mood = mood,
        action = "eating",
        pixelSize = pixelSize,
        modifier = modifier
    )
}
