package com.cottonchou.dshphone.core.run

import kotlinx.serialization.Serializable

/** Full description of one run, decided once at start. */
@Serializable
data class RunSpec(
    val id: String,
    val goal: String,
    val threadId: String,
    val priorTurns: List<String> = emptyList(),
    val capabilities: Set<String> = setOf("phone", "web"),
    val readOnly: Boolean = false,
    val parentRunId: String? = null,
    val stepBudget: Int = UNLIMITED_STEPS,
) {
    companion object {
        const val UNLIMITED_STEPS = -1
    }
}

enum class RunPhase { QUEUED, PREPARING, RUNNING, WAITING_USER, PAUSED, FINISHING, ENDED, INTERRUPTED }

enum class RunOrigin { UI, TRIGGER, CHILD }

enum class Attendance { PRESENT, UNATTENDED }

enum class RunOutcome { DONE, PARTIAL, NOT_POSSIBLE, UNKNOWN }

/** Where this run came from and whether a human is around. */
data class RunProvenance(
    val origin: RunOrigin,
    val attendance: Attendance,
)
