package com.example.llamadroid.tama.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.llamadroid.tama.data.GeneticTraits
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.Mood
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Canvas-based pet renderer that draws cute pets using geometric shapes.
 * Uses GeneticTraits to vary appearance without needing image assets.
 */

// Color palette (retro LCD style)
val PetBodyLight = Color(0xFFE8E8D0)      // Light cream
val PetBodyDark = Color(0xFF2C2C2C)       // Dark outline
val PetBodyMid = Color(0xFF8B8B7A)        // Mid tone for shadows
val PetBlush = Color(0xFFD4A0A0)          // Blush cheeks
val PetHighlight = Color(0xFFF5F5E8)      // Highlight spots

@Composable
fun PetCanvas(
    stage: GrowthStage,
    genetics: GeneticTraits,
    mood: Mood,
    isMad: Boolean = false,
    modifier: Modifier = Modifier
) {
    val size = when (stage) {
        GrowthStage.EGG -> 80.dp
        GrowthStage.BABY -> 100.dp
        GrowthStage.CHILD -> 120.dp
        GrowthStage.TEEN -> 140.dp
        GrowthStage.ADULT -> 160.dp
        GrowthStage.SENIOR -> 160.dp
    }
    
    Canvas(
        modifier = modifier.size(size)
    ) {
        when (stage) {
            GrowthStage.EGG -> drawEgg(genetics)
            GrowthStage.BABY -> drawBaby(genetics, mood, isMad)
            GrowthStage.CHILD -> drawChild(genetics, mood, isMad)
            GrowthStage.TEEN -> drawTeen(genetics, mood, isMad)
            GrowthStage.ADULT -> drawAdult(genetics, mood, isMad)
            GrowthStage.SENIOR -> drawSenior(genetics, mood, isMad)
        }
    }
}

// ==================== EGG ====================

private fun DrawScope.drawEgg(genetics: GeneticTraits) {
    val centerX = size.width / 2
    val centerY = size.height / 2
    val eggWidth = size.width * 0.6f
    val eggHeight = size.height * 0.8f
    
    // Egg body (oval)
    drawOval(
        color = PetBodyLight,
        topLeft = Offset(centerX - eggWidth/2, centerY - eggHeight/2),
        size = Size(eggWidth, eggHeight)
    )
    
    // Egg outline
    drawOval(
        color = PetBodyDark,
        topLeft = Offset(centerX - eggWidth/2, centerY - eggHeight/2),
        size = Size(eggWidth, eggHeight),
        style = Stroke(width = 3f)
    )
    
    // Question mark or pattern based on genetics
    val patternType = genetics.bodyStyle % 4
    when (patternType) {
        0 -> drawQuestionMark(centerX, centerY)
        1 -> drawEggSpots(centerX, centerY, eggWidth, eggHeight)
        2 -> drawEggStripes(centerX, centerY, eggWidth, eggHeight)
        3 -> drawEggHeart(centerX, centerY)
    }
}

private fun DrawScope.drawQuestionMark(cx: Float, cy: Float) {
    val path = Path().apply {
        moveTo(cx - 8f, cy - 15f)
        cubicTo(cx - 8f, cy - 25f, cx + 10f, cy - 25f, cx + 10f, cy - 12f)
        cubicTo(cx + 10f, cy - 5f, cx, cy - 5f, cx, cy + 2f)
    }
    drawPath(path, PetBodyDark, style = Stroke(width = 4f, cap = StrokeCap.Round))
    drawCircle(PetBodyDark, 3f, Offset(cx, cy + 12f))
}

private fun DrawScope.drawEggSpots(cx: Float, cy: Float, w: Float, h: Float) {
    drawCircle(PetBodyMid, 8f, Offset(cx - 15f, cy - 10f))
    drawCircle(PetBodyMid, 6f, Offset(cx + 12f, cy + 5f))
    drawCircle(PetBodyMid, 5f, Offset(cx - 5f, cy + 15f))
}

private fun DrawScope.drawEggStripes(cx: Float, cy: Float, w: Float, h: Float) {
    for (i in -1..1) {
        drawLine(
            PetBodyMid,
            Offset(cx - 15f, cy + i * 12f),
            Offset(cx + 15f, cy + i * 12f),
            strokeWidth = 3f
        )
    }
}

private fun DrawScope.drawEggHeart(cx: Float, cy: Float) {
    val heartPath = Path().apply {
        moveTo(cx, cy + 5f)
        cubicTo(cx - 15f, cy - 10f, cx - 15f, cy - 20f, cx, cy - 10f)
        cubicTo(cx + 15f, cy - 20f, cx + 15f, cy - 10f, cx, cy + 5f)
    }
    drawPath(heartPath, PetBlush, style = Fill)
}

// ==================== BABY ====================

private fun DrawScope.drawBaby(genetics: GeneticTraits, mood: Mood, isMad: Boolean) {
    val cx = size.width / 2
    val cy = size.height / 2
    val headRadius = size.width * 0.35f
    
    // Body (small blob under head)
    drawOval(
        PetBodyLight,
        Offset(cx - headRadius * 0.6f, cy + headRadius * 0.3f),
        Size(headRadius * 1.2f, headRadius * 0.8f)
    )
    
    // Head (big circle - babies have big heads)
    drawCircle(PetBodyLight, headRadius, Offset(cx, cy))
    drawCircle(PetBodyDark, headRadius, Offset(cx, cy), style = Stroke(3f))
    
    // Ears based on genetics
    drawEars(cx, cy - headRadius * 0.7f, headRadius * 0.4f, genetics.earStyle, small = true)
    
    // Face
    drawFace(cx, cy, headRadius * 0.5f, genetics, mood, baby = true, isMad = isMad)
    
    // Blush
    drawBlush(cx, cy, headRadius * 0.3f)
}

// ==================== CHILD ====================

private fun DrawScope.drawChild(genetics: GeneticTraits, mood: Mood, isMad: Boolean) {
    val cx = size.width / 2
    val cy = size.height / 2
    val headRadius = size.width * 0.28f
    val bodyHeight = size.height * 0.35f
    
    // Body
    val bodyTop = cy + headRadius * 0.5f
    drawOval(
        PetBodyLight,
        Offset(cx - headRadius * 0.8f, bodyTop),
        Size(headRadius * 1.6f, bodyHeight)
    )
    drawOval(
        PetBodyDark,
        Offset(cx - headRadius * 0.8f, bodyTop),
        Size(headRadius * 1.6f, bodyHeight),
        style = Stroke(2f)
    )
    
    // Little arms
    drawArms(cx, bodyTop + bodyHeight * 0.2f, headRadius * 0.5f, genetics.armStyle, small = true)
    
    // Little feet
    drawFeet(cx, bodyTop + bodyHeight - 5f, headRadius * 0.3f, genetics.legStyle, small = true)
    
    // Head
    drawCircle(PetBodyLight, headRadius, Offset(cx, cy - headRadius * 0.2f))
    drawCircle(PetBodyDark, headRadius, Offset(cx, cy - headRadius * 0.2f), style = Stroke(2.5f))
    
    // Ears
    drawEars(cx, cy - headRadius * 1.1f, headRadius * 0.35f, genetics.earStyle, small = false)
    
    // Face
    drawFace(cx, cy - headRadius * 0.2f, headRadius * 0.6f, genetics, mood, baby = false, isMad = isMad)
    
    // Blush
    drawBlush(cx, cy - headRadius * 0.15f, headRadius * 0.35f)
}

// ==================== TEEN ====================

private fun DrawScope.drawTeen(genetics: GeneticTraits, mood: Mood, isMad: Boolean) {
    val cx = size.width / 2
    val cy = size.height / 2
    val headRadius = size.width * 0.22f
    val bodyHeight = size.height * 0.4f
    
    // Body (taller, thinner)
    val bodyTop = cy + headRadius * 0.3f
    val bodyPath = Path().apply {
        moveTo(cx - headRadius * 0.7f, bodyTop)
        lineTo(cx - headRadius * 0.9f, bodyTop + bodyHeight)
        lineTo(cx + headRadius * 0.9f, bodyTop + bodyHeight)
        lineTo(cx + headRadius * 0.7f, bodyTop)
        close()
    }
    drawPath(bodyPath, PetBodyLight)
    drawPath(bodyPath, PetBodyDark, style = Stroke(2f))
    
    // Arms
    drawArms(cx, bodyTop + bodyHeight * 0.15f, headRadius * 0.6f, genetics.armStyle, small = false)
    
    // Legs
    drawLegs(cx, bodyTop + bodyHeight - 5f, headRadius * 0.4f, genetics.legStyle)
    
    // Head
    drawCircle(PetBodyLight, headRadius, Offset(cx, cy - headRadius * 0.5f))
    drawCircle(PetBodyDark, headRadius, Offset(cx, cy - headRadius * 0.5f), style = Stroke(2.5f))
    
    // Ears
    drawEars(cx, cy - headRadius * 1.4f, headRadius * 0.4f, genetics.earStyle, small = false)
    
    // Face
    drawFace(cx, cy - headRadius * 0.5f, headRadius * 0.65f, genetics, mood, baby = false, isMad = isMad)
    
    // Blush
    drawBlush(cx, cy - headRadius * 0.45f, headRadius * 0.35f)
}

// ==================== ADULT ====================

private fun DrawScope.drawAdult(genetics: GeneticTraits, mood: Mood, isMad: Boolean) {
    val cx = size.width / 2
    val cy = size.height / 2
    val headRadius = size.width * 0.18f
    val bodyHeight = size.height * 0.45f
    
    // Body (fuller, more defined)
    val bodyTop = cy + headRadius * 0.2f
    val bodyWidth = headRadius * 1.8f
    
    drawOval(
        PetBodyLight,
        Offset(cx - bodyWidth / 2, bodyTop),
        Size(bodyWidth, bodyHeight)
    )
    drawOval(
        PetBodyDark,
        Offset(cx - bodyWidth / 2, bodyTop),
        Size(bodyWidth, bodyHeight),
        style = Stroke(2.5f)
    )
    
    // Arms
    drawArms(cx, bodyTop + bodyHeight * 0.15f, headRadius * 0.7f, genetics.armStyle, small = false)
    
    // Legs
    drawLegs(cx, bodyTop + bodyHeight - 8f, headRadius * 0.45f, genetics.legStyle)
    
    // Head
    drawCircle(PetBodyLight, headRadius, Offset(cx, cy - headRadius * 0.7f))
    drawCircle(PetBodyDark, headRadius, Offset(cx, cy - headRadius * 0.7f), style = Stroke(2.5f))
    
    // Ears
    drawEars(cx, cy - headRadius * 1.6f, headRadius * 0.45f, genetics.earStyle, small = false)
    
    // Face
    drawFace(cx, cy - headRadius * 0.7f, headRadius * 0.7f, genetics, mood, baby = false, isMad = isMad)
    
    // Blush
    drawBlush(cx, cy - headRadius * 0.65f, headRadius * 0.35f)
}

// ==================== SENIOR ====================

private fun DrawScope.drawSenior(genetics: GeneticTraits, mood: Mood, isMad: Boolean) {
    // Draw adult first
    drawAdult(genetics, mood, isMad)
    
    val cx = size.width / 2
    val headRadius = size.width * 0.18f
    val cy = size.height / 2
    
    // Add wrinkles/age marks
    val wrinkleY = cy - headRadius * 1.2f
    drawLine(PetBodyMid, Offset(cx - 8f, wrinkleY), Offset(cx - 3f, wrinkleY), strokeWidth = 1.5f)
    drawLine(PetBodyMid, Offset(cx + 3f, wrinkleY), Offset(cx + 8f, wrinkleY), strokeWidth = 1.5f)
    
    // Halo/wisdom aura
    drawCircle(PetHighlight.copy(alpha = 0.3f), headRadius * 1.3f, Offset(cx, cy - headRadius * 0.7f))
}

// ==================== COMPONENTS ====================

private fun DrawScope.drawEars(cx: Float, topY: Float, earSize: Float, style: Int, small: Boolean) {
    val earType = style % 5
    val spread = if (small) earSize * 1.2f else earSize * 1.5f
    
    when (earType) {
        0 -> { // Pointy cat ears
            val leftEar = Path().apply {
                moveTo(cx - spread, topY + earSize)
                lineTo(cx - spread + earSize * 0.3f, topY - earSize * 0.5f)
                lineTo(cx - spread + earSize * 0.6f, topY + earSize)
                close()
            }
            val rightEar = Path().apply {
                moveTo(cx + spread - earSize * 0.6f, topY + earSize)
                lineTo(cx + spread - earSize * 0.3f, topY - earSize * 0.5f)
                lineTo(cx + spread, topY + earSize)
                close()
            }
            drawPath(leftEar, PetBodyLight)
            drawPath(rightEar, PetBodyLight)
            drawPath(leftEar, PetBodyDark, style = Stroke(2f))
            drawPath(rightEar, PetBodyDark, style = Stroke(2f))
        }
        1 -> { // Round ears
            drawCircle(PetBodyLight, earSize * 0.5f, Offset(cx - spread, topY + earSize * 0.3f))
            drawCircle(PetBodyLight, earSize * 0.5f, Offset(cx + spread, topY + earSize * 0.3f))
            drawCircle(PetBodyDark, earSize * 0.5f, Offset(cx - spread, topY + earSize * 0.3f), style = Stroke(2f))
            drawCircle(PetBodyDark, earSize * 0.5f, Offset(cx + spread, topY + earSize * 0.3f), style = Stroke(2f))
            // Inner ear
            drawCircle(PetBlush.copy(alpha = 0.5f), earSize * 0.25f, Offset(cx - spread, topY + earSize * 0.3f))
            drawCircle(PetBlush.copy(alpha = 0.5f), earSize * 0.25f, Offset(cx + spread, topY + earSize * 0.3f))
        }
        2 -> { // Floppy ears
            val leftEar = Path().apply {
                moveTo(cx - spread * 0.5f, topY + earSize * 0.5f)
                quadraticBezierTo(cx - spread * 1.2f, topY, cx - spread * 1.3f, topY + earSize * 1.5f)
            }
            val rightEar = Path().apply {
                moveTo(cx + spread * 0.5f, topY + earSize * 0.5f)
                quadraticBezierTo(cx + spread * 1.2f, topY, cx + spread * 1.3f, topY + earSize * 1.5f)
            }
            drawPath(leftEar, PetBodyDark, style = Stroke(width = earSize * 0.4f, cap = StrokeCap.Round))
            drawPath(rightEar, PetBodyDark, style = Stroke(width = earSize * 0.4f, cap = StrokeCap.Round))
        }
        3 -> { // Bunny ears (tall)
            val earHeight = earSize * 1.5f
            drawOval(PetBodyLight, Offset(cx - spread - earSize * 0.2f, topY - earHeight), Size(earSize * 0.4f, earHeight))
            drawOval(PetBodyLight, Offset(cx + spread - earSize * 0.2f, topY - earHeight), Size(earSize * 0.4f, earHeight))
            drawOval(PetBodyDark, Offset(cx - spread - earSize * 0.2f, topY - earHeight), Size(earSize * 0.4f, earHeight), style = Stroke(2f))
            drawOval(PetBodyDark, Offset(cx + spread - earSize * 0.2f, topY - earHeight), Size(earSize * 0.4f, earHeight), style = Stroke(2f))
        }
        4 -> { // No visible ears (like a blob)
            // Just small bumps
            drawCircle(PetBodyLight, earSize * 0.25f, Offset(cx - spread * 0.6f, topY + earSize * 0.5f))
            drawCircle(PetBodyLight, earSize * 0.25f, Offset(cx + spread * 0.6f, topY + earSize * 0.5f))
        }
    }
}

private fun DrawScope.drawFace(cx: Float, cy: Float, faceWidth: Float, genetics: GeneticTraits, mood: Mood, baby: Boolean, isMad: Boolean) {
    val eyeSpacing = faceWidth * 0.4f
    val eyeY = cy - faceWidth * 0.1f
    val mouthY = cy + faceWidth * 0.35f
    
    // Eyes based on mood and genetics
    drawEyes(cx, eyeY, eyeSpacing, faceWidth * 0.15f, genetics.eyeStyle, mood, baby, isMad)
    
    // Mouth based on mood
    drawMouth(cx, mouthY, faceWidth * 0.3f, genetics.mouthStyle, mood)
}

private fun DrawScope.drawEyes(cx: Float, cy: Float, spacing: Float, eyeSize: Float, style: Int, mood: Mood, baby: Boolean, isMad: Boolean) {
    val leftX = cx - spacing
    val rightX = cx + spacing
    
    // Mood overrides eye style
    val finalMood = if (isMad && mood == Mood.HAPPY) Mood.ANGRY else mood
    
    when (finalMood) {
        Mood.SLEEPING -> {
            // Closed eyes (arcs) - using path instead of drawArc for simplicity
            val leftArc = Path().apply {
                moveTo(leftX - eyeSize, cy)
                quadraticBezierTo(leftX, cy - eyeSize * 0.5f, leftX + eyeSize, cy)
            }
            val rightArc = Path().apply {
                moveTo(rightX - eyeSize, cy)
                quadraticBezierTo(rightX, cy - eyeSize * 0.5f, rightX + eyeSize, cy)
            }
            drawPath(leftArc, PetBodyDark, style = Stroke(2f))
            drawPath(rightArc, PetBodyDark, style = Stroke(2f))
            return
        }
        Mood.SICK -> {
            // X eyes
            val s = eyeSize * 0.7f
            drawLine(PetBodyDark, Offset(leftX - s, cy - s), Offset(leftX + s, cy + s), strokeWidth = 2f)
            drawLine(PetBodyDark, Offset(leftX + s, cy - s), Offset(leftX - s, cy + s), strokeWidth = 2f)
            drawLine(PetBodyDark, Offset(rightX - s, cy - s), Offset(rightX + s, cy + s), strokeWidth = 2f)
            drawLine(PetBodyDark, Offset(rightX + s, cy - s), Offset(rightX - s, cy + s), strokeWidth = 2f)
            return
        }
        Mood.SAD -> {
            // Teardrop eyes
            drawCircle(PetBodyDark, eyeSize * 0.6f, Offset(leftX, cy))
            drawCircle(PetBodyDark, eyeSize * 0.6f, Offset(rightX, cy))
            // Tears
            drawOval(Color(0xFF6699CC), Offset(leftX + eyeSize, cy), Size(eyeSize * 0.4f, eyeSize * 0.8f))
            return
        }
        Mood.ANGRY -> {
            // Angry eyes (with brows)
            drawCircle(PetBodyDark, eyeSize * 0.5f, Offset(leftX, cy))
            drawCircle(PetBodyDark, eyeSize * 0.5f, Offset(rightX, cy))
            // Angry brows
            drawLine(PetBodyDark, Offset(leftX - eyeSize, cy - eyeSize * 1.2f), Offset(leftX + eyeSize * 0.5f, cy - eyeSize * 0.6f), strokeWidth = 2.5f)
            drawLine(PetBodyDark, Offset(rightX + eyeSize, cy - eyeSize * 1.2f), Offset(rightX - eyeSize * 0.5f, cy - eyeSize * 0.6f), strokeWidth = 2.5f)
            return
        }
        else -> { /* Use genetic style */ }
    }
    
    // Genetic eye styles
    val eyeType = style % 6
    when (eyeType) {
        0 -> { // Simple dots
            drawCircle(PetBodyDark, if (baby) eyeSize * 0.8f else eyeSize * 0.5f, Offset(leftX, cy))
            drawCircle(PetBodyDark, if (baby) eyeSize * 0.8f else eyeSize * 0.5f, Offset(rightX, cy))
        }
        1 -> { // Big cute eyes with highlight
            drawCircle(PetBodyDark, eyeSize, Offset(leftX, cy))
            drawCircle(PetBodyDark, eyeSize, Offset(rightX, cy))
            drawCircle(PetHighlight, eyeSize * 0.35f, Offset(leftX - eyeSize * 0.3f, cy - eyeSize * 0.3f))
            drawCircle(PetHighlight, eyeSize * 0.35f, Offset(rightX - eyeSize * 0.3f, cy - eyeSize * 0.3f))
        }
        2 -> { // Happy curved eyes
            val leftSmile = Path().apply {
                moveTo(leftX - eyeSize, cy)
                quadraticBezierTo(leftX, cy + eyeSize * 0.6f, leftX + eyeSize, cy)
            }
            val rightSmile = Path().apply {
                moveTo(rightX - eyeSize, cy)
                quadraticBezierTo(rightX, cy + eyeSize * 0.6f, rightX + eyeSize, cy)
            }
            drawPath(leftSmile, PetBodyDark, style = Stroke(2.5f))
            drawPath(rightSmile, PetBodyDark, style = Stroke(2.5f))
        }
        3 -> { // Oval eyes
            drawOval(PetBodyDark, Offset(leftX - eyeSize * 0.6f, cy - eyeSize), Size(eyeSize * 1.2f, eyeSize * 2f))
            drawOval(PetBodyDark, Offset(rightX - eyeSize * 0.6f, cy - eyeSize), Size(eyeSize * 1.2f, eyeSize * 2f))
        }
        4 -> { // Star eyes (for ecstatic)
            drawStar(leftX, cy, eyeSize * 0.8f)
            drawStar(rightX, cy, eyeSize * 0.8f)
        }
        5 -> { // Wavy eyes
            val wavePath = Path().apply {
                moveTo(leftX - eyeSize, cy)
                quadraticBezierTo(leftX, cy - eyeSize * 0.5f, leftX + eyeSize, cy)
            }
            drawPath(wavePath, PetBodyDark, style = Stroke(2.5f))
            val wavePath2 = Path().apply {
                moveTo(rightX - eyeSize, cy)
                quadraticBezierTo(rightX, cy - eyeSize * 0.5f, rightX + eyeSize, cy)
            }
            drawPath(wavePath2, PetBodyDark, style = Stroke(2.5f))
        }
    }
}

private fun DrawScope.drawStar(cx: Float, cy: Float, radius: Float) {
    val path = Path()
    for (i in 0 until 5) {
        val outerAngle = (i * 72 - 90) * PI / 180
        val innerAngle = ((i * 72) + 36 - 90) * PI / 180
        val outerX = cx + (radius * cos(outerAngle)).toFloat()
        val outerY = cy + (radius * sin(outerAngle)).toFloat()
        val innerX = cx + (radius * 0.4f * cos(innerAngle)).toFloat()
        val innerY = cy + (radius * 0.4f * sin(innerAngle)).toFloat()
        
        if (i == 0) path.moveTo(outerX, outerY)
        else path.lineTo(outerX, outerY)
        path.lineTo(innerX, innerY)
    }
    path.close()
    drawPath(path, PetBodyDark)
}

private fun DrawScope.drawMouth(cx: Float, cy: Float, width: Float, style: Int, mood: Mood) {
    when (mood) {
        Mood.ECSTATIC -> {
            // Big happy smile
            val smilePath = Path().apply {
                moveTo(cx - width, cy)
                quadraticBezierTo(cx, cy + width * 0.8f, cx + width, cy)
            }
            drawPath(smilePath, PetBodyDark, style = Stroke(2.5f, cap = StrokeCap.Round))
        }
        Mood.HAPPY -> {
            // Simple smile
            val smilePath = Path().apply {
                moveTo(cx - width * 0.7f, cy)
                quadraticBezierTo(cx, cy + width * 0.5f, cx + width * 0.7f, cy)
            }
            drawPath(smilePath, PetBodyDark, style = Stroke(2f, cap = StrokeCap.Round))
        }
        Mood.SAD -> {
            // Frown
            val frownPath = Path().apply {
                moveTo(cx - width * 0.6f, cy + width * 0.2f)
                quadraticBezierTo(cx, cy - width * 0.3f, cx + width * 0.6f, cy + width * 0.2f)
            }
            drawPath(frownPath, PetBodyDark, style = Stroke(2f, cap = StrokeCap.Round))
        }
        Mood.ANGRY -> {
            // Grumpy line
            drawLine(PetBodyDark, Offset(cx - width * 0.5f, cy), Offset(cx + width * 0.5f, cy), strokeWidth = 2.5f)
        }
        Mood.SICK -> {
            // Wavy sick mouth
            val sickPath = Path().apply {
                moveTo(cx - width * 0.5f, cy)
                quadraticBezierTo(cx - width * 0.25f, cy - width * 0.2f, cx, cy)
                quadraticBezierTo(cx + width * 0.25f, cy + width * 0.2f, cx + width * 0.5f, cy)
            }
            drawPath(sickPath, PetBodyDark, style = Stroke(2f))
        }
        else -> {
            // Genetic mouth style for neutral/sleeping
            val mouthType = style % 4
            when (mouthType) {
                0 -> { // Dot
                    drawCircle(PetBodyDark, width * 0.15f, Offset(cx, cy))
                }
                1 -> { // Cat mouth (w shape)
                    val catPath = Path().apply {
                        moveTo(cx - width * 0.4f, cy)
                        lineTo(cx, cy + width * 0.25f)
                        lineTo(cx + width * 0.4f, cy)
                    }
                    drawPath(catPath, PetBodyDark, style = Stroke(2f))
                }
                2 -> { // Small o
                    drawCircle(PetBodyDark, width * 0.2f, Offset(cx, cy), style = Stroke(2f))
                }
                3 -> { // Tiny smile
                    val smilePath = Path().apply {
                        moveTo(cx - width * 0.3f, cy)
                        quadraticBezierTo(cx, cy + width * 0.25f, cx + width * 0.3f, cy)
                    }
                    drawPath(smilePath, PetBodyDark, style = Stroke(2f))
                }
            }
        }
    }
}

private fun DrawScope.drawBlush(cx: Float, cy: Float, spacing: Float) {
    drawCircle(PetBlush.copy(alpha = 0.4f), spacing * 0.35f, Offset(cx - spacing * 1.1f, cy + spacing * 0.3f))
    drawCircle(PetBlush.copy(alpha = 0.4f), spacing * 0.35f, Offset(cx + spacing * 1.1f, cy + spacing * 0.3f))
}

private fun DrawScope.drawArms(cx: Float, armY: Float, length: Float, style: Int, small: Boolean) {
    val armType = style % 3
    val thickness = if (small) 4f else 6f
    
    when (armType) {
        0 -> { // Straight arms down
            drawLine(PetBodyDark, Offset(cx - length * 1.3f, armY), Offset(cx - length * 1.3f, armY + length * 0.8f), strokeWidth = thickness, cap = StrokeCap.Round)
            drawLine(PetBodyDark, Offset(cx + length * 1.3f, armY), Offset(cx + length * 1.3f, armY + length * 0.8f), strokeWidth = thickness, cap = StrokeCap.Round)
        }
        1 -> { // Arms out (waving)
            drawLine(PetBodyDark, Offset(cx - length * 1.2f, armY), Offset(cx - length * 2f, armY - length * 0.3f), strokeWidth = thickness, cap = StrokeCap.Round)
            drawLine(PetBodyDark, Offset(cx + length * 1.2f, armY), Offset(cx + length * 2f, armY - length * 0.3f), strokeWidth = thickness, cap = StrokeCap.Round)
        }
        2 -> { // Arms on hips
            val armPath = Path().apply {
                moveTo(cx - length * 1.2f, armY)
                quadraticBezierTo(cx - length * 1.5f, armY + length * 0.5f, cx - length * 1.2f, armY + length * 0.8f)
            }
            drawPath(armPath, PetBodyDark, style = Stroke(thickness, cap = StrokeCap.Round))
            val armPath2 = Path().apply {
                moveTo(cx + length * 1.2f, armY)
                quadraticBezierTo(cx + length * 1.5f, armY + length * 0.5f, cx + length * 1.2f, armY + length * 0.8f)
            }
            drawPath(armPath2, PetBodyDark, style = Stroke(thickness, cap = StrokeCap.Round))
        }
    }
}

private fun DrawScope.drawFeet(cx: Float, feetY: Float, footSize: Float, style: Int, small: Boolean) {
    val spacing = footSize * 1.2f
    
    // Simple oval feet
    drawOval(PetBodyDark, Offset(cx - spacing - footSize/2, feetY), Size(footSize, footSize * 0.6f))
    drawOval(PetBodyDark, Offset(cx + spacing - footSize/2, feetY), Size(footSize, footSize * 0.6f))
}

private fun DrawScope.drawLegs(cx: Float, legY: Float, length: Float, style: Int) {
    val legType = style % 3
    val spacing = length * 0.8f
    
    when (legType) {
        0 -> { // Straight legs
            drawLine(PetBodyDark, Offset(cx - spacing, legY), Offset(cx - spacing, legY + length), strokeWidth = 5f, cap = StrokeCap.Round)
            drawLine(PetBodyDark, Offset(cx + spacing, legY), Offset(cx + spacing, legY + length), strokeWidth = 5f, cap = StrokeCap.Round)
            // Feet
            drawOval(PetBodyDark, Offset(cx - spacing - length * 0.3f, legY + length - 3f), Size(length * 0.6f, length * 0.35f))
            drawOval(PetBodyDark, Offset(cx + spacing - length * 0.3f, legY + length - 3f), Size(length * 0.6f, length * 0.35f))
        }
        1 -> { // Bent legs
            val legPath = Path().apply {
                moveTo(cx - spacing, legY)
                quadraticBezierTo(cx - spacing * 1.3f, legY + length * 0.5f, cx - spacing, legY + length)
            }
            drawPath(legPath, PetBodyDark, style = Stroke(5f, cap = StrokeCap.Round))
            val legPath2 = Path().apply {
                moveTo(cx + spacing, legY)
                quadraticBezierTo(cx + spacing * 1.3f, legY + length * 0.5f, cx + spacing, legY + length)
            }
            drawPath(legPath2, PetBodyDark, style = Stroke(5f, cap = StrokeCap.Round))
        }
        2 -> { // Stubby legs with round feet
            drawCircle(PetBodyDark, length * 0.4f, Offset(cx - spacing, legY + length * 0.4f))
            drawCircle(PetBodyDark, length * 0.4f, Offset(cx + spacing, legY + length * 0.4f))
        }
    }
}
