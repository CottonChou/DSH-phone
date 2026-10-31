package com.cottonchou.dshphone.core.agent

import com.cottonchou.dshphone.core.action.ActionExecutor
import com.cottonchou.dshphone.core.memory.MemoryStore
import com.cottonchou.dshphone.core.model.ChatModel
import com.cottonchou.dshphone.core.model.ModelMessage
import com.cottonchou.dshphone.core.model.ModelTurn
import com.cottonchou.dshphone.core.model.SteeredAwayException
import com.cottonchou.dshphone.core.model.ToolCallRequest
import com.cottonchou.dshphone.core.observe.EvidenceLedger
import com.cottonchou.dshphone.core.observe.ObsKind
import com.cottonchou.dshphone.core.observe.Observation
import com.cottonchou.dshphone.core.observe.Place
import com.cottonchou.dshphone.core.run.RunInbox
import com.cottonchou.dshphone.core.run.RunInput
import com.cottonchou.dshphone.core.run.RunOutcome
import com.cottonchou.dshphone.core.run.RunSpec
import com.cottonchou.dshphone.core.tool.CapabilityApi
import com.cottonchou.dshphone.core.tool.ToolContext
import com.cottonchou.dshphone.core.tool.ToolDispatcher
import com.cottonchou.dshphone.core.tool.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Why the loop stopped. */
sealed interface StopReason {
    data class Answered(val text: String) : StopReason
    data class EndedNotDone(val message: String, val subtype: String) : StopReason
    data object Cancelled : StopReason
    data class ModelFailed(val error: String) : StopReason
    data class StepBudgetExhausted(val steps: Int) : StopReason
    data class Stuck(val reason: String) : StopReason
}

class LoopConfig(
    val maxSteps: Int = 40,
    val maxEmptyReplies: Int = 2,
    val maxConsecutiveFailures: Int = 4,
    val maxRepeatSameAction: Int = 3,
    val questionTimeoutMs: Long = 10 * 60_000,
)

data class RunResult(
    val reason: StopReason,
    val outcome: RunOutcome,
    val steps: Int,
)

/**
 * The run's beating heart. It only schedules; every judgement lives in a
 * dedicated piece (turn policy, stop rules, tool dispatcher).
 *
 * Steer semantics: user messages arriving mid-run are drained before each model
 * call and appended to the history — earlier work is never rolled back. An
 * in-flight model request is dropped when a steer lands, and the loop re-asks
 * with everything kept.
 */
class AgentLoop(
    private val spec: RunSpec,
    private val model: ChatModel,
    capabilities: List<CapabilityApi>,
    private val executor: ActionExecutor,
    private val memory: MemoryStore,
    private val inbox: RunInbox,
    private val ledger: EvidenceLedger,
    private val systemPrompt: String,
    private val config: LoopConfig = LoopConfig(),
) {
    private val history = ArrayList<ModelMessage>()
    private val turnPolicy = TurnPolicy(config)
    private val stopRules = StopRules(config)
    private var awaitingAnswer = false
    private var notDone: Pair<String, String>? = null

    private val toolContext = ToolContext(
        step = 0,
        executor = executor,
        ledger = ledger,
        memory = memory,
        capabilities = capabilities.associateBy { it.id },
        askUser = { question -> askUserAndWait(question) },
        finishNotDone = { message, subtype ->
            notDone = message to subtype
            throw FinishedNotDone
        },
    )

    private val dispatcher = ToolDispatcher(ToolRegistry.standard(capabilities), toolContext)

    suspend fun run(): RunResult {
        var step = 0
        history.add(ModelMessage.system(systemPrompt))
        if (spec.priorTurns.isNotEmpty()) {
            history.add(ModelMessage.user("此前对话：\n${spec.priorTurns.joinToString("\n\n")}"))
        }
        history.add(ModelMessage.user(spec.goal))
        dispatcher.arm()

        while (step < config.maxSteps) {
            val steerTexts = ArrayList<String>()
            inbox.drain().forEach { input ->
                when (input) {
                    is RunInput.Steer -> steerTexts.add(input.text)
                    is RunInput.Answer -> {
                        awaitingAnswer = false
                        history.add(ModelMessage.user("（回答）${input.text}"))
                    }
                    is RunInput.HandBack -> {
                        history.add(ModelMessage.user("（已交还）用户处理完毕：${input.note.ifBlank { "页面已交回" }}；先核实现场再继续"))
                    }
                    is RunInput.Confirm -> history.add(
                        ModelMessage.user("（确认）${if (input.approved) "同意" else "不同意"}${input.note.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""}")
                    )
                    RunInput.Cancel -> return RunResult(StopReason.Cancelled, RunOutcome.UNKNOWN, step)
                }
            }
            if (steerTexts.isNotEmpty()) {
                history.add(ModelMessage.user("（插话）${steerTexts.joinToString("；")}——把它作为补充，继续当前任务"))
            }

            val turn: ModelTurn = try {
                model.complete(
                    history,
                    dispatcher.currentSchemas(),
                    cancellable = { inbox.hasPendingSteer() },
                )
            } catch (e: SteeredAwayException) {
                continue // request dropped mid-flight; the steer is picked up next pass
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return RunResult(StopReason.ModelFailed(e.message ?: "model error"), RunOutcome.UNKNOWN, step)
            }

            if (turn.wantsTools) {
                history.add(ModelMessage.assistant(turn.text, turn.toolCalls))
                var failures = 0
                for (call in turn.toolCalls) {
                    step++
                    toolContext.step = step
                    val result = dispatcher.dispatch(call)
                    history.add(ModelMessage.toolResult(call.id, result.text))
                    ledger.add(Observation(
                        id = "", kind = ObsKind.OUTPUT, sourceCapability = "tool",
                        place = Place(Place.Kind.COMMAND, call.name),
                        title = call.name, text = result.text.take(2_000), step = step,
                        timestampMs = System.currentTimeMillis(),
                    ))
                    if (!result.ok) failures++
                    notDone?.let { (msg, sub) ->
                        return RunResult(StopReason.EndedNotDone(msg, sub), RunOutcome.NOT_POSSIBLE, step)
                    }
                }
                stopRules.noteFailures(failures)
                stopRules.noteRepeatedAction(turn.toolCalls)
                stopRules.assessStuck()?.let { return RunResult(it, RunOutcome.UNKNOWN, step) }
                continue
            }

            if (turn.text.isBlank()) {
                if (turnPolicy.remindEmpty()) {
                    history.add(ModelMessage.user("（系统提示）你上一条回复是空的；要么继续调用工具，要么给出明确回答"))
                    continue
                }
                return RunResult(StopReason.Stuck("连续空回复"), RunOutcome.UNKNOWN, step)
            }
            if (awaitingAnswer) {
                turnPolicy.remindUnanswered(history)
            }
            history.add(ModelMessage.assistant(turn.text))
            if (inbox.closeUnlessSteered()) {
                val outcome = if (stopRules.suspiciousAnswer(turn.text)) RunOutcome.PARTIAL else RunOutcome.DONE
                return RunResult(StopReason.Answered(turn.text), outcome, step)
            }
            // A steer landed while wrapping up; keep going with it.
        }
        return RunResult(StopReason.StepBudgetExhausted(config.maxSteps), RunOutcome.PARTIAL, config.maxSteps)
    }

    private suspend fun askUserAndWait(question: String): String? {
        awaitingAnswer = true
        val deadline = System.currentTimeMillis() + config.questionTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            val inputs = inbox.drain()
            for (input in inputs) {
                when (input) {
                    is RunInput.Answer -> {
                        awaitingAnswer = false
                        history.add(ModelMessage.user("（回答）${input.text}"))
                        return input.text
                    }
                    RunInput.Cancel -> return null
                    else -> inbox.offer(input) // put non-answers back for the main loop
                }
            }
            delay(500)
        }
        awaitingAnswer = false
        return null
    }

    private object FinishedNotDone : Throwable() {
        override fun fillInStackTrace(): Throwable = this
    }
}

/** Nudges that keep a run honest without deciding anything itself. */
class TurnPolicy(private val config: LoopConfig) {
    private var emptyReminders = 0

    fun remindEmpty(): Boolean {
        if (emptyReminders >= config.maxEmptyReplies) return false
        emptyReminders++
        return true
    }

    fun remindUnanswered(history: MutableList<ModelMessage>) {
        history.add(ModelMessage.user("（系统提示）用户刚回答了你的问题；先处理这个回答再继续"))
    }
}

/** Mechanical stop conditions: repeats, failure streaks, dead ends. */
class StopRules(private val config: LoopConfig) {
    private var consecutiveFailures = 0
    private val actionCounts = HashMap<String, Int>()

    fun noteFailures(failures: Int) {
        if (failures > 0) consecutiveFailures += failures else consecutiveFailures = 0
    }

    fun noteRepeatedAction(calls: List<ToolCallRequest>) {
        calls.forEach {
            val key = it.name + it.argumentsJson.take(80)
            actionCounts[key] = (actionCounts[key] ?: 0) + 1
        }
    }

    fun assessStuck(): StopReason.Stuck? {
        if (consecutiveFailures >= config.maxConsecutiveFailures) {
            return StopReason.Stuck("连续 $consecutiveFailures 个工具调用失败，停止任务")
        }
        actionCounts.entries.firstOrNull { it.value >= config.maxRepeatSameAction }?.let {
            return StopReason.Stuck("重复同一动作已达 ${it.value} 次，判定为没有进展")
        }
        return null
    }

    fun suspiciousAnswer(text: String): Boolean =
        text.contains("未能") || text.contains("无法") || text.contains("没做成")
}
