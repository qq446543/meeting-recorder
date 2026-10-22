package com.meetnote.recorder.capture

import java.nio.ByteBuffer

/**
 * 把 ImageProxy 的 Y 平面降采样成 64×36 网格。
 * 必须按 rowStride/pixelStride 寻址，否则会读到行尾填充或色度字节。
 */
object YuvFrames {

    fun toGrid(
        y: ByteBuffer,
        imageWidth: Int,
        imageHeight: Int,
        rowStride: Int,
        pixelStride: Int,
        outW: Int = AnalysisConfig.W,
        outH: Int = AnalysisConfig.H,
    ): FrameGrid {
        require(imageWidth > 0 && imageHeight > 0) { "非法图像尺寸" }
        require(rowStride >= imageWidth * pixelStride) { "rowStride 小于一行所需字节数" }

        val out = IntArray(outW * outH)
        val limit = y.limit()
        for (gy in 0 until outH) {
            val sy = (gy * imageHeight / outH).coerceIn(0, imageHeight - 1)
            val rowOffset = sy * rowStride
            for (gx in 0 until outW) {
                val sx = (gx * imageWidth / outW).coerceIn(0, imageWidth - 1)
                val index = rowOffset + sx * pixelStride
                out[gy * outW + gx] =
                    if (index < limit) (y.get(index).toInt() and 0xFF) else 0
            }
        }
        return FrameGrid(outW, outH, out)
    }
}
