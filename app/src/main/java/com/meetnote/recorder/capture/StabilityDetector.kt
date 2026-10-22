package com.meetnote.recorder.capture

/**
 * 两级判定（spec §6.2）：
 *  1) 画面必须先稳定（连续 [stableFrames] 帧帧间 MAD ≤ [stableMad]），
 *     这样翻页动画（300–500ms）不会被抓到中间态；
 *  2) 稳定后与"上次抓拍帧"的差异必须 > [changeMad]，内容没变就不重拍；
 *  3) 每次抓拍后 [cooldownMs] 内不重复触发，防连拍风暴。
 */
class StabilityDetector(
    private val stableMad: Float = AnalysisConfig.STABLE_MAD,
    private val stableFrames: Int = AnalysisConfig.STABLE_FRAMES,
    private val changeMad: Float = AnalysisConfig.CHANGE_MAD,
    private val cooldownMs: Long = AnalysisConfig.COOLDOWN_MS,
) {

    private var prev: FrameGrid? = null
    private var reference: FrameGrid? = null
    private var stableRun = 0
    private var lastCaptureTs = Long.MIN_VALUE / 2

    fun reset() {
        prev = null
        reference = null
        stableRun = 0
        lastCaptureTs = Long.MIN_VALUE / 2
    }

    fun onFrame(grid: FrameGrid, tsMs: Long): Verdict {
        val last = prev
        prev = grid
        if (last == null) {
            stableRun = 0
            return Verdict.Initialising
        }

        val mad = meanAbsDiff(last, grid)
        if (mad > stableMad) {
            stableRun = 0
            return Verdict.Stabilising
        }

        stableRun++
        if (stableRun < stableFrames) return Verdict.Stabilising

        if (tsMs - lastCaptureTs < cooldownMs) return Verdict.NoChange

        val ref = reference
        if (ref == null) {
            reference = grid
            lastCaptureTs = tsMs
            stableRun = 0
            return Verdict.CaptureNow
        }

        val changed = meanAbsDiff(ref, grid)
        if (changed <= changeMad) return Verdict.NoChange

        reference = grid
        lastCaptureTs = tsMs
        stableRun = 0
        return Verdict.CaptureNow
    }
}

sealed interface Verdict {
    /** 首帧，还没有参照 */
    data object Initialising : Verdict
    /** 画面还在动（翻页动画 / 手抖） */
    data object Stabilising : Verdict
    /** 已稳定但内容没变，或处于冷却期 */
    data object NoChange : Verdict
    /** 应当抓拍 */
    data object CaptureNow : Verdict
}
