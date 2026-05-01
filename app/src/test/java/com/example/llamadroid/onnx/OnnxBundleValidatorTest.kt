package com.example.llamadroid.onnx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class OnnxBundleValidatorTest {

    @Test
    fun `valid bundle passes strict SDAI validation`() {
        val root = createBundleRoot()
        createRequiredBundleFiles(root)

        val result = OnnxBundleValidator.validateDirectory(root)

        assertTrue(result.isValid)
        assertTrue(result.missingPaths.isEmpty())
        assertEquals(setOf("txt2img"), result.supportedCapabilities)
    }

    @Test
    fun `missing bundle files are reported`() {
        val root = createBundleRoot()
        createRequiredBundleFiles(root)
        File(root, "tokenizer/merges.txt").delete()
        File(root, "unet/model.ort").delete()

        val result = OnnxBundleValidator.validateDirectory(root)

        assertFalse(result.isValid)
        assertEquals(
            listOf("unet/model.ort", "tokenizer/merges.txt"),
            result.missingPaths
        )
        assertTrue(result.supportedCapabilities.isEmpty())
    }

    @Test
    fun `bundle with vae encoder supports img2img`() {
        val root = createBundleRoot()
        createRequiredBundleFiles(root)
        File(root, OnnxBundleValidator.img2imgEncoderRelativePath).apply {
            parentFile?.mkdirs()
            writeText("encoder")
        }

        val result = OnnxBundleValidator.validateDirectory(root)

        assertTrue(result.isValid)
        assertEquals(setOf("txt2img", "img2img"), result.supportedCapabilities)
    }

    @Test
    fun `partial vae encoder folder is treated as invalid`() {
        val root = createBundleRoot()
        createRequiredBundleFiles(root)
        File(root, "vae_encoder").mkdirs()

        val result = OnnxBundleValidator.validateDirectory(root)

        assertFalse(result.isValid)
        assertTrue(result.missingPaths.contains(OnnxBundleValidator.img2imgEncoderRelativePath))
    }

    private fun createBundleRoot(): File =
        createTempDirectory("onnx-bundle-test").toFile().apply { deleteOnExit() }

    private fun createRequiredBundleFiles(root: File) {
        OnnxBundleValidator.requiredRelativePaths.forEach { relative ->
            val file = File(root, relative)
            file.parentFile?.mkdirs()
            file.writeText("stub")
        }
    }
}
