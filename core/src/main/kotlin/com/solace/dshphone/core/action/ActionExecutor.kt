package com.solace.dshphone.core.action

import com.solace.dshphone.core.run.RunProvenance
import com.solace.dshphone.core.safety.DeviceAction
import com.solace.dshphone.core.safety.HostSafetyPolicy
import com.solace.dshphone.core.safety.SafetyVerdict

/** Why an action did not run. */
sealed interface Block {
    data class SafetyDenied(val reason: String) : Block
    data class ConfirmationRequired(val prompt: String, val amount: String?) : Block
    data class ApprovalUnavailable(val reason: String) : Block
    data class PacingWait(val waitedMs: Long) : Block
}

/** Outcome of executing one action. */
sealed interface ActionResult {
    data class Ok(val effectSummary: String, val waitedMs: Long = 0) : ActionResult
    data class Blocked(val block: Block) : ActionResult
    data class Failed(val error: String) : ActionResult
}

/** The concrete performer behind the executor, implemented per capability. */
fun interface ActionPerformer {
    suspend fun perform(action: DeviceAction, placeId: String): String
}

/**
 * The single door through which every world-changing action passes:
 * safety policy → payment approval → pacing → perform → record.
 * There is no other way to act, and callers cannot construct passes directly.
 */
class ActionExecutor(
    private val safety: HostSafetyPolicy,
    private val approval: ApprovalGate,
    private val pacing: OutwardPacing?,
    private val performer: ActionPerformer,
    private val provenance: RunProvenance,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val records = ArrayList<ActionRecord>()

    suspend fun execute(action: DeviceAction, placeId: String, targetText: String, onScreenText: String): ActionResult {
        // 1. host safety
        val decision = safety.evaluate(placeId, action, provenance.attendance == com.solace.dshphone.core.run.Attendance.PRESENT)
        when (decision.verdict) {
            SafetyVerdict.DENY_HARD -> return ActionResult.Blocked(Block.SafetyDenied(decision.reason))
            SafetyVerdict.DENY_READ_ONLY -> return ActionResult.Blocked(Block.SafetyDenied(decision.reason))
            SafetyVerdict.REQUIRE_CONFIRM -> {
                if (approval.mode == ApprovalMode.AUTONOMOUS) {
                    // In autonomous mode the comms confirm tier collapses; the hard tiers above still hold.
                } else {
                    val answered = approval.requestConfirmation("向 $placeId 发送内容：${(action as? DeviceAction.External)?.summary ?: ""}")
                    if (!answered) return ActionResult.Blocked(Block.SafetyDenied("你未确认这次发送，已跳过"))
                }
            }
            SafetyVerdict.ALLOW -> {}
        }

        val classified = RiskJudge.classify(action, targetText, onScreenText)

        // 2. payment approval — only ever a one-shot, only with the user present
        if (classified.payment) {
            if (provenance.origin != com.solace.dshphone.core.run.RunOrigin.UI ||
                provenance.attendance != com.solace.dshphone.core.run.Attendance.PRESENT
            ) {
                return ActionResult.Blocked(Block.ApprovalUnavailable("花钱的操作只能由你在场时确认"))
            }
            if (approval.mode == ApprovalMode.ASK) {
                val amount = classified.payment
                val ok = approval.requestConfirmation("检测到付款操作（$targetText）。确认只放行这一次吗？", payment = amount)
                if (!ok) return ActionResult.Blocked(Block.ConfirmationRequired("付款未确认", null))
            }
        }

        // 3. pacing between irreversible outward actions on the same target
        var waited = 0L
        if (classified.outward && classified.irreversible && pacing != null) {
            val wait = pacing.waitRequiredMs(placeId)
            if (wait > 0) {
                kotlinx.coroutines.delay(wait)
                waited = wait
            }
        }

        // 4. perform
        return try {
            val effect = performer.perform(action, placeId)
            if (classified.outward && pacing != null) pacing.markDone(placeId)
            records.add(ActionRecord(clock(), action, placeId, targetText, classified, waited, effect))
            ActionResult.Ok(effect, waited)
        } catch (e: Exception) {
            ActionResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun records(): List<ActionRecord> = records.toList()
}

data class ActionRecord(
    val atMs: Long,
    val action: DeviceAction,
    val placeId: String,
    val targetText: String,
    val classified: ClassifiedRisk,
    val waitedMs: Long,
    val effect: String,
)

enum class ApprovalMode { ASK, AUTONOMOUS }

/**
 * User-consent surface for the run. Consent is per-action and per-task:
 * there is no standing grant, and unattended runs can never reach a human here.
 */
interface ApprovalGate {
    val mode: ApprovalMode

    /** Returns true only if the user approved this one action. */
    suspend fun requestConfirmation(prompt: String, payment: Boolean = false): Boolean
}
