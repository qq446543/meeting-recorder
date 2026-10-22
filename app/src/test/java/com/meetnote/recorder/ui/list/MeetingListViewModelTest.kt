package com.meetnote.recorder.ui.list

import android.os.Looper
import com.meetnote.recorder.data.Meeting
import com.meetnote.recorder.data.MeetingRepository
import com.meetnote.recorder.data.MeetingStats
import com.meetnote.recorder.data.ShotResult
import com.meetnote.recorder.data.SlidePage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** 仓库的内存假实现：只覆盖 ViewModel 用到的方法，其余一律不支持 */
private class FakeRepo : MeetingRepository {
    val stats = MutableStateFlow<List<MeetingStats>>(emptyList())
    var lastTitle: String? = null
    private var nextId = 1L

    override fun observeMeetings(): Flow<List<MeetingStats>> = stats

    override suspend fun createMeeting(title: String): Long {
        lastTitle = title
        val id = nextId++
        stats.value = stats.value + MeetingStats(
            id = id, title = title, createdAt = id * 1000,
            endedAt = null, audioDurationMs = 0, pageCount = 0,
        )
        return id
    }

    override fun observePages(meetingId: Long): Flow<List<SlidePage>> =
        throw UnsupportedOperationException()

    override suspend fun meeting(id: Long): Meeting? = throw UnsupportedOperationException()

    override suspend fun pageCount(id: Long): Int = throw UnsupportedOperationException()

    override suspend fun finishMeeting(id: Long, audioPath: String?, durationMs: Long) =
        throw UnsupportedOperationException()

    override suspend fun deleteMeeting(id: Long) = throw UnsupportedOperationException()

    override suspend fun deletePage(pageId: Long) = throw UnsupportedOperationException()

    override suspend fun registerShot(
        meetingId: Long,
        imageFile: File,
        width: Int,
        height: Int,
        similarityToLast: Float,
    ): ShotResult = throw UnsupportedOperationException()

    override suspend fun targetFileFor(meetingId: Long, similarityToLast: Float): File =
        throw UnsupportedOperationException()

    override fun meetingAudioFile(meetingId: Long): File = throw UnsupportedOperationException()

    override fun freeBytes(): Long = throw UnsupportedOperationException()
}

/**
 * ViewModel 用到 viewModelScope（Dispatchers.Main），需 Robolectric 提供主 Looper；
 * 其余适配见各用例注释。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeetingListViewModelTest {

    @Test
    fun `createMeeting stores title and emits navigation event`() = runBlocking {
        val repo = FakeRepo()
        val vm = MeetingListViewModel(repo)
        // meetings 用 WhileSubscribed，必须有订阅者才会收集仓库上游
        val collector = subscribeToMeetings(vm)

        vm.createMeeting("需求评审")
        idleMainLooper()

        assertEquals("需求评审", repo.lastTitle)
        assertNotNull(vm.navigateTo.value)
        assertEquals(1, vm.meetings.value.size)
        collector.cancel()
    }

    @Test
    fun `consumeNavigation clears the event`() {
        val vm = MeetingListViewModel(FakeRepo())
        vm.createMeeting("复盘")
        idleMainLooper()
        assertNotNull(vm.navigateTo.value) // 先证明一次性事件确实发出了

        vm.consumeNavigation()
        assertNull(vm.navigateTo.value)
    }

    @Test
    fun `meetings flow mirrors repository`() = runBlocking {
        val repo = FakeRepo()
        val vm = MeetingListViewModel(repo)
        val collector = subscribeToMeetings(vm)

        repo.stats.value = listOf(
            MeetingStats(1, "A", 1, null, 0, 3),
            MeetingStats(2, "B", 2, null, 0, 0),
        )
        idleMainLooper()

        assertEquals(listOf("A", "B"), vm.meetings.value.map { it.title })
        assertEquals(3, vm.meetings.value.first().pageCount)
        collector.cancel()
    }

    /** 挂一个空收集器充当订阅者，UNDISPATCHED 启动保证订阅立即生效 */
    private fun CoroutineScope.subscribeToMeetings(vm: MeetingListViewModel): Job =
        launch(start = CoroutineStart.UNDISPATCHED) { vm.meetings.collect {} }

    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }
}
