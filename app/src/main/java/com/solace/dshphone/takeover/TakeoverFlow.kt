package com.solace.dshphone.takeover

import com.solace.dshphone.core.run.RunInbox
import com.solace.dshphone.core.run.RunInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class TakeoverState { NONE, WAITING, RETURNED, TIMEOUT }

/**
 * Real takeover, not a toast. The run pauses, a notification with a jump target
 * goes out, the app hands the foreground back to the user, and a supervisor
 * watches the clock: if nobody comes back within [timeoutMs] the run ends
 * honestly instead of hanging forever.
 */
class TakeoverFlow(private val scope: CoroutineScope, private val timeoutMs: Long = 10 * 60_000) {

    var state: TakeoverState = TakeoverState.NONE
        private set
    var reason: String = ""
        private set
    private var watchdog: Job? = null
    var onStateChange: ((TakeoverState) -> Unit)? = null

    /** Called by the engine when it needs the human (login, captcha, payment). */
    suspend fun begin(reason: String, notify: suspend (String) -> Unit): String {
        state = TakeoverState.WAITING
        this.reason = reason
        onStateChange?.invoke(state)
        notify("需要你接管：$reason。处理完回到 DSH-phone 点「交还」，任务会先核实现场再继续。")
        watchdog = scope.launch {
            delay(timeoutMs)
            if (state == TakeoverState.WAITING) {
                state = TakeoverState.TIMEOUT
                onStateChange?.invoke(state)
            }
        }
        return reason
    }

    /** User finished and handed back; true when the run should resume. */
    suspend fun handBack(note: String, inbox: RunInbox): Boolean {
        watchdog?.cancel()
        return when (state) {
            TakeoverState.WAITING -> {
                state = TakeoverState.RETURNED
                onStateChange?.invoke(state)
                inbox.offer(RunInput.HandBack(note))
                true
            }
            else -> false
        }
    }

    /** True when the watchdog fired; the run should end with an honest report. */
    fun timedOut(): Boolean = state == TakeoverState.TIMEOUT

    fun reset() {
        watchdog?.cancel()
        state = TakeoverState.NONE
        onStateChange?.invoke(state)
    }
}
