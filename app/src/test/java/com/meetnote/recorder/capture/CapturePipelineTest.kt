package com.meetnote.recorder.capture

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Canvas
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.meetnote.recorder.data.AppDatabase
import com.meetnote.recorder.data.DefaultMeetingRepository
import com.meetnote.recorder.data.FileStore
import com.meetnote.recorder.data.MeetingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// 需要真实光栅化：默认图形模式下 Canvas 不落像素，striped/other 会退化成纯色图
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CapturePipelineTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: MeetingRepository
    private lateinit var root: File
    private var meetingId: Long = 0L
    private val statuses = mutableListOf<PipelineStatus>()
    private var pictureRequests = 0

    @Before
    fun setUp() = runBlocking {
        root = File(System.getProperty("java.io.tmpdir"), "pipe-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries()
            // 直通执行器：Room 挂起调用在调用线程同步完成，避免流水线落库与
            // 断言之间的竞态（Robolectric 单线程内可复现，见 task-10 报告 Decisions）
            .setQueryExecutor { it.run() }
            .build()
        repo = DefaultMeetingRepository(db.meetingDao(), FileStore(root))
        meetingId = repo.createMeeting("流水线测试")
        statuses.clear()
        pictureRequests = 0
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    /** 直通矫正器：不做任何 OpenCV 处理 */
    private fun passthrough(bmp: Bitmap) = Correction(bmp, null, degraded = false, reason = null)

    /** 连续 n 帧完全相同的画面 */
    private fun grid(value: Int) =
        FrameGrid(AnalysisConfig.W, AnalysisConfig.H,
            IntArray(AnalysisConfig.W * AnalysisConfig.H) { value })

    private fun pipeline(
        correct: (Bitmap) -> Correction = ::passthrough,
    ) = CapturePipeline(
        meetingId = meetingId,
        repository = repo,
        scope = CoroutineScope(Dispatchers.Unconfined),
        ioContext = Dispatchers.Unconfined,
        correct = correct,
        onStatus = { statuses += it },
    ).also { p ->
        p.onPictureRequest = { pictureRequests++ }
    }

    /** 模拟分析帧序列直到产生抓拍请求 */
    private fun feedStable(p: CapturePipeline, startTs: Long = 0L, n: Int = 8, value: Int = 100) {
        repeat(n) { i -> p.analyze(grid(value), startTs + i * 33L) }
    }

    /** 清晰可过质量门的图：白底黑竖条保证拉普拉斯方差与平均亮度都达标 */
    private fun striped(w: Int = 320, h: Int = 180): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            bmp.eraseColor(Color.WHITE)   // 透明底 RGB 全 0 会被质量门当纯黑拒掉
            val canvas = Canvas(bmp)
            val paint = Paint().apply { color = Color.BLACK; strokeWidth = 6f }
            var x = 0f
            while (x < w) {
                canvas.drawLine(x, 0f, x, h.toFloat(), paint)
                x += 16f
            }
        }

    /** 纯色图：过不了清晰度门 */
    private fun flat(w: Int = 320, h: Int = 180, color: Int = Color.rgb(128, 128, 128)): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private suspend fun pageCount() = repo.pageCount(meetingId)

    @Test
    fun `stable frames trigger picture request`() {
        val p = pipeline()
        feedStable(p)
        assertEquals(1, pictureRequests)   // 稳定 + 冷却内不再重复触发
    }

    @Test
    fun `paused pipeline never triggers`() {
        val p = pipeline()
        p.setPaused(true)
        feedStable(p)
        assertEquals(0, pictureRequests)

        p.setPaused(false)
        feedStable(p, startTs = 10_000L)
        assertEquals(1, pictureRequests)
    }

    @Test
    fun `first picture becomes page one`() = runBlocking {
        val p = pipeline()
        p.onPicture(striped())

        assertEquals(1, pageCount())
        assertEquals(1, statuses.last().shot?.index)
        assertFalse(statuses.last().shot!!.overwritten)
        assertTrue(statuses.last().text.contains("第 1 页"))
        // 图片文件真的写出来了
        val page = repo.observePages(meetingId).first().single()
        assertTrue(File(page.filePath).exists())
        assertTrue(page.filePath.endsWith("page_0001.jpg"))
    }

    @Test
    fun `same page with more content overwrites instead of appending`() = runBlocking {
        val p = pipeline()
        p.onPicture(striped())
        p.onPicture(striped())            // 同一页又拍了一张（累积内容）

        assertEquals("同页必须只留一张", 1, pageCount())
        val page = repo.observePages(meetingId).first().single()
        assertEquals(2, page.captureCount)
        assertTrue(statuses.last().text.contains("已更新第 1 页"))
    }

    @Test
    fun `different looking slide appends page two`() = runBlocking {
        val p = pipeline()
        p.onPicture(striped())
        // 换一张完全不同的图（大块纯色区域，与条纹图差异明显）
        val other = flat(320, 180, Color.rgb(40, 40, 40)).also { bmp ->
            val canvas = Canvas(bmp)
            val paint = Paint().apply { color = Color.WHITE; strokeWidth = 30f }
            canvas.drawLine(10f, 10f, 310f, 170f, paint)
        }
        p.onPicture(other)

        assertEquals(2, pageCount())
        assertEquals(2, statuses.last().shot?.index)
    }

    @Test
    fun `quality gate silently drops bad frames`() = runBlocking {
        val p = pipeline()
        p.onPicture(flat())                // 纯灰图，清晰度为 0

        assertEquals(0, pageCount())
        assertTrue(statuses.isEmpty())
    }

    @Test
    fun `three consecutive detection failures enter degraded mode`() {
        val p = pipeline(correct = { bmp -> Correction(bmp, null, degraded = true, reason = "未检测到屏幕边缘") })

        p.onPicture(striped())
        assertFalse(statuses.last().degraded)
        p.onPicture(striped())
        assertFalse(statuses.last().degraded)
        p.onPicture(striped())
        assertTrue("连续 3 次检测失败应进入降级提示", statuses.last().degraded)

        // 恢复正常检测后降级标记应当消失
        val p2 = pipeline()
        p2.onPicture(striped())
        assertFalse(statuses.last().degraded)
    }

    @Test
    fun `null bitmap resets in-flight state`() {
        val p = pipeline()
        feedStable(p)
        assertEquals(1, pictureRequests)
        p.onPicture(null)                  // 拍摄失败
        // 失败后仍可再次触发（inFlight 已复位；换内容越过 changeMad，见 Decisions）
        p.analyze(grid(100), 100_000L)
        feedStable(p, startTs = 110_000L, value = 200)
        assertTrue(pictureRequests >= 2)
    }

    @Test
    fun `corrector crash emits failure status and does not wedge`() {
        val p = pipeline(correct = { _ -> throw IllegalStateException("boom") })
        feedStable(p)
        assertEquals(1, pictureRequests)

        p.onPicture(striped())              // corrector 抛异常 → 回传失败状态而非崩溃
        assertEquals("处理失败，继续监测…", statuses.last().text)

        // 异常后 inFlight 已复位：换内容仍能再次触发
        feedStable(p, startTs = 110_000L, value = 200)
        assertTrue(pictureRequests >= 2)
    }
}
