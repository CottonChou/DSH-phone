package com.solace.dshphone.core.tool

import com.solace.dshphone.core.action.Risk
import com.solace.dshphone.core.model.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What a tool call returned to the model. */
data class ToolResult(
    val ok: Boolean,
    val text: String,
    val opensGroup: String? = null,
) {
    companion object {
        fun ok(text: String, opensGroup: String? = null) = ToolResult(true, text, opensGroup)
        fun fail(text: String) = ToolResult(false, text)
    }
}

/**
 * One agent tool. Everything the runtime needs to police a tool is declared
 * here as data — risk, capability, whether it is an entry that unfolds a group —
 * so dispatch decisions come from properties, never from hard-coded names.
 */
abstract class AgentTool(
    val name: String,
    val summary: String,
    val risk: Risk,
    val capability: String,
    /** Present only on entry tools: calling this unfolds the named group. */
    val entry: String? = null,
    /** Parameters as a JSON-schema object. */
    val parametersJson: String = "{}",
) {
    abstract suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult

    fun schema() = ToolSchema(
        name = name,
        description = summary,
        parametersJson = parametersJson,
    )
}

/** Everything a tool can touch, injected by the host at run start. */
class ToolContext(
    var step: Int,
    val executor: com.solace.dshphone.core.action.ActionExecutor,
    val ledger: com.solace.dshphone.core.observe.EvidenceLedger,
    val memory: com.solace.dshphone.core.memory.MemoryStore,
    val capabilities: Map<String, CapabilityApi>,
    val askUser: suspend (question: String) -> String?,
    val finishNotDone: suspend (message: String, subtype: String) -> Nothing,
)

/** Per-capability API surface the core can host without knowing implementations. */
interface CapabilityApi {
    val id: String

    /** Short prompt paragraph appended to the system prompt while unfolded. */
    fun promptSection(): String

    /** Tools owned by this capability. */
    fun tools(): List<AgentTool>
}

object Args {
    private val json = Json { ignoreUnknownKeys = true }

    fun str(args: JsonObject, key: String, default: String = ""): String =
        args[key]?.jsonPrimitive?.content ?: default

    fun int(args: JsonObject, key: String, default: Int = 0): Int =
        (args[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: default

    fun bool(args: JsonObject, key: String, default: Boolean = false): Boolean =
        (args[key] as? JsonPrimitive)?.let { it.content == "true" || it.content == "1" } ?: default

    fun arr(args: JsonObject, key: String): List<JsonObject> =
        (args[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
}

/** General tools every run carries, regardless of capability set. */
class AskUserTool : AgentTool(
    name = "ask_user",
    summary = "向用户提问并等待回答；只有当面在场时可用",
    risk = Risk.READ,
    capability = "general",
) {
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val question = Args.str(args, "question")
        if (question.isBlank()) return ToolResult.fail("question 不能为空")
        val answer = ctx.askUser(question) ?: return ToolResult.fail("用户没有回答")
        return ToolResult.ok(answer)
    }
}

class EndNotDoneTool : AgentTool(
    name = "end_not_done",
    summary = "任务做不成或只完成一部分时结束；说清已确认做了什么、卡在哪、现在界面是什么",
    risk = Risk.READ,
    capability = "general",
    parametersJson = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("message", buildJsonObject { put("type", "string") })
            put("subtype", buildJsonObject {
                put("type", "string")
                put("enum", JsonArray(listOf(
                    "blocked", "login_incomplete", "captcha_incomplete",
                    "route_failed", "device_unavailable", "interaction_failed",
                ).map { JsonPrimitive(it) }))
            })
        })
        put("required", JsonArray(listOf("message").map { JsonPrimitive(it) }))
    }.toString(),
) {
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        ctx.finishNotDone(Args.str(args, "message", "任务未完成"), Args.str(args, "subtype", "blocked"))
    }
}

class RememberTool : AgentTool(
    name = "remember",
    summary = "记住用户明确说过的偏好或做法；密码、验证码、卡号等敏感信息不会入库",
    risk = Risk.WRITE,
    capability = "general",
    parametersJson = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("content", buildJsonObject { put("type", "string") })
            put("tag", buildJsonObject { put("type", "string") })
        })
        put("required", JsonArray(listOf("content").map { JsonPrimitive(it) }))
    }.toString(),
) {
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val content = Args.str(args, "content")
        val kept = ctx.memory.remember(content, Args.str(args, "tag", ""))
        return if (kept) ToolResult.ok("已记住：$content")
        else ToolResult.fail("内容含敏感信息（密码/验证码/卡号等），不会存入记忆")
    }
}

class RecallTool : AgentTool(
    name = "recall",
    summary = "查询本地记忆里与关键词相关的条目",
    risk = Risk.READ,
    capability = "general",
    parametersJson = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("query", buildJsonObject { put("type", "string") })
        })
    }.toString(),
) {
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val hits = ctx.memory.recall(Args.str(args, "query"))
        return if (hits.isEmpty()) ToolResult.ok("记忆里没有相关内容")
        else ToolResult.ok(hits.joinToString("\n") { "- ${it.content}（${it.tag}）" })
    }
}
