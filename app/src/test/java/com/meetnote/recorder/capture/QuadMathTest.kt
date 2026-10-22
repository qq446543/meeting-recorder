package com.meetnote.recorder.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class QuadMathTest {

    @Test
    fun `orderPoints sorts shuffled corners`() {
        val shuffled = listOf(
            Point2(640f, 480f),   // BR
            Point2(10f, 470f),    // BL
            Point2(8f, 12f),      // TL
            Point2(630f, 15f),    // TR
        )
        val q = QuadMath.orderPoints(shuffled)

        assertPoint(8f, 12f, q.tl)
        assertPoint(630f, 15f, q.tr)
        assertPoint(640f, 480f, q.br)
        assertPoint(10f, 470f, q.bl)
    }

    private fun assertPoint(x: Float, y: Float, p: Point2) {
        assertTrue("期望 ($x,$y) 实得 (${p.x},${p.y})", abs(p.x - x) < 0.01f && abs(p.y - y) < 0.01f)
    }

    @Test
    fun `orderPoints requires exactly four points`() {
        try {
            QuadMath.orderPoints(listOf(Point2(0f, 0f), Point2(1f, 1f)))
            throw AssertionError("应当抛出 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // 通过
        }
    }

    @Test
    fun `quadArea is shoelace of unit square`() {
        val q = Quad(
            Point2(0f, 0f), Point2(10f, 0f),
            Point2(10f, 10f), Point2(0f, 10f),
        )
        assertEquals(100f, QuadMath.quadArea(q), 0.01f)
    }

    @Test
    fun `quadArea handles trapezoid`() {
        // 上底 4、下底 8、高 4 → 面积 24
        val q = Quad(
            Point2(2f, 0f), Point2(6f, 0f),
            Point2(8f, 4f), Point2(0f, 4f),
        )
        assertEquals(24f, QuadMath.quadArea(q), 0.01f)
    }

    @Test
    fun `aspectRatio of 16比9 rectangle is about 1点78`() {
        val q = Quad(
            Point2(0f, 0f), Point2(160f, 0f),
            Point2(160f, 90f), Point2(0f, 90f),
        )
        assertEquals(1.777f, QuadMath.aspectRatio(q), 0.01f)
    }

    @Test
    fun `aspectRatio of trapezoid uses average edge lengths`() {
        // 上底 4、下底 8 → 平均宽 6；斜边 √20 ≈ 4.47 → 比值 ≈ 1.34
        val q = Quad(
            Point2(2f, 0f), Point2(6f, 0f),
            Point2(8f, 4f), Point2(0f, 4f),
        )
        assertEquals(1.3416f, QuadMath.aspectRatio(q), 0.01f)
    }

    private fun rect(x0: Float, y0: Float, x1: Float, y1: Float) =
        Quad(Point2(x0, y0), Point2(x1, y0), Point2(x1, y1), Point2(x0, y1))

    @Test
    fun `plausible accepts a normal projector screen`() {
        // 640x360 画面中占 70% 面积的 16:9 屏幕
        val q = rect(100f, 60f, 580f, 330f)
        assertTrue(QuadMath.isPlausible(q, 640, 360))
    }

    @Test
    fun `plausible rejects too small region`() {
        // 只占画面 3% 的小方块（多半是误检的课桌/白纸）
        val q = rect(300f, 170f, 340f, 200f)
        assertFalse(QuadMath.isPlausible(q, 640, 360))
    }

    @Test
    fun `plausible rejects absurdly wide strip`() {
        // 宽高比 8 → 不可能是幻灯片
        val q = rect(10f, 160f, 630f, 200f)
        assertFalse(QuadMath.isPlausible(q, 640, 360))
    }

    @Test
    fun `targetSize downscales to max width`() {
        val q = rect(0f, 0f, 3200f, 1800f)
        assertEquals(1600 to 900, QuadMath.targetSize(q, maxWidth = 1600))
    }

    @Test
    fun `targetSize keeps small images intact`() {
        val q = rect(0f, 0f, 1280f, 720f)
        assertEquals(1280 to 720, QuadMath.targetSize(q, maxWidth = 1600))
    }

    @Test
    fun `targetSize never returns zero dimension`() {
        val q = rect(0f, 0f, 3f, 900f)
        val (tw, th) = QuadMath.targetSize(q, maxWidth = 1600)
        assertTrue(tw >= 1)
        assertTrue(th >= 1)
    }
}
