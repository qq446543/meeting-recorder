package com.meetnote.recorder.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meetnote.recorder.data.MeetingRepository
import com.meetnote.recorder.data.MeetingStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MeetingListViewModel(
    private val repo: MeetingRepository,
) : ViewModel() {

    val meetings: StateFlow<List<MeetingStats>> = repo.observeMeetings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _navigateTo = MutableStateFlow<Long?>(null)
    val navigateTo: StateFlow<Long?> = _navigateTo

    fun createMeeting(title: String) {
        viewModelScope.launch {
            val id = repo.createMeeting(title)
            _navigateTo.value = id
        }
    }

    fun consumeNavigation() {
        _navigateTo.value = null
    }
}
