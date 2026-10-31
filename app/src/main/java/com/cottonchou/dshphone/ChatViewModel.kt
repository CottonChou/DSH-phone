package com.cottonchou.dshphone

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cottonchou.dshphone.task.TaskRunner
import com.cottonchou.dshphone.ui.ChatMessage
import com.cottonchou.dshphone.ui.MessageRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UiState(
    val messages: List<ChatMessage> = emptyList(),
    val running: Boolean = false,
    val status: String = "",
)

/** Owns the conversation and forwards sends/stops into the task runner. */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val dsh = app as DshPhoneApp
    private val runner = TaskRunner(dsh)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val priorTurns = ArrayList<String>()

    init {
        dsh.taskCenter.onChange = {
            _uiState.value = _uiState.value.copy(
                running = dsh.taskCenter.isBusy(THREAD),
            )
        }
    }

    fun send(text: String) {
        append(ChatMessage(MessageRole.USER, text, timeFmt.format(Date())))
        priorTurns.add("用户：$text")
        _uiState.value = _uiState.value.copy(status = "已收到，正在处理…")

        viewModelScope.launch {
            val result = dsh.taskCenter.begin(THREAD, text, dsh.settings.preferredEngine) {
                // A busy conversation turns a new message into a steer.
                if (!runner.steer(THREAD, text)) {
                    runner.run(
                        threadId = THREAD,
                        goal = text,
                        priorTurns = priorTurns.dropLast(1),
                        origin = com.cottonchou.dshphone.core.run.RunOrigin.UI,
                        onEvent = { event ->
                            append(ChatMessage(MessageRole.EVENT, event))
                            _uiState.value = _uiState.value.copy(status = event)
                        },
                        onAnswer = { answer ->
                            if (answer.isNotBlank()) {
                                append(ChatMessage(MessageRole.ASSISTANT, answer, timeFmt.format(Date())))
                                priorTurns.add("DSH-phone：$answer")
                            }
                        },
                    )
                }
            }
            when (result) {
                com.cottonchou.dshphone.task.TaskCenter.StartResult.STEER_INSTEAD ->
                    append(ChatMessage(MessageRole.EVENT, "已收到，下一步处理"))
                com.cottonchou.dshphone.task.TaskCenter.StartResult.QUEUED ->
                    append(ChatMessage(MessageRole.EVENT, "已排队，等当前任务结束就执行"))
                else -> {}
            }
        }
    }

    fun stop() {
        dsh.taskCenter.cancel(THREAD)
        append(ChatMessage(MessageRole.EVENT, "已停止"))
    }

    private fun append(msg: ChatMessage) {
        _uiState.value = _uiState.value.copy(messages = _uiState.value.messages + msg)
    }

    companion object {
        private const val THREAD = "main"
    }
}
