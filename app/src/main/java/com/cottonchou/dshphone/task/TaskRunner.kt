package com.cottonchou.dshphone.task

import com.cottonchou.dshphone.DshPhoneApp
import com.cottonchou.dshphone.core.action.ActionExecutor
import com.cottonchou.dshphone.core.action.ActionPerformer
import com.cottonchou.dshphone.core.action.ApprovalGate
import com.cottonchou.dshphone.core.action.ApprovalMode
import com.cottonchou.dshphone.core.action.OutwardPacing
import com.cottonchou.dshphone.core.agent.AgentLoop
import com.cottonchou.dshphone.core.memory.MemoryStore
import com.cottonchou.dshphone.core.model.HttpChatModel
import com.cottonchou.dshphone.core.observe.EvidenceLedger
import com.cottonchou.dshphone.core.run.Attendance
import com.cottonchou.dshphone.core.run.RunInbox
import com.cottonchou.dshphone.core.run.RunInput
import com.cottonchou.dshphone.core.run.RunOrigin
import com.cottonchou.dshphone.core.run.RunProvenance
import com.cottonchou.dshphone.core.run.RunSpec
import com.cottonchou.dshphone.core.safety.DeviceAction
import com.cottonchou.dshphone.core.safety.HostSafetyPolicy
import com.cottonchou.dshphone.core.tool.CapabilityApi
import com.cottonchou.dshphone.notify.Notifier
import com.cottonchou.dshphone.phone.AgentAccessibilityService
import com.cottonchou.dshphone.phone.PhoneCapability
import com.cottonchou.dshphone.takeover.TakeoverFlow
import com.cottonchou.dshphone.web.WebCapability
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap

/**
 * Assembles one run: endpoint → model, capabilities → dispatcher, safety →
 * executor, then the loop. This is the only place the pieces meet.
 */
class TaskRunner(private val app: DshPhoneApp) {

    private val activeInboxes = ConcurrentHashMap<String, RunInbox>()
    private val takeovers = ConcurrentHashMap<String, TakeoverFlow>()

    val memory = MemoryStore()

    fun inboxOf(threadId: String): RunInbox? = activeInboxes[threadId]

    fun steer(threadId: String, text: String): Boolean =
        activeInboxes[threadId]?.offer(RunInput.Steer(text)) == true

    fun confirm(threadId: String, approved: Boolean, note: String = ""): Boolean =
        activeInboxes[threadId]?.offer(RunInput.Confirm(approved, note)) == true

    fun handBack(threadId: String, note: String): Boolean {
        val flow = takeovers[threadId]
        val inbox = activeInboxes[threadId]
        if (flow != null && inbox != null) return kotlinx.coroutines.runBlocking { flow.handBack(note, inbox) }
        return false
    }

    suspend fun run(
        threadId: String,
        goal: String,
        priorTurns: List<String>,
        origin: RunOrigin,
        onEvent: (String) -> Unit,
        onAnswer: (String) -> Unit,
    ): String {
        val endpoint = app.settings.endpoint()
            ?: run {
                onAnswer("还没有配置模型。到「设置」里添加 API Key 后再试。")
                return ""
            }
        val engine = app.engineHost.current()

        val spec = RunSpec(
            id = "run-${System.currentTimeMillis()}",
            goal = goal,
            threadId = threadId,
            priorTurns = priorTurns,
            capabilities = if (AgentAccessibilityService.isAvailable()) setOf("phone", "web") else setOf("web"),
        )
        val inbox = RunInbox()
        activeInboxes[threadId] = inbox

        val takeover = TakeoverFlow(app.appScope)
        takeovers[threadId] = takeover

        val safety = HostSafetyPolicy(app.packageName)
        val approval = object : ApprovalGate {
            override val mode: ApprovalMode = app.settings.approvalMode
            override suspend fun requestConfirmation(prompt: String, payment: Boolean): Boolean {
                inbox.offer(RunInput.Steer("__awaiting_confirm__"))
                Notifier.needsInput(app, "需要确认", prompt)
                onEvent("需要确认：$prompt")
                val answer = waitForAnswer(inbox, timeoutMs = 5 * 60_000)
                return answer?.startsWith("同意") == true || answer == "1"
            }
        }
        val ledger = EvidenceLedger()

        val web = com.cottonchou.dshphone.web.WebCapability { app.agentWebView }
        val capabilities = ArrayList<CapabilityApi>()
        if (AgentAccessibilityService.isAvailable()) capabilities.add(PhoneCapability(app.packageName))
        capabilities.add(web)

        val executor = ActionExecutor(
            safety = safety,
            approval = approval,
            pacing = OutwardPacing(),
            performer = ActionPerformer { action, placeId ->
                // Device side effects are executed by the capability tools
                // themselves; this hook records the world-visible summary.
                when (action) {
                    DeviceAction.Tap -> "点击已送达 $placeId"
                    is DeviceAction.Input -> "文本已填入 $placeId"
                    is DeviceAction.Gesture -> "手势 ${action.kind} 已执行"
                    is DeviceAction.External -> "对外操作：${action.summary}"
                    DeviceAction.Read -> "读取 $placeId"
                }
            },
            provenance = RunProvenance(origin, if (origin == RunOrigin.UI) Attendance.PRESENT else Attendance.UNATTENDED),
        )

        val loop = AgentLoop(
            spec = spec,
            model = HttpChatModel(endpoint),
            capabilities = capabilities,
            executor = executor,
            memory = memory,
            inbox = inbox,
            ledger = ledger,
            systemPrompt = buildSystemPrompt(engine),
        )

        onEvent("任务开始（${if (engine == com.cottonchou.dshphone.engine.EngineHost.Kind.DSH) "外部引擎" else "内置引擎"}）")
        Notifier.progress(app, "DSH-phone", "正在执行：${goal.take(24)}…")

        val result = loop.run()

        activeInboxes.remove(threadId)
        takeovers.remove(threadId)

        val answer = when (val reason = result.reason) {
            is com.cottonchou.dshphone.core.agent.StopReason.Answered -> reason.text
            is com.cottonchou.dshphone.core.agent.StopReason.EndedNotDone ->
                "没做完：${reason.message}（原因：${reason.subtype}）"
            is com.cottonchou.dshphone.core.agent.StopReason.Cancelled -> "已停止。${executor.records().size} 个动作已完成。"
            is com.cottonchou.dshphone.core.agent.StopReason.ModelFailed -> "模型调用失败：${reason.error}"
            is com.cottonchou.dshphone.core.agent.StopReason.Stuck -> reason.reason
            is com.cottonchou.dshphone.core.agent.StopReason.StepBudgetExhausted -> "步数用完（${reason.steps} 步），任务暂停。"
        }
        Notifier.reminder(app, "DSH-phone", if (result.outcome == com.cottonchou.dshphone.core.run.RunOutcome.DONE) "任务完成" else "任务未完成")
        onAnswer(answer)
        return answer
    }

    private suspend fun waitForAnswer(inbox: RunInbox, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            inbox.drain().forEach { input ->
                if (input is RunInput.Answer) return input.text
                if (input is RunInput.Confirm && input.approved) return "同意"
                if (input is RunInput.Confirm) return "不同意"
                if (input is RunInput.Steer) return input.text
            }
            kotlinx.coroutines.delay(400)
        }
        return null
    }

    private fun buildSystemPrompt(engine: com.cottonchou.dshphone.engine.EngineHost.Kind): String = buildString {
        appendLine("你是 DSH-phone，一部安卓手机上的私人助理。你把用户的一句话变成做完的事。")
        appendLine("规则：")
        appendLine("1. 先看清再动手：操作前用读取类工具确认界面内容。")
        appendLine("2. 只做用户交代的事；范围外的动作先问。")
        appendLine("3. 做不成时用 end_not_done 说清：已确认做了什么、卡在哪、现在界面是什么。")
        appendLine("4. 银行类应用与付款环节永远交给用户，你不要尝试。")
        appendLine("5. 不代填密码、验证码、卡号。")
        memory.promptParagraph().takeIf { it.isNotBlank() }?.let { appendLine(it) }
    }
}
