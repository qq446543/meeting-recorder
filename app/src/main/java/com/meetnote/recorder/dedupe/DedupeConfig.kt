package com.meetnote.recorder.dedupe

/**
 * 同页去重的全部阈值与网格尺寸。真机调参时只改这里（spec 风险 #4）。
 */
object DedupeConfig {
    /** 粗网格：判定"墨迹"分布，用于 recall / coverage */
    const val INK_W = 64
    const val INK_H = 48

    /** 细网格：判定"旧文字是否还在原处"，用于 fgOldSim */
    const val FINE_W = 400
    const val FINE_H = 225

    /** 归一化灰度低于此值视为"有内容（墨迹）" */
    const val INK_THRESHOLD = 0.72f

    /** 同页三条件（同时满足才判同页） */
    const val SAME_RECALL = 0.90f
    const val SAME_FG = 0.85f
    const val SAME_COVERAGE = 0.90f

    /**
     * fgOldSim 的逐格缩放系数（**非死区容差**）：每格相似度 =
     * `1 - min(1, |Δ灰度| / FG_TOLERANCE)`。小于该值的漂移同样被线性放大惩罚
     * （Δ=0.05 → 该格 0.50），并非"不超即视为不变"。存在意义：深色文字被深色
     * 图示整块盖住时每格均值差仅 ~0.17，纯绝对差度量实测 0.8514 > 0.85 会误判同页。
     *
     * 漂移预算：在 SAME_FG=0.85 下，均匀曝光/对齐漂移预算 ≈ FG_TOLERANCE × 0.15
     * = 0.015（约 4/255 灰阶），比纯绝对差度量（预算 0.15）紧约 10 倍。
     * 方向安全：逐格 min(1, ·) 截断使 fgOldSim 只会低于纯绝对差版本，只会把
     * 同页翻成新页（宁可多留一页），不会把新页翻成同页。
     *
     * 真机复核（spec §15 风险 #4，首次真机调参）：必须用累积内容场景在曝光/对齐
     * 漂移下验证仍判同页；若被误判为新页，调高 FG_TOLERANCE 放宽（调低只会收紧、
     * 更易误判新页），配合 `adb logcat -s Dedupe` 观察 recall/fg/cov 定位掉线项。
     */
    const val FG_TOLERANCE = 0.10f

    /** 汇报用的综合分，仅用于日志与调参显示，不参与判定 */
    const val RECALL_WEIGHT = 0.5f
}
