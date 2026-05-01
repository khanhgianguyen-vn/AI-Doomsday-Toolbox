package com.example.llamadroid.tama.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FarmCropBlueFringeFilterTest {

    @Test
    fun removesBluePixelsConnectedToTransparentEdge() {
        val transparent = 0x00000000.toInt()
        val blueFringe = argb(255, 20, 90, 220)
        val greenLeaf = argb(255, 70, 200, 60)
        val pixels = intArrayOf(
            transparent, transparent, transparent,
            transparent, blueFringe, greenLeaf,
            transparent, transparent, transparent,
        )

        val filtered = FarmCropBlueFringeFilter.removeEdgeConnectedBlueFringe(pixels, width = 3, height = 3)

        assertNotNull(filtered)
        assertEquals(transparent, filtered!![4])
        assertEquals(greenLeaf, filtered[5])
    }

    @Test
    fun keepsInteriorBluePixelThatIsNotEdgeConnected() {
        val opaqueBrown = argb(255, 120, 70, 30)
        val bluePixel = argb(255, 18, 92, 220)
        val pixels = intArrayOf(
            opaqueBrown, opaqueBrown, opaqueBrown,
            opaqueBrown, bluePixel, opaqueBrown,
            opaqueBrown, opaqueBrown, opaqueBrown,
        )

        val filtered = FarmCropBlueFringeFilter.removeEdgeConnectedBlueFringe(pixels, width = 3, height = 3)

        assertNull(filtered)
    }

    @Test
    fun ignoresNonBluePixels() {
        val greenLeaf = argb(255, 70, 200, 60)
        val pixels = intArrayOf(
            0,
            greenLeaf,
            0,
        )

        val filtered = FarmCropBlueFringeFilter.removeEdgeConnectedBlueFringe(pixels, width = 3, height = 1)

        assertNull(filtered)
    }

    @Test
    fun removesEdgeConnectedPixelNearSampledChromaColor() {
        val transparent = 0x00000000.toInt()
        val sampledChromaResidue = argb(180, 2, 28, 78)
        val warmCropPixel = argb(255, 180, 140, 48)
        val pixels = intArrayOf(
            transparent, transparent, transparent,
            transparent, sampledChromaResidue, warmCropPixel,
            transparent, transparent, transparent,
        )

        val filtered = FarmCropBlueFringeFilter.removeEdgeConnectedBlueFringe(pixels, width = 3, height = 3)

        assertNotNull(filtered)
        assertEquals(transparent, filtered!![4])
        assertEquals(warmCropPixel, filtered[5])
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int {
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }
}
