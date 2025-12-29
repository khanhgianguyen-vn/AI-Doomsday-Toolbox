package com.example.llamadroid.ui.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.IBinder
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.*
import com.example.llamadroid.ui.navigation.Screen
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import kotlinx.coroutines.launch
import com.example.llamadroid.ui.components.SliderWithInput
import com.example.llamadroid.ui.components.IntSliderWithInput
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Image Generation Screen using stable-diffusion.cpp
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageGenScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val settingsRepo = remember { SettingsRepository(context) }
    
    // Available SD models - Classic checkpoints (SD1.5/SDXL)
    val sdCheckpoints by db.modelDao().getModelsByType(ModelType.SD_CHECKPOINT)
        .collectAsState(initial = emptyList())
    
    // FLUX component models
    val fluxDiffusionModels by db.modelDao().getModelsByType(ModelType.SD_DIFFUSION)
        .collectAsState(initial = emptyList())
    val vaeModels by db.modelDao().getModelsByType(ModelType.SD_VAE)
        .collectAsState(initial = emptyList())
    val clipLModels by db.modelDao().getModelsByType(ModelType.SD_CLIP_L)
        .collectAsState(initial = emptyList())
    val t5xxlModels by db.modelDao().getModelsByType(ModelType.SD_T5XXL)
        .collectAsState(initial = emptyList())
    val controlNetModels by db.modelDao().getModelsByType(ModelType.SD_CONTROLNET)
        .collectAsState(initial = emptyList())
    // Note: loraModels removed - using separate loraEnabled/selectedLoraPath state
    
    // Available upscaler models
    val upscalerModels by db.modelDao().getModelsByType(ModelType.SD_UPSCALER)
        .collectAsState(initial = emptyList())
    
    // Combined model list for selection (checkpoints + FLUX diffusion)
    val allGenerationModels = sdCheckpoints + fluxDiffusionModels
    
    // UI State
    var selectedModelPath by remember { mutableStateOf<String?>(null) }
    var selectedModelType by remember { mutableStateOf<ModelType?>(null) } // To track if FLUX or checkpoint
    var prompt by remember { mutableStateOf("") }
    var negativePrompt by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }
    
    // FLUX component selections (only shown when FLUX model is selected)
    var selectedVaePath by remember { mutableStateOf<String?>(null) }
    var selectedClipLPath by remember { mutableStateOf<String?>(null) }
    var selectedT5xxlPath by remember { mutableStateOf<String?>(null) }
    
    // ControlNet settings (optional)
    var controlNetEnabled by remember { mutableStateOf(false) }
    var selectedControlNetPath by remember { mutableStateOf<String?>(null) }
    var controlStrength by remember { mutableFloatStateOf(0.9f) }
    
    // LoRA settings (optional)
    var loraEnabled by remember { mutableStateOf(false) }
    var selectedLoraPath by remember { mutableStateOf<String?>(null) }
    var loraStrength by remember { mutableFloatStateOf(1.0f) }
    
    // Main tab selection: 0 = Generate, 1 = Gallery
    var mainTab by remember { mutableIntStateOf(0) }
    
    // Gallery filter: 0 = All, 1 = txt2img, 2 = img2img, 3 = upscaled
    var galleryFilter by remember { mutableIntStateOf(0) }
    
    // Mode selection: 0 = txt2img, 1 = img2img, 2 = upscale
    var selectedMode by remember { mutableIntStateOf(0) }
    
    // Image input for img2img/upscale
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedImagePath by remember { mutableStateOf<String?>(null) }
    var imageResolution by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    
    // Img2img strength
    var strength by remember { mutableFloatStateOf(0.75f) }
    
    // Quantization type for --type
    var selectedQuantType by remember { mutableStateOf("") }
    
    // Upscale factor and repeats
    var upscaleFactor by remember { mutableIntStateOf(2) } // Default 2, will be auto-detected from model
    var upscaleRepeats by remember { mutableIntStateOf(1) } // User-controlled repeats (1-4)
    
    // Threads for generation (user-controlled, -1 = auto)
    var threads by remember { mutableIntStateOf(-1) }
    
    // Determine if selected model is FLUX type
    val isFluxModel = selectedModelType == ModelType.SD_DIFFUSION
    
    // Check for shared file (from share intent)
    LaunchedEffect(Unit) {
        val pendingFile = com.example.llamadroid.data.SharedFileHolder.consumePendingFile()
        if (pendingFile != null && pendingFile.mimeType.startsWith("image/")) {
            try {
                // Set mode based on targetScreen
                selectedMode = when (pendingFile.targetScreen) {
                    "imagegen_upscale" -> 2  // Upscale mode
                    "imagegen_img2img" -> 1  // img2img mode
                    else -> 1 // Default to img2img
                }
                
                // Load the image
                val inputStream = context.contentResolver.openInputStream(pendingFile.uri)
                val originalBitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                originalBitmap?.let { bmp ->
                    imageResolution = Pair(bmp.width, bmp.height)
                    
                    // Pre-pad to square if needed
                    val processedBitmap = if (bmp.width != bmp.height) {
                        val size = maxOf(bmp.width, bmp.height)
                        val squareBitmap = android.graphics.Bitmap.createBitmap(
                            size, size, android.graphics.Bitmap.Config.ARGB_8888
                        )
                        val canvas = android.graphics.Canvas(squareBitmap)
                        canvas.drawColor(android.graphics.Color.BLACK)
                        val left = (size - bmp.width) / 2f
                        val top = (size - bmp.height) / 2f
                        canvas.drawBitmap(bmp, left, top, null)
                        squareBitmap
                    } else {
                        bmp
                    }
                    
                    // Save to temp file
                    val tempFile = File(context.cacheDir, "shared_input_image.png")
                    java.io.FileOutputStream(tempFile).use { out ->
                        processedBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    }
                    selectedImagePath = tempFile.absolutePath
                    selectedImageUri = pendingFile.uri
                }
            } catch (e: Exception) {
                android.util.Log.e("ImageGenScreen", "Failed to load shared image: ${e.message}")
            }
        }
    }
    
    // Fullscreen gallery viewer
    var fullscreenImage by remember { mutableStateOf<File?>(null) }
    
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            selectedImageUri = it
            // Copy to internal storage and get resolution
            val inputStream = context.contentResolver.openInputStream(it)
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            originalBitmap?.let { bmp ->
                imageResolution = Pair(bmp.width, bmp.height)
                
                // Pre-pad to square if needed (to avoid SD cropping)
                val processedBitmap = if (bmp.width != bmp.height) {
                    val size = maxOf(bmp.width, bmp.height)
                    val squareBitmap = android.graphics.Bitmap.createBitmap(
                        size, size, android.graphics.Bitmap.Config.ARGB_8888
                    )
                    val canvas = android.graphics.Canvas(squareBitmap)
                    // Fill with black (or could use transparent)
                    canvas.drawColor(android.graphics.Color.BLACK)
                    // Center the original image
                    val left = (size - bmp.width) / 2f
                    val top = (size - bmp.height) / 2f
                    canvas.drawBitmap(bmp, left, top, null)
                    squareBitmap
                } else {
                    bmp
                }
                
                // Save to temp file
                val tempFile = File(context.cacheDir, "input_image.png")
                FileOutputStream(tempFile).use { out ->
                    processedBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
                selectedImagePath = tempFile.absolutePath
            }
        }
    }
    
    // Generation parameters
    var width by remember { mutableIntStateOf(512) }
    var height by remember { mutableIntStateOf(512) }
    var steps by remember { mutableIntStateOf(20) }
    var cfgScale by remember { mutableFloatStateOf(7.0f) }
    var seed by remember { mutableLongStateOf(-1L) }
    var selectedSampler by remember { mutableStateOf(SamplingMethod.EULER_A) }
    
    // Get the mode-specific state holder based on selected mode
    val modeStateHolder = remember(selectedMode) { SDModeStateHolder.getForModeIndex(selectedMode) }
    
    // Generation state from mode-specific holder (persists across navigation)
    val generationState by modeStateHolder.state.collectAsState()
    val progress by modeStateHolder.progress.collectAsState()
    val generatedImages by modeStateHolder.generatedImages.collectAsState()
    
    // Load prompt from mode state holder on init and mode change
    val persistedPrompt by modeStateHolder.currentPrompt.collectAsState()
    LaunchedEffect(selectedMode) {
        // Restore prompt from state holder when mode changes
        if (persistedPrompt.isNotBlank()) {
            prompt = persistedPrompt
        }
    }
    
    val isGenerating = generationState is SDGenerationState.Generating
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // Update persisted prompt when user types
    LaunchedEffect(prompt, selectedMode) {
        modeStateHolder.updatePrompt(prompt)
    }
    
    // Output directory for generated images
    val outputDir = remember { File(context.filesDir, "sd_output").apply { mkdirs() } }
    
    // Local list of images from disk (for gallery)
    var diskImages by remember { mutableStateOf<List<File>>(emptyList()) }
    
    // Load existing images from disk (including subfolders)
    LaunchedEffect(Unit) {
        val allImages = mutableListOf<File>()
        // Scan root and subfolders (txt2img, img2img, upscaled, workflow)
        listOf(outputDir, File(outputDir, "txt2img"), File(outputDir, "img2img"), File(outputDir, "upscaled"), File(outputDir, "workflow"))
            .forEach { dir ->
                dir.listFiles()
                    ?.filter { it.extension.lowercase() in listOf("png", "jpg", "jpeg") }
                    ?.let { allImages.addAll(it) }
            }
        diskImages = allImages.sortedByDescending { it.lastModified() }
    }
    
    // Combine holder images with disk images for gallery
    val galleryImages = remember(generatedImages, diskImages) {
        (generatedImages + diskImages).distinctBy { it.absolutePath }
    }
    
    // Service connection
    var sdService by remember { mutableStateOf<SDService?>(null) }
    val serviceConnection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                sdService = (service as? SDService.LocalBinder)?.getService()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                sdService = null
            }
        }
    }
    
    // Bind to service
    DisposableEffect(Unit) {
        val intent = Intent(context, SDService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        onDispose {
            context.unbindService(serviceConnection)
        }
    }
    
    // Generate function - handles all modes
    val generate: () -> Unit = {
        // Validate based on mode
        val canGenerate = when (selectedMode) {
            0 -> selectedModelPath != null && prompt.isNotBlank() // txt2img
            1 -> selectedModelPath != null && prompt.isNotBlank() && selectedImagePath != null // img2img
            2 -> selectedModelPath != null && selectedImagePath != null // upscale needs upscaler + image
            else -> false
        }
        
        if (canGenerate) {
            errorMessage = null
            
            val mode = when (selectedMode) {
                1 -> SDMode.IMG2IMG
                2 -> SDMode.UPSCALE
                else -> SDMode.TXT2IMG
            }
            
            // Create mode-specific subfolder
            val subfolder = when (mode) {
                SDMode.TXT2IMG -> "txt2img"
                SDMode.IMG2IMG -> "img2img"
                SDMode.UPSCALE -> "upscaled"
            }
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "sd_$timestamp.png"
            
            // Always generate to internal storage (sd.cpp needs direct file paths)
            val modeOutputDir = File(outputDir, subfolder).apply { mkdirs() }
            val outputFile = File(modeOutputDir, filename)
            
            // Get custom folder URI for copy-on-success
            val customFolderUri = settingsRepo.outputFolderUri.value
            
            // Get mode-specific thread count - use local if set, otherwise from settings
            val threadCount = if (threads > 0) threads else when (mode) {
                SDMode.TXT2IMG -> settingsRepo.sdTxt2imgThreads.value
                SDMode.IMG2IMG -> settingsRepo.sdImg2imgThreads.value
                SDMode.UPSCALE -> settingsRepo.sdUpscaleThreads.value
            }
            
            val config = SDConfig(
                mode = mode,
                modelPath = selectedModelPath ?: "",
                prompt = prompt,
                negativePrompt = negativePrompt,
                width = width,
                height = height,
                steps = steps,
                cfgScale = cfgScale,
                seed = seed,
                samplingMethod = selectedSampler,
                outputPath = outputFile.absolutePath,
                initImage = selectedImagePath,
                strength = strength,
                upscaleModel = if (mode == SDMode.UPSCALE) selectedModelPath else null,
                upscaleRepeats = upscaleRepeats, // User-controlled repeats
                threads = threadCount,
                // FLUX components
                isFluxModel = isFluxModel,
                vaePath = if (isFluxModel) selectedVaePath else null,
                clipLPath = if (isFluxModel) selectedClipLPath else null,
                t5xxlPath = if (isFluxModel) selectedT5xxlPath else null,
                // ControlNet
                controlNetPath = if (controlNetEnabled) selectedControlNetPath else null,
                controlImagePath = if (controlNetEnabled) selectedImagePath else null,
                controlStrength = controlStrength,
                // LoRA
                loraPath = if (loraEnabled) selectedLoraPath else null,
                loraStrength = loraStrength,
                // Memory Optimization (VAE Tiling)
                vaeTiling = settingsRepo.sdVaeTiling.value,
                vaeTileOverlap = settingsRepo.sdVaeTileOverlap.value,
                vaeTileSize = settingsRepo.sdVaeTileSize.value,
                vaeRelativeTileSize = settingsRepo.sdVaeRelativeTileSize.value,
                tensorTypeRules = settingsRepo.sdTensorTypeRules.value,
                quantizationType = selectedQuantType
            )
            
            // Start service
            context.startForegroundService(Intent(context, SDService::class.java))
            
            sdService?.generate(config) { result ->
                result.fold(
                    onSuccess = { 
                        // Auto-crop for upscaled/img2img images (remove black padding)
                        val shouldAutoCrop = (mode == SDMode.UPSCALE || mode == SDMode.IMG2IMG) && 
                            imageResolution != null && outputFile.exists()
                        
                        if (shouldAutoCrop) {
                            try {
                                val (origW, origH) = imageResolution!!
                                val originalAspect = origW.toFloat() / origH.toFloat()
                                
                                // Load output image first to get actual dimensions
                                val outputBitmap = BitmapFactory.decodeFile(outputFile.absolutePath)
                                if (outputBitmap != null) {
                                    val outW = outputBitmap.width
                                    val outH = outputBitmap.height
                                    
                                    // Calculate target dimensions preserving original aspect ratio
                                    val (targetW, targetH) = if (outW > outH) {
                                        // Landscape or square output
                                        if (originalAspect >= 1f) {
                                            // Original was landscape - fit width
                                            Pair(outW, (outW / originalAspect).toInt())
                                        } else {
                                            // Original was portrait - fit height
                                            Pair((outH * originalAspect).toInt(), outH)
                                        }
                                    } else {
                                        // Portrait output
                                        if (originalAspect >= 1f) {
                                            // Original was landscape - fit width
                                            Pair(outW, (outW / originalAspect).toInt())
                                        } else {
                                            // Original was portrait - fit height
                                            Pair((outH * originalAspect).toInt(), outH)
                                        }
                                    }
                                    
                                    android.util.Log.d("ImageGenScreen", "Auto-crop: original=${origW}x${origH}, output=${outW}x${outH}, target=${targetW}x${targetH}")
                                    
                                    // Only crop if dimensions differ
                                    if (outW != targetW || outH != targetH) {
                                        val cropLeft = (outW - targetW) / 2
                                        val cropTop = (outH - targetH) / 2
                                        
                                        if (cropLeft > 0 || cropTop > 0) {
                                            val finalW = targetW.coerceIn(1, outW - cropLeft.coerceAtLeast(0))
                                            val finalH = targetH.coerceIn(1, outH - cropTop.coerceAtLeast(0))
                                            
                                            val croppedBitmap = android.graphics.Bitmap.createBitmap(
                                                outputBitmap,
                                                cropLeft.coerceAtLeast(0),
                                                cropTop.coerceAtLeast(0),
                                                finalW,
                                                finalH
                                            )
                                            
                                            // Save cropped image back
                                            FileOutputStream(outputFile).use { out ->
                                                croppedBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                                            }
                                            croppedBitmap.recycle()
                                            android.util.Log.d("ImageGenScreen", "Auto-cropped from ${outW}x${outH} to ${finalW}x${finalH}")
                                        }
                                    }
                                    outputBitmap.recycle()
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("ImageGenScreen", "Auto-crop failed: ${e.message}")
                            }
                        }
                        
                        // Copy to custom folder if set
                        if (customFolderUri != null && outputFile.exists()) {
                            try {
                                val treeUri = android.net.Uri.parse(customFolderUri)
                                val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                                
                                // Create/get images/ parent folder
                                var imagesDoc = rootDoc?.findFile("images")
                                if (imagesDoc == null) {
                                    imagesDoc = rootDoc?.createDirectory("images")
                                }
                                
                                // Create/get mode subfolder (txt2img/img2img/upscale)
                                var subfolderDoc = imagesDoc?.findFile(subfolder)
                                if (subfolderDoc == null) {
                                    subfolderDoc = imagesDoc?.createDirectory(subfolder)
                                }
                                
                                // Create and copy file
                                val newFile = subfolderDoc?.createFile("image/png", filename)
                                if (newFile != null) {
                                    context.contentResolver.openOutputStream(newFile.uri)?.use { outStream ->
                                        outputFile.inputStream().use { inStream ->
                                            inStream.copyTo(outStream)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // Log error but don't fail - image is still in internal storage
                                android.util.Log.e("ImageGenScreen", "Failed to copy to custom folder: ${e.message}")
                            }
                        }
                    },
                    onFailure = { e ->
                        errorMessage = e.message
                    }
                )
            }
        }
    }
    
    // Cancel function - cancels only the current mode's generation
    val cancelGeneration: () -> Unit = {
        val mode = when (selectedMode) {
            0 -> SDMode.TXT2IMG
            1 -> SDMode.IMG2IMG
            else -> SDMode.UPSCALE
        }
        sdService?.cancelMode(mode)
    }
    
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
            }
            Text(
                "🎨 " + stringResource(R.string.imagegen_title),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                )
            )
        }
        
        // Main Tab Selector: Generate vs Gallery
        val mainTabs = listOf("🎨 Generate", "📂 Gallery")
        TabRow(
            selectedTabIndex = mainTab,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            mainTabs.forEachIndexed { index, title ->
                Tab(
                    selected = mainTab == index,
                    onClick = { mainTab = index },
                    text = { Text(title) }
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Content based on main tab
        if (mainTab == 0) {
            // GENERATE TAB
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
        ) {
            // Mode Tabs
            val modes = listOf("txt2img", "img2img", "upscale")
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = selectedMode == index,
                        onClick = { selectedMode = index },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = modes.size
                        )
                    ) {
                        Text(mode)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Image Input (for img2img and upscale modes)
            if (selectedMode > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            if (selectedMode == 1) stringResource(R.string.imagegen_mode_img2img) else stringResource(R.string.imagegen_mode_upscale),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        if (selectedImagePath != null && imageResolution != null) {
                            // Show selected image with resolution
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val bitmap = remember(selectedImagePath) {
                                    BitmapFactory.decodeFile(selectedImagePath)?.asImageBitmap()
                                }
                                bitmap?.let {
                                    Image(
                                        bitmap = it,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(80.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "${imageResolution!!.first} × ${imageResolution!!.second}",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        stringResource(R.string.imagegen_resolution),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { imagePicker.launch("image/*") }) {
                                    Icon(Icons.Default.Edit, stringResource(R.string.action_change))
                                }
                            }
                        } else {
                            // Image picker button
                            OutlinedButton(
                                onClick = { imagePicker.launch("image/*") },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Add, null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.imagegen_select_image))
                            }
                        }
                        
                        // Img2img strength slider
                        if (selectedMode == 1) {
                            Spacer(modifier = Modifier.height(12.dp))
                            SliderWithInput(
                                value = strength,
                                onValueChange = { strength = it },
                                valueRange = 0.1f..1.0f,
                                label = "Strength",
                                decimalPlaces = 2
                            )
                            Text(
                                "Lower = more like original, Higher = more creative",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        // Upscale settings (for upscale mode)
                        if (selectedMode == 2) {
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // Only show upscale info when a model is selected
                            if (selectedModelPath != null) {
                                // Model factor (auto-detected)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Model Factor", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${upscaleFactor}x",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    "Auto-detected from model filename",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                
                                Spacer(modifier = Modifier.height(12.dp))
                                
                                // Upscale repeats slider
                                IntSliderWithInput(
                                    value = upscaleRepeats,
                                    onValueChange = { upscaleRepeats = it },
                                    valueRange = 1..4,
                                    label = "Upscale Repeats",
                                    steps = 2
                                )
                                
                                // Calculate final factor and resolution
                                // Note: Upscaler first fits image to 512x512 (preserving aspect ratio) then upscales
                                val finalFactor = Math.pow(upscaleFactor.toDouble(), upscaleRepeats.toDouble()).toInt()
                                val baseSize = 512 // SD always starts from max 512 on longest side
                                
                                // Calculate output dimensions accounting for 512 downscale first
                                val (outputW, outputH, fittedW, fittedH) = if (imageResolution != null) {
                                    val (origW, origH) = imageResolution!!
                                    // First fit to 512x512 box preserving aspect ratio
                                    val scale = baseSize.toFloat() / maxOf(origW, origH)
                                    val fitW = (origW * scale).toInt()
                                    val fitH = (origH * scale).toInt()
                                    // Then apply upscale factor
                                    listOf(fitW * finalFactor, fitH * finalFactor, fitW, fitH)
                                } else {
                                    // No image selected yet
                                    listOf(baseSize * finalFactor, baseSize * finalFactor, baseSize, baseSize)
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                // Output info card
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Final Factor:", style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                "${finalFactor}x",
                                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("Output Resolution:", style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                "${outputW} × ${outputH}",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                            )
                                        }
                                        if (imageResolution != null) {
                                            val (origW, origH) = imageResolution!!
                                            Text(
                                                "Original: ${origW}×${origH} → Base: ${fittedW}×${fittedH}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                
                                // Warning for multiple repeats
                                if (upscaleRepeats > 1) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.Warning,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.tertiary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                "Multiple repeats = longer processing time",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer
                                            )
                                        }
                                    }
                                }
                            } else {
                                // No model selected yet
                                Text(
                                    "Select an upscaler model to see scaling options",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
            }
            // Model Selector (show different models based on mode)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        if (selectedMode == 2) "Upscaler Model" else "Model",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val modelsToShow = if (selectedMode == 2) upscalerModels else allGenerationModels
                    
                    if (modelsToShow.isEmpty()) {
                        Text(
                            if (selectedMode == 2) "No upscaler models installed." else "No SD/FLUX models installed.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { navController.navigate(Screen.SDModels.route) }
                        ) {
                            Icon(Icons.Default.Add, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (selectedMode == 2) "Get Upscaler Models" else "Get SD Models")
                        }
                    } else {
                        var expanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = !expanded }
                        ) {
                            OutlinedTextField(
                                value = selectedModelPath?.substringAfterLast("/") ?: "Select model",
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
                            )
                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                modelsToShow.forEach { model ->
                                    DropdownMenuItem(
                                        text = { Text(model.filename) },
                                        onClick = {
                                            selectedModelPath = model.path
                                            selectedModelType = model.type
                                            expanded = false
                                            
                                            // Reset FLUX component selections when switching models
                                            if (model.type != ModelType.SD_DIFFUSION) {
                                                selectedVaePath = null
                                                selectedClipLPath = null
                                                selectedT5xxlPath = null
                                            }
                                            // Auto-detect upscale factor from filename (2x, 4x, 8x or x2, x4, x8)
                                            if (selectedMode == 2) {
                                                // Match both "4x" and "x4" patterns
                                                val factorRegex = Regex("(\\d+)[xX]|[xX](\\d+)")
                                                val match = factorRegex.find(model.filename)
                                                if (match != null) {
                                                    // First group is for "4x" format, second for "x4" format
                                                    val detected = (match.groupValues[1].takeIf { it.isNotBlank() } 
                                                        ?: match.groupValues[2]).toIntOrNull()
                                                    if (detected != null && detected in listOf(2, 4, 8)) {
                                                        upscaleFactor = detected
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            // VAE Selection (Visible for both SD and Flux)
            if (selectedModelPath != null && selectedMode != 2) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "VAE (Optional)",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            if (isFluxModel && selectedVaePath == null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    Icons.Default.Warning,
                                    null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        
                        if (isFluxModel && selectedVaePath == null) {
                            Text(
                                "Flux models strictly require a VAE if not baked into the GGUF.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            Text(
                                "Required only if the model doesn't have a built-in VAE.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        if (vaeModels.isEmpty()) {
                            Text(
                                "No VAE models installed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            var vaeExpanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(
                                expanded = vaeExpanded,
                                onExpandedChange = { vaeExpanded = !vaeExpanded }
                            ) {
                                OutlinedTextField(
                                    value = selectedVaePath?.substringAfterLast("/") ?: "Select VAE (or system default)",
                                    onValueChange = {},
                                    readOnly = true,
                                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = vaeExpanded) },
                                    shape = RoundedCornerShape(12.dp)
                                )
                                ExposedDropdownMenu(
                                    expanded = vaeExpanded,
                                    onDismissRequest = { vaeExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("None (Use Built-in)") },
                                        onClick = {
                                            selectedVaePath = null
                                            vaeExpanded = false
                                        }
                                    )
                                    vaeModels.forEach { model ->
                                        DropdownMenuItem(
                                            text = { Text(model.filename) },
                                            onClick = {
                                                selectedVaePath = model.path
                                                vaeExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // FLUX Encoders (only shown when FLUX model is selected)
            if (isFluxModel && selectedMode != 2) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "⚡ FLUX Encoders",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            "FLUX models require text encoders (CLIP-L & T5) to understand prompts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // CLIP-L Picker
                        Text("CLIP-L Text Encoder", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        if (clipLModels.isEmpty()) {
                            Text(
                                "No CLIP-L models installed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            var clipExpanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(
                                expanded = clipExpanded,
                                onExpandedChange = { clipExpanded = !clipExpanded }
                            ) {
                                OutlinedTextField(
                                    value = selectedClipLPath?.substringAfterLast("/") ?: "Select CLIP-L",
                                    onValueChange = {},
                                    readOnly = true,
                                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = clipExpanded) }
                                )
                                ExposedDropdownMenu(
                                    expanded = clipExpanded,
                                    onDismissRequest = { clipExpanded = false }
                                ) {
                                    clipLModels.forEach { model ->
                                        DropdownMenuItem(
                                            text = { Text(model.filename) },
                                            onClick = {
                                                selectedClipLPath = model.path
                                                clipExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // T5-XXL Picker
                        Text("T5-XXL Text Encoder", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        if (t5xxlModels.isEmpty()) {
                            Text(
                                "No T5-XXL models installed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            var t5Expanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(
                                expanded = t5Expanded,
                                onExpandedChange = { t5Expanded = !t5Expanded }
                            ) {
                                OutlinedTextField(
                                    value = selectedT5xxlPath?.substringAfterLast("/") ?: "Select T5-XXL",
                                    onValueChange = {},
                                    readOnly = true,
                                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = t5Expanded) }
                                )
                                ExposedDropdownMenu(
                                    expanded = t5Expanded,
                                    onDismissRequest = { t5Expanded = false }
                                ) {
                                    t5xxlModels.forEach { model ->
                                        DropdownMenuItem(
                                            text = { Text(model.filename) },
                                            onClick = {
                                                selectedT5xxlPath = model.path
                                                t5Expanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        
                        // Link to SD Models screen if any components missing
                        if (vaeModels.isEmpty() || clipLModels.isEmpty() || t5xxlModels.isEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { navController.navigate(Screen.SDModels.route) }
                            ) {
                                Icon(Icons.Default.Add, null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Get FLUX Components")
                            }
                        }
                    }
                }
            }
            
            // Quantization selector (mostly for checkpoints/saved-as-safetensors)
            if (!isFluxModel && selectedModelPath != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Model Quantization (--type)",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("none", "f16", "q8_0", "q4_0").forEach { type ->
                                FilterChip(
                                    selected = selectedQuantType == (if (type == "none") "" else type),
                                    onClick = { selectedQuantType = if (type == "none") "" else type },
                                    label = { Text(type.uppercase()) }
                                )
                            }
                        }
                        
                        if (selectedQuantType.isNotBlank()) {
                            Text(
                                "Will load model using $selectedQuantType quantization.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            
            // Prompt Input (only for txt2img and img2img, not upscale)
            if (selectedMode != 2) {
                Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Prompt",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (isFluxModel && (selectedVaePath == null || selectedClipLPath == null || selectedT5xxlPath == null)) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                "⚠️ Warning: Flux might fail if VAE or Encoders are missing.",
                                modifier = Modifier.padding(8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        placeholder = { Text("Describe the image you want to generate...") },
                        shape = RoundedCornerShape(12.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Negative Prompt (collapsible)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAdvanced = !showAdvanced },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Advanced Options",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            if (showAdvanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    if (showAdvanced) {
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Text("Negative Prompt", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = negativePrompt,
                            onValueChange = { negativePrompt = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Things to avoid in the image...") },
                            shape = RoundedCornerShape(12.dp)
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Dimensions
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                IntSliderWithInput(
                                    value = width,
                                    onValueChange = { width = it },
                                    valueRange = 256..1024,
                                    label = "Width"
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                IntSliderWithInput(
                                    value = height,
                                    onValueChange = { height = it },
                                    valueRange = 256..1024,
                                    label = "Height"
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Steps and CFG
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                IntSliderWithInput(
                                    value = steps,
                                    onValueChange = { steps = it },
                                    valueRange = 1..50,
                                    label = "Steps"
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                SliderWithInput(
                                    value = cfgScale,
                                    onValueChange = { cfgScale = it },
                                    valueRange = 1f..20f,
                                    label = "CFG",
                                    decimalPlaces = 1
                                )
                            }
                        }
                        
                        // Sampler
                        Text("Sampler", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        var samplerExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = samplerExpanded,
                            onExpandedChange = { samplerExpanded = !samplerExpanded }
                        ) {
                            OutlinedTextField(
                                value = selectedSampler.cliName,
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = samplerExpanded) }
                            )
                            ExposedDropdownMenu(
                                expanded = samplerExpanded,
                                onDismissRequest = { samplerExpanded = false }
                            ) {
                                SamplingMethod.entries.forEach { sampler ->
                                    DropdownMenuItem(
                                        text = { Text(sampler.cliName) },
                                        onClick = {
                                            selectedSampler = sampler
                                            samplerExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Seed
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = if (seed == -1L) "" else seed.toString(),
                                onValueChange = { seed = it.toLongOrNull() ?: -1L },
                                modifier = Modifier.weight(1f),
                                label = { Text("Seed (-1 = random)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                shape = RoundedCornerShape(12.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(onClick = { seed = (0..Int.MAX_VALUE).random().toLong() }) {
                                Icon(Icons.Default.Refresh, "Random seed")
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Threads
                        IntSliderWithInput(
                            value = if (threads <= 0) 4 else threads,
                            onValueChange = { threads = it },
                            valueRange = 1..16,
                            label = "Threads (-1 = auto)",
                            steps = 14
                        )
                        Text(
                            "Number of CPU threads for generation",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            } // End of prompt section if (selectedMode != 2)
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Generate/Cancel Button
            if (isGenerating) {
                // Progress Card during generation
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Generating...",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Progress bar with percentage
                        val progressPercent = (progress * 100).toInt()
                        val totalStepsVal by modeStateHolder.totalSteps.collectAsState()
                        val currentStepVal by modeStateHolder.currentStep.collectAsState()
                        Text(
                            "Step $currentStepVal / $totalStepsVal ($progressPercent%)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Cancel button
                        OutlinedButton(
                            onClick = cancelGeneration,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Close, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Cancel")
                        }
                    }
                }
            } else {
                // Generate Button - enable based on mode requirements
                // Flux models are NOW allowed without optional components as per latest request
                val buttonEnabled = when (selectedMode) {
                    0 -> selectedModelPath != null && prompt.isNotBlank() // txt2img
                    1 -> selectedModelPath != null && prompt.isNotBlank() && selectedImagePath != null // img2img
                    2 -> selectedModelPath != null && selectedImagePath != null // upscale
                    else -> false
                }
                Button(
                    onClick = generate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = buttonEnabled,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Create, null)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        when (selectedMode) {
                            2 -> "Upscale Image"
                            else -> "Generate Image"
                        },
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            // Error message
            errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        error,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            
            // Show completion message
            if (generationState is SDGenerationState.Complete) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Image generated successfully!",
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
        } else {
            // GALLERY TAB
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                // Filter buttons (with emoji for workflow)
                val filterLabels = listOf("All", "txt2img", "img2img", "upscaled", "⚙️")
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    filterLabels.forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = galleryFilter == index,
                            onClick = { galleryFilter = index },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = filterLabels.size
                            )
                        ) {
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Filter images based on selection
                val filteredImages = remember(galleryImages, galleryFilter) {
                    when (galleryFilter) {
                        1 -> galleryImages.filter { it.parentFile?.name == "txt2img" }
                        2 -> galleryImages.filter { it.parentFile?.name == "img2img" }
                        3 -> galleryImages.filter { it.parentFile?.name == "upscaled" }
                        4 -> galleryImages.filter { it.parentFile?.name == "workflow" }
                        else -> galleryImages
                    }
                }
                
                if (filteredImages.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("📷", style = MaterialTheme.typography.displayLarge)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                if (galleryFilter == 0) "No images yet" else "No ${filterLabels[galleryFilter]} images",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = filteredImages,
                            key = { it.absolutePath }
                        ) { imageFile ->
                            // Progressive loading: async load thumbnail with placeholder
                            var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
                            
                            LaunchedEffect(imageFile.absolutePath) {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    try {
                                        // Load thumbnail at 1/4 resolution for fast scrolling
                                        val options = BitmapFactory.Options().apply {
                                            inSampleSize = 4
                                        }
                                        bitmap = BitmapFactory.decodeFile(imageFile.absolutePath, options)?.asImageBitmap()
                                    } catch (e: Exception) {
                                        // Ignore decode errors
                                    }
                                }
                            }
                            
                            // Determine type badge from folder
                            val typeBadge = when (imageFile.parentFile?.name) {
                                "txt2img" -> "🎨"
                                "img2img" -> "🔄"
                                "upscaled" -> "⬆️"
                                "workflow" -> "⚙️"
                                else -> null
                            }
                            
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { fullscreenImage = imageFile }
                            ) {
                                if (bitmap != null) {
                                    Image(
                                        bitmap = bitmap!!,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    // Placeholder while loading
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                                // Type badge
                                typeBadge?.let {
                                    Surface(
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(4.dp),
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                                    ) {
                                        Text(
                                            it,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Fullscreen image viewer dialog
    if (fullscreenImage != null) {
        val bitmap = remember(fullscreenImage) {
            fullscreenImage?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }
        }
        
        AlertDialog(
            onDismissRequest = { fullscreenImage = null },
            confirmButton = {
                Button(
                    onClick = {
                        // Share the image with error handling
                        fullscreenImage?.let { file ->
                            try {
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/png"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share Image"))
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(
                                    context, 
                                    "Failed to share: ${e.message}", 
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share")
                }
            },
            dismissButton = {
                Row {
                    // Delete button
                    TextButton(
                        onClick = {
                            fullscreenImage?.let { file ->
                                // Show confirmation then delete
                                if (file.delete()) {
                                    // Remove from disk images list
                                    diskImages = diskImages.filter { it.absolutePath != file.absolutePath }
                                    // Remove from all mode state holders (to fix phantom image)
                                    SDModeStateHolder.txt2img.removeImage(file)
                                    SDModeStateHolder.img2img.removeImage(file)
                                    SDModeStateHolder.upscale.removeImage(file)
                                    android.widget.Toast.makeText(context, "Image deleted", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(context, "Failed to delete", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                fullscreenImage = null
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    // Close button
                    TextButton(onClick = { fullscreenImage = null }) {
                        Text("Close")
                    }
                }
            },
            title = { 
                Column {
                    Text("Generated Image")
                    bitmap?.let {
                        Text(
                            "${it.width} × ${it.height}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                ) {
                    bitmap?.let {
                        Image(
                            bitmap = it,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        )
    }
}
