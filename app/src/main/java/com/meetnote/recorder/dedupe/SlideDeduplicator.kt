package com.meetnote.recorder.dedupe

data class Similarity(
    /** 旧页内容在新帧中的保留比例 */
    val recall: Float,
    /** 旧页墨迹区域的细网格灰度相似度 */
    val fgOldSim: Float,
    /** 新帧墨迹量 / 旧页墨迹量 */
    val coverage: Float,
    /** 汇报用综合分（不参与判定） */
    val score: Float,
    val isSamePage: Boolean,
)

object SlideDeduplicator {

    fun compare(old: ImageSignals, candidate: ImageSignals): Similarity {
        // 旧页没有内容时无法确认同页，一律按新页处理
        if (old.inkCount == 0) {
            return Similarity(
                recall = 0f, fgOldSim = 1f, coverage = Float.MAX_VALUE,
                score = 0f, isSamePage = false,
            )
        }

        var shared = 0
        var newInk = 0
        val mask = old.inkMask
        val candMask = candidate.inkMask
        for (i in mask.indices) {
            val oldHas = mask[i] == 1.toByte()
            val candHas = candMask[i] == 1.toByte()
            if (oldHas && candHas) shared++
            if (candHas) newInk++
        }

        val recall = shared.toFloat() / old.inkCount
        val coverage = newInk.toFloat() / old.inkCount

        // 只在"旧页有内容"的细格上比对：文字被替换或擦除会显著拉低该值，
        // 而"旧文字原地不动、旁边新增内容"时该值仍接近 1。
        // 每格相似度 = 1 - min(1, |Δ| / FG_TOLERANCE)（缩放截断，非死区容差，
        // 详见 DedupeConfig.FG_TOLERANCE 注释）：纯均值绝对差在"深色文字被深色
        // 图示盖住"时每格仅 ~0.17，整页实测 0.8514 会被误判同页。
        val oldFine = old.fineGray
        val candFine = candidate.fineGray
        var simSum = 0f
        var oldInkCells = 0
        for (i in oldFine.indices) {
            if (oldFine[i] < DedupeConfig.INK_THRESHOLD) {
                val diff = kotlin.math.abs(oldFine[i] - candFine[i])
                simSum += 1f - (diff / DedupeConfig.FG_TOLERANCE).coerceAtMost(1f)
                oldInkCells++
            }
        }
        val fgOldSim = if (oldInkCells == 0) 1f else simSum / oldInkCells

        val score = DedupeConfig.RECALL_WEIGHT * recall +
            (1f - DedupeConfig.RECALL_WEIGHT) * fgOldSim

        val isSamePage =
            recall >= DedupeConfig.SAME_RECALL &&
                fgOldSim >= DedupeConfig.SAME_FG &&
                coverage >= DedupeConfig.SAME_COVERAGE

        return Similarity(recall, fgOldSim, coverage, score, isSamePage)
    }
}
