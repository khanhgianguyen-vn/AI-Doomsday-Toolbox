package com.example.llamadroid.ui.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ImageGenPreviewImageTest {

    @Test
    fun `missing preview image returns null without crashing`() = runBlocking {
        assertNull(loadPreviewImageBitmap(null))

        val missingFile = File.createTempFile("missing-preview", ".png").apply {
            delete()
            deleteOnExit()
        }
        assertNull(loadPreviewImageBitmap(missingFile.absolutePath))
    }
}
