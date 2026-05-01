package com.example.llamadroid.data.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelBackupPolicyTest {
    @Test
    fun `local imported model rows are kept`() {
        val model = model(
            type = ModelType.LLM,
            repoId = "local-import",
            isDownloaded = false
        )

        assertTrue(ModelBackupPolicy.shouldKeepInPortableBackup(model))
    }

    @Test
    fun `downloaded hugging face model rows are skipped`() {
        val model = model(
            type = ModelType.LLM,
            repoId = "owner/repo",
            isDownloaded = true
        )

        assertFalse(ModelBackupPolicy.shouldKeepInPortableBackup(model))
    }

    @Test
    fun `downloaded rows with local import marker are skipped`() {
        val model = model(
            type = ModelType.LLM,
            repoId = "local-import",
            isDownloaded = true
        )

        assertFalse(ModelBackupPolicy.shouldKeepInPortableBackup(model))
        assertTrue(ModelBackupPolicy.IMPORTED_MODEL_SQL_PREDICATE.contains("isDownloaded = 0"))
    }

    @Test
    fun `downloaded whisper model rows are skipped unless locally imported`() {
        val downloaded = model(
            type = ModelType.WHISPER,
            repoId = "ggerganov/whisper.cpp",
            isDownloaded = true
        )
        val imported = model(
            type = ModelType.WHISPER,
            repoId = "local-import",
            isDownloaded = false
        )

        assertFalse(ModelBackupPolicy.shouldKeepInPortableBackup(downloaded))
        assertTrue(ModelBackupPolicy.shouldKeepInPortableBackup(imported))
    }

    @Test
    fun `custom ONNX imports are kept but catalog ONNX downloads are skipped`() {
        val catalog = model(
            type = ModelType.ONNX_IMAGE_GEN,
            repoId = "ShiftHackZ/Local-Diffusion-Models-SDAI-ONXX",
            isDownloaded = true,
            onnxAssetKind = "sdai_catalog_bundle"
        )
        val custom = model(
            type = ModelType.ONNX_IMAGE_GEN,
            repoId = "custom-import/my-bundle",
            isDownloaded = true,
            onnxAssetKind = "custom_import_bundle"
        )

        assertFalse(ModelBackupPolicy.shouldKeepInPortableBackup(catalog))
        assertTrue(ModelBackupPolicy.shouldKeepInPortableBackup(custom))
    }

    private fun model(
        type: ModelType,
        repoId: String,
        isDownloaded: Boolean,
        onnxAssetKind: String? = null
    ): ModelEntity = ModelEntity(
        filename = "model.bin",
        path = "/models/model.bin",
        sizeBytes = 1L,
        type = type,
        repoId = repoId,
        isDownloaded = isDownloaded,
        onnxAssetKind = onnxAssetKind
    )
}
