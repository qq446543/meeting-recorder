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

    /** 基底上翻转一块矩形区域的亮度：空间结构变化（整体亮度居中后仍保留差异） */
    private fun gridWithPatch(base: Int, patch: Int, x0: Int, x1: Int, y0: Int, y1: Int): FrameGrid {
        val values = IntArray(AnalysisConfig.W * AnalysisConfig.H) { base }
        for (y in y0 until y1) for (x in x0 until x1) values[y * AnalysisConfig.W + x] = patch
        return FrameGrid(AnalysisConfig.W, AnalysisConfig.H, values)
    }

    /** 左右交替的空间抖动（手抖/动画建模）。原用整体亮度交替（100+i%2*40）建模，
     *  真机验收第二轮改去亮度居中对比后，纯亮度差恒为 0 不再算"抖动"，必须换成空间变化 */
    private fun shaking(i: Int): FrameGrid =
        if (i % 2 == 0) gridWithPatch(100, 40, 0, 32, 0, 36)
        else gridWithPatch(100, 40, 32, 64, 0, 36)

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

    /** 模拟分析帧序列直到产生抓拍请求（基线需 STABLE_FRAMES + 1 帧才到 CaptureNow） */
    private fun feedStable(p: CapturePipeline, startTs: Long = 0L, n: Int = AnalysisConfig.STABLE_FRAMES + 1, value: Int = 100) {
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
    fun `quality gate drops bad frames and reports the skip`() = runBlocking {
        val p = pipeline()
        p.onPicture(flat(320, 180))         // 纯灰图，清晰度为 0

        // 原意图保留：坏帧绝不落库；新增（真机验收裁定）：跳过必须可见，且带上原图尺寸
        assertEquals(0, pageCount())
        val st = statuses.single()
        assertEquals("画面模糊或过亮，已跳过，继续监测…", st.text)
        assertFalse(st.degraded)
        assertEquals(320, st.imageW)
        assertEquals(180, st.imageH)
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

    @Test
    fun `manual shutter fires even while the frame is shaking`() {
        val p = pipeline()
        // 持续抖动的帧（空间变化建模）：自动判定永不触发
        repeat(20) { i -> p.analyze(shaking(i), i * 33L) }
        assertEquals(0, pictureRequests)

        p.forceCapture()
        assertEquals("手动快门必须无视稳定判定", 1, pictureRequests)
    }

    @Test
    fun `manual shutter is ignored while a shot is already in flight`() {
        val p = pipeline()
        p.forceCapture()
        p.forceCapture()
        assertEquals(1, pictureRequests)
    }

    @Test
    fun `detector stages emit status only on transition`() {
        val p = pipeline()
        // 首帧 Initialising → 发一次"正在观察画面…"
        p.analyze(grid(100), 0L)
        // 连续 15 个抖动帧（空间变化建模，同一 Stabilising 阶段）→ 只应发一次，防逐帧刷屏
        repeat(15) { i -> p.analyze(shaking(i), 33L + i * 33L) }
        // 稳定帧攒满 STABLE_FRAMES 后触发抓拍（inFlight 期间后续帧被丢弃）
        repeat(11) { i -> p.analyze(grid(100), 600L + i * 33L) }
        assertEquals(1, pictureRequests)
        // 拍摄失败复位 inFlight；画面内容未变 → 稳定后进入 NoChange
        p.onPicture(null)
        // 同一画面继续喂 15 帧：NoChange 只应发一次
        repeat(15) { i -> p.analyze(grid(100), 10_000L + i * 33L) }

        val stages = statuses.map { it.text }.filter {
            it == "正在观察画面…" || it == "画面有变化，等待稳定…" || it == "画面已稳定，等待翻页"
        }
        assertEquals(
            listOf("正在观察画面…", "画面有变化，等待稳定…", "画面已稳定，等待翻页"),
            stages,
        )
    }

    @Test
    fun `status carries source image dimensions for the overlay`() = runBlocking {
        val p = pipeline()
        p.onPicture(striped(480, 270))

        val st = statuses.last()
        assertTrue(st.text.contains("第 1 页"))
        // 绿框叠加层要用拍摄原图尺寸换算坐标（quad 在原图坐标系）
        assertEquals(480, st.imageW)
        assertEquals(270, st.imageH)
    }

    @Test
    fun `stage lines keep the green box overlay after capture`() {
        // 矫正器始终给出屏幕四边形；degraded=true 用于在第 3 次拍摄时攒满降级阈值（DEGRADE_AFTER=3）
        val box = Quad(Point2(10f, 20f), Point2(300f, 20f), Point2(310f, 250f), Point2(5f, 255f))
        val p = pipeline(correct = { bmp -> Correction(bmp, box, degraded = true, reason = "未检测到屏幕边缘") })

        // ① 稳定触发拍摄：成功状态必须携带绿框与原图尺寸
        feedStable(p)
        assertEquals(1, pictureRequests)
        p.onPicture(striped(480, 270))
        val success = statuses.last()
        assertEquals("已拍第 1 页", success.text)
        assertEquals(box, success.quad)
        assertEquals(480, success.imageW)
        assertEquals(270, success.imageH)

        // ② 画面未变 → 阶段行"画面已稳定，等待翻页"：绿框与原图尺寸必须继续随行（物理屏幕位置没变）
        repeat(11) { i -> p.analyze(grid(100), 10_000L + i * 33L) }
        val stage = statuses.last()
        assertEquals("画面已稳定，等待翻页", stage.text)
        assertEquals("阶段行不得抹掉绿框", box, stage.quad)
        assertEquals(480, stage.imageW)
        assertEquals(270, stage.imageH)

        // ③ 连续 3 次降级矫正攒满 DEGRADE_AFTER：degraded 也必须随阶段行保留
        feedStable(p, startTs = 20_000L, value = 200)
        p.onPicture(striped(480, 270))          // 第 2 次：detectFailures=2 → 尚未降级
        assertFalse(statuses.last().degraded)
        feedStable(p, startTs = 30_000L, value = 150)
        p.onPicture(striped(480, 270))          // 第 3 次：detectFailures=3 → 降级
        assertTrue("连续 3 次检测失败应进入降级提示", statuses.last().degraded)

        repeat(11) { i -> p.analyze(grid(150), 40_000L + i * 33L) }
        val stage2 = statuses.last()
        assertEquals("画面已稳定，等待翻页", stage2.text)
        assertTrue("降级标记必须随阶段行保留", stage2.degraded)
        assertEquals(box, stage2.quad)
        assertEquals(480, stage2.imageW)
        assertEquals(270, stage2.imageH)
    }
}
