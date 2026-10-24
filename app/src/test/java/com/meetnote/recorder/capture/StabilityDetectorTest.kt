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
    fun `fewer than stable-frames-threshold does not trigger`() {
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
            // 帧间差 30 恒大于稳定阈值（阈值无关的写法：30 远高于任何合理 STABLE_MAD）
            last = d.onFrame(grid(100 + (i % 2) * 30), i * 33L)
        }
        assertEquals(Verdict.Stabilising, last)
    }

    @Test
    fun `no repeat capture when nothing changed`() {
        val d = detector()
        // 稳定并拍下第一张（基线需 STABLE_FRAMES + 1 帧才到 CaptureNow，首拍 ts = STABLE_FRAMES*33 = 330）
        val first = (0 until AnalysisConfig.STABLE_FRAMES + 1).map { i -> d.onFrame(grid(100), i * 33L) }
        assertTrue(first.last() is Verdict.CaptureNow)

        // 画面持续不动：再多喂 30 帧，不得重复拍
        val rest = (AnalysisConfig.STABLE_FRAMES + 1 until AnalysisConfig.STABLE_FRAMES + 31)
            .map { i -> d.onFrame(grid(100), i * 33L) }
        assertTrue("画面没变不应重拍", rest.none { it is Verdict.CaptureNow })
    }

    @Test
    fun `content change triggers new capture`() {
        val d = detector()
        // 基线：稳定 + 首拍（ts = STABLE_FRAMES*33 = 330）
        (0 until AnalysisConfig.STABLE_FRAMES + 1).forEach { i -> d.onFrame(grid(100), i * 33L) }
        // 翻页：先抖几帧（动画），再稳定。
        // 时间戳从 800ms 起：首拍发生在 330ms，冷却期到 730ms；
        // 若沿用紧邻基线的时间戳，整段动画都落在冷却期内，永远等不到拍摄
        //（与 `cooldown suppresses immediate retrigger` 的 400ms 冷却语义冲突）。
        val verdicts = (11 until 11 + 14).map { i ->
            d.onFrame(grid(if (i < 14) 60 else 200), 800L + (i - 11) * 33L)
        }
        assertTrue(
            "翻页后应拍到新页，实际 $verdicts",
            verdicts.any { it is Verdict.CaptureNow },
        )
    }

    @Test
    fun `cooldown suppresses immediate retrigger`() {
        val d = detector()
        // 基线 11 帧：首拍发生在 ts = STABLE_FRAMES*33 = 330，冷却期到 330 + 400 = 730
        (0 until AnalysisConfig.STABLE_FRAMES + 1).forEach { i -> d.onFrame(grid(100), i * 33L) }

        // 冷却期内喂"真的稳定且已变化"的整段（11 帧，ts 396..726 全部 < 730）：不得拍摄
        val early = (12..22).map { i -> d.onFrame(grid(200), i * 33L) }
        assertTrue(
            "冷却期内不应拍摄", early.none { it is Verdict.CaptureNow },
        )

        // 冷却期一过（ts ≥ 730）同样这段画面立即可拍——冷却只延迟、不吞掉触发
        val late = (23..24).map { i -> d.onFrame(grid(200), i * 33L) }
        assertTrue("冷却期结束后应恢复拍摄", late.any { it is Verdict.CaptureNow })
    }

    @Test
    fun `reset forgets reference`() {
        val d = detector()
        d.feedStable(0, 6)
        d.reset()
        assertEquals(Verdict.Initialising, d.onFrame(grid(100), 0L))
    }
}
