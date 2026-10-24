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

    /**
     * 同页三条件（同时满足才判同页）：SAME_RECALL/SAME_COVERAGE=0.85 吸收曝光漂移
     * 引起的墨迹掩码边界翻格（实测夹具 recall≈0.87 落在判别带 (0.85, 0.90)）
     */
    const val SAME_RECALL = 0.85f
    const val SAME_FG = 0.85f
    const val SAME_COVERAGE = 0.85f

    /**
     * fgOldSim 的逐格缩放系数（**非死区容差**）：每格相似度 =
     * `1 - min(1, |Δ灰度| / FG_TOLERANCE)`。小于该值的漂移同样被线性放大惩罚
     * （Δ=0.05 → 该格 0.50），并非"不超即视为不变"。存在意义：深色文字被深色
     * 图示整块盖住时每格均值差仅 ~0.17，纯绝对差度量实测 0.8514 > 0.85 会误判同页
     * （护栏用例 `dark text covered by dark figure is NOT same page` 锁死该场景）。
     *
     * 漂移预算（真机验收第二轮）：在 SAME_FG=0.85 下，均匀曝光/对齐漂移预算
     * ≈ FG_TOLERANCE × 0.15 = 0.30 × 0.15 = 0.045（约 11.5/255 灰阶），
     * 覆盖真机 AE 漂移（±4 灰阶）+ 四边形矫正对齐抖动（±2px 细格错位），
     * 同一张页面多次拍摄收敛为一页（只保留最后完整的那张，覆盖语义）。
     *
     * 方向（用户裁定，真机验收第二轮反转）：近似重复帧（同一张页面，曝光/对齐
     * 抖动）必须收敛为最后那次完整拍摄；**真正不同的页面绝不能被合并**——内容
     * 丢失不可恢复，recall/coverage/fg 三条件合取即为此设，护栏用例（换文字、
     * 深色图示盖字、内容变少、空白页）绝不放宽。0.30 时"深色图示盖字"场景
     * 页级实测 fg = 0.7309 < 0.85（0.44 只是变动格的逐格惩罚 1−0.17/0.30，
     * 约 47.5% 细格有差异），护栏要到 FG_TOLERANCE ≈ 0.54 才会合并——0.30 有余量。
     *
     * recall/coverage 同样取 0.85（审查裁定）：吸收曝光漂移引起的墨迹掩码边界翻格
     *（贴近 0.72 阈值的格子被抬亮后翻出掩码，实测夹具 recall ≈ 0.87 落在判别带
     * (0.85, 0.90)），护栏用例（换文字 recall=0.7535、盖字 recall=1.0/fg=0.7309）
     * 全部仍判新页。
     *
     * 真机复核（spec §15 风险 #4，真机验收第二轮后）：用 `adb logcat -s Dedupe`
     * 逐张观察 recall/fg/cov（loopback 已验证可读）；若同一张页面仍分裂成多页，
     * 下一轮直接读每张的 recall/fg/cov，定位是哪个条件掉线再调对应阈值。
     */
    const val FG_TOLERANCE = 0.30f

    /** 汇报用的综合分，仅用于日志与调参显示，不参与判定 */
    const val RECALL_WEIGHT = 0.5f
}
