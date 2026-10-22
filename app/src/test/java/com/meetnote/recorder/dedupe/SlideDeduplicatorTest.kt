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
