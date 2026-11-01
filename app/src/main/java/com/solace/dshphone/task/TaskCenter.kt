package com.solace.dshphone.task

import com.solace.dshphone.core.run.RunPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Live state of one run, as the UI sees it. */
data class RunState(
    val runId: String,
    val threadId: String,
    val goal: String,
    val phase: RunPhase = RunPhase.QUEUED,
    val engine: String = "builtin",
    val step: Int = 0,
    val lastEvent: String = "",
)

/**
 * Task center: at most one run per conversation, a bounded number of
 * conversations running in parallel, the rest queued FIFO. A message sent to a
 * busy conversation becomes a steer of the live run instead of a new task.
 */
class TaskCenter(private val scope: CoroutineScope, private val maxParallel: Int = 2) {

    private val mutex = Mutex()
    private val live = ConcurrentHashMap<String, Job>()      // threadId -> job
    private val queue = ArrayDeque<() -> Unit>()
    private val states = LinkedHashMap<String, RunState>()
    var onChange: (() -> Unit)? = null

    fun stateFor(threadId: String): RunState? = synchronized(states) {
        states.values.lastOrNull { it.threadId == threadId }
    }

    fun allStates(): List<RunState> = synchronized(states) { states.values.toList().reversed() }

    fun isBusy(threadId: String): Boolean = live.containsKey(threadId)

    /** Try to start a run for [threadId]; when parallel slots are full, queue it. */
    suspend fun begin(
        threadId: String,
        goal: String,
        engine: String,
        start: suspend () -> Unit,
    ): StartResult = mutex.withLock {
        if (live.containsKey(threadId)) return StartResult.STEER_INSTEAD
        val runId = "run-${System.currentTimeMillis()}"
        if (live.size >= maxParallel) {
            queue.add {
                val queuedJob = scope.launch { runOne(runId, threadId, goal, engine, start) }
                live[threadId] = queuedJob
            }
            synchronized(states) {
                states[runId] = RunState(runId, threadId, goal, RunPhase.QUEUED, engine)
                snapshotChanged()
            }
            return StartResult.QUEUED
        }
        val job = scope.launch { runOne(runId, threadId, goal, engine, start) }
        live[threadId] = job
        StartResult.STARTED
    }

    private suspend fun runOne(runId: String, threadId: String, goal: String, engine: String, start: suspend () -> Unit) {
        update(runId) { it.copy(phase = RunPhase.RUNNING) }
        try {
            start()
            update(runId) { it.copy(phase = RunPhase.ENDED) }
        } catch (e: Exception) {
            update(runId) { it.copy(phase = RunPhase.INTERRUPTED, lastEvent = e.message ?: "中断") }
        } finally {
            live.remove(threadId)
            next()
        }
    }

    private fun next() {
        mutex.tryLock().let { locked ->
            if (!locked) return
            try {
                while (live.size < maxParallel && queue.isNotEmpty()) queue.removeFirst().invoke()
            } finally {
                mutex.unlock()
            }
        }
    }

    fun cancel(threadId: String) {
        live[threadId]?.cancel()
        live.remove(threadId)
        synchronized(states) {
            states.values.filter { it.threadId == threadId }.forEach { st ->
                states[st.runId] = st.copy(phase = RunPhase.INTERRUPTED)
            }
            snapshotChanged()
        }
    }

    fun update(runId: String, patch: (RunState) -> RunState) {
        synchronized(states) {
            states[runId]?.let { states[runId] = patch(it) }
            snapshotChanged()
        }
    }

    private fun snapshotChanged() {
        onChange?.invoke()
    }

    enum class StartResult { STARTED, QUEUED, STEER_INSTEAD }
}
