package com.meetnote.recorder.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StabilityDetectorTest {

    private fun detector() = StabilityDetector()

    /** 连续喂 n 帧完全相同的画，返回最后一次判定 */
    private fun StabilityDetector.feedStable(startTs: Long, n: Int, value: Int = 100): Verdict {
        var last: Verdict = Verdict.Initialising
        for (i in 0 until n) {
            last = onFrame(FrameGrid(AnalysisConfig.W, AnalysisConfig.H,
                IntArray(AnalysisConfig.W * AnalysisConfig.H) { value }), startTs + i * 33L)
        }
        return last
    }

    private fun grid(value: Int) =
        FrameGrid(AnalysisConfig.W, AnalysisConfig.H,
            IntArray(AnalysisConfig.W * AnalysisConfig.H) { value })

    @Test
    fun `first frame only initialises`() {
        assertEquals(Verdict.Initialising, detector().onFrame(grid(100), 0L))
    }

    @Test
    fun `fewer than five stable frames does not trigger`() {
        val d = detector()
        // 注意：首帧固定返回 Initialising（见 `first frame only initialises`），
        // 因此这里断言"不触发拍摄"而非逐帧断言 Stabilising——原断言与首帧语义冲突。
        repeat(AnalysisConfig.STABLE_FRAMES - 1) { i ->
            val v = d.onFrame(grid(100), i * 33L)
            assertTrue("第 ${i + 1} 帧未满稳定阈值不应拍摄，实际 $v", v !is Verdict.CaptureNow)
        }
    }

    @Test
    fun `first stable shot triggers after threshold`() {
        val d = detector()
        val verdicts = (0 until AnalysisConfig.STABLE_FRAMES + 1).map { i ->
            d.onFrame(grid(100), i * 33L)
        }
        assertTrue(
            "最后应为 CaptureNow，实际 $verdicts",
            verdicts.last() is Verdict.CaptureNow,
        )
        // 前几帧都在稳定过程中
        assertTrue(verdicts[1] is Verdict.Stabilising)
    }

    @Test
    fun `shaking frames never trigger`() {
        val d = detector()
        var last: Verdict = Verdict.Initialising
        for (i in 0 until 40) {
            last = d.onFrame(grid(100 + (i % 2) * 30), i * 33L)   // 帧间差 30 > 2.0
        }
        assertEquals(Verdict.Stabilising, last)
    }

    @Test
    fun `no repeat capture when nothing changed`() {
        val d = detector()
        // 稳定并拍下第一张
        val first = (0 until 6).map { i -> d.onFrame(grid(100), i * 33L) }
        assertTrue(first.last() is Verdict.CaptureNow)

        // 画面持续不动：再多喂 30 帧，不得重复拍
        val rest = (6 until 36).map { i -> d.onFrame(grid(100), i * 33L) }
        assertTrue("画面没变不应重拍", rest.none { it is Verdict.CaptureNow })
    }

    @Test
    fun `content change triggers new capture`() {
        val d = detector()
        // 基线：稳定 + 首拍
        (0 until 6).forEach { i -> d.onFrame(grid(100), i * 33L) }
        // 翻页：先抖几帧（动画），再稳定。
        // 时间戳从 700ms 起：首拍发生在 165ms，冷却期到 565ms；
        // 若沿用 i*33（≤561ms）整段动画都落在冷却期内，永远等不到拍摄
        //（与 `cooldown suppresses immediate retrigger` 的 400ms 冷却语义冲突）。
        val verdicts = (6 until 6 + 12).map { i ->
            d.onFrame(grid(if (i < 9) 60 else 200), 700L + (i - 6) * 33L)
        }
        assertTrue(
            "翻页后应拍到新页，实际 $verdicts",
            verdicts.any { it is Verdict.CaptureNow },
        )
    }

    @Test
    fun `cooldown suppresses immediate retrigger`() {
        val d = detector()
        // 首拍发生在 ts = 5*33 = 165
        (0 until 6).forEach { i -> d.onFrame(grid(100), i * 33L) }

        // 冷却期内（165 + 400 = 565 之前）即使已经稳定并变化也不拍
        val early = (6 until 6 + 6).map { i -> d.onFrame(grid(200), 200L + i * 30L) }
        assertTrue(
            "冷却期内不应拍摄", early.none { it is Verdict.CaptureNow },
        )
    }

    @Test
    fun `reset forgets reference`() {
        val d = detector()
        d.feedStable(0, 6)
        d.reset()
        assertEquals(Verdict.Initialising, d.onFrame(grid(100), 0L))
    }
}
