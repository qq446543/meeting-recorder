package com.meetnote.recorder.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class YuvFramesTest {

    /** 4 行 8 列 Y 平面，rowStride=12（尾部 4 字节填充），pixelStride=1 */
    private fun paddedYPlane(): ByteBuffer {
        val rowStride = 12
        val buf = ByteBuffer.allocate(rowStride * 4)
        for (row in 0 until 4) {
            for (col in 0 until 8) {
                buf.put(row * rowStride + col, (row * 10 + col + 1).toByte())
            }
            // 填充区故意写 99，若实现错用 rowStride 计算就会读到它
            for (col in 8 until rowStride) {
                buf.put(row * rowStride + col, 99)
            }
        }
        buf.position(0)
        return buf
    }

    @Test
    fun `downsample respects row stride padding`() {
        val grid = YuvFrames.toGrid(
            y = paddedYPlane(), imageWidth = 8, imageHeight = 4,
            rowStride = 12, pixelStride = 1, outW = 4, outH = 2,
        )

        assertEquals(4, grid.width)
        assertEquals(2, grid.height)
        // 第 0 行取源行 0、第 0/2/4/6 列 → 1,3,5,7
        assertEquals(1, grid.values[0])
        assertEquals(3, grid.values[1])
        assertEquals(5, grid.values[2])
        assertEquals(7, grid.values[3])
        // 第 1 输出行取源行 2 → 值 21,23,25,27
        assertEquals(21, grid.values[4])
        assertEquals(27, grid.values[7])
        // 绝不能读到填充区的 99
        check(grid.values.none { it == 99 }) { "读到了 rowStride 填充字节" }
    }

    @Test
    fun `pixel stride of 2 skips luma correctly`() {
        // 模拟 UYVY 风格：每 2 字节取 1
        val buf = ByteBuffer.allocate(8)
        // 列序: Y0 V0 Y1 U0 | Y2 V1 Y3 U1
        byteArrayOf(10, 0, 11, 0, 12, 0, 13, 0).forEachIndexed { i, b -> buf.put(i, b) }
        val grid = YuvFrames.toGrid(
            y = buf, imageWidth = 4, imageHeight = 1,
            rowStride = 8, pixelStride = 2, outW = 4, outH = 1,
        )
        assertEquals(10, grid.values[0])
        assertEquals(11, grid.values[1])
        assertEquals(12, grid.values[2])
        assertEquals(13, grid.values[3])
    }

    @Test
    fun `uniform frame has zero diff`() {
        val g = FrameGrid(4, 4, IntArray(16) { 128 })
        assertEquals(0f, meanAbsDiff(g, g), 0.0001f)
    }

    @Test
    fun `diff is mean absolute difference`() {
        val a = FrameGrid(2, 2, intArrayOf(100, 100, 100, 100))
        val b = FrameGrid(2, 2, intArrayOf(110, 90, 100, 100))
        assertEquals(5f, meanAbsDiff(a, b), 0.0001f)   // (10+10+0+0)/4
    }
}
