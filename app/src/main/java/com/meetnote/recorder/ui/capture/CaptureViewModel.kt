package com.meetnote.recorder.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meetnote.recorder.audio.RecordingState
import com.meetnote.recorder.audio.RecordingStatus
import com.meetnote.recorder.capture.OpenCvLoader
import com.meetnote.recorder.capture.Point2
import com.meetnote.recorder.capture.Quad
import com.meetnote.recorder.data.MeetingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CaptureUiState(
    val title: String = "",
    val statusText: String = "准备中…",
    val pageCount: Int = 0,
    val paused: Boolean = false,
    val recording: RecordingStatus = RecordingStatus(),
    val quad: Quad? = null,
    val degraded: Boolean = false,
    val opencvAvailable: Boolean = false,
)

class CaptureViewModel(
    private val meetingId: Long,
    private val repo: MeetingRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(CaptureUiState())
    val ui: StateFlow<CaptureUiState> = _ui.asStateFlow()

    init {
        // 同步探测（幂等、失败缓存）：JVM 单测里 System.loadLibrary 失败 → false
        val opencv = runCatching { OpenCvLoader.isAvailable() }.getOrDefault(false)
        _ui.update { it.copy(opencvAvailable = opencv) }

        viewModelScope.launch {
            val m = repo.meeting(meetingId)
            _ui.update { it.copy(title = m?.title ?: "会议") }
        }
        viewModelScope.launch {
            repo.observePages(meetingId).collect { pages ->
                _ui.update { it.copy(pageCount = pages.size) }
            }
        }
        viewModelScope.launch {
            RecordingState.status.collect { rec ->
                _ui.update { it.copy(recording = rec) }
            }
        }
    }

    /** 暂停/继续抓拍，返回新状态 */
    fun togglePause(): Boolean {
        val next = !_ui.value.paused
        _ui.update { it.copy(paused = next) }
        return next
    }

    fun onPipelineStatus(text: String, quad: Quad?, degraded: Boolean) {
        _ui.update { it.copy(statusText = text, quad = quad, degraded = degraded) }
    }

    fun refreshPageCount(n: Int) {
        _ui.update { it.copy(pageCount = n) }
    }

    /**
     * 结束会议：录音服务是写入时长的主路径，这里只在服务没写成（权限被拒、
     * 服务未启动等）时兜底，保证 endedAt 一定有值。
     * [onDone] 在兜底写入（或跳过/失败）完成后回调——拍摄页此时才可以安全离开，
     * 否则导航销毁本 ViewModel 会取消 [viewModelScope]，兜底写入被丢弃。
     */
    fun endMeeting(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                val m = repo.meeting(meetingId) ?: return@launch
                if (m.endedAt == null) {
                    repo.finishMeeting(
                        id = meetingId,
                        audioPath = m.audioPath,
                        durationMs = RecordingState.status.value.elapsedMs,
                    )
                }
            } catch (t: Throwable) {
                // 兜底写入失败不阻断离开（服务是主写入路径）——按全局不得引入崩溃约束
            } finally {
                onDone()
            }
        }
    }

    fun meetingId(): Long = meetingId
}
