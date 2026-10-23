package com.meetnote.recorder.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meetnote.recorder.data.MeetingRepository
import com.meetnote.recorder.data.SlidePage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DetailUiState(
    val title: String = "",
    val createdAtMs: Long = 0L,
    val audioDurationMs: Long = 0L,
    val audioAvailable: Boolean = false,
    val pages: List<SlidePage> = emptyList(),
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val error: String? = null,
)

class MeetingDetailViewModel(
    private val meetingId: Long,
    private val repo: MeetingRepository,
    private val player: AudioPlayer,
) : ViewModel() {

    private val meetingFlow = MutableStateFlow<com.meetnote.recorder.data.Meeting?>(null)
    private val playback = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val ui: StateFlow<DetailUiState> = combine(
        repo.observePages(meetingId),
        meetingFlow,
        playback,
        error,
    ) { pages, meeting, playing, err ->
        DetailUiState(
            title = meeting?.title ?: "会议",
            createdAtMs = meeting?.createdAt ?: 0L,
            audioDurationMs = meeting?.audioDurationMs ?: 0L,
            audioAvailable = !meeting?.audioPath.isNullOrBlank() ||
                (meeting?.audioDurationMs ?: 0L) > 0L,
            pages = pages.sortedBy { it.index },
            playing = playing,
            positionMs = if (playing) player.positionMs() else 0L,
            error = err,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            meetingFlow.value = repo.meeting(meetingId)
        }
    }

    fun togglePlay() {
        val meeting = meetingFlow.value
        val path = meeting?.audioPath
        if (path.isNullOrBlank() && (meeting?.audioDurationMs ?: 0L) <= 0L) {
            error.value = "本次会议没有录音"
            playback.value = false
            return
        }
        if (playback.value) {
            player.pause()
            playback.value = false
            return
        }
        val file = repo.meetingAudioFile(meetingId)
        if (!player.prepare(file)) {
            error.value = "录音文件无法播放"
            playback.value = false
            return
        }
        if (player.play()) {
            playback.value = true
            error.value = null
        } else {
            error.value = "播放失败"
        }
    }

    fun deletePage(page: SlidePage) {
        viewModelScope.launch {
            repo.deletePage(page.id)
            refresh()
        }
    }

    fun deleteMeeting(onDone: () -> Unit) {
        viewModelScope.launch {
            player.release()
            repo.deleteMeeting(meetingId)
            onDone()
        }
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
