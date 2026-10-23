package com.meetnote.recorder.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfLayoutTest {

    private val pageW = PdfLayout.PAGE_WIDTH
    private val pageH = PdfLayout.PAGE_HEIGHT
    private val margin = PdfLayout.MARGIN

    @Test
    fun `landscape image fits width and centers vertically`() {
        val f = PdfLayout.fit(1600, 900, pageW, pageH, margin)

        assertEquals(margin, f.left, 0.01f)
        assertEquals(pageW - margin, f.left + f.width, 0.01f)
        assertEquals(16f / 9f, f.width / f.height, 0.02f)
        // 垂直居中：上下留白相等
        assertEquals(f.top, pageH - (f.top + f.height), 0.5f)
        assertTrue(f.top > margin)
    }

    @Test
    fun `tall image fits height and centers horizontally`() {
        val f = PdfLayout.fit(600, 2000, pageW, pageH, margin)

        assertEquals(margin, f.top, 0.01f)
        assertEquals(pageH - margin, f.top + f.height, 0.01f)
        // 水平居中
        assertEquals(f.left, pageW - (f.left + f.width), 0.5f)
        assertTrue(f.width < pageW - 2 * margin + 0.01f)
    }

    @Test
    fun `small image is not enlarged`() {
        // 原图只有 200x150：PDF 点阵下不应被放大到整页
        val f = PdfLayout.fit(200, 150, pageW, pageH, margin)
        assertTrue("小图不应放大：${f.width}", f.width <= 200f)
    }

    @Test
    fun `square image centers on both axes`() {
        val f = PdfLayout.fit(1000, 1000, pageW, pageH, margin)
        assertEquals(f.left, pageW - (f.left + f.width), 0.5f)
        assertEquals(f.top, pageH - (f.top + f.height), 0.5f)
    }

    @Test
    fun `invalid dimensions fall back to a visible box`() {
        val f = PdfLayout.fit(0, 0, pageW, pageH, margin)
        assertTrue(f.width > 0)
        assertTrue(f.height > 0)
        assertTrue(f.left >= 0 && f.top >= 0)
    }

    @Test
    fun `content never exceeds page minus margin`() {
        listOf(
            Triple(4000, 300, 0),
            Triple(300, 4000, 0),
            Triple(1600, 900, 0),
        ).forEach { (w, h, _) ->
            val f = PdfLayout.fit(w, h, pageW, pageH, margin)
            assertTrue(f.left >= margin - 0.01f)
            assertTrue(f.top >= margin - 0.01f)
            assertTrue(f.left + f.width <= pageW - margin + 0.01f)
            assertTrue(f.top + f.height <= pageH - margin + 0.01f)
        }
    }
}
