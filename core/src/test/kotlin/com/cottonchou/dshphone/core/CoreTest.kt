package com.cottonchou.dshphone.core

import com.cottonchou.dshphone.core.action.ActionExecutor
import com.cottonchou.dshphone.core.action.ActionPerformer
import com.cottonchou.dshphone.core.action.ApprovalGate
import com.cottonchou.dshphone.core.action.ApprovalMode
import com.cottonchou.dshphone.core.action.OutwardPacing
import com.cottonchou.dshphone.core.action.Risk
import com.cottonchou.dshphone.core.agent.AgentLoop
import com.cottonchou.dshphone.core.memory.MemoryStore
import com.cottonchou.dshphone.core.model.ChatModel
import com.cottonchou.dshphone.core.model.ModelMessage
import com.cottonchou.dshphone.core.model.ModelTurn
import com.cottonchou.dshphone.core.model.ToolCallRequest
import com.cottonchou.dshphone.core.model.ToolSchema
import com.cottonchou.dshphone.core.observe.EvidenceLedger
import com.cottonchou.dshphone.core.run.RunInbox
import com.cottonchou.dshphone.core.run.RunInput
import com.cottonchou.dshphone.core.run.RunSpec
import com.cottonchou.dshphone.core.safety.DeviceAction
import com.cottonchou.dshphone.core.safety.HostSafetyPolicy
import com.cottonchou.dshphone.core.tool.AgentTool
import com.cottonchou.dshphone.core.tool.CapabilityApi
import com.cottonchou.dshphone.core.tool.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A scripted model: replays canned turns, so the loop runs on the JVM. */
private class FakeModel(vararg turns: ModelTurn, val delayMs: Long = 0) : ChatModel {
    private val queue = ArrayDeque(turns.toList())
    var asked = 0

    override suspend fun complete(
        messages: List<ModelMessage>,
        tools: List<ToolSchema>,
        cancellable: (() -> Boolean)?,
    ): ModelTurn {
        asked++
        if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
        return queue.removeFirstOrNull() ?: ModelTurn("好的，任务完成。")
    }
}

private class EchoCapability : CapabilityApi {
    override val id = "echo"
    var performed = 0

    override fun promptSection() = "echo 能力说明"

    override fun tools(): List<AgentTool> = listOf(object : AgentTool(
        name = "echo_say",
        summary = "输出一句话",
        risk = Risk.READ,
        capability = id,
        parametersJson = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("text", buildJsonObject { put("type", "string") }) })
        }.toString(),
    ) {
        override suspend fun run(args: JsonObject, ctx: com.cottonchou.dshphone.core.tool.ToolContext): ToolResult {
            performed++
            return ToolResult.ok("echo: " + com.cottonchou.dshphone.core.tool.Args.str(args, "text"))
        }
    })
}

class AgentLoopTest {

    private fun makeLoop(turns: List<ModelTurn>, cap: EchoCapability, delayMs: Long = 0): Triple<AgentLoop, RunInbox, FakeModel> {
        val model = FakeModel(*turns.toTypedArray(), delayMs = delayMs)
        val executor = ActionExecutor(
            safety = HostSafetyPolicy("com.cottonchou.dshphone"),
            approval = object : ApprovalGate {
                override val mode = ApprovalMode.ASK
                override suspend fun requestConfirmation(prompt: String, payment: Boolean) = true
            },
            pacing = null,
            performer = ActionPerformer { _, _ -> "done" },
            provenance = com.cottonchou.dshphone.core.run.RunProvenance(
                com.cottonchou.dshphone.core.run.RunOrigin.UI,
                com.cottonchou.dshphone.core.run.Attendance.PRESENT,
            ),
        )
        val inbox = RunInbox()
        val loop = AgentLoop(
            spec = RunSpec(id = "r1", goal = "测试任务", threadId = "t1"),
            model = model,
            capabilities = listOf(cap),
            executor = executor,
            memory = MemoryStore(),
            inbox = inbox,
            ledger = EvidenceLedger(),
            systemPrompt = "你是测试助手",
        )
        return Triple(loop, inbox, model)
    }

    @Test
    fun `plain answer ends the run`() = runBlocking {
        val cap = EchoCapability()
        val (loop, _, _) = makeLoop(listOf(ModelTurn("答案是 42")), cap)
        val result = loop.run()
        assertTrue(result.reason is com.cottonchou.dshphone.core.agent.StopReason.Answered)
        assertEquals("答案是 42", (result.reason as com.cottonchou.dshphone.core.agent.StopReason.Answered).text)
        assertEquals(com.cottonchou.dshphone.core.run.RunOutcome.DONE, result.outcome)
    }

    @Test
    fun `tool calls execute then answer`() = runBlocking {
        val cap = EchoCapability()
        val (loop, _, _) = makeLoop(listOf(
            ModelTurn("", listOf(ToolCallRequest("c1", "echo_say", """{"text":"你好"}"""))),
            ModelTurn("说完了"),
        ), cap)
        val result = loop.run()
        assertEquals(1, cap.performed)
        assertEquals(com.cottonchou.dshphone.core.run.RunOutcome.DONE, result.outcome)
    }

    @Test
    fun `end_not_done tool ends with not possible`() = runBlocking {
        val (loop, _, _) = makeLoop(listOf(
            ModelTurn("", listOf(ToolCallRequest("c1", "end_not_done", """{"message":"登录页卡住","subtype":"login_incomplete"}"""))),
        ), EchoCapability())
        val result = loop.run()
        assertTrue(result.reason is com.cottonchou.dshphone.core.agent.StopReason.EndedNotDone)
        assertEquals(com.cottonchou.dshphone.core.run.RunOutcome.NOT_POSSIBLE, result.outcome)
    }

    @Test
    fun `steer arriving mid-run is consumed not lost`() = runBlocking {
        val cap = EchoCapability()
        val (loop, inbox, model) = makeLoop(listOf(
            ModelTurn("", listOf(ToolCallRequest("c1", "echo_say", """{"text":"第一步"}"""))),
            ModelTurn("已把插话并入，最终回答"),
        ), cap, delayMs = 200)
        // Steer lands right before the second model call.
        val t = Thread {
            Thread.sleep(150)
            runBlocking { inbox.offer(RunInput.Steer("顺便把标题改成红色")) }
        }
        t.start()
        val result = loop.run()
        t.join()
        assertEquals(com.cottonchou.dshphone.core.run.RunOutcome.DONE, result.outcome)
        assertTrue("steer 应让循环多走一轮模型调用", model.asked >= 2)
    }

    @Test
    fun `repeating the same tool call trips the stuck rule`() = runBlocking {
        val call = ToolCallRequest("c", "echo_say", """{"text":"重复"}""")
        val (loop, _, _) = makeLoop(listOf(
            ModelTurn("", listOf(call)), ModelTurn("", listOf(call)), ModelTurn("", listOf(call)),
            ModelTurn("", listOf(call)),
        ), EchoCapability())
        val result = loop.run()
        assertTrue(result.reason is com.cottonchou.dshphone.core.agent.StopReason.Stuck)
    }
}

class SafetyPolicyTest {

    private fun policy() = HostSafetyPolicy("com.cottonchou.dshphone", clock = { 0L })

    @Test
    fun `bank package is hard denied with no toggle`() {
        val p = policy()
        val d = p.evaluate("com.chinamworld.icbc", DeviceAction.Tap, userPresent = true)
        assertEquals(com.cottonchou.dshphone.core.safety.SafetyVerdict.DENY_HARD, d.verdict)
    }

    @Test
    fun `comms write requires confirmation when present`() {
        val p = policy()
        val d = p.evaluate("com.tencent.mm", DeviceAction.External("发送消息"), userPresent = true)
        assertEquals(com.cottonchou.dshphone.core.safety.SafetyVerdict.REQUIRE_CONFIRM, d.verdict)
    }

    @Test
    fun `comms write is refused when nobody is around`() {
        val p = policy()
        val d = p.evaluate("com.tencent.mm", DeviceAction.External("发送消息"), userPresent = false)
        assertEquals(com.cottonchou.dshphone.core.safety.SafetyVerdict.DENY_HARD, d.verdict)
    }

    @Test
    fun `system settings are read only until granted for the task`() {
        val p = policy()
        val denied = p.evaluate("com.android.settings", DeviceAction.Tap, userPresent = true)
        assertEquals(com.cottonchou.dshphone.core.safety.SafetyVerdict.DENY_READ_ONLY, denied.verdict)
        p.grant("com.android.settings", com.cottonchou.dshphone.core.safety.TaskGrant.GrantKind.SYSTEM_WRITE)
        val allowed = p.evaluate("com.android.settings", DeviceAction.Tap, userPresent = true)
        assertEquals(com.cottonchou.dshphone.core.safety.SafetyVerdict.ALLOW, allowed.verdict)
        p.clearGrants()
        val deniedAgain = p.evaluate("com.android.settings", DeviceAction.Tap, userPresent = true)
        assertEquals(com.cottonchou.dshphone.core.safety.SafetyVerdict.DENY_READ_ONLY, deniedAgain.verdict)
    }

    @Test
    fun `agent never operates its own ui unattended`() {
        val p = policy()
        val d = p.evaluate("com.cottonchou.dshphone", DeviceAction.Tap, userPresent = false)
        assertEquals(com.cottonchou.dshphone.core.safety.SafetyVerdict.DENY_HARD, d.verdict)
    }
}

class MemoryTest {

    @Test
    fun `sensitive content is never stored`() {
        val m = MemoryStore()
        assertFalse(m.remember("我的密码是 abc123", ""))
        assertFalse(m.remember("验证码是 884233", ""))
        assertFalse(m.remember("卡号 6222600260001072444", ""))
        assertTrue(m.remember("用户喜欢深色模式", "偏好"))
        assertEquals(1, m.all().size)
    }
}

class TriggerTest {
    @Test
    fun `conditional trigger reschedules when condition fails`() {
        val t = com.cottonchou.dshphone.core.trigger.Trigger(
            id = "t", threadId = "c", goal = "下雨才提醒", kind = com.cottonchou.dshphone.core.trigger.Trigger.Kind.CONDITIONAL,
            nextFireMs = 100, intervalMs = 3_600_000, condition = "下雨",
        )
        val decision = com.cottonchou.dshphone.core.trigger.TriggerEvaluator.evaluate(t, conditionHolds = false, nowMs = 200)
        assertEquals(com.cottonchou.dshphone.core.trigger.FireDecision.Reschedule, decision)
    }
}

class RiskJudgeTest {
    @Test
    fun `payment button classifies as external and irreversible`() {
        val c = com.cottonchou.dshphone.core.action.RiskJudge.classify(
            DeviceAction.Tap, "确认付款", "应付金额 ¥128.00",
        )
        assertTrue(c.payment)
        assertTrue(c.irreversible)
        assertTrue(c.outward)
    }

    @Test
    fun `plain tap is a write only`() {
        val c = com.cottonchou.dshphone.core.action.RiskJudge.classify(DeviceAction.Tap, "设置", "")
        assertFalse(c.payment)
        assertEquals(com.cottonchou.dshphone.core.action.Risk.WRITE, c.risk)
    }
}
