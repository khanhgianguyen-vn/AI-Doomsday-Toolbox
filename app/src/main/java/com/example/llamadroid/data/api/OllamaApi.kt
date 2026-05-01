package com.example.llamadroid.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Streaming

interface OllamaApi {
    @GET("/api/tags")
    suspend fun getTags(): OllamaTagsResponse

    @POST("/api/pull")
    @Streaming
    fun pullModel(@Body request: OllamaPullRequest): retrofit2.Call<ResponseBody>

    @retrofit2.http.HTTP(method = "DELETE", path = "/api/delete", hasBody = true)
    suspend fun deleteModel(@Body request: OllamaDeleteRequest)

    @POST("/api/show")
    suspend fun showModel(@Body request: OllamaShowRequest): OllamaShowResponse

    @POST("/api/create")
    @Streaming
    fun createModel(@Body request: OllamaCreateRequest): retrofit2.Call<ResponseBody>
    
    @POST("/api/copy")
    suspend fun copyModel(@Body request: OllamaCopyRequest)
}

@Serializable
data class OllamaTagsResponse(
    val models: List<OllamaModel>
)

@Serializable
data class OllamaModel(
    val name: String,
    val model: String,
    val modified_at: String,
    val size: Long,
    val digest: String,
    val details: OllamaModelDetails
)

@Serializable
data class OllamaModelDetails(
    val parent_model: String = "",
    val format: String = "",
    val family: String = "",
    val families: List<String> = emptyList(),
    val parameter_size: String = "",
    val quantization_level: String = ""
)

@Serializable
data class OllamaPullRequest(
    val name: String,
    val stream: Boolean = true
)

@Serializable
data class OllamaDeleteRequest(
    val name: String
)

@Serializable
data class OllamaShowRequest(
    val name: String
)

@Serializable
data class OllamaShowResponse(
    val license: String = "",
    val modelfile: String = "",
    val parameters: String = "",
    val template: String = "",
    val details: OllamaModelDetails? = null,
    val capabilities: List<String> = emptyList()
)

@Serializable
data class OllamaCreateRequest(
    val model: String,
    val from: String,
    val system: String? = null,
    val template: String? = null,
    val license: String? = null,
    val parameters: Map<String, JsonElement>? = null,
    val messages: List<OllamaCreateMessage>? = null,
    val stream: Boolean = true
)

@Serializable
data class OllamaCreateMessage(
    val role: String,
    val content: String
)

@Serializable
data class OllamaCopyRequest(
    val source: String,
    val destination: String
)

@Serializable
data class OllamaProgressResponse(
    val status: String,
    val digest: String? = null,
    val total: Long? = null,
    val completed: Long? = null
)
