package com.meetnote.recorder.capture

/**
 * 抓拍候选的质量门（spec §6.4）。三条全过才允许入库，否则静默丢弃。
 * 输入灰度取 0..255（与拉普拉斯阈值 60 的量纲一致）。
 */
object QualityGate {
    const val MIN_LAPLACIAN = 60.0
    const val MIN_MEAN = 25.0
    const val MAX_MEAN = 235.0
    const val MIN_ASPECT = 0.6f
    const val MAX_ASPECT = 2.5f

    /**
     * 4 邻域拉普拉斯算子的方差。图片模糊时该值显著偏低。
     * 边缘像素用复制邻域的方式处理，避免边界伪影。
     */
    fun laplacianVariance(gray255: FloatArray, width: Int, height: Int): Double {
        require(gray255.size >= width * height) { "灰度数组长度不足" }
        require(width > 2 && height > 2) { "尺寸过小无法计算拉普拉斯" }

        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val lap =
                    gray255[i - 1] + gray255[i + 1] + gray255[i - width] +
                        gray255[i + width] - 4f * gray255[i]
                sum += lap
                sumSq += lap * lap
                count++
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return sumSq / count - mean * mean
    }

    fun meanLuma(gray255: FloatArray): Double {
        if (gray255.isEmpty()) return 0.0
        var sum = 0.0
        for (v in gray255) sum += v
        return sum / gray255.size
    }

    fun accept(laplacianVar: Double, meanLuma: Double, aspect: Float): Boolean =
        laplacianVar >= MIN_LAPLACIAN &&
            meanLuma in MIN_MEAN..MAX_MEAN &&
            aspect in MIN_ASPECT..MAX_ASPECT

    /** ARGB 像素 → 0..255 灰度（Rec.601），供清晰度/亮度计算使用 */
    fun grayOf(pixels: IntArray): FloatArray =
        FloatArray(pixels.size) { i ->
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            0.299f * r + 0.587f * g + 0.114f * b
        }
}
