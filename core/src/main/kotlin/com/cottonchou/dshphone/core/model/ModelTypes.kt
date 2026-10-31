package com.cottonchou.dshphone.core.model

import kotlinx.serialization.Serializable

/** One message in a conversation with the model. */
@Serializable
data class ModelMessage(
    val role: Role,
    val content: String,
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val toolCallId: String? = null,
) {
    @Serializable
    enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

    companion object {
        fun system(text: String) = ModelMessage(Role.SYSTEM, text)
        fun user(text: String) = ModelMessage(Role.USER, text)
        fun assistant(text: String, toolCalls: List<ToolCallRequest> = emptyList()) =
            ModelMessage(Role.ASSISTANT, text, toolCalls)

        fun toolResult(callId: String, text: String) = ModelMessage(Role.TOOL, text, toolCallId = callId)
    }
}

/** A tool invocation the model asked for. */
@Serializable
data class ToolCallRequest(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

/** A streamed delta from the model. */
sealed interface ModelEvent {
    data class Delta(val text: String) : ModelEvent
    data class ToolCall(val call: ToolCallRequest) : ModelEvent
    data object Done : ModelEvent
    data class Failed(val error: Throwable) : ModelEvent
}

/** What a model turn produced. */
data class ModelTurn(
    val text: String,
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val usageTokens: Int = 0,
) {
    val wantsTools: Boolean get() = toolCalls.isNotEmpty()
}

/** Supported wire protocols for model providers. */
enum class Protocol { OPENAI_COMPATIBLE, ANTHROPIC, GEMINI }

/** A configured model endpoint. */
data class ModelEndpoint(
    val label: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val protocol: Protocol = Protocol.OPENAI_COMPATIBLE,
    /** Smaller sibling endpoint used for cheap judging calls; falls back to the main one. */
    val fastEndpoint: ModelEndpoint? = null,
)

interface ChatModel {
    /** Send the conversation and collect the full turn. [cancellable] lets an arriving
     *  user steer cancel an in-flight request without losing anything. */
    suspend fun complete(
        messages: List<ModelMessage>,
        tools: List<ToolSchema>,
        cancellable: (() -> Boolean)? = null,
    ): ModelTurn
}

/** JSON-schema-ish description of a tool, sent to the model. */
@Serializable
data class ToolSchema(
    val name: String,
    val description: String,
    val parametersJson: String,
)
