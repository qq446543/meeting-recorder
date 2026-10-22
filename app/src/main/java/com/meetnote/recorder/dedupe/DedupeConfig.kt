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
     * fgOldSim 的逐格容差：灰度变化不超过此值视为"旧文字仍在原处"。
     * 必须远小于换色文字/深色图示与正文的灰度差，否则"深色文字被深色图示
     * 整块盖住"这种换页只按均值灰度差算仅降 ~0.15，会被误判同页（实测 0.8514 > 0.85）。
     */
    const val FG_TOLERANCE = 0.10f

    /** 汇报用的综合分，仅用于日志与调参显示，不参与判定 */
    const val RECALL_WEIGHT = 0.5f
}
