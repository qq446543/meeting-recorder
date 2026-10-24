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

    /** 基底上翻转一块矩形区域的亮度：空间结构变化（整体亮度居中后仍保留差异） */
    private fun gridWithPatch(base: Int, patch: Int, x0: Int, x1: Int, y0: Int, y1: Int): FrameGrid {
        val values = IntArray(AnalysisConfig.W * AnalysisConfig.H) { base }
        for (y in y0 until y1) for (x in x0 until x1) values[y * AnalysisConfig.W + x] = patch
        return FrameGrid(AnalysisConfig.W, AnalysisConfig.H, values)
    }

    /** 左右交替的空间抖动（手抖/翻页动画建模）。原用整体亮度交替（100/130）建模，
     *  真机验收第二轮改去亮度居中对比后，纯亮度差恒为 0 不再算"抖动"，必须换成空间变化 */
    private fun shaking(i: Int): FrameGrid =
        if (i % 2 == 0) gridWithPatch(100, 40, 0, 32, 0, 36)
        else gridWithPatch(100, 40, 32, 64, 0, 36)

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
            // 空间抖动：左右交替的补丁使居中 MAD ≈ 60，远高于任何合理 STABLE_MAD
            //（阈值无关的写法不变；原整体亮度交替 100/130 在居中对比下差为 0，已失效）
            last = d.onFrame(shaking(i), i * 33L)
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
        // 翻页：先抖几帧（动画，空间变化建模——整体亮度差在居中对比下不算变化），
        // 再稳定在新内容（左上象限补丁：结构与均值都变了）。
        // 时间戳从 800ms 起：首拍发生在 330ms，冷却期到 730ms；
        // 若沿用紧邻基线的时间戳，整段动画都落在冷却期内，永远等不到拍摄
        //（与 `cooldown suppresses immediate retrigger` 的 400ms 冷却语义冲突）。
        val newPage = gridWithPatch(100, 40, 0, 32, 0, 18)
        val verdicts = (11 until 11 + 14).map { i ->
            d.onFrame(if (i < 14) shaking(i) else newPage, 800L + (i - 11) * 33L)
        }
        assertTrue(
            "翻页后应拍到新页，实际 $verdicts",
            verdicts.any { it is Verdict.CaptureNow },
        )
    }

    @Test
    fun `ae brightness drift never triggers`() {
        val d = detector()
        // 基线：稳定 + 首拍（ts = STABLE_FRAMES*33 = 330）
        (0 until AnalysisConfig.STABLE_FRAMES + 1).forEach { i -> d.onFrame(grid(100), i * 33L) }
        // 真机曝光反馈漂移（实测 ±4 灰阶）：先恒定 +4 偏置，再 104↔100 交替。
        // 旧实现按原始 MAD：4 > CHANGE_MAD(2.5) 每过一个冷却期就误拍一次
        //（用户反馈「没有翻页也经常自己拍摄」的根因）；去亮度居中后两帧结构相同 → 永不触发
        val frames = buildList {
            repeat(15) { add(grid(104)) }
            repeat(20) { add(grid(if (it % 2 == 0) 104 else 100)) }
        }
        val verdicts = frames.mapIndexed { idx, g -> d.onFrame(g, 800L + idx * 33L) }
        assertTrue(
            "AE 漂移不应触发拍摄，实际触发 ${verdicts.count { it is Verdict.CaptureNow }} 次",
            verdicts.none { it is Verdict.CaptureNow },
        )
    }

    @Test
    fun `whole screen inversion triggers via mean shift`() {
        val d = detector()
        // 基线：稳定 + 首拍（grid(100)，ts 330）
        (0 until AnalysisConfig.STABLE_FRAMES + 1).forEach { i -> d.onFrame(grid(100), i * 33L) }
        // 整页明暗翻转（白底幻灯片 → 黑底）：两帧都是平场，居中 MAD ≈ 0，
        // 必须由 GLOBAL_SHIFT_MAD 兜底触发，否则此类翻转会漏拍
        val verdicts = (11 until 11 + 14).map { i ->
            d.onFrame(grid(40), 800L + (i - 11) * 33L)
        }
        assertTrue(
            "明暗翻转应触发拍摄，实际 $verdicts",
            verdicts.any { it is Verdict.CaptureNow },
        )
    }

    @Test
    fun `spatial change with zero mean shift still triggers`() {
        val d = detector()
        // 基线：稳定 + 首拍（ts 330）
        (0 until AnalysisConfig.STABLE_FRAMES + 1).forEach { i -> d.onFrame(grid(100), i * 33L) }
        // 同面积、不同位置的补丁：两帧均值相同（纯结构变化，均值位移为 0），
        // 证明局部内容变化走居中 MAD 路径照常触发
        val top = gridWithPatch(100, 40, 0, 64, 0, 18)
        val bottom = gridWithPatch(100, 40, 0, 64, 18, 36)
        val verdicts = (11 until 11 + 14).map { i ->
            d.onFrame(if (i < 14) (if (i % 2 == 0) top else bottom) else bottom, 800L + (i - 11) * 33L)
        }
        assertTrue(
            "零均值位移的结构变化应触发拍摄，实际 $verdicts",
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
