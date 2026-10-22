package com.meetnote.recorder.dedupe

data class ImageSignals(
    /** INK_W×INK_H，1 = 该格有内容 */
    val inkMask: ByteArray,
    val inkCount: Int,
    /** FINE_W×FINE_H，归一化灰度 0..1 */
    val fineGray: FloatArray,
)

object SignalBuilder {

    /**
     * @param pixels ARGB 像素，行优先
     * @param width  原图宽
     * @param height 原图高
     */
    fun fromPixels(pixels: IntArray, width: Int, height: Int): ImageSignals {
        require(width > 0 && height > 0) { "非法尺寸 ${width}x$height" }
        require(pixels.size >= width * height) { "像素数不足" }

        val inkMask = ByteArray(DedupeConfig.INK_W * DedupeConfig.INK_H)
        val fineGray = FloatArray(DedupeConfig.FINE_W * DedupeConfig.FINE_H)
        var inkCount = 0

        // 粗网格：每格取中心附近 3x3 平均灰度，低于阈值记为墨迹
        for (gy in 0 until DedupeConfig.INK_H) {
            val y0 = gy * height / DedupeConfig.INK_H
            val y1 = ((gy + 1) * height / DedupeConfig.INK_H).coerceAtLeast(y0 + 1)
            val yc = (y0 + y1) / 2
            for (gx in 0 until DedupeConfig.INK_W) {
                val x0 = gx * width / DedupeConfig.INK_W
                val x1 = ((gx + 1) * width / DedupeConfig.INK_W).coerceAtLeast(x0 + 1)
                val xc = (x0 + x1) / 2
                val g = normalizedGray(pixels, width, height, xc, yc)
                if (g < DedupeConfig.INK_THRESHOLD) {
                    inkMask[gy * DedupeConfig.INK_W + gx] = 1
                    inkCount++
                }
            }
        }

        // 细网格：整格平均灰度
        for (fy in 0 until DedupeConfig.FINE_H) {
            val y0 = fy * height / DedupeConfig.FINE_H
            val y1 = ((fy + 1) * height / DedupeConfig.FINE_H).coerceAtLeast(y0 + 1)
            for (fx in 0 until DedupeConfig.FINE_W) {
                val x0 = fx * width / DedupeConfig.FINE_W
                val x1 = ((fx + 1) * width / DedupeConfig.FINE_W).coerceAtLeast(x0 + 1)
                var sum = 0f
                var n = 0
                for (y in y0 until y1) {
                    val row = y * width
                    for (x in x0 until x1) {
                        sum += grayOf(pixels[row + x])
                        n++
                    }
                }
                fineGray[fy * DedupeConfig.FINE_W + fx] = if (n == 0) 1f else sum / n
            }
        }

        return ImageSignals(inkMask = inkMask, inkCount = inkCount, fineGray = fineGray)
    }

    private fun normalizedGray(px: IntArray, w: Int, h: Int, x: Int, y: Int): Float {
        var sum = 0f
        var n = 0
        for (dy in -1..1) {
            val yy = y + dy
            if (yy < 0 || yy >= h) continue
            val row = yy * w
            for (dx in -1..1) {
                val xx = x + dx
                if (xx < 0 || xx >= w) continue
                sum += grayOf(px[row + xx])
                n++
            }
        }
        return if (n == 0) 1f else sum / n
    }

    /** Rec.601 灰度，归一化 0..1 */
    private fun grayOf(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b) / 255f
    }
}
