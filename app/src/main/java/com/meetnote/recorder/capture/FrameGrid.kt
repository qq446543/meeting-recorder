package com.meetnote.recorder.capture

/**
 * 分析用参数集中于此（spec §6.2）。
 *
 * 调参总原则（真机验收第一轮，OnePlus 13）：宁可多拍不可漏拍
 * （漏拍不可恢复，多拍由同页去重兜底——真机验收第二轮把同页去重放宽后，
 * 这张兜底网已足够可靠，可吸收残余误触发）。
 *
 * 真机验收第二轮语义变更：稳定/变化判定全部改用**去整体亮度漂移的对比**
 * （[meanCenteredAbsDiff]，两帧各减自身均值后再取平均绝对差）：
 * 曝光闪变/AE 反馈漂移（真机实测 ±4 灰阶）是全局加性亮度平移，居中后 ≈ 0，
 * 不再算变化也不再重置稳定计数；局部内容变化（新要点、翻页）照常触发。
 * 整页明暗翻转（白底→黑底，两帧都是平场，居中 MAD ≈ 0）由
 * [GLOBAL_SHIFT_MAD] 均值位移兜底触发。
 */
object AnalysisConfig {
    const val W = 64
    const val H = 36

    /** 稳定判定（居中 MAD）阈值：真机传感器噪声与 AE 漂移在居中后 ≈ 0，6.0 仍放噪声通过，翻页动画的 30/140 仍远高于它 */
    const val STABLE_MAD = 6.0f

    /** 连续稳定帧数：10 帧 ≈ 333ms，覆盖 300–500ms 翻页动画收尾（旧值 5 帧 ≈ 165ms 会拍到动画中间态） */
    const val STABLE_FRAMES = 10

    /** 内容变化（居中 MAD）阈值：PPT 逐条动画（bullet 级）约 3.6，旧值 4.0 会漏拍；2.5 能触发，多拍由 DedupeConfig 同页去重兜底（页数不涨） */
    const val CHANGE_MAD = 2.5f

    /** 抓拍冷却：400ms 内不重复触发，防连拍风暴（只延迟不吞掉触发） */
    const val COOLDOWN_MS = 400L

    /**
     * 整页明暗翻转兜底：两帧都是平场时居中 MAD ≈ 0，仅靠它会把"白底页翻黑底页"判成没变化。
     * 帧均值位移超过本值即视为变化（走同一条 CaptureNow 路径）。
     * 12.0 远高于真机 AE 反馈漂移（±4），远低于真实明暗翻转（~100+）
     */
    const val GLOBAL_SHIFT_MAD = 12.0f
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

/** 帧平均灰度，取值 0..255（供整页明暗翻转兜底判定用） */
fun meanOf(grid: FrameGrid): Float {
    var sum = 0L
    for (v in grid.values) sum += v
    return sum.toFloat() / grid.values.size
}

/**
 * 去整体亮度漂移的平均绝对差：两帧各减去自身均值后再逐格取绝对差平均，取值 0..255。
 * 全局加性亮度平移（曝光闪变 / AE 反馈漂移）在两帧中同减后 ≈ 0，不影响判定；
 * 局部结构变化（新要点、翻页）的量级与 [meanAbsDiff] 一致（同为灰阶）。
 */
fun meanCenteredAbsDiff(a: FrameGrid, b: FrameGrid): Float {
    require(a.width == b.width && a.height == b.height) { "网格尺寸不一致" }
    val meanA = meanOf(a)
    val meanB = meanOf(b)
    var sum = 0.0
    for (i in a.values.indices) {
        sum += kotlin.math.abs((a.values[i] - meanA) - (b.values[i] - meanB))
    }
    return (sum / a.values.size).toFloat()
}
