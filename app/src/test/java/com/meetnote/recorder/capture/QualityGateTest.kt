package com.meetnote.recorder.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityGateTest {

    /** 纯色图 → 清晰度为 0 */
    private fun flat(w: Int, h: Int, v: Float) = FloatArray(w * h) { v }

    /** 竖向正弦纹理 → 有明显边缘 */
    private fun textured(w: Int, h: Int): FloatArray =
        FloatArray(w * h) { i ->
            val x = i % w
            ((kotlin.math.sin(x / 3.0) * 0.5 + 0.5) * 255).toFloat()
        }

    @Test
    fun `flat image has zero laplacian variance`() {
        assertEquals(0.0, QualityGate.laplacianVariance(flat(32, 32, 128f), 32, 32), 0.001)
    }

    @Test
    fun `textured image passes laplacian threshold`() {
        val v = QualityGate.laplacianVariance(textured(64, 64), 64, 64)
        assertTrue("清晰图应远高于 60，实得 $v", v > QualityGate.MIN_LAPLACIAN)
    }

    @Test
    fun `mean luma computed from values`() {
        val gray = floatArrayOf(0f, 255f, 100f, 155f)
        assertEquals(127.5, QualityGate.meanLuma(gray), 0.01)
    }

    @Test
    fun `accept boundaries are inclusive`() {
        assertTrue(QualityGate.accept(60.0, 25.0, 0.6f))
        assertTrue(QualityGate.accept(60.0, 235.0, 2.5f))
        assertFalse("清晰度不足", QualityGate.accept(59.9, 100.0, 1.0f))
        assertFalse("过暗", QualityGate.accept(100.0, 24.9, 1.0f))
        assertFalse("过亮（反光）", QualityGate.accept(100.0, 235.1, 1.0f))
        assertFalse("太扁", QualityGate.accept(100.0, 100.0, 0.59f))
        assertFalse("太瘦", QualityGate.accept(100.0, 100.0, 2.51f))
    }

    @Test
    fun `accept passes a typical projector frame`() {
        assertTrue(QualityGate.accept(240.0, 140.0, 1.78f))
    }

    @Test
    fun `grayOf uses Rec 601 weights on 0-255 scale`() {
        val px = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
        val g = QualityGate.grayOf(px)
        assertEquals(76.2f, g[0], 0.5f)    // 0.299*255
        assertEquals(149.7f, g[1], 0.5f)   // 0.587*255
        assertEquals(29.1f, g[2], 0.5f)    // 0.114*255
    }
}
