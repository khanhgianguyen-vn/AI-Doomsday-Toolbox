package com.example.llamadroid.data.repository

import android.util.Base64
import com.example.llamadroid.data.api.OllamaApi
import com.example.llamadroid.data.api.OllamaCopyRequest
import com.example.llamadroid.data.api.OllamaCreateMessage
import com.example.llamadroid.data.api.OllamaCreateRequest
import com.example.llamadroid.data.api.OllamaDeleteRequest
import com.example.llamadroid.data.api.OllamaModel
import com.example.llamadroid.data.api.OllamaProgressResponse
import com.example.llamadroid.data.api.OllamaPullRequest
import com.example.llamadroid.data.api.OllamaShowRequest
import com.example.llamadroid.data.api.OllamaShowResponse
import com.example.llamadroid.data.db.OllamaServerDao
import com.example.llamadroid.data.db.OllamaServerEntity
import com.example.llamadroid.service.SSHService
import com.example.llamadroid.util.DebugLog
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import java.net.URI

class OllamaRepository(
    private val ollamaServerDao: OllamaServerDao
) {
    private val json = Json { ignoreUnknownKeys = true }
    
    // Cache the last created client to avoid recreating it if the URL hasn't changed.
    // In a real dependency injection scenario, we might handle this differently.
    private var currentClient: OllamaApi? = null
    private var currentUrl: String = ""

    val savedServers: Flow<List<OllamaServerEntity>> = ollamaServerDao.getAllServers()

    suspend fun addServer(name: String, url: String) {
        val server = OllamaServerEntity(name = name, url = url, lastConnected = System.currentTimeMillis())
        ollamaServerDao.insertServer(server)
    }

    suspend fun updateServer(server: OllamaServerEntity) {
        ollamaServerDao.updateServer(server)
    }

    suspend fun deleteServer(server: OllamaServerEntity) {
        ollamaServerDao.deleteServer(server)
    }

    private fun getClient(url: String): OllamaApi {
        if (currentClient != null && currentUrl == url) {
            return currentClient!!
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // Disable read timeout for streaming
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        
        // Ensure URL ends with /
        val baseUrl = if (url.endsWith("/")) url else "$url/"

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        currentClient = retrofit.create(OllamaApi::class.java)
        currentUrl = url
        return currentClient!!
    }

    suspend fun getModels(serverUrl: String): List<OllamaModel> {
        return try {
            val api = getClient(serverUrl)
            val response = api.getTags()
            // Update last connected if successful
            // We'd need the ID to update standard CRUD, but here we might need to look it up or handle it in UI
            response.models
        } catch (e: Exception) {
            DebugLog.log("Error fetching models: ${e.localizedMessage}")
            throw e
        }
    }

    suspend fun updateLastConnected(serverId: Long) {
        ollamaServerDao.updateLastConnected(serverId, System.currentTimeMillis())
    }

    fun pullModel(serverUrl: String, modelName: String): Flow<OllamaProgressResponse> = flow {
        val api = getClient(serverUrl)
        // Use execute() for blocking synchronous call within IO dispatcher
        val call = api.pullModel(OllamaPullRequest(name = modelName))
        val response = call.execute()
        
        if (!response.isSuccessful) {
             val errorBody = response.errorBody()?.string() ?: "Unknown error"
             throw Exception("Failed to pull model: ${response.code()} $errorBody")
        }

        response.body()?.byteStream()?.bufferedReader()?.use { reader ->
            var line = reader.readLine()
            while (line != null) {
                try {
                    if (line.isNotBlank()) {
                         val progress = json.decodeFromString<OllamaProgressResponse>(line)
                         emit(progress)
                    }
                } catch (e: Exception) {
                    DebugLog.log("Error parsing pull progress: ${e.localizedMessage}")
                }
                line = reader.readLine()
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun deleteModel(serverUrl: String, modelName: String) {
        val api = getClient(serverUrl)
        api.deleteModel(OllamaDeleteRequest(name = modelName))
    }
    
    suspend fun getModelInfo(serverUrl: String, modelName: String): OllamaShowResponse {
        val api = getClient(serverUrl)
        return api.showModel(OllamaShowRequest(name = modelName))
    }

    enum class FromSourceKind {
        ExistingModel,
        LocalPath,
        HfReference,
        HuggingFaceResolveUrl,
        Unsupported
    }

    data class FromSourceAnalysis(
        val kind: FromSourceKind,
        val rawValue: String,
        val normalizedValue: String,
        val remoteApiSupported: Boolean,
        val localCliSupported: Boolean,
        val derivedHfReference: String? = null,
        val reason: String? = null
    )

    data class ModelfileAnalysis(
        val normalizedModelfile: String,
        val request: OllamaCreateRequest,
        val unsupportedDirectives: List<String>,
        val warnings: List<String>,
        val fromSource: FromSourceAnalysis
    )

    fun normalizeCreateModelName(name: String): String = name.trim()

    fun isValidCreateModelName(name: String): Boolean {
        val normalized = normalizeCreateModelName(name)
        if (normalized.isBlank()) return false
        if (normalized.any { it.isWhitespace() || it.isISOControl() }) return false
        return Regex("""^[A-Za-z0-9._/-]+(?::[A-Za-z0-9._-]+)?$""").matches(normalized)
    }

    fun normalizeModelfile(modelfile: String, fallbackFromModel: String): String {
        if (modelfile.isBlank()) {
            if (fallbackFromModel.isBlank()) return ""
            val fallbackSource = analyzeFromSource(fallbackFromModel)
            return "FROM ${fallbackSource.normalizedValue}\n"
        }

        var replacedFrom = false
        val normalizedLines = modelfile.lines().map { line ->
            val trimmed = line.trim()
            if (!replacedFrom && trimmed.uppercase().startsWith("FROM ")) {
                val fromTarget = trimmed.substringAfter("FROM ").trim()
                val source = if (fromTarget.startsWith("/blobs/") && fallbackFromModel.isNotBlank()) {
                    analyzeFromSource(fallbackFromModel)
                } else {
                    analyzeFromSource(fromTarget)
                }
                replacedFrom = true
                "FROM ${source.normalizedValue}"
            } else {
                line
            }
        }.toMutableList()

        if (!replacedFrom && fallbackFromModel.isNotBlank()) {
            val fallbackSource = analyzeFromSource(fallbackFromModel)
            normalizedLines.add(0, "FROM ${fallbackSource.normalizedValue}")
        }

        return normalizedLines.joinToString("\n")
    }

    fun suggestCreateModelName(originName: String, analysis: ModelfileAnalysis): String {
        val candidates = listOf(
            originName.substringBefore(":").substringAfterLast('/'),
            analysis.fromSource.normalizedValue.substringAfterLast('/').substringBefore(":"),
            analysis.fromSource.rawValue.substringAfterLast('/').substringBefore('?').substringBefore(".gguf"),
            "custom-model"
        )
        val base = candidates
            .map { sanitizeSuggestedModelSeed(it) }
            .firstOrNull { it.isNotBlank() }
            ?: "custom-model"
        return if (base.endsWith("-custom", ignoreCase = true)) base else "$base-custom"
    }

    fun inspectModelfile(name: String, fallbackFromModel: String, modelfile: String): ModelfileAnalysis {
        val normalizedModelfile = normalizeModelfile(modelfile, fallbackFromModel)
        var parsedFrom = fallbackFromModel
        var systemPrompt: String? = null
        var templateContent: String? = null
        var licenseContent: String? = null
        val parameters = linkedMapOf<String, JsonElement>()
        val messages = mutableListOf<OllamaCreateMessage>()
        val unsupportedDirectives = linkedSetOf<String>()
        val warnings = mutableListOf<String>()
        var fromSource = analyzeFromSource(fallbackFromModel)

        var currentDirective: String? = null
        val currentContent = StringBuilder()

        fun flushDirective() {
            val content = currentContent.toString().trimEnd('\n').trim()
            when (currentDirective) {
                "SYSTEM" -> if (content.isNotBlank()) systemPrompt = content
                "TEMPLATE" -> if (content.isNotBlank()) templateContent = content
                "LICENSE" -> if (content.isNotBlank()) licenseContent = content
            }
            currentContent.setLength(0)
            currentDirective = null
        }

        normalizedModelfile.lines().forEach { line ->
            val trimmed = line.trim()
            val upper = trimmed.uppercase()

            when {
                trimmed.isBlank() && currentDirective == null -> Unit
                trimmed.startsWith("#") && currentDirective == null -> Unit
                upper.startsWith("PARAMETER ") -> {
                    flushDirective()
                    val paramContent = trimmed.substringAfter("PARAMETER ").trim()
                    val spaceIdx = paramContent.indexOf(' ')
                    if (spaceIdx > 0) {
                        val key = paramContent.substring(0, spaceIdx).trim()
                        val value = paramContent.substring(spaceIdx + 1).trim()
                        val parsedValue = parseScalarValue(value)
                        parameters[key] = mergeParameterValue(parameters[key], parsedValue)
                    }
                }
                upper.startsWith("FROM ") -> {
                    flushDirective()
                    val fromValue = trimmed.substringAfter("FROM ").trim()
                    if (fromValue.isNotBlank()) {
                        fromSource = analyzeFromSource(fromValue)
                        parsedFrom = fromSource.normalizedValue
                    }
                }
                upper.startsWith("MESSAGE ") -> {
                    flushDirective()
                    val messageContent = trimmed.substringAfter("MESSAGE ").trim()
                    val splitIndex = messageContent.indexOf(' ')
                    if (splitIndex > 0) {
                        val role = messageContent.substring(0, splitIndex).trim().lowercase()
                        val content = stripQuotedDirective(messageContent.substring(splitIndex + 1).trim())
                        if (role.isNotBlank() && content.isNotBlank()) {
                            messages += OllamaCreateMessage(role = role, content = content)
                        }
                    }
                }
                upper == "SYSTEM" || upper.startsWith("SYSTEM ") -> {
                    flushDirective()
                    currentDirective = "SYSTEM"
                    val content = trimmed.substringAfter("SYSTEM", "").trim()
                    if (content.isNotBlank()) currentContent.appendLine(stripQuotedDirective(content))
                }
                upper == "TEMPLATE" || upper.startsWith("TEMPLATE ") -> {
                    flushDirective()
                    currentDirective = "TEMPLATE"
                    val content = trimmed.substringAfter("TEMPLATE", "").trim()
                    if (content.isNotBlank()) currentContent.appendLine(stripQuotedDirective(content))
                }
                upper == "LICENSE" || upper.startsWith("LICENSE ") -> {
                    flushDirective()
                    currentDirective = "LICENSE"
                    val content = trimmed.substringAfter("LICENSE", "").trim()
                    if (content.isNotBlank()) currentContent.appendLine(stripQuotedDirective(content))
                }
                upper.startsWith("ADAPTER ") -> {
                    flushDirective()
                    unsupportedDirectives += "ADAPTER"
                }
                upper.startsWith("REQUIRES ") -> {
                    flushDirective()
                    unsupportedDirectives += "REQUIRES"
                }
                currentDirective != null -> currentContent.appendLine(stripQuotedDirective(line))
                Regex("^[A-Z_]+\\b").containsMatchIn(trimmed) -> {
                    flushDirective()
                    unsupportedDirectives += trimmed.substringBefore(' ')
                }
            }
        }
        flushDirective()

        if (parsedFrom.isBlank()) {
            warnings += "Missing FROM directive. Falling back to the selected model."
            fromSource = analyzeFromSource(fallbackFromModel)
            parsedFrom = fromSource.normalizedValue
        }
        if (unsupportedDirectives.isNotEmpty()) {
            warnings += "Unsupported directives for API mode: ${unsupportedDirectives.joinToString(", ")}"
        }
        fromSource.reason?.let { warnings += it }
        if (fromSource.kind == FromSourceKind.HuggingFaceResolveUrl && fromSource.derivedHfReference != null) {
            warnings += "Derived recommended hf.co reference ${fromSource.derivedHfReference}"
        }

        return ModelfileAnalysis(
            normalizedModelfile = normalizedModelfile,
            request = OllamaCreateRequest(
                model = name,
                from = parsedFrom,
                system = systemPrompt,
                template = templateContent,
                license = licenseContent,
                parameters = parameters.takeIf { it.isNotEmpty() },
                messages = messages.takeIf { it.isNotEmpty() }
            ),
            unsupportedDirectives = unsupportedDirectives.toList(),
            warnings = warnings,
            fromSource = fromSource
        )
    }

    fun createModel(serverUrl: String, name: String, fromModel: String, modelfile: String): Flow<OllamaProgressResponse> = flow {
        val normalizedName = normalizeCreateModelName(name)
        require(isValidCreateModelName(normalizedName)) { "Invalid model name" }

        val analysis = inspectModelfile(normalizedName, fromModel, modelfile)
        if (analysis.unsupportedDirectives.isNotEmpty()) {
            throw Exception("API mode does not support: ${analysis.unsupportedDirectives.joinToString(", ")}")
        }
        if (!analysis.fromSource.remoteApiSupported) {
            throw Exception(analysis.fromSource.reason ?: "Remote API mode cannot use FROM ${analysis.fromSource.rawValue}.")
        }

        val api = getClient(serverUrl)
        val request = analysis.request.copy(model = normalizedName)

        DebugLog.log(
            "Creating model '$normalizedName' from '${request.from}' with system=${request.system != null}, " +
                "template=${request.template != null}, license=${request.license != null}, " +
                "messages=${request.messages?.size ?: 0}, params=${request.parameters?.keys?.joinToString().orEmpty()}"
        )

        val call = api.createModel(request)
        val response = call.execute()

        if (!response.isSuccessful) {
            val errorBody = sanitizeModelCreateOutput(response.errorBody()?.string() ?: "Unknown error")
            throw Exception("Failed to create model: ${response.code()} $errorBody")
        }

        response.body()?.byteStream()?.bufferedReader()?.use { reader ->
            var line = reader.readLine()
            while (line != null) {
                try {
                    if (line.isNotBlank()) {
                        emit(json.decodeFromString<OllamaProgressResponse>(line))
                    }
                } catch (e: Exception) {
                    DebugLog.log("Error parsing create progress: ${e.localizedMessage}")
                }
                line = reader.readLine()
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun createModelLocally(sshService: SSHService, name: String, fromModel: String, modelfile: String): Result<String> {
        val normalizedName = normalizeCreateModelName(name)
        if (!isValidCreateModelName(normalizedName)) {
            return Result.failure(IllegalArgumentException("Invalid model name"))
        }

        val analysis = inspectModelfile(normalizedName, fromModel, modelfile)
        if (!analysis.fromSource.localCliSupported) {
            return Result.failure(IllegalArgumentException(analysis.fromSource.reason ?: "Local CLI cannot use FROM ${analysis.fromSource.rawValue}."))
        }

        val safeName = shellQuote(normalizedName)
        val encoded = Base64.encodeToString(analysis.normalizedModelfile.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val tmpFile = "/tmp/ollama_modelfile_${System.currentTimeMillis()}.Modelfile"
        val command = buildString {
            append("TMP='")
            append(tmpFile)
            append("'; ")
            append("echo '")
            append(encoded)
            append("' | base64 -d > \"\$TMP\"; ")
            append("ollama create '")
            append(safeName)
            append("' -f \"\$TMP\" 2>&1; ")
            append("STATUS=$?; ")
            append("rm -f \"\$TMP\"; ")
            append("printf '\\n__OLLAMA_EXIT__:%s\\n' \"\$STATUS\"")
        }

        val output = sshService.executeCommand(command).getOrElse { return Result.failure(it) }
        val cleanedOutput = output.replace("\r", "").trimEnd()
        val exitMarker = cleanedOutput.lineSequence().lastOrNull { it.startsWith("__OLLAMA_EXIT__:") }
        val exitCode = exitMarker?.substringAfter(':')?.trim()?.toIntOrNull() ?: -1
        val visibleOutput = sanitizeModelCreateOutput(
            cleanedOutput
            .lineSequence()
            .filterNot { it.startsWith("__OLLAMA_EXIT__:") }
            .joinToString("\n")
            .trim()
        )

        return if (exitCode == 0) {
            Result.success(visibleOutput.ifBlank { "Model created successfully." })
        } else {
            Result.failure(Exception(visibleOutput.ifBlank { "Local ollama create failed with exit code $exitCode." }))
        }
    }

    private fun analyzeFromSource(rawValue: String): FromSourceAnalysis {
        val trimmed = rawValue.trim()
        if (trimmed.isBlank()) {
            return FromSourceAnalysis(
                kind = FromSourceKind.Unsupported,
                rawValue = rawValue,
                normalizedValue = rawValue,
                remoteApiSupported = false,
                localCliSupported = false,
                reason = "Missing FROM source."
            )
        }

        if (trimmed.startsWith("/blobs/")) {
            return FromSourceAnalysis(
                kind = FromSourceKind.Unsupported,
                rawValue = rawValue,
                normalizedValue = rawValue,
                remoteApiSupported = false,
                localCliSupported = false,
                reason = "The Modelfile uses an internal Ollama blob path. Open the model from a named parent model instead."
            )
        }

        val lower = trimmed.lowercase()
        if (lower.startsWith("hf.co/")) {
            return analyzeHfReference(trimmed, rawValue)
        }

        if (lower.startsWith("https://hf.co/") || lower.startsWith("http://hf.co/")) {
            return analyzeHfReference(trimmed.substringAfter("://"), rawValue)
        }

        if (lower.startsWith("https://huggingface.co/") || lower.startsWith("http://huggingface.co/")) {
            return analyzeHuggingFaceResolveUrl(trimmed)
        }

        if (isLikelyLocalPath(trimmed)) {
            return FromSourceAnalysis(
                kind = FromSourceKind.LocalPath,
                rawValue = rawValue,
                normalizedValue = trimmed,
                remoteApiSupported = false,
                localCliSupported = true,
                reason = "Remote API mode cannot use a local GGUF path. Use Local CLI or replace FROM with an hf.co reference."
            )
        }

        return FromSourceAnalysis(
            kind = FromSourceKind.ExistingModel,
            rawValue = rawValue,
            normalizedValue = trimmed,
            remoteApiSupported = true,
            localCliSupported = true
        )
    }

    private fun analyzeHfReference(reference: String, rawValue: String): FromSourceAnalysis {
        val normalized = reference.trim()
        val remainder = normalized.substringAfter("hf.co/", "")
        val hasResolvePath = remainder.contains("/resolve/", ignoreCase = true)
        val segments = remainder.split('/').filter { it.isNotBlank() }

        if (hasResolvePath || segments.size > 2) {
            val derived = deriveHfReferenceFromSegments(segments)
            return FromSourceAnalysis(
                kind = FromSourceKind.HuggingFaceResolveUrl,
                rawValue = rawValue,
                normalizedValue = rawValue,
                remoteApiSupported = false,
                localCliSupported = false,
                derivedHfReference = derived,
                reason = if (derived != null) {
                    "This hf.co path is not a valid Ollama model reference. Replace FROM with the explicit hf.co reference $derived."
                } else {
                    "This hf.co path is not a valid Ollama model reference. Use the explicit hf.co/{owner}/{repo}:{tag} form instead."
                }
            )
        }

        val isCanonicalReference = Regex("""^hf\.co/[^/]+/[^/:]+(?::[A-Za-z0-9._-]+)?$""").matches(normalized)
        if (!isCanonicalReference) {
            return FromSourceAnalysis(
                kind = FromSourceKind.Unsupported,
                rawValue = rawValue,
                normalizedValue = rawValue,
                remoteApiSupported = false,
                localCliSupported = false,
                reason = "This hf.co path is not valid. Use the explicit hf.co/{owner}/{repo}:{tag} form."
            )
        }

        return FromSourceAnalysis(
            kind = FromSourceKind.HfReference,
            rawValue = rawValue,
            normalizedValue = normalized,
            remoteApiSupported = true,
            localCliSupported = true,
            derivedHfReference = normalized
        )
    }

    private fun analyzeHuggingFaceResolveUrl(rawValue: String): FromSourceAnalysis {
        return try {
            val uri = URI(rawValue)
            val segments = uri.path.trim('/').split('/').filter { it.isNotBlank() }
            val isCanonicalResolve = segments.size >= 5 && segments[2] == "resolve"
            val filename = segments.lastOrNull().orEmpty()
            if (!isCanonicalResolve || !filename.endsWith(".gguf", ignoreCase = true)) {
                return FromSourceAnalysis(
                    kind = FromSourceKind.HuggingFaceResolveUrl,
                    rawValue = rawValue,
                    normalizedValue = rawValue,
                    remoteApiSupported = false,
                    localCliSupported = false,
                    reason = "Only canonical Hugging Face resolve URLs pointing directly to a .gguf file can be converted into a recommended hf.co reference. Download the GGUF locally or replace FROM with an explicit hf.co reference."
                )
            }

            val owner = segments[0]
            val repo = segments[1]
            val quantization = extractQuantizationTag(filename)
            if (quantization == null) {
                return FromSourceAnalysis(
                    kind = FromSourceKind.HuggingFaceResolveUrl,
                    rawValue = rawValue,
                    normalizedValue = rawValue,
                    remoteApiSupported = false,
                    localCliSupported = false,
                    reason = "Could not derive an Ollama hf.co quantization tag from $filename. Replace FROM with an explicit hf.co reference or use a local GGUF file path."
                )
            }

            val derived = "hf.co/$owner/$repo:$quantization"
            FromSourceAnalysis(
                kind = FromSourceKind.HuggingFaceResolveUrl,
                rawValue = rawValue,
                normalizedValue = rawValue,
                remoteApiSupported = false,
                localCliSupported = false,
                derivedHfReference = derived,
                reason = "Raw Hugging Face resolve URLs are not accepted directly. Replace FROM with the explicit hf.co reference $derived or use a local GGUF file path."
            )
        } catch (_: Exception) {
            FromSourceAnalysis(
                kind = FromSourceKind.HuggingFaceResolveUrl,
                rawValue = rawValue,
                normalizedValue = rawValue,
                remoteApiSupported = false,
                localCliSupported = false,
                reason = "This Hugging Face URL could not be parsed. Replace FROM with an explicit hf.co reference or use a local GGUF file path."
            )
        }
    }

    private fun extractQuantizationTag(filename: String): String? {
        val baseName = filename.substringBeforeLast(".gguf", filename)
        val patterns = listOf(
            Regex("""(?i)(?:^|[-_])((?:IQ|Q)\d+(?:_[A-Z0-9]+)+)$"""),
            Regex("""(?i)(?:^|[-_])((?:IQ|Q)\d+_\d+)$""")
        )
        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(baseName)?.groupValues?.getOrNull(1)
        }
    }

    private fun deriveHfReferenceFromSegments(segments: List<String>): String? {
        if (segments.size < 5 || !segments[2].equals("resolve", ignoreCase = true)) {
            return null
        }
        val owner = segments[0]
        val repo = segments[1]
        val filename = segments.lastOrNull().orEmpty()
        val quantization = extractQuantizationTag(filename) ?: return null
        return "hf.co/$owner/$repo:$quantization"
    }

    private fun isLikelyLocalPath(value: String): Boolean {
        val lower = value.lowercase()
        return value.startsWith("/") ||
            value.startsWith("./") ||
            value.startsWith("../") ||
            value.startsWith("~") ||
            lower.endsWith(".gguf") ||
            value.contains('\\')
    }

    private fun sanitizeSuggestedModelSeed(seed: String): String {
        return seed
            .trim()
            .replace(Regex("""\.gguf$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""[^A-Za-z0-9._-]+"""), "-")
            .replace(Regex("""-+"""), "-")
            .trim('-', '.', '_')
    }
    
    suspend fun copyModel(serverUrl: String, source: String, destination: String) {
        val api = getClient(serverUrl)
        api.copyModel(OllamaCopyRequest(source = source, destination = destination))
    }

    private fun parseScalarValue(rawValue: String): JsonElement {
        val cleanValue = stripQuotedDirective(rawValue)
        return cleanValue.toLongOrNull()?.let { JsonPrimitive(it) }
            ?: cleanValue.toDoubleOrNull()?.let { JsonPrimitive(it) }
            ?: when (cleanValue.lowercase()) {
                "true" -> JsonPrimitive(true)
                "false" -> JsonPrimitive(false)
                else -> JsonPrimitive(cleanValue)
            }
    }

    private fun mergeParameterValue(existing: JsonElement?, newValue: JsonElement): JsonElement {
        return when (existing) {
            null -> newValue
            is JsonArray -> JsonArray(existing + newValue)
            else -> JsonArray(listOf(existing, newValue))
        }
    }

    private fun stripQuotedDirective(value: String): String {
        return value
            .removePrefix("\"\"\"")
            .removeSuffix("\"\"\"")
            .removePrefix("\"")
            .removeSuffix("\"")
    }

    private fun sanitizeModelCreateOutput(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(Regex("""\u001B\[[0-?]*[ -/]*[@-~]"""), "")
            .replace(Regex("""\[(?:\?[0-9;]+[A-Za-z]|[0-9;]+[A-Za-z]|K)"""), "")
            .replace(Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]"""), "")
            .replace("\r", "")
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("gathering model components", ignoreCase = true) }
            .joinToString("\n")
            .trim()
    }

    private fun shellQuote(value: String): String = value.replace("'", "'\"'\"'")
}
