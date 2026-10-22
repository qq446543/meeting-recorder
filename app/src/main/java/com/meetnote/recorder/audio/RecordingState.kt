package com.meetnote.recorder.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RecordingStatus(
    val meetingId: Long = 0L,
    val running: Boolean = false,
    val elapsedMs: Long = 0L,
    val error: String? = null,
)

/** 进程内全局录音状态：服务更新，UI 观察 */
object RecordingState {

    private val _status = MutableStateFlow(RecordingStatus())
    val status: StateFlow<RecordingStatus> = _status.asStateFlow()

    fun bind(meetingId: Long, durationMs: Long) {
        _status.value = RecordingStatus(meetingId = meetingId, elapsedMs = durationMs)
    }

    fun onStart() {
        val s = _status.value
        _status.value = s.copy(running = true, error = null, elapsedMs = 0L)
    }

    fun onTick(ms: Long) {
        _status.value = _status.value.copy(elapsedMs = ms)
    }

    fun onStop(durationMs: Long) {
        _status.value = _status.value.copy(running = false, elapsedMs = durationMs)
    }

    fun onError(message: String) {
        _status.value = _status.value.copy(running = false, error = message)
    }

    fun clear() {
        _status.value = RecordingStatus()
    }
}
