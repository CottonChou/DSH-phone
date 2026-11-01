package com.solace.dshphone.core.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Limited retries for transient failures: 429, 5xx and network errors. */
class RetryPolicy(
    val maxAttempts: Int = 3,
    private val baseDelayMs: Long = 1_500,
    val maxTotalWaitMs: Long = 60_000,
) {
    fun shouldRetry(status: Int?): Boolean = status == 429 || (status in 500..599) || status == null

    fun delayForAttempt(attempt: Int): Long {
        val jitter = (200..600).random()
        return minOf(baseDelayMs * (1 shl attempt) + jitter, 30_000)
    }
}

/**
 * HTTP client speaking the three common agent-facing protocols.
 * All wire work is hand-rolled JSON over OkHttp; nothing is shared with the reference project.
 */
class HttpChatModel(
    private val endpoint: ModelEndpoint,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build(),
    private val retry: RetryPolicy = RetryPolicy(),
) : ChatModel {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    override suspend fun complete(
        messages: List<ModelMessage>,
        tools: List<ToolSchema>,
        cancellable: (() -> Boolean)?,
    ): ModelTurn {
        var attempt = 0
        var totalWait = 0L
        while (true) {
            if (cancellable?.invoke() == true) throw SteeredAwayException()
            val response = runCatching { call(messages, tools) }
            val outcome = response.getOrNull()
            val status = outcome?.first
            if (outcome != null && status in 200..299) {
                return parseBody(outcome.second)
            }
            if (attempt >= retry.maxAttempts || !retry.shouldRetry(status)) {
                val err = response.exceptionOrNull()
                    ?: IllegalStateException("model call failed: HTTP $status ${outcome?.second?.take(300)}")
                throw ModelCallException("model call failed after ${attempt + 1} attempts", err)
            }
            val wait = retry.delayForAttempt(attempt)
            totalWait += wait
            if (totalWait > retry.maxTotalWaitMs) throw ModelCallException("retry budget exhausted", response.exceptionOrNull())
            attempt++
            delay(wait)
            if (cancellable?.invoke() == true) throw SteeredAwayException()
        }
    }

    private fun call(messages: List<ModelMessage>, tools: List<ToolSchema>): Pair<Int, String> {
        val (path, body) = when (endpoint.protocol) {
            Protocol.OPENAI_COMPATIBLE -> "/chat/completions" to openAiBody(messages, tools)
            Protocol.ANTHROPIC -> "/v1/messages" to anthropicBody(messages, tools)
            Protocol.GEMINI -> "/v1beta/models/${endpoint.model}:generateContent" to geminiBody(messages, tools)
        }
        val url = endpoint.baseUrl.trimEnd('/') + path
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${endpoint.apiKey}")
            .apply { if (endpoint.protocol == Protocol.ANTHROPIC) header("x-api-key", endpoint.apiKey) }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            return resp.code to (resp.body?.string() ?: "")
        }
    }

    private fun openAiBody(messages: List<ModelMessage>, tools: List<ToolSchema>): JsonObject {
        return buildJsonObject {
            put("model", endpoint.model)
            put("messages", JsonArray(messages.map { m ->
                buildJsonObject {
                    put("role", m.role.name.lowercase().let { if (it == "tool") "tool" else it })
                    when (m.role) {
                        ModelMessage.Role.ASSISTANT -> {
                            if (m.content.isNotBlank()) put("content", m.content) else put("content", JsonNull.safe)
                            if (m.toolCalls.isNotEmpty()) {
                                put("tool_calls", JsonArray(m.toolCalls.map { c ->
                                    buildJsonObject {
                                        put("id", c.id)
                                        put("type", "function")
                                        put("function", buildJsonObject {
                                            put("name", c.name)
                                            put("arguments", c.argumentsJson)
                                        })
                                    }
                                }))
                            }
                        }
                        ModelMessage.Role.TOOL -> {
                            put("content", m.content)
                            put("tool_call_id", m.toolCallId ?: "")
                        }
                        else -> put("content", m.content)
                    }
                }
            }))
            if (tools.isNotEmpty()) {
                put("tools", JsonArray(tools.map { t ->
                    buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            put("parameters", json.parseToJsonElement(t.parametersJson))
                        })
                    }
                }))
            }
        }
    }

    private fun anthropicBody(messages: List<ModelMessage>, tools: List<ToolSchema>): JsonObject {
        val systemText = messages.filter { it.role == ModelMessage.Role.SYSTEM }.joinToString("\n") { it.content }
        val turns = messages.filterNot { it.role == ModelMessage.Role.SYSTEM }
        return buildJsonObject {
            put("model", endpoint.model)
            put("max_tokens", 8192)
            if (systemText.isNotBlank()) put("system", systemText)
            put("messages", JsonArray(turns.map { m ->
                buildJsonObject {
                    put("role", if (m.role == ModelMessage.Role.ASSISTANT) "assistant" else "user")
                    put("content", JsonArray(listOf(buildJsonObject {
                        put("type", "text")
                        put("text", m.content.ifBlank { " " })
                    })))
                }
            }))
            if (tools.isNotEmpty()) {
                put("tools", JsonArray(tools.map { t ->
                    buildJsonObject {
                        put("name", t.name)
                        put("description", t.description)
                        put("input_schema", json.parseToJsonElement(t.parametersJson))
                    }
                }))
            }
        }
    }

    private fun geminiBody(messages: List<ModelMessage>, tools: List<ToolSchema>): JsonObject {
        return buildJsonObject {
            put("contents", JsonArray(messages.filterNot { it.role == ModelMessage.Role.SYSTEM }.map { m ->
                buildJsonObject {
                    put("role", if (m.role == ModelMessage.Role.ASSISTANT) "model" else "user")
                    put("parts", JsonArray(listOf(buildJsonObject { put("text", m.content.ifBlank { " " }) })))
                }
            }))
            if (tools.isNotEmpty()) {
                put("tools", JsonArray(listOf(buildJsonObject {
                    put("functionDeclarations", JsonArray(tools.map { t ->
                        buildJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            put("parameters", json.parseToJsonElement(t.parametersJson))
                        }
                    }))
                })))
            }
        }
    }

    private fun parseBody(body: String): ModelTurn {
        val root = json.parseToJsonElement(body).jsonObject
        return when (endpoint.protocol) {
            Protocol.OPENAI_COMPATIBLE -> {
                val msg = root["choices"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("message")?.jsonObject
                    ?: throw ModelCallException("unexpected response shape", null)
                val text = msg["content"]?.jsonPrimitive?.content ?: ""
                val calls = msg["tool_calls"]?.jsonArray?.map { c ->
                    val o = c.jsonObject
                    ToolCallRequest(
                        id = o["id"]?.jsonPrimitive?.content ?: "call_${System.nanoTime()}",
                        name = o["function"]!!.jsonObject["name"]!!.jsonPrimitive.content,
                        argumentsJson = o["function"]!!.jsonObject["arguments"]?.jsonPrimitive?.content ?: "{}",
                    )
                }.orEmpty()
                ModelTurn(text, calls)
            }
            Protocol.ANTHROPIC -> {
                val blocks = root["content"]?.jsonArray ?: JsonArray(emptyList())
                val text = blocks.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }.joinToString("")
                val calls = blocks.mapNotNull { b ->
                    val o = b.jsonObject
                    if (o["type"]?.jsonPrimitive?.content == "tool_use") ToolCallRequest(
                        id = o["id"]!!.jsonPrimitive.content,
                        name = o["name"]!!.jsonPrimitive.content,
                        argumentsJson = (o["input"] as? JsonObject ?: JsonObject(emptyMap())).toString(),
                    ) else null
                }
                ModelTurn(text, calls)
            }
            Protocol.GEMINI -> {
                val parts = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject?.get("parts")?.jsonArray
                    ?: throw ModelCallException("unexpected response shape", null)
                val text = parts.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }.joinToString("")
                val calls = parts.mapNotNull { p ->
                    val fn = p.jsonObject["functionCall"]?.jsonObject ?: return@mapNotNull null
                    ToolCallRequest(
                        id = "call_${System.nanoTime()}",
                        name = fn["name"]!!.jsonPrimitive.content,
                        argumentsJson = (fn["args"] as? JsonObject ?: JsonObject(emptyMap())).toString(),
                    )
                }
                ModelTurn(text, calls)
            }
        }
    }
}

/** Helper so an assistant message with tool calls can carry an explicit empty content field. */
private object JsonNull {
    val safe = JsonPrimitive("")
}

class ModelCallException(message: String, cause: Throwable?) : Exception(message, cause)

/** Thrown when a request was dropped because the user steered the run mid-flight. */
class SteeredAwayException : CancellationException("steered")
