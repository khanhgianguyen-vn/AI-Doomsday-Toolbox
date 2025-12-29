package com.example.llamadroid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey val filename: String,
    val path: String,
    val sizeBytes: Long,
    val type: ModelType,
    val repoId: String, // HuggingFace repo ID
    val isDownloaded: Boolean = false,
    val isVision: Boolean = false, // Whether this is a vision-capable model (for LLM)
    val mmprojPath: String? = null, // Path to the mmproj file for vision models
    // SD-specific fields
    val sdCapabilities: String? = null, // Comma-separated: "txt2img,img2img,upscale"
    // Layer count for distributed inference
    val layerCount: Int = 0  // Number of transformer layers (from GGUF metadata)
)

enum class ModelType {
    // LLM types
    LLM,
    EMBEDDING,
    VISION,
    VISION_PROJECTOR,
    MMPROJ,           // Multi-modal projector file
    // Stable Diffusion types
    SD_CHECKPOINT,    // Base SD model (supports txt2img, img2img) - SD1.5/SDXL
    SD_VAE,           // Standalone VAE for SD
    SD_LORA,          // LoRA for SD
    SD_UPSCALER,      // ESRGAN upscaling model
    // FLUX-specific types (multi-component architecture)
    SD_DIFFUSION,     // Standalone diffusion/transformer model (FLUX)
    SD_CLIP_L,        // CLIP-L text encoder (for FLUX)
    SD_T5XXL,         // T5-XXL text encoder (for FLUX)
    SD_CONTROLNET,    // ControlNet conditioning model
    // Whisper types
    WHISPER           // WhisperCPP speech-to-text model
}

