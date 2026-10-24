package com.meetnote.recorder.capture

/**
 * 分析用参数集中于此（spec §6.2）。
 *
 * 调参总原则（真机验收第一轮，OnePlus 13）：宁可多拍不可漏拍
 * （漏拍不可恢复，多拍由同页去重兜底）。
 */
object AnalysisConfig {
    const val W = 64
    const val H = 36

    /** 稳定判定 MAD 阈值：真机传感器噪声约 ±1–4，旧值 2.0 永远判不稳定（漏拍根因）；6.0 放噪声通过，翻页动画的 30/140 仍远高于它 */
    const val STABLE_MAD = 6.0f

    /** 连续稳定帧数：10 帧 ≈ 333ms，覆盖 300–500ms 翻页动画收尾（旧值 5 帧 ≈ 165ms 会拍到动画中间态） */
    const val STABLE_FRAMES = 10

    /** 内容变化 MAD 阈值：PPT 逐条动画（bullet 级）约 3.6，旧值 4.0 会漏拍；2.5 能触发，多拍由 DedupeConfig 同页去重兜底（页数不涨） */
    const val CHANGE_MAD = 2.5f

    /** 抓拍冷却：400ms 内不重复触发，防连拍风暴（只延迟不吞掉触发） */
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
