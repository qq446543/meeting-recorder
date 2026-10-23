package com.meetnote.recorder.ui.detail

import android.os.Looper
import com.meetnote.recorder.data.Meeting
import com.meetnote.recorder.data.MeetingRepository
import com.meetnote.recorder.data.MeetingStats
import com.meetnote.recorder.data.ShotResult
import com.meetnote.recorder.data.SlidePage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

private class FakePlayer : AudioPlayer {
    var prepared = false
    var playing = false
    override fun prepare(file: File): Boolean { prepared = true; return true }
    override fun play(): Boolean { playing = true; return true }
    override fun pause() { playing = false }
    override fun isPlaying(): Boolean = playing
    override fun positionMs(): Long = 1_000
    override fun durationMs(): Long = 60_000
    override fun release() { playing = false }
}

private class DetailFakeRepo : MeetingRepository {
    val pages = MutableStateFlow<List<SlidePage>>(emptyList())
    var meeting = Meeting(1, "周会", 1_000L, endedAt = 2_000L, audioDurationMs = 120_000L)
    var deletedPages = mutableListOf<Long>()
    var deletedMeeting = false

    override fun observeMeetings(): Flow<List<MeetingStats>> = MutableStateFlow(emptyList())
    override fun observePages(meetingId: Long): Flow<List<SlidePage>> = pages
    override suspend fun meeting(id: Long): Meeting? = meeting
    override suspend fun pageCount(id: Long): Int = pages.value.size
    override suspend fun createMeeting(title: String): Long = 1L
    override suspend fun finishMeeting(id: Long, audioPath: String?, durationMs: Long) = Unit
    override suspend fun deleteMeeting(id: Long) { deletedMeeting = true }
    override suspend fun deletePage(pageId: Long) {
        deletedPages += pageId
        pages.value = pages.value.filterNot { it.id == pageId }
    }
    override suspend fun registerShot(
        meetingId: Long, imageFile: File, width: Int, height: Int, isSamePage: Boolean,
    ): ShotResult = ShotResult(1, isSamePage, 1)
    override suspend fun targetFileFor(meetingId: Long, isSamePage: Boolean): File = File("x")
    override fun meetingAudioFile(meetingId: Long): File = File("x")
    override fun freeBytes(): Long = Long.MAX_VALUE
}

private fun page(id: Long, index: Int) = SlidePage(
    meetingId = 1, id = id, index = index, filePath = "/p/$index.jpg",
    width = 1600, height = 900, captureCount = 1, createdAt = 1L, updatedAt = 1L,
)

/** ViewModel 用到 viewModelScope（Dispatchers.Main），需 Robolectric 提供主 Looper */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeetingDetailViewModelTest {

    @Test
    fun `state carries meeting info and pages`() {
        val repo = DetailFakeRepo()
        repo.pages.value = listOf(page(11, 1), page(12, 2))
        val vm = MeetingDetailViewModel(1L, repo, FakePlayer())
        // ui 用 WhileSubscribed(5_000)，无订阅者时 ui.value 停留在初始值——先订阅一次
        runBlocking { vm.ui.first() }

        assertEquals("周会", vm.ui.value.title)
        assertEquals(120_000L, vm.ui.value.audioDurationMs)
        assertEquals(2, vm.ui.value.pages.size)
        assertTrue(vm.ui.value.audioAvailable)
    }

    @Test
    fun `toggle play starts then pauses`() {
        val repo = DetailFakeRepo()
        val player = FakePlayer()
        val vm = MeetingDetailViewModel(1L, repo, player)
        runBlocking { vm.ui.first() }

        vm.togglePlay()
        idleMainLooper()
        assertTrue(vm.ui.value.playing)
        assertTrue(player.prepared)

        vm.togglePlay()
        idleMainLooper()
        assertFalse(vm.ui.value.playing)
    }

    @Test
    fun `toggle play is inert when no audio file`() {
        val repo = DetailFakeRepo()
        repo.meeting = repo.meeting.copy(audioPath = null, audioDurationMs = 0L)
        val vm = MeetingDetailViewModel(1L, repo, FakePlayer())
        runBlocking { vm.ui.first() }

        vm.togglePlay()
        idleMainLooper()

        assertFalse("没有录音就不该进入播放态", vm.ui.value.playing)
        assertEquals("没有录音时给用户提示", "本次会议没有录音", vm.ui.value.error)
    }

    @Test
    fun `delete page updates list`() {
        val repo = DetailFakeRepo()
        repo.pages.value = listOf(page(11, 1), page(12, 2), page(13, 3))
        val vm = MeetingDetailViewModel(1L, repo, FakePlayer())
        runBlocking { vm.ui.first() }

        vm.deletePage(repo.pages.value[1])
        idleMainLooper()

        // 删除的是第 2 页（id=12）：仓库记录的是被删页的 id，而非剩余页
        assertEquals(listOf(12L), repo.deletedPages)
        assertEquals(2, vm.ui.value.pages.size)
    }

    @Test
    fun `delete meeting reports completion`() = runBlocking {
        var done = false
        val repo = DetailFakeRepo()
        val vm = MeetingDetailViewModel(1L, repo, FakePlayer())

        vm.deleteMeeting { done = true }

        assertTrue(done)
        assertTrue(repo.deletedMeeting)
    }

    @Test
    fun `pages sorted by index ascending`() {
        val repo = DetailFakeRepo()
        repo.pages.value = listOf(page(31, 3), page(11, 1), page(21, 2))
        val vm = MeetingDetailViewModel(1L, repo, FakePlayer())
        runBlocking { vm.ui.first() }
        assertEquals(listOf(1, 2, 3), vm.ui.value.pages.map { it.index })
    }

    /** combine 的状态更新经 Channel 恢复会 post 到主 Looper，暂停模式下需手动 idle */
    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }
}
