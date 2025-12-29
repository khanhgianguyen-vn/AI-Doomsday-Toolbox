package com.example.llamadroid.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.ui.navigation.Screen

data class AIToolItem(
    val emoji: String,
    val title: String,
    val description: String,
    val gradientColors: List<Color>,
    val route: String
)

/**
 * AI Hub - 2-column square grid layout
 * Notes Manager moved to bottom nav bar
 */
@Composable
fun AIHubScreen(navController: NavController) {
    val aiTools = listOf(
        AIToolItem(
            emoji = "💬",
            title = stringResource(R.string.ai_chat),
            description = stringResource(R.string.ai_chat_desc),
            gradientColors = listOf(
                Color(0xFF4CAF50).copy(alpha = 0.15f),
                Color(0xFF388E3C).copy(alpha = 0.3f)
            ),
            route = Screen.Chat.route
        ),
        AIToolItem(
            emoji = "🎨",
            title = stringResource(R.string.ai_image_gen),
            description = stringResource(R.string.ai_image_gen_desc),
            gradientColors = listOf(
                Color(0xFF2196F3).copy(alpha = 0.15f),
                Color(0xFF1976D2).copy(alpha = 0.3f)
            ),
            route = Screen.ImageGen.route
        ),
        AIToolItem(
            emoji = "🎙️",
            title = stringResource(R.string.ai_transcription),
            description = stringResource(R.string.ai_transcription_desc),
            gradientColors = listOf(
                Color(0xFF00BCD4).copy(alpha = 0.15f),
                Color(0xFF00ACC1).copy(alpha = 0.3f)
            ),
            route = Screen.AudioTranscription.route
        ),
        AIToolItem(
            emoji = "🎬",
            title = stringResource(R.string.ai_video_upscaler),
            description = stringResource(R.string.ai_video_upscaler_desc),
            gradientColors = listOf(
                Color(0xFF9C27B0).copy(alpha = 0.15f),
                Color(0xFF7B1FA2).copy(alpha = 0.3f)
            ),
            route = Screen.VideoUpscaler.route
        ),
        AIToolItem(
            emoji = "📄",
            title = stringResource(R.string.ai_pdf_tools),
            description = stringResource(R.string.ai_pdf_tools_desc),
            gradientColors = listOf(
                Color(0xFFE91E63).copy(alpha = 0.15f),
                Color(0xFFC2185B).copy(alpha = 0.3f)
            ),
            route = "pdf_toolbox"
        ),
        AIToolItem(
            emoji = "🎥",
            title = stringResource(R.string.ai_video_sumup),
            description = stringResource(R.string.ai_video_sumup_desc),
            gradientColors = listOf(
                Color(0xFFFF5722).copy(alpha = 0.15f),
                Color(0xFFE64A19).copy(alpha = 0.3f)
            ),
            route = "video_sumup"
        ),
        AIToolItem(
            emoji = "⚙️",
            title = "Workflows",
            description = "Chain AI operations",
            gradientColors = listOf(
                Color(0xFF607D8B).copy(alpha = 0.15f),
                Color(0xFF455A64).copy(alpha = 0.3f)
            ),
            route = Screen.Workflows.route
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                )
            )
    ) {
        // Header
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "🤖 " + stringResource(R.string.ai_hub_title),
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp
                )
            )
            Text(
                stringResource(R.string.ai_hub_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // 2-column grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(aiTools) { item ->
                AISquareCard(
                    emoji = item.emoji,
                    title = item.title,
                    description = item.description,
                    gradientColors = item.gradientColors,
                    enabled = item.route.isNotEmpty(),
                    onClick = { 
                        if (item.route.isNotEmpty()) {
                            navController.navigate(item.route) 
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun AISquareCard(
    emoji: String,
    title: String,
    description: String,
    gradientColors: List<Color>,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .aspectRatio(1f) // Square
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) 
                MaterialTheme.colorScheme.surface 
            else 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (enabled) 4.dp else 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(brush = Brush.verticalGradient(gradientColors))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Emoji
                Text(
                    emoji,
                    style = MaterialTheme.typography.displaySmall,
                    fontSize = 48.sp
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Title
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (enabled) 
                        MaterialTheme.colorScheme.onSurface 
                    else 
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                
                // Description
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (enabled) 1f else 0.5f
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
