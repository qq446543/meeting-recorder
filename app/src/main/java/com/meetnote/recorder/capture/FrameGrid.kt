package com.meetnote.recorder.capture

/** 分析用参数集中于此（spec §6.2） */
object AnalysisConfig {
    const val W = 64
    const val H = 36
    const val STABLE_MAD = 2.0f
    const val STABLE_FRAMES = 5
    const val CHANGE_MAD = 4.0f
    const val COOLDOWN_MS = 400L
}

/** 降采样后的亮度网格，0..255，行优先 */
data class FrameGrid(
    val width: Int,
    val height: Int,
    val values: IntArray,
) {
    init {
        require(values.size == width * height) {
            "网格尺寸不符：${width}x$height 需要 ${width * height}，实际 ${values.size}"
        }
    }

    fun withValue(v: Int): FrameGrid =
        FrameGrid(width, height, IntArray(values.size) { v })

    override fun equals(other: Any?): Boolean =
        other is FrameGrid && other.width == width && other.height == height &&
            other.values.contentEquals(values)

    override fun hashCode(): Int =
        (31 * (31 * width + height) + values.contentHashCode())
}

/** 平均绝对差，取值 0..255。两帧尺寸不同时直接抛错（属编程错误） */
fun meanAbsDiff(a: FrameGrid, b: FrameGrid): Float {
    require(a.width == b.width && a.height == b.height) { "网格尺寸不一致" }
    var sum = 0L
    for (i in a.values.indices) {
        sum += kotlin.math.abs(a.values[i] - b.values[i])
    }
    return sum.toFloat() / a.values.size
}
