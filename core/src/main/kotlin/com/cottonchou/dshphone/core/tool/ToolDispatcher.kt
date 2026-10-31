package com.cottonchou.dshphone.core.tool

import com.cottonchou.dshphone.core.model.ToolCallRequest
import com.cottonchou.dshphone.core.model.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Finds tools by name, unfolds capability groups on demand, and funnels every
 * call through the single action executor when it can change the world.
 *
 * Tool groups fold: only general tools and entry tools are offered at first;
 * a successful entry call (or a model call to a folded tool by memory) unfolds
 * its whole group for the rest of the run.
 */
class ToolDispatcher(
    private val registry: ToolRegistry,
    private val context: ToolContext,
) {
    private val unfolded = HashSet<String>()
    private val offered = LinkedHashMap<String, AgentTool>()

    /** Prepare the tool set for the run: general tools always; capabilities
     *  with entries show only entries, capabilities without entries open fully. */
    fun arm() {
        offered.clear()
        registry.general().forEach { offered[it.name] = it }
        registry.capabilities().forEach { cap ->
            val tools = cap.tools()
            val entries = tools.filter { it.entry != null }
            if (entries.isEmpty()) tools.forEach { offered[it.name] = it }
            else entries.forEach { offered[it.name] = it }
        }
    }

    fun unfoldedGroups(): Set<String> = unfolded.toSet()

    fun currentSchemas(): List<ToolSchema> = offered.values.map { it.schema() }

    fun capabilityPromptSections(): List<String> =
        registry.capabilities().filter { unfolded.contains(it.id) }.map { it.promptSection() }

    fun entryPromptLines(): String =
        offered.values.filter { it.entry != null }.joinToString("\n") {
            "- ${it.name}: ${it.summary}"
        }

    suspend fun dispatch(call: ToolCallRequest): ToolResult {
        val tool = offered[call.name]
            ?: return ToolResult.fail("没有名为 ${call.name} 的工具")
        val args = runCatching {
            jsonParser.parseToJsonElement(call.argumentsJson).jsonObject
        }.getOrElse { return ToolResult.fail("参数不是合法 JSON 对象：${it.message}") }

        // A remembered call into a folded group still works and unfolds it.
        tool.entry?.let { unfold(it) }

        return runCatching { tool.run(args, context) }
            .getOrElse { ToolResult.fail("工具执行出错：${it.message}") }
            .also { res ->
                res.opensGroup?.let { unfold(it) }
            }
    }

    fun unfold(group: String) {
        if (!unfolded.add(group)) return
        val cap = registry.capabilities().firstOrNull { it.id == group } ?: return
        cap.tools().forEach { offered[it.name] = it }
    }

    companion object {
        private val jsonParser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}

/** Tools and capabilities available to one run. */
class ToolRegistry(
    private val general: List<AgentTool>,
    private val capabilities: List<CapabilityApi>,
) {
    fun general(): List<AgentTool> = general
    fun capabilities(): List<CapabilityApi> = capabilities

    companion object {
        fun standard(capabilities: List<CapabilityApi>): ToolRegistry = ToolRegistry(
            general = listOf(AskUserTool(), EndNotDoneTool(), RememberTool(), RecallTool()),
            capabilities = capabilities,
        )
    }
}

/** Convenience for tools that simply delegate to the action executor. */
suspend fun ToolContext.act(
    action: com.cottonchou.dshphone.core.safety.DeviceAction,
    placeId: String,
    targetText: String = "",
    onScreenText: String = "",
): com.cottonchou.dshphone.core.action.ActionResult =
    executor.execute(action, placeId, targetText, onScreenText)

fun JsonObject.empty() = JsonObject(emptyMap())
