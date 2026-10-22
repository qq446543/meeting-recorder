package com.meetnote.recorder.capture

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentCorrectorTest {

    @Before
    fun setUp() {
        OpenCvLoader.resetForTest()
    }

    @After
    fun tearDown() {
        OpenCvLoader.resetForTest()
    }

    private fun whiteBitmap(w: Int = 640, h: Int = 480): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    @Test
    fun `loader failure is cached not retried every frame`() {
        var attempts = 0
        OpenCvLoader.setLoadActionForTest {
            attempts++
            false
        }

        assertFalse(OpenCvLoader.ensure())
        assertFalse(OpenCvLoader.ensure())
        assertFalse(OpenCvLoader.ensure())

        assertEquals("加载只应尝试一次，否则每帧都去加载 native 库", 1, attempts)
        assertFalse(OpenCvLoader.isAvailable())
    }

    @Test
    fun `loader success is cached too`() {
        var attempts = 0
        OpenCvLoader.setLoadActionForTest {
            attempts++
            true
        }

        assertTrue(OpenCvLoader.ensure())
        assertTrue(OpenCvLoader.ensure())
        assertEquals(1, attempts)
    }

    @Test
    fun `loader throwing is treated as failure not crash`() {
        OpenCvLoader.setLoadActionForTest { throw UnsatisfiedLinkError("no lib") }
        assertFalse(OpenCvLoader.ensure())
    }

    @Test
    fun `reset clears cached state`() {
        OpenCvLoader.setLoadActionForTest { false }
        OpenCvLoader.ensure()
        OpenCvLoader.resetForTest()

        var attempts = 0
        OpenCvLoader.setLoadActionForTest { attempts++; true }
        assertTrue(OpenCvLoader.ensure())
        assertEquals(1, attempts)
    }

    @Test
    fun `correct degrades to original bitmap when opencv unavailable`() {
        OpenCvLoader.setLoadActionForTest { false }
        val src = whiteBitmap()

        val result = DocumentCorrector().correct(src)

        assertTrue("native 不可用时必须降级", result.degraded)
        assertSame("降级时不得改动原图", src, result.bitmap)
        assertNull("没有检测到四边形时 quad 为 null", result.quad)
        assertTrue(!result.reason.isNullOrBlank())
    }

    @Test
    fun `degraded correction keeps original dimensions`() {
        OpenCvLoader.setLoadActionForTest { false }
        val src = whiteBitmap(1024, 768)
        val result = DocumentCorrector().correct(src)
        assertEquals(1024, result.bitmap.width)
        assertEquals(768, result.bitmap.height)
    }
}
