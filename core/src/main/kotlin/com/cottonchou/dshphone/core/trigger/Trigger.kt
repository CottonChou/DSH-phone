package com.cottonchou.dshphone.core.trigger

import kotlinx.serialization.Serializable

/** A task trigger: one-shot reminder, periodic check, or condition-gated. */
@Serializable
data class Trigger(
    val id: String,
    val threadId: String,
    val goal: String,
    val kind: Kind,
    /** Epoch ms for one-shot; anchor for periodic. */
    val nextFireMs: Long,
    val intervalMs: Long = 0,
    /** Natural-language condition, e.g. 「下雨才提醒」; empty means always fire. */
    val condition: String = "",
    val enabled: Boolean = true,
) {
    @Serializable
    enum class Kind { ONCE, PERIODIC, CONDITIONAL }
}

/** Evaluation result the host acts on. */
sealed interface FireDecision {
    data object Fire : FireDecision
    data class Skip(val because: String) : FireDecision
    data object Reschedule : FireDecision
}

/** Pure scheduling logic; the host supplies wall time and condition checks. */
object TriggerEvaluator {

    fun due(trigger: Trigger, nowMs: Long): Boolean =
        trigger.enabled && nowMs >= trigger.nextFireMs

    /** Next fire time after a periodic trigger ran. */
    fun reschedule(trigger: Trigger, nowMs: Long): Trigger =
        trigger.copy(nextFireMs = nowMs + trigger.intervalMs)

    /**
     * Conditional triggers only fire when the condition holds. If the condition
     * can't be evaluated (model down, network gone), we reschedule instead of
     * firing blindly.
     */
    fun evaluate(trigger: Trigger, conditionHolds: Boolean?, nowMs: Long): FireDecision = when {
        !due(trigger, nowMs) -> FireDecision.Skip("还没到时间")
        trigger.condition.isBlank() -> FireDecision.Fire
        conditionHolds == true -> FireDecision.Fire
        conditionHolds == false -> FireDecision.Reschedule
        else -> FireDecision.Skip("条件暂时无法判断，本轮跳过")
    }
}
