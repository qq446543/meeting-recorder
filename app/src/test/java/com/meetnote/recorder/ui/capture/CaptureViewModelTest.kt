package com.meetnote.recorder.ui.capture

import com.meetnote.recorder.audio.RecordingState
import com.meetnote.recorder.capture.Point2
import com.meetnote.recorder.capture.Quad
import com.meetnote.recorder.audio.RecordingStatus
import com.meetnote.recorder.data.MeetingRepository
import com.meetnote.recorder.data.MeetingStats
import com.meetnote.recorder.data.Meeting
import com.meetnote.recorder.data.ShotResult
import com.meetnote.recorder.data.SlidePage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

private class PagesFakeRepo : MeetingRepository {
    val pages = MutableStateFlow<List<SlidePage>>(emptyList())
    var title = "需求评审"
    var meeting: Meeting? = Meeting(1, title, 1L, endedAt = null)
    var finishedCalls = mutableListOf<Pair<String?, Long>>()

    override fun observeMeetings(): Flow<List<MeetingStats>> = MutableStateFlow(emptyList())
    override fun observePages(meetingId: Long): Flow<List<SlidePage>> = pages
    override suspend fun meeting(id: Long): Meeting? = meeting
    override suspend fun pageCount(id: Long): Int = pages.value.size
    override suspend fun createMeeting(title: String): Long = 1L
    override suspend fun finishMeeting(id: Long, audioPath: String?, durationMs: Long) {
        finishedCalls += audioPath to durationMs
        meeting = meeting?.copy(endedAt = 1L, audioPath = audioPath, audioDurationMs = durationMs)
    }
    override suspend fun deleteMeeting(id: Long) = Unit
    override suspend fun deletePage(pageId: Long) = Unit
    override suspend fun registerShot(
        meetingId: Long, imageFile: File, width: Int, height: Int, isSamePage: Boolean,
    ): ShotResult = ShotResult(1, isSamePage, 1)
    override suspend fun targetFileFor(meetingId: Long, isSamePage: Boolean): File = File("x")
    override fun meetingAudioFile(meetingId: Long): File = File("x")
    override fun freeBytes(): Long = Long.MAX_VALUE
}

/**
 * ViewModel 用到 viewModelScope（Dispatchers.Main），需 Robolectric 提供主 Looper
 * （与 MeetingListViewModelTest 同因同解；plain JUnit 下构造即抛
 * "Module with the Main dispatcher had failed to initialize"）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureViewModelTest {

    @Before
    fun setUp() = RecordingState.clear()

    @After
    fun tearDown() = RecordingState.clear()

    private fun page(id: Long, index: Int) = SlidePage(
        meetingId = id, index = index, filePath = "/p/$index.jpg",
        width = 1600, height = 900, createdAt = 1L, updatedAt = 1L,
    )

    @Test
    fun `ui state carries meeting title`() {
        val vm = CaptureViewModel(1L, PagesFakeRepo())
        assertEquals("需求评审", vm.ui.value.title)
    }

    @Test
    fun `page count follows repository pages`() {
        val repo = PagesFakeRepo()
        val vm = CaptureViewModel(1L, repo)
        repo.pages.value = listOf(page(1, 1), page(1, 2))
        // 页面流是 stateIn，直接再读一次当前值即可（无需等待）
        vm.refreshPageCount(2)
        assertEquals(2, vm.ui.value.pageCount)
    }

    @Test
    fun `pause toggle flips and persists`() {
        val vm = CaptureViewModel(1L, PagesFakeRepo())
        assertFalse(vm.ui.value.paused)
        assertTrue(vm.togglePause())
        assertTrue(vm.ui.value.paused)
        assertFalse(vm.togglePause())
    }

    @Test
    fun `pipeline status updates text and quad`() {
        val vm = CaptureViewModel(1L, PagesFakeRepo())
        vm.onPipelineStatus("已拍第 3 页", quad = null, degraded = false)
        assertEquals("已拍第 3 页", vm.ui.value.statusText)
        assertFalse(vm.ui.value.degraded)
        // 默认参数：未带尺寸时保持预置默认值（1280×720，首拍前行为不变）
        assertEquals(1280, vm.ui.value.imageW)
        assertEquals(720, vm.ui.value.imageH)

        vm.onPipelineStatus("未检测到屏幕边缘", quad = null, degraded = true)
        assertTrue(vm.ui.value.degraded)
        assertNotNull(vm.ui.value.statusText)

        // 真机验收第一轮：状态携带拍摄原图尺寸，供绿框叠加层换算坐标
        vm.onPipelineStatus("已拍第 4 页", quad = null, degraded = false, imageW = 1920, imageH = 1080)
        assertEquals(1920, vm.ui.value.imageW)
        assertEquals(1080, vm.ui.value.imageH)

        // 真机验收第一轮修复：带框的命名调用先记录框与尺寸
        val box = Quad(Point2(10f, 20f), Point2(300f, 20f), Point2(310f, 250f), Point2(5f, 255f))
        vm.onPipelineStatus("翻页已确认", quad = box, degraded = false, imageW = 1920, imageH = 1080)
        assertEquals(box, vm.ui.value.quad)

        // 阶段状态行（真机验收裁定：流水线阶段行随行携带快照框，无框时才为 null）：
        // 3 参形态无尺寸形参 → 尺寸沿用 VM 的 null-keep，绿框照常记录不清除
        vm.onPipelineStatus("画面已稳定，等待翻页", quad = box, degraded = false)
        assertEquals(1920, vm.ui.value.imageW)
        assertEquals(1080, vm.ui.value.imageH)
        assertEquals(box, vm.ui.value.quad)
    }

    @Test
    fun `recording status is mirrored from global state`() {
        val vm = CaptureViewModel(1L, PagesFakeRepo())
        RecordingState.bind(1L, 0)
        RecordingState.onStart()
        RecordingState.onTick(65_000)

        val rec: RecordingStatus = vm.ui.value.recording
        assertTrue(rec.running)
        assertEquals(65_000L, rec.elapsedMs)
    }

    @Test
    fun `endMeeting writes end time once`() = runBlocking {
        val repo = PagesFakeRepo()
        val vm = CaptureViewModel(1L, repo)
        RecordingState.bind(1L, 0)
        RecordingState.onStart()
        RecordingState.onTick(30_000)

        vm.endMeeting()

        assertEquals(1, repo.finishedCalls.size)
        assertEquals(30_000L, repo.finishedCalls.single().second)
        assertNotNull(repo.meeting(1L)!!.endedAt)

        // 已经结束过就不要重复写（服务可能已经写过）
        vm.endMeeting()
        assertEquals("重复结束不应二次写入", 1, repo.finishedCalls.size)
    }

    @Test
    fun `endMeeting is a no-op if service already finished`() = runBlocking {
        val repo = PagesFakeRepo()
        repo.meeting = Meeting(1, "已结束", 1L, endedAt = 99L, audioDurationMs = 5_000)
        val vm = CaptureViewModel(1L, repo)

        vm.endMeeting()

        assertTrue(repo.finishedCalls.isEmpty())
    }

    @Test
    fun `endMeeting invokes onDone after the fallback write lands`() = runBlocking {
        val repo = PagesFakeRepo()
        val vm = CaptureViewModel(1L, repo)
        RecordingState.bind(1L, 0)
        RecordingState.onStart()
        RecordingState.onTick(30_000)

        var finishedCallsAtDone = -1
        vm.endMeeting { finishedCallsAtDone = repo.finishedCalls.size }

        assertEquals(1, finishedCallsAtDone)   // 回调时写入必须已经落库
        assertNotNull(repo.meeting(1L)!!.endedAt)
    }
}
