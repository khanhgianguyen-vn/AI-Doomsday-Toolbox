package com.example.llamadroid.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.util.DebugLog
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

data class PdfExtractionResult(
    val text: String,
    val totalPages: Int,
    val textLayerPages: Int,
    val ocrPages: Int,
    val emptyPages: Int
)

/**
 * Service for PDF operations: merge, split, extract text
 */
class PDFService(private val context: Context) {
    
    private val settingsRepo = SettingsRepository(context)
    
    init {
        // Initialize PDFBox
        PDFBoxResourceLoader.init(context)
    }
    
    /**
     * Merge multiple PDFs into one
     */
    suspend fun mergePdfs(pdfUris: List<Uri>): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            DebugLog.log("[PDF] Merging ${pdfUris.size} PDFs")
            
            val mergedDoc = PDDocument()
            try {
                pdfUris.forEach { uri ->
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val doc = PDDocument.load(inputStream)
                        try {
                            for (i in 0 until doc.numberOfPages) {
                                mergedDoc.importPage(doc.getPage(i))
                            }
                        } finally {
                            doc.close()
                        }
                    } ?: throw IllegalStateException("Could not open PDF")
                }

                // Save to output folder
                val outputUri = saveToOutputFolder(mergedDoc, "merged_${System.currentTimeMillis()}.pdf")

                DebugLog.log("[PDF] Merge complete: $outputUri")
                Result.success(outputUri)
            } finally {
                mergedDoc.close()
            }
        } catch (e: Exception) {
            DebugLog.log("[PDF] Merge failed: ${e.message}")
            Result.failure(e)
        }
    }

    private fun resolveDocumentSize(pdfUri: Uri): Long {
        val assetLength = runCatching {
            context.contentResolver.openAssetFileDescriptor(pdfUri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it >= 0L }
            }
        }.getOrNull()
        if (assetLength != null) return assetLength

        val documentLength = DocumentFile.fromSingleUri(context, pdfUri)?.length()
        if (documentLength != null && documentLength >= 0L) {
            return documentLength
        }

        return 0L
    }

    private fun measureDocumentSize(doc: PDDocument): Long {
        val tempFile = File.createTempFile("pdf_measure_", ".pdf", context.cacheDir)
        return try {
            doc.save(tempFile)
            tempFile.length()
        } finally {
            tempFile.delete()
        }
    }

    private fun importPageRange(sourceDoc: PDDocument, targetDoc: PDDocument, startPage: Int, endPageInclusive: Int) {
        for (pageIndex in startPage..endPageInclusive) {
            targetDoc.importPage(sourceDoc.getPage(pageIndex))
        }
    }

    /**
     * Split PDF by extracting specific pages
     * @param pageRange Format: "1-3, 5, 7-10" 
     */
    suspend fun splitPdf(pdfUri: Uri, pageRange: String): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            DebugLog.log("[PDF] Splitting PDF: $pageRange")
            
            val pages = parsePageRange(pageRange)
            if (pages.isEmpty()) {
                return@withContext Result.failure(Exception("Invalid page range"))
            }
            
            context.contentResolver.openInputStream(pdfUri)?.use { inputStream ->
                val sourceDoc = PDDocument.load(inputStream)
                val newDoc = PDDocument()
                try {
                    val maxPage = sourceDoc.numberOfPages
                    pages.filter { it in 1..maxPage }.forEach { pageNum ->
                        val page = sourceDoc.getPage(pageNum - 1) // 0-indexed
                        newDoc.importPage(page)
                    }

                    if (newDoc.numberOfPages == 0) {
                        return@withContext Result.failure(Exception("No valid pages in range"))
                    }

                    val outputUri = saveToOutputFolder(newDoc, "split_${System.currentTimeMillis()}.pdf")

                    DebugLog.log("[PDF] Split complete: $outputUri")
                    return@withContext Result.success(outputUri)
                } finally {
                    newDoc.close()
                    sourceDoc.close()
                }
            }
            
            Result.failure(Exception("Could not open PDF"))
        } catch (e: Exception) {
            DebugLog.log("[PDF] Split failed: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Extract all text from PDF
     */
    suspend fun extractText(pdfUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        extractTextDetailed(pdfUri).map { it.text }
    }

    suspend fun extractTextDetailed(pdfUri: Uri): Result<PdfExtractionResult> = withContext(Dispatchers.IO) {
        try {
            DebugLog.log("[PDF] Extracting text from PDF with text-layer + OCR fallback")

            val cachedPdf = copyPdfToCache(pdfUri)
            try {
                val pfd = ParcelFileDescriptor.open(cachedPdf, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val doc = runCatching { PDDocument.load(cachedPdf) }.getOrNull()

                try {
                    if (doc?.isEncrypted == true) {
                        runCatching { doc.setAllSecurityToBeRemoved(true) }
                    }

                    val totalPages = renderer.pageCount
                    if (totalPages == 0) {
                        return@withContext Result.failure(Exception("PDF has no pages"))
                    }

                    val pageTexts = mutableListOf<String>()
                    var textLayerPages = 0
                    var ocrPages = 0
                    var emptyPages = 0

                    for (pageIndex in 0 until totalPages) {
                        val pageNumber = pageIndex + 1
                        val textLayerText = if (doc != null && pageNumber <= doc.numberOfPages) {
                            runCatching { extractTextFromPage(doc, pageNumber) }
                                .onFailure { DebugLog.log("[PDF] Page $pageNumber text-layer extract failed: ${it.message}") }
                                .getOrDefault("")
                        } else {
                            ""
                        }

                        val normalizedTextLayer = normalizeExtractedText(textLayerText)
                        val finalText = if (shouldUseOcrFallback(normalizedTextLayer)) {
                            val ocrText = runCatching { extractTextWithOcr(renderer, recognizer, pageIndex) }
                                .onFailure { DebugLog.log("[PDF] Page $pageNumber OCR failed: ${it.message}") }
                                .getOrDefault("")
                            val normalizedOcr = normalizeExtractedText(ocrText)
                            when {
                                normalizedOcr.isNotBlank() -> {
                                    ocrPages += 1
                                    normalizedOcr
                                }
                                normalizedTextLayer.isNotBlank() -> {
                                    textLayerPages += 1
                                    normalizedTextLayer
                                }
                                else -> {
                                    emptyPages += 1
                                    ""
                                }
                            }
                        } else {
                            textLayerPages += 1
                            normalizedTextLayer
                        }

                        if (finalText.isNotBlank()) {
                            pageTexts.add(finalText)
                        }
                    }

                    val extractedText = pageTexts.joinToString("\n\n").trim()
                    if (extractedText.isBlank()) {
                        return@withContext Result.failure(Exception("No extractable text found"))
                    }

                    DebugLog.log(
                        "[PDF] Extracted ${extractedText.length} characters across $totalPages pages " +
                            "(text=$textLayerPages, ocr=$ocrPages, empty=$emptyPages)"
                    )
                    return@withContext Result.success(
                        PdfExtractionResult(
                            text = extractedText,
                            totalPages = totalPages,
                            textLayerPages = textLayerPages,
                            ocrPages = ocrPages,
                            emptyPages = emptyPages
                        )
                    )
                } finally {
                    runCatching { doc?.close() }
                    runCatching { recognizer.close() }
                    runCatching { renderer.close() }
                    runCatching { pfd.close() }
                }
            } finally {
                cachedPdf.delete()
            }
        } catch (e: Exception) {
            DebugLog.log("[PDF] Extract failed: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Get page count for a PDF
     */
    suspend fun getPageCount(pdfUri: Uri): Int = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(pdfUri)?.use { inputStream ->
                val doc = PDDocument.load(inputStream)
                val count = doc.numberOfPages
                doc.close()
                return@withContext count
            }
            0
        } catch (e: Exception) {
            0
        }
    }
    
    /**
     * Parse page range string like "1-3, 5, 7-10" into list of page numbers
     */
    private fun parsePageRange(range: String): List<Int> {
        val pages = mutableListOf<Int>()
        
        range.split(",").forEach { part ->
            val trimmed = part.trim()
            if (trimmed.contains("-")) {
                val (start, end) = trimmed.split("-").map { it.trim().toIntOrNull() }
                if (start != null && end != null && start <= end) {
                    pages.addAll(start..end)
                }
            } else {
                trimmed.toIntOrNull()?.let { pages.add(it) }
            }
        }
        
        return pages.distinct().sorted()
    }

    private fun copyPdfToCache(pdfUri: Uri): File {
        val cacheFile = File(context.cacheDir, "pdf_extract_${System.currentTimeMillis()}.pdf")
        context.contentResolver.openInputStream(pdfUri)?.use { input ->
            FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Could not open PDF")
        return cacheFile
    }

    private fun extractTextFromPage(doc: PDDocument, pageNumber: Int): String {
        val stripper = PDFTextStripper()
        stripper.setStartPage(pageNumber)
        stripper.setEndPage(pageNumber)
        stripper.setSortByPosition(true)
        stripper.setAddMoreFormatting(true)
        stripper.setLineSeparator("\n")
        stripper.setPageEnd("\n\n")
        return stripper.getText(doc)
    }

    private fun normalizeExtractedText(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\u00AD", "")
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    private fun shouldUseOcrFallback(text: String): Boolean {
        if (text.isBlank()) return true

        val alnumCount = text.count { it.isLetterOrDigit() }
        if (alnumCount < 24) return true

        val replacementChars = text.count { it == '\uFFFD' || it == '\u0000' }
        if (replacementChars > 0) return true

        val visibleChars = text.count { !it.isWhitespace() }.coerceAtLeast(1)
        val alnumRatio = alnumCount.toFloat() / visibleChars
        return alnumRatio < 0.45f
    }

    private suspend fun extractTextWithOcr(
        renderer: PdfRenderer,
        recognizer: TextRecognizer,
        pageIndex: Int
    ): String {
        val page = renderer.openPage(pageIndex)
        try {
            val scale = computeOcrScale(page.width, page.height)
            val bitmapWidth = (page.width * scale).roundToInt().coerceAtLeast(page.width)
            val bitmapHeight = (page.height * scale).roundToInt().coerceAtLeast(page.height)
            val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)

            try {
                Canvas(bitmap).drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val image = InputImage.fromBitmap(bitmap, 0)
                return recognizeText(recognizer, image)
            } finally {
                bitmap.recycle()
            }
        } finally {
            page.close()
        }
    }

    private fun computeOcrScale(width: Int, height: Int): Float {
        val longSide = maxOf(width, height).coerceAtLeast(1)
        return (2000f / longSide.toFloat()).coerceIn(1.5f, 2.5f)
    }

    private suspend fun recognizeText(recognizer: TextRecognizer, image: InputImage): String =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val extractedText = result.textBlocks.joinToString("\n\n") { block ->
                        block.lines.joinToString("\n") { it.text }
                    }
                    continuation.resume(extractedText)
                }
                .addOnFailureListener { error ->
                    continuation.resumeWithException(error)
                }
        }
    
    /**
     * Save PDF document to output folder
     */
    private suspend fun saveToOutputFolder(doc: PDDocument, filename: String): Uri {
        val outputFolderUri = settingsRepo.outputFolderUri.value
        
        return if (outputFolderUri != null) {
            // Save to user-selected folder
            val folderUri = Uri.parse(outputFolderUri)
            val folder = DocumentFile.fromTreeUri(context, folderUri)
            
            // Create pdfs subfolder
            val pdfFolder = folder?.findFile("pdfs") ?: folder?.createDirectory("pdfs")
            val outputFile = pdfFolder?.createFile("application/pdf", filename)
            
            outputFile?.let { file ->
                context.contentResolver.openOutputStream(file.uri)?.use { outputStream ->
                    doc.save(outputStream)
                }
                file.uri
            } ?: run {
                // Fallback to cache
                saveToCacheAndGetUri(doc, filename)
            }
        } else {
            // Save to app cache
            saveToCacheAndGetUri(doc, filename)
        }
    }
    
    private fun saveToCacheAndGetUri(doc: PDDocument, filename: String): Uri {
        val pdfDir = File(context.cacheDir, "pdfs").apply { mkdirs() }
        val outputFile = File(pdfDir, filename)
        FileOutputStream(outputFile).use { fos ->
            doc.save(fos)
        }
        return Uri.fromFile(outputFile)
    }
    
    /**
     * Convert images to PDF
     */
    suspend fun imagesToPdf(imageUris: List<Uri>): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            DebugLog.log("[PDF] Converting ${imageUris.size} images to PDF")
            
            val doc = PDDocument()
            
            for (uri in imageUris) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                        if (bitmap != null) {
                            // Create page with image dimensions
                            val pageWidth = 595f  // A4 width in points
                            val pageHeight = 842f // A4 height in points
                            
                            val page = PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle(pageWidth, pageHeight))
                            doc.addPage(page)
                            
                            // Scale image to fit page
                            val imgWidth = bitmap.width.toFloat()
                            val imgHeight = bitmap.height.toFloat()
                            val scale = minOf(pageWidth / imgWidth, pageHeight / imgHeight) * 0.9f
                            val scaledWidth = imgWidth * scale
                            val scaledHeight = imgHeight * scale
                            
                            // Center image on page
                            val x = (pageWidth - scaledWidth) / 2
                            val y = (pageHeight - scaledHeight) / 2
                            
                            // Convert bitmap to JPEG bytes
                            val baos = java.io.ByteArrayOutputStream()
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, baos)
                            val imageBytes = baos.toByteArray()
                            
                            // Create PDImageXObject
                            val pdImage = com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromByteArray(doc, imageBytes)
                            
                            // Draw image on page
                            val contentStream = com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page)
                            contentStream.drawImage(pdImage, x, y, scaledWidth, scaledHeight)
                            contentStream.close()
                            
                            bitmap.recycle()
                        }
                    }
                } catch (e: Exception) {
                    DebugLog.log("[PDF] Error processing image: ${e.message}")
                }
            }
            
            if (doc.numberOfPages == 0) {
                doc.close()
                return@withContext Result.failure(Exception("No valid images to convert"))
            }
            
            val outputUri = saveToOutputFolder(doc, "images_${System.currentTimeMillis()}.pdf")
            doc.close()
            
            DebugLog.log("[PDF] Images to PDF complete: $outputUri")
            Result.success(outputUri)
            
        } catch (e: Exception) {
            DebugLog.log("[PDF] Images to PDF failed: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Perform OCR on an image using ML Kit Text Recognition
     * Note: Requires ML Kit dependency: com.google.mlkit:text-recognition
     */
    suspend fun performOCR(imageUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            DebugLog.log("[PDF] Performing OCR on image")
            
            // Load bitmap from URI
            val bitmap = context.contentResolver.openInputStream(imageUri)?.use { inputStream ->
                android.graphics.BitmapFactory.decodeStream(inputStream)
            } ?: return@withContext Result.failure(Exception("Could not load image"))
            
            // Use ML Kit Text Recognition
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            
            return@withContext suspendCancellableCoroutine { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        val extractedText = result.textBlocks.joinToString("\n\n") { block ->
                            block.lines.joinToString("\n") { it.text }
                        }
                        DebugLog.log("[PDF] OCR extracted ${extractedText.length} characters")
                        bitmap.recycle()
                        recognizer.close()
                        continuation.resume(Result.success(extractedText))
                    }
                    .addOnFailureListener { e ->
                        DebugLog.log("[PDF] OCR failed: ${e.message}")
                        bitmap.recycle()
                        recognizer.close()
                        continuation.resume(Result.failure(e))
                    }
            }
            
        } catch (e: Exception) {
            DebugLog.log("[PDF] OCR error: ${e.message}")
            Result.failure(e)
        }
    }
    /**
     * Compress PDF by reducing image quality
     * @param compressionLevel 1-9 (1=best quality/least compression, 9=worst quality/most compression)
     */
    suspend fun compressPdf(pdfUri: Uri, compressionLevel: Int): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val level = compressionLevel.coerceIn(1, 9)
            // Quality: level 1 = 0.9, level 9 = 0.1
            val quality = 1.0f - (level * 0.1f)
            DebugLog.log("[PDF] Compressing PDF with level $level (quality=$quality)")
            
            // Get original file size
            val originalSize = try {
                resolveDocumentSize(pdfUri)
            } catch (_: Exception) { 0L }
            
            context.contentResolver.openInputStream(pdfUri)?.use { inputStream ->
                val doc = PDDocument.load(inputStream)
                try {
                    // Handle encrypted PDFs
                    if (doc.isEncrypted) {
                        try {
                            doc.setAllSecurityToBeRemoved(true)
                        } catch (e: Exception) {
                            DebugLog.log("[PDF] Could not remove encryption: ${e.message}")
                        }
                    }

                    var imagesCompressed = 0

                    // Iterate through pages and compress images
                    for (i in 0 until doc.numberOfPages) {
                        val page = doc.getPage(i)
                        val resources = page.resources ?: continue // Skip pages without resources

                        // Get all XObjects (includes images)
                        val xObjectNames = try {
                            resources.xObjectNames
                        } catch (_: Exception) {
                            null
                        } ?: continue

                        xObjectNames.forEach { name ->
                            try {
                                val xObject = resources.getXObject(name)
                                if (xObject is com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject) {
                                    val image = xObject.image
                                    if (image != null) {
                                        val jpegImage = com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(
                                            doc, image, quality
                                        )
                                        resources.put(name, jpegImage)
                                        imagesCompressed++
                                    }
                                }
                            } catch (_: Exception) {
                                // Some images can't be re-encoded, skip
                            }
                        }
                    }

                    DebugLog.log("[PDF] Compressed $imagesCompressed images")

                    val compressedSize = measureDocumentSize(doc)

                    DebugLog.log("[PDF] Original: ${originalSize/1024}KB, Compressed: ${compressedSize/1024}KB")

                    // Only keep if smaller
                    if (compressedSize >= originalSize && imagesCompressed > 0) {
                        DebugLog.log("[PDF] Compression would increase size - aborting")
                        return@withContext Result.failure(Exception("Compression would increase file size (${originalSize/1024}KB → ${compressedSize/1024}KB). Try a higher compression level or the PDF may not be compressible."))
                    }

                    // Move temp to output folder
                    val outputUri = saveToOutputFolder(doc, "compressed_L${level}_${System.currentTimeMillis()}.pdf")

                    val savings = if (originalSize > 0) ((originalSize - compressedSize) * 100 / originalSize) else 0
                    DebugLog.log("[PDF] Compression complete: $outputUri (saved $savings%)")
                    return@withContext Result.success(outputUri)
                } finally {
                    doc.close()
                }
            }
            
            Result.failure(Exception("Could not open PDF"))
        } catch (e: Exception) {
            DebugLog.log("[PDF] Compression failed: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Split PDF by max file size
     * Groups multiple pages together until max size is reached
     * @param maxSizeBytes Maximum size per output file in bytes
     * @return List of URIs for the split parts
     */
    suspend fun splitBySize(pdfUri: Uri, maxSizeBytes: Long): Result<List<Uri>> = withContext(Dispatchers.IO) {
        try {
            DebugLog.log("[PDF] Splitting PDF by size: ${maxSizeBytes / 1024}KB max per file")
            
            context.contentResolver.openInputStream(pdfUri)?.use { inputStream ->
                val sourceDoc = PDDocument.load(inputStream)
                try {
                    // Handle encrypted PDFs
                    if (sourceDoc.isEncrypted) {
                        try {
                            sourceDoc.setAllSecurityToBeRemoved(true)
                        } catch (_: Exception) {}
                    }

                    val totalPages = sourceDoc.numberOfPages
                    val outputUris = mutableListOf<Uri>()

                    // Collect pages into groups
                    var startPage = 0
                    var partNumber = 1

                    while (startPage < totalPages) {
                        // Binary search-style: keep adding pages until we exceed max size
                        var endPage = startPage
                        var lastGoodEnd = startPage

                        while (endPage < totalPages) {
                            val partSize = PDDocument().let { testDoc ->
                                try {
                                    importPageRange(sourceDoc, testDoc, startPage, endPage)
                                    measureDocumentSize(testDoc)
                                } finally {
                                    testDoc.close()
                                }
                            }

                            if (partSize <= maxSizeBytes) {
                                lastGoodEnd = endPage
                                endPage++
                            } else {
                                // Size exceeded - use lastGoodEnd if we have pages, else force include current
                                if (lastGoodEnd >= startPage) {
                                    break
                                } else {
                                    // Single page exceeds max - include it anyway
                                    lastGoodEnd = endPage
                                    break
                                }
                            }
                        }

                        // Handle edge case where we reached end
                        if (endPage >= totalPages) {
                            lastGoodEnd = totalPages - 1
                        }

                        val outputUri = PDDocument().let { partDoc ->
                            try {
                                importPageRange(sourceDoc, partDoc, startPage, lastGoodEnd)
                                saveToOutputFolder(partDoc, "part${partNumber}_${System.currentTimeMillis()}.pdf")
                            } finally {
                                partDoc.close()
                            }
                        }
                        outputUris.add(outputUri)

                        DebugLog.log("[PDF] Part $partNumber: pages ${startPage + 1}-${lastGoodEnd + 1}")

                        startPage = lastGoodEnd + 1
                        partNumber++
                    }

                    DebugLog.log("[PDF] Split into ${outputUris.size} parts")
                    return@withContext Result.success(outputUris)
                } finally {
                    sourceDoc.close()
                }
            }
            
            Result.failure(Exception("Could not open PDF"))
        } catch (e: Exception) {
            DebugLog.log("[PDF] Split by size failed: ${e.message}")
            Result.failure(e)
        }
    }
}
