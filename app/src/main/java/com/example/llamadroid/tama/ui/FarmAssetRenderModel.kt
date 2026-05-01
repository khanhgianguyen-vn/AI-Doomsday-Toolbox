package com.example.llamadroid.tama.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation

private const val FARM_CROP_ASSET_URI_PREFIX = "file:///android_asset/farm/Crops/"

@Composable
internal fun rememberFarmAssetModel(assetUri: String): Any {
    val context = LocalContext.current
    return remember(context, assetUri) {
        if (!assetUri.startsWith(FARM_CROP_ASSET_URI_PREFIX)) {
            assetUri
        } else {
            ImageRequest.Builder(context)
                .data(assetUri)
                .allowHardware(false)
                .transformations(FarmCropBlueFringeTransformation)
                .build()
        }
    }
}

internal object FarmCropBlueFringeTransformation : Transformation {
    override val cacheKey: String = "farm_crop_blue_fringe_v2"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val width = input.width
        val height = input.height
        if (width <= 0 || height <= 0) return input

        val pixels = IntArray(width * height)
        input.getPixels(pixels, 0, width, 0, 0, width, height)
        val filtered = FarmCropBlueFringeFilter.removeEdgeConnectedBlueFringe(pixels, width, height) ?: return input

        val output = input.copy(Bitmap.Config.ARGB_8888, true)
        output.setPixels(filtered, 0, width, 0, 0, width, height)
        return output
    }
}

internal object FarmCropBlueFringeFilter {
    private val CHROMA_SAMPLE_COLORS = arrayOf(
        intArrayOf(0, 24, 72),
        intArrayOf(0, 32, 80),
        intArrayOf(0, 24, 80),
        intArrayOf(0, 32, 88),
        intArrayOf(0, 16, 72),
        intArrayOf(0, 32, 72),
        intArrayOf(32, 80, 152),
        intArrayOf(8, 40, 88),
        intArrayOf(8, 40, 96),
        intArrayOf(16, 48, 112),
        intArrayOf(16, 56, 112),
        intArrayOf(16, 56, 120),
        intArrayOf(32, 72, 144),
        intArrayOf(8, 48, 104),
        intArrayOf(24, 64, 128),
        intArrayOf(40, 80, 152),
        intArrayOf(24, 64, 136),
        intArrayOf(24, 56, 120),
        intArrayOf(8, 48, 112),
        intArrayOf(0, 24, 88),
    )
    private const val TRANSPARENT_ALPHA_THRESHOLD = 16
    private const val CANDIDATE_ALPHA_THRESHOLD = 8
    private const val OPAQUE_SAMPLE_DISTANCE_SQUARED = 42 * 42
    private const val SOFT_SAMPLE_DISTANCE_SQUARED = 54 * 54

    fun removeEdgeConnectedBlueFringe(pixels: IntArray, width: Int, height: Int): IntArray? {
        if (pixels.isEmpty() || width <= 0 || height <= 0) return null

        val candidate = BooleanArray(pixels.size)
        for (index in pixels.indices) {
            candidate[index] = isBlueFringeCandidate(pixels[index])
        }

        val remove = BooleanArray(pixels.size)
        val queue = ArrayDeque<Int>()
        for (index in pixels.indices) {
            if (!candidate[index]) continue
            if (touchesTransparentEdge(index, pixels, width, height)) {
                remove[index] = true
                queue.addLast(index)
            }
        }

        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            val x = index % width
            val y = index / width
            visitNeighbor(x - 1, y, width, height, candidate, remove, queue)
            visitNeighbor(x + 1, y, width, height, candidate, remove, queue)
            visitNeighbor(x, y - 1, width, height, candidate, remove, queue)
            visitNeighbor(x, y + 1, width, height, candidate, remove, queue)
            visitNeighbor(x - 1, y - 1, width, height, candidate, remove, queue)
            visitNeighbor(x + 1, y - 1, width, height, candidate, remove, queue)
            visitNeighbor(x - 1, y + 1, width, height, candidate, remove, queue)
            visitNeighbor(x + 1, y + 1, width, height, candidate, remove, queue)
        }

        if (remove.none { it }) return null

        var changed = false
        val filtered = pixels.copyOf()
        for (index in remove.indices) {
            if (!remove[index]) continue
            filtered[index] = 0
            changed = true
        }
        return if (changed) filtered else null
    }

    private fun visitNeighbor(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        candidate: BooleanArray,
        remove: BooleanArray,
        queue: ArrayDeque<Int>,
    ) {
        if (x !in 0 until width || y !in 0 until height) return
        val neighbor = y * width + x
        if (!candidate[neighbor] || remove[neighbor]) return
        remove[neighbor] = true
        queue.addLast(neighbor)
    }

    private fun touchesTransparentEdge(index: Int, pixels: IntArray, width: Int, height: Int): Boolean {
        val x = index % width
        val y = index / width
        if (x == 0 || y == 0 || x == width - 1 || y == height - 1) return true

        val left = index - 1
        val right = index + 1
        val up = index - width
        val down = index + width
        val upLeft = up - 1
        val upRight = up + 1
        val downLeft = down - 1
        val downRight = down + 1
        return isTransparent(pixels[left]) ||
            isTransparent(pixels[right]) ||
            isTransparent(pixels[up]) ||
            isTransparent(pixels[down]) ||
            isTransparent(pixels[upLeft]) ||
            isTransparent(pixels[upRight]) ||
            isTransparent(pixels[downLeft]) ||
            isTransparent(pixels[downRight])
    }

    private fun isBlueFringeCandidate(pixel: Int): Boolean {
        val alpha = (pixel ushr 24) and 0xFF
        if (alpha < CANDIDATE_ALPHA_THRESHOLD) return false

        val red = (pixel ushr 16) and 0xFF
        val green = (pixel ushr 8) and 0xFF
        val blue = pixel and 0xFF
        if (matchesSampledChroma(red, green, blue, alpha)) return true

        val maxOther = maxOf(red, green)
        val dominance = blue - maxOther

        if (blue < 56) return false
        if (blue < red + 4 || blue < green + 4) return false
        if (alpha < 96) {
            return dominance >= 4
        }
        if (dominance < 12) return false
        if (green > blue + 18) return false
        if (red > blue + 4) return false

        return true
    }

    private fun matchesSampledChroma(red: Int, green: Int, blue: Int, alpha: Int): Boolean {
        val threshold = if (alpha < 128) SOFT_SAMPLE_DISTANCE_SQUARED else OPAQUE_SAMPLE_DISTANCE_SQUARED
        return CHROMA_SAMPLE_COLORS.any { sample ->
            val dr = red - sample[0]
            val dg = green - sample[1]
            val db = blue - sample[2]
            (dr * dr) + (dg * dg) + (db * db) <= threshold
        }
    }

    private fun isTransparent(pixel: Int): Boolean = ((pixel ushr 24) and 0xFF) < TRANSPARENT_ALPHA_THRESHOLD
}
