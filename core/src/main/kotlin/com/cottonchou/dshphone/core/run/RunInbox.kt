package com.cottonchou.dshphone.core.run

import java.util.ArrayDeque

/** Everything that can arrive from outside while a run is in flight. */
sealed interface RunInput {
    /** A mid-task user message: supplements what has been done, never replaces it. */
    data class Steer(val text: String) : RunInput

    /** Answer to an earlier question asked by the agent. */
    data class Answer(val text: String) : RunInput

    /** Decision on a confirmation request (one-shot, never standing consent). */
    data class Confirm(val approved: Boolean, val note: String = "") : RunInput

    /** User finished the takeover handoff and gave control back. */
    data class HandBack(val note: String = "") : RunInput

    data object Cancel : RunInput
}

/**
 * The single door through which outside input reaches a running agent.
 * Steers accumulate until the loop picks them up; the inbox only closes when
 * no unconsumed steer is pending. Thread-safe without suspension, so callers
 * in any context can offer input.
 */
class RunInbox {
    private val lock = Object()
    private val pending = ArrayDeque<RunInput>()
    private var closed = false
    private var pendingSteers = 0

    fun offer(input: RunInput): Boolean = synchronized(lock) {
        if (closed) return false
        if (input is RunInput.Steer) pendingSteers++
        pending.add(input)
        true
    }

    /** Drain everything pending; called by the loop before each model request. */
    fun drain(): List<RunInput> = synchronized(lock) {
        val out = pending.toList()
        pending.clear()
        pendingSteers = 0
        out
    }

    /** Cheap check used to cancel in-flight model requests when a steer lands. */
    fun hasPendingSteer(): Boolean = synchronized(lock) { pendingSteers > 0 }

    /** Close the inbox unless a fresh steer is waiting; returns true when closed. */
    fun closeUnlessSteered(): Boolean = synchronized(lock) {
        if (pendingSteers > 0) return false
        closed = true
        true
    }

    fun isClosed(): Boolean = synchronized(lock) { closed }
}
