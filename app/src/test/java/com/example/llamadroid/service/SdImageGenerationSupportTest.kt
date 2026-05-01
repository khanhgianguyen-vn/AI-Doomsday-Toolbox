package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SdImageGenerationSupportTest {

    @Test
    fun `upscale preflight rejects missing input image before service launch`() {
        val binary = createReadableTempFile("sd-binary", ".bin")
        val model = createReadableTempFile("upscaler-model", ".bin")

        val issue = validateSdLaunchInputs(
            mode = SDMode.UPSCALE,
            modelPath = model.absolutePath,
            inputImagePath = null,
            sdBinaryPath = binary.absolutePath
        )

        assertEquals(SdLaunchIssue.MISSING_INPUT_IMAGE, issue)
    }

    @Test
    fun `upscale auto crop is skipped as restore first fallback`() {
        val reason = resolveSdAutoCropSkipReason(
            mode = SDMode.UPSCALE,
            outputWidth = 4096,
            outputHeight = 4096
        )

        assertEquals("restore_first_upscale_skip", reason)
    }

    @Test
    fun `oversized img2img output skips auto crop`() {
        val reason = resolveSdAutoCropSkipReason(
            mode = SDMode.IMG2IMG,
            outputWidth = 5000,
            outputHeight = 5000
        )

        assertTrue(reason?.startsWith("oversized_output_") == true)
    }

    @Test
    fun `launch breadcrumb details include upscale metadata`() {
        val details = buildSdLaunchBreadcrumbDetails(
            SDConfig(
                mode = SDMode.UPSCALE,
                modelPath = "/models/realesrgan-x4.bin",
                prompt = "",
                outputPath = "/tmp/out.png",
                initImage = "/tmp/input.png",
                upscaleModel = "/models/realesrgan-x4.bin",
                upscaleRepeats = 2
            )
        )

        assertTrue(details.contains("mode=UPSCALE"))
        assertTrue(details.contains("model=realesrgan-x4.bin"))
        assertTrue(details.contains("inputImage=true"))
        assertTrue(details.contains("repeats=2"))
    }

    @Test
    fun `regular img2img output still allows crop`() {
        val reason = resolveSdAutoCropSkipReason(
            mode = SDMode.IMG2IMG,
            outputWidth = 1024,
            outputHeight = 1024
        )

        assertNull(reason)
    }

    private fun createReadableTempFile(prefix: String, suffix: String): File =
        File.createTempFile(prefix, suffix).apply {
            deleteOnExit()
            setReadable(true)
        }
}
