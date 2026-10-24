package com.meetnote.recorder.dedupe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideDeduplicatorTest {

    private val w = 800
    private val h = 450

    /** 全白幻灯片底 */
    private fun white() = IntArray(w * h) { 0xFFFFFFFF.toInt() }

    /** 画一条深色横带（模拟标题或一行要点），y 起点按 450 高度给定 */
    private fun band(pixels: IntArray, y0: Int, y1: Int, x0: Int, x1: Int, color: Int = 0xFF1A1A2E.toInt()) {
        for (y in y0 until y1.coerceAtMost(h)) {
            for (x in x0 until x1.coerceAtMost(w)) {
                pixels[y * w + x] = color
            }
        }
    }

    /** 标准版式：顶部标题带 + 两条要点 */
    private fun slideWithTwoBullets(): IntArray = white().apply {
        band(this, 30, 70, 60, 740)          // 标题
        band(this, 130, 160, 60, 500)        // 要点 1
        band(this, 200, 230, 60, 460)        // 要点 2
    }

    /** 同一页又多讲了两条要点（核心场景：内容累积） */
    private fun slideWithFourBullets(): IntArray = white().apply {
        band(this, 30, 70, 60, 740)
        band(this, 130, 160, 60, 500)
        band(this, 200, 230, 60, 460)
        band(this, 270, 300, 60, 520)        // 新增 3
        band(this, 340, 370, 60, 430)        // 新增 4
    }

    /** 完全换页：标题位置和正文都不同 */
    private fun differentSlide(): IntArray = white().apply {
        band(this, 30, 70, 60, 640)
        band(this, 120, 300, 60, 740, color = 0xFF2E4A7A.toInt())   // 大块图示
    }

    /** 只有标题的过渡页（内容比上一帧少） */
    private fun titleOnly(): IntArray = white().apply {
        band(this, 30, 70, 60, 740)
    }

    /** 同版式但正文整体右移（模拟"换了一段文字但版面雷同"） */
    private fun sameLayoutOtherText(): IntArray = white().apply {
        band(this, 30, 70, 60, 740)
        band(this, 130, 160, 260, 700)
        band(this, 200, 230, 300, 740)
    }

    private fun signals(pixels: IntArray) = SignalBuilder.fromPixels(pixels, w, h)

    /** 同一张幻灯片整体提亮 delta/255（模拟自动曝光漂移；背景钳到 255，内容整体平移） */
    private fun lifted(pixels: IntArray, delta: Int): IntArray = IntArray(pixels.size) { i ->
        val c = pixels[i]
        val r = ((c shr 16) and 0xFF) + delta
        val g = ((c shr 8) and 0xFF) + delta
        val b = (c and 0xFF) + delta
        (0xFF shl 24) or (r.coerceAtMost(255) shl 16) or (g.coerceAtMost(255) shl 8) or b.coerceAtMost(255)
    }

    /** 内容整体右移 dx 像素（模拟手抖导致的对齐抖动） */
    private fun shiftedRight(pixels: IntArray, dx: Int): IntArray = IntArray(pixels.size) { i ->
        val y = i / w
        val x = i % w
        pixels[y * w + (x - dx).coerceAtLeast(0)]
    }

    /** 标准版式 + 两条贴近墨迹阈值的浅灰行（0xB0B0B0 → gray ≈ 0.690 < 0.72 算墨迹，
     *  +10/255 提亮后 ≈ 0.729 > 0.72 翻出掩码）：建模曝光漂移下的掩码抖动。
     *  行宽刻意不同（60..460 ≈ 32 格、60..600 ≈ 43 格），使 recall/cov 落入
     *  (0.85, 0.90) 判别带——低于旧阈值 0.90、高于新阈值 0.85（实测 ≈ 0.87） */
    private fun slideWithThresholdBand(): IntArray = slideWithTwoBullets().apply {
        band(this, 260, 270, 60, 460, color = 0xFFB0B0B0.toInt())
        band(this, 300, 310, 60, 600, color = 0xFFB0B0B0.toInt())
    }

    /** 标准版式整体右移 2px（细格恰好 2×2px，即亚格级错位） */
    private fun slideWithTwoBulletsShifted(dx: Int): IntArray = shiftedRight(slideWithTwoBullets(), dx)

    /** 深色文字被深色图示整块盖住（DedupeConfig.FG_TOLERANCE KDoc 记录的设计用例）：
     *  文字 0x1A1A2E（gray ≈ 0.111）被 0x2E4A7A（gray ≈ 0.279）盖住，每格均值差 ≈ 0.17，
     *  纯绝对差度量实测 0.8514 会误判同页——三条件必须仍判新页（护栏，绝不放宽） */
    private fun textCoveredByDarkFigure(): IntArray = white().apply {
        band(this, 30, 70, 60, 740)                                  // 标题原样保留
        band(this, 120, 240, 60, 740, color = 0xFF2E4A7A.toInt())   // 深色图示盖住两条要点
    }

    @Test
    fun `exposure lifted duplicate stays ONE page`() {
        // 真机验收第二轮根因：自动曝光把整帧抬亮 10/255，旧 FG_TOLERANCE=0.10 下
        // fgOldSim ≈ 1 − (10/255)/0.10 = 0.61 < 0.85 → 同一张页面被追加成多页
        val s = SlideDeduplicator.compare(
            signals(slideWithTwoBullets()),
            signals(lifted(slideWithTwoBullets(), 10)),
        )
        assertTrue(
            "曝光漂移的重复帧必须判同页：recall=${s.recall} fg=${s.fgOldSim} cov=${s.coverage}",
            s.isSamePage,
        )
    }

    @Test
    fun `exposure lifted duplicate with ink mask flips stays ONE page`() {
        // 掩码抖动：贴近 0.72 阈值的浅灰行被 +10/255 抬过阈值、翻出墨迹掩码，
        // recall/cov 落入 (0.85, 0.90) 判别带——正是 SAME_RECALL/SAME_COVERAGE
        // 由 0.90 降到 0.85 要吸收的墨迹掩码边界翻格；fg 靠 FG_TOLERANCE=0.30 吸收
        val s = SlideDeduplicator.compare(
            signals(slideWithThresholdBand()),
            signals(lifted(slideWithThresholdBand(), 10)),
        )
        assertTrue(
            "夹具必须落在判别带：recall=${s.recall} 应 < 0.90（低于旧阈值才对阈值变更判别）",
            s.recall < 0.90f,
        )
        assertTrue(
            "掩码翻格 recall=${s.recall} 应 ≥ SAME_RECALL=${DedupeConfig.SAME_RECALL}",
            s.recall >= DedupeConfig.SAME_RECALL,
        )
        assertTrue(
            "掩码翻格 coverage=${s.coverage} 应 ≥ SAME_COVERAGE=${DedupeConfig.SAME_COVERAGE}",
            s.coverage >= DedupeConfig.SAME_COVERAGE,
        )
        assertTrue(
            "掩码抖动的曝光重复帧必须判同页：recall=${s.recall} fg=${s.fgOldSim} cov=${s.coverage}",
            s.isSamePage,
        )
    }

    @Test
    fun `sub cell alignment jitter stays ONE page`() {
        // 手抖对齐抖动：内容整体右移 2px（= 1 个细格）。曝光 + 对齐叠加时
        // 边缘格差值被旧容差线性放大到截断，导致同页被判新页；放宽后必须吸收
        val s = SlideDeduplicator.compare(
            signals(slideWithTwoBullets()),
            signals(slideWithTwoBulletsShifted(2)),
        )
        assertTrue(
            "亚格错位的重复帧必须判同页：recall=${s.recall} fg=${s.fgOldSim} cov=${s.coverage}",
            s.isSamePage,
        )
    }

    @Test
    fun `dark text covered by dark figure is NOT same page`() {
        // 护栏（绝不放宽）：整块深色图示盖住深色文字，每格均值差仅 ~0.17。
        // 页级实测 fg = 0.7309 仍必须 < SAME_FG（0.44 只是变动格逐格惩罚 1−0.17/0.30；
        // 护栏合并临界点 TOL ≈ 0.54，0.30 有真实余量）
        val s = SlideDeduplicator.compare(
            signals(slideWithTwoBullets()),
            signals(textCoveredByDarkFigure()),
        )
        assertFalse(
            "被盖住的文字页必须判新页：recall=${s.recall} fg=${s.fgOldSim} cov=${s.coverage}",
            s.isSamePage,
        )
    }

    @Test
    fun `identical page is same page with perfect score`() {
        val s = SlideDeduplicator.compare(signals(slideWithTwoBullets()), signals(slideWithTwoBullets()))
        assertEquals(1f, s.recall, 0.02f)
        assertEquals(1f, s.fgOldSim, 0.02f)
        assertTrue(s.isSamePage)
    }

    @Test
    fun `adding bullets to same page stays ONE page`() {
        // 核心需求：同一页多次增加内容，只保留最后一张
        val s = SlideDeduplicator.compare(signals(slideWithTwoBullets()), signals(slideWithFourBullets()))
        assertTrue("recall=${s.recall} fg=${s.fgOldSim} cov=${s.coverage}", s.recall >= 0.9f)
        assertTrue("fgOldSim=${s.fgOldSim} 必须仍高，因为旧要点没动", s.fgOldSim >= 0.85f)
        assertTrue(s.coverage > 1f)
        assertTrue("累积内容必须判为同页，实得 score=${s.score}", s.isSamePage)
    }

    @Test
    fun `completely different slide is a new page`() {
        val s = SlideDeduplicator.compare(signals(slideWithTwoBullets()), signals(differentSlide()))
        assertFalse(s.isSamePage)
    }

    @Test
    fun `content shrink is NOT same page`() {
        // 从两条要点退回只剩标题：多半是动画中间态或换页
        val s = SlideDeduplicator.compare(signals(slideWithTwoBullets()), signals(titleOnly()))
        assertFalse("coverage=${s.coverage}", s.isSamePage)
        assertTrue("内容变少时 coverage 必须小于 0.9", s.coverage < 0.9f)
    }

    @Test
    fun `same layout but different text is a new page`() {
        val s = SlideDeduplicator.compare(signals(slideWithTwoBullets()), signals(sameLayoutOtherText()))
        assertTrue("旧要点仍在原处，recall 应偏高：${s.recall}", s.recall > 0.5f)
        assertFalse(
            "文字换了 fgOldSim 必须掉下来：${s.fgOldSim}",
            s.isSamePage,
        )
    }

    @Test
    fun `blank previous page never matches`() {
        val blank = signals(white())
        val s = SlideDeduplicator.compare(blank, signals(slideWithTwoBullets()))
        assertFalse(s.isSamePage)   // 旧页无内容 → 无法确认同页 → 新页
    }

    @Test
    fun `score is weighted average of cues`() {
        val s = SlideDeduplicator.compare(signals(slideWithTwoBullets()), signals(slideWithFourBullets()))
        val expected = 0.5f * s.recall + 0.5f * s.fgOldSim
        assertEquals(expected, s.score, 0.001f)
    }

    @Test
    fun `signal builder produces counts and normalized gray`() {
        val sig = signals(slideWithTwoBullets())
        val cells = DedupeConfig.INK_W * DedupeConfig.INK_H
        assertEquals(cells, sig.inkMask.size)
        // inkCount 是"有墨迹的格子数"而非全部格子：三条横带约占 503/3072 格。
        // 原断言 assertEquals(cells, sig.inkCount) 按 brief Step 4 的预案改为区间断言
        assertTrue("inkCount=${sig.inkCount}", sig.inkCount in 400..600)
        assertEquals(DedupeConfig.FINE_W * DedupeConfig.FINE_H, sig.fineGray.size)
        sig.fineGray.forEach { g -> assertTrue(g in 0f..1f) }

        val blank = signals(white())
        assertEquals(0, blank.inkCount)
    }
}
