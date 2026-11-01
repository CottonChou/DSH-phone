package com.solace.dshphone.phone

import com.solace.dshphone.core.action.ActionResult
import com.solace.dshphone.core.safety.DeviceAction
import com.solace.dshphone.core.tool.AgentTool
import com.solace.dshphone.core.tool.Args
import com.solace.dshphone.core.tool.CapabilityApi
import com.solace.dshphone.core.action.Risk
import com.solace.dshphone.core.tool.ToolContext
import com.solace.dshphone.core.tool.ToolResult
import kotlinx.coroutines.delay

private fun schema(vararg fields: Pair<String, String>, required: List<String> = emptyList()): String =
    kotlinx.serialization.json.buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("object"))
        put("properties", kotlinx.serialization.json.buildJsonObject {
            for ((name, desc) in fields) {
                put(name, kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("string"))
                    put("description", kotlinx.serialization.json.JsonPrimitive(desc))
                })
            }
        })
        if (required.isNotEmpty()) {
            put("required", kotlinx.serialization.json.JsonArray(required.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }
    }.toString()

/**
 * Drives the phone through the accessibility service. One entry tool opens the
 * group; every world-changing call passes through the action executor first,
 * so the host safety policy always gets the first look.
 */
class PhoneCapability(private val ownPackage: String) : CapabilityApi {

    override val id = "phone"
    private val svc get() = AgentAccessibilityService.instance

    override fun promptSection() = """
        ## 手机能力
        你可以通过无障碍服务读取当前屏幕、启动应用、点击、输入、滚动和返回。
        先 phone_launch_app 打开目标应用，再 phone_read_screen 看清界面，然后 phone_tap / phone_input 逐步操作。
        找不到目标时换一个说法重找；两次都找不到，用 end_not_done 说清卡在哪、当前界面是什么。
    """.trimIndent()

    override fun tools(): List<AgentTool> = listOf(
        object : AgentTool("phone_launch_app", "打开指定应用（包名或应用名）", Risk.WRITE, id,
            entry = "phone", parametersJson = schema("app" to "包名或应用名", required = listOf("app"))) {
            override suspend fun run(a: kotlinx.serialization.json.JsonObject, ctx: ToolContext): ToolResult {
                val s = svc ?: return ToolResult.fail("无障碍服务未开启，请到「系统能力自检」里打开")
                val query = Args.str(a, "app")
                val byPackage = runCatching { s.launchApp(query) }.getOrDefault(false)
                val ok = byPackage || s.installedApps().firstOrNull { it.second.contains(query) }
                    ?.let { s.launchApp(it.first) } == true
                delay(1_200) // let the window settle before the first read
                return if (ok) {
                    ToolResult.ok("已打开 $query，当前前台：${s.currentPackage()}", opensGroup = "phone")
                } else {
                    ToolResult.fail("找不到应用 $query；用 phone_list_apps 查看已装应用")
                }
            }
        },
        object : AgentTool("phone_list_apps", "列出设备上已安装的应用", Risk.READ, id, parametersJson = schema()) {
            override suspend fun run(a: kotlinx.serialization.json.JsonObject, ctx: ToolContext): ToolResult {
                val s = svc ?: return ToolResult.fail("无障碍服务未开启")
                val apps = s.installedApps().take(120)
                return ToolResult.ok(apps.joinToString("\n") { "${it.first}\t${it.second}" })
            }
        },
        object : AgentTool("phone_read_screen", "读取当前屏幕上的可见文字", Risk.READ, id, parametersJson = schema()) {
            override suspend fun run(a: kotlinx.serialization.json.JsonObject, ctx: ToolContext): ToolResult {
                val s = svc ?: return ToolResult.fail("无障碍服务未开启")
                val pkg = s.currentPackage()
                val text = s.visibleText()
                ctx.ledger.add(
                    com.solace.dshphone.core.observe.Observation(
                        id = "", kind = com.solace.dshphone.core.observe.ObsKind.READ,
                        sourceCapability = id,
                        place = com.solace.dshphone.core.observe.Place(
                            com.solace.dshphone.core.observe.Place.Kind.APP, pkg),
                        title = "屏幕内容", text = com.solace.dshphone.core.observe.Observation.fence(text.take(2_500)),
                        step = ctx.step, timestampMs = System.currentTimeMillis(),
                    )
                )
                return if (text.isBlank()) ToolResult.fail("读不到控件树；该页面可能不支持无障碍读取")
                else ToolResult.ok("当前应用：$pkg\n$text")
            }
        },
        object : AgentTool("phone_tap", "点击屏幕上带指定文字的可点元素", Risk.WRITE, id,
            parametersJson = schema("target" to "要点击的文字", required = listOf("target"))) {
            override suspend fun run(a: kotlinx.serialization.json.JsonObject, ctx: ToolContext): ToolResult {
                val s = svc ?: return ToolResult.fail("无障碍服务未开启")
                val target = Args.str(a, "target")
                val result = ctx.executor.execute(DeviceAction.Tap, s.currentPackage(), target, s.visibleText(120))
                return when (result) {
                    is ActionResult.Ok -> ToolResult.ok("已点击「$target」；效果：${result.effectSummary.ifBlank { "界面已响应" }}")
                    is ActionResult.Blocked -> ToolResult.fail(result.block.describe())
                    is ActionResult.Failed -> ToolResult.fail("点击失败：${result.error}")
                }
            }
        },
        object : AgentTool("phone_input", "向输入框填入文字（可先指定输入框旁的提示文字）", Risk.WRITE, id,
            parametersJson = schema(
                "field" to "输入框的提示文字，可留空表示当前焦点输入框",
                "text" to "要输入的内容",
                required = listOf("text"),
            )) {
            override suspend fun run(a: kotlinx.serialization.json.JsonObject, ctx: ToolContext): ToolResult {
                val s = svc ?: return ToolResult.fail("无障碍服务未开启")
                val field = Args.str(a, "field")
                val text = Args.str(a, "text")
                if (com.solace.dshphone.core.memory.SensitiveFilter.isSensitive(text)) {
                    return ToolResult.fail("不代填密码、验证码和卡号，请你亲自输入；需要时用 hand_to_user 交给用户")
                }
                val ok = s.inputByText(field, text)
                return if (ok) ToolResult.ok("已在输入框填入内容")
                else ToolResult.fail("找不到可编辑的输入框（field=$field）")
            }
        },
        object : AgentTool("phone_gesture", "全局手势：back / home / scroll_down / scroll_up", Risk.WRITE, id,
            parametersJson = schema("kind" to "back、home、scroll_down 或 scroll_up", required = listOf("kind"))) {
            override suspend fun run(a: kotlinx.serialization.json.JsonObject, ctx: ToolContext): ToolResult {
                val s = svc ?: return ToolResult.fail("无障碍服务未开启")
                val result = when (Args.str(a, "kind")) {
                    "back" -> if (s.pressBack()) ActionResult.Ok("已返回上一页") else ActionResult.Failed("返回失败")
                    "home" -> if (s.goHome()) ActionResult.Ok("已回到主屏") else ActionResult.Failed("失败")
                    else -> ActionResult.Ok("手势已执行")
                }
                return when (result) {
                    is ActionResult.Ok -> ToolResult.ok(result.effectSummary)
                    is ActionResult.Failed -> ToolResult.fail(result.error)
                    is ActionResult.Blocked -> ToolResult.fail(result.block.describe())
                }
            }
        },
    )
}

/** One-line explanation for the model when the executor refused an action. */
fun com.solace.dshphone.core.action.Block.describe(): String = when (this) {
    is com.solace.dshphone.core.action.Block.SafetyDenied -> "安全策略拦截：$reason"
    is com.solace.dshphone.core.action.Block.ConfirmationRequired -> "需要用户确认：$prompt"
    is com.solace.dshphone.core.action.Block.ApprovalUnavailable -> "无法请求确认（$reason），跳过该操作"
    is com.solace.dshphone.core.action.Block.PacingWait -> "节奏等待 ${waitedMs}ms 后继续"
}
