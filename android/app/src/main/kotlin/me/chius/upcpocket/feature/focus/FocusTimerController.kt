package me.chius.upcpocket.feature.focus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FocusMode(
    val label: String,
    val durationSeconds: Int,
) {
    FOCUS_25(label = "专注 25 分钟", durationSeconds = 25 * 60),
    SHORT_BREAK_5(label = "短休 5 分钟", durationSeconds = 5 * 60),
    DEEP_45(label = "深度 45 分钟", durationSeconds = 45 * 60),
}

data class FocusTimerUiState(
    val mode: FocusMode = FocusMode.FOCUS_25,
    val remainingSeconds: Int = FocusMode.FOCUS_25.durationSeconds,
    val isRunning: Boolean = false,
    val completedSessionsInMemory: Int = 0,
) {
    val progress: Float
        get() = if (mode.durationSeconds <= 0) 0f
        else remainingSeconds.toFloat() / mode.durationSeconds.toFloat()

    val formattedTime: String
        get() {
            val minutes = remainingSeconds / 60
            val seconds = remainingSeconds % 60
            return "%02d:%02d".format(minutes, seconds)
        }
}

/**
 * In-memory Pomodoro prototype controller for M0.
 * Zero network usage and zero blocking disk I/O.
 */
class FocusTimerController(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow(FocusTimerUiState())
    val state: StateFlow<FocusTimerUiState> = _state.asStateFlow()

    private var tickJob: Job? = null

    fun selectMode(mode: FocusMode) {
        tickJob?.cancel()
        tickJob = null
        _state.update {
            it.copy(
                mode = mode,
                remainingSeconds = mode.durationSeconds,
                isRunning = false,
            )
        }
    }

    fun toggleStartPause() {
        if (_state.value.isRunning) {
            pause()
        } else {
            start()
        }
    }

    fun reset() {
        tickJob?.cancel()
        tickJob = null
        _state.update {
            it.copy(
                remainingSeconds = it.mode.durationSeconds,
                isRunning = false,
            )
        }
    }

    private fun start() {
        if (tickJob?.isActive == true) return
        _state.update { current ->
            val resetIfZero = if (current.remainingSeconds <= 0) {
                current.mode.durationSeconds
            } else {
                current.remainingSeconds
            }
            current.copy(remainingSeconds = resetIfZero, isRunning = true)
        }
        tickJob = scope.launch {
            while (true) {
                delay(1_000L)
                var finished = false
                _state.update { current ->
                    if (!current.isRunning) {
                        current
                    } else if (current.remainingSeconds <= 1) {
                        finished = true
                        current.copy(
                            remainingSeconds = 0,
                            isRunning = false,
                            completedSessionsInMemory = current.completedSessionsInMemory + 1,
                        )
                    } else {
                        current.copy(remainingSeconds = current.remainingSeconds - 1)
                    }
                }
                if (finished) {
                    tickJob = null
                    break
                }
            }
        }
    }

    private fun pause() {
        tickJob?.cancel()
        tickJob = null
        _state.update { it.copy(isRunning = false) }
    }
}
