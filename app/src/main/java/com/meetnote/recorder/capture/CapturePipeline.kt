package com.meetnote.recorder.capture

import android.graphics.Bitmap
import com.meetnote.recorder.data.MeetingRepository
import com.meetnote.recorder.data.ShotResult
import com.meetnote.recorder.dedupe.ImageSignals
import com.meetnote.recorder.dedupe.SignalBuilder
import com.meetnote.recorder.dedupe.SlideDeduplicator
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/** 流水线状态，交由 UI 显示 */
data class PipelineStatus(
    val text: String,
    val quad: Quad? = null,
    val degraded: Boolean = false,
    val shot: ShotResult? = null,
    /** 拍摄原图尺寸（quad 在原图坐标系；叠加层据此换算坐标）。流水线持叠加层快照，每条状态随行携带 */
    val imageW: Int? = null,
    val imageH: Int? = null,
)

/**
 * 抓拍流水线（spec §5）：
 *   稳定判定 → 触发拍摄 → 文档矫正 → 质量门 → 同页判定 → 覆盖或追加
 *
 * 线程约定：[analyze] 由相机分析线程同步调用（只做 64×36 的差分，极轻）；
 * [onPicture] 的处理在 [scope] 中执行，文件 IO 指定到 [ioContext]。
 */
class CapturePipeline(
    private val meetingId: Long,
    private val repository: MeetingRepository,
    private val scope: CoroutineScope,
    private val ioContext: CoroutineContext = Dispatchers.IO,
    private val correct: (Bitmap) -> Correction = DocumentCorrector()::correct,
    private val onStatus: (PipelineStatus) -> Unit = {},
) {

    /** 由拍摄页注入：请求相机拍一张全分辨率图，结果回传 [onPicture] */
    var onPictureRequest: () -> Unit = {}

    private val stability = StabilityDetector()

    @Volatile private var paused = false
    @Volatile private var inFlight = false
    private var detectFailures = 0

    /** 叠加层快照（真机验收审查裁定：流水线持有，每条状态行完整携带）。
     *  [analyze]/emitStage 在相机分析线程读它，[process] 在 io 协程写它 → 必须 @Volatile */
    private data class OverlaySnapshot(val quad: Quad?, val degraded: Boolean, val w: Int, val h: Int)

    @Volatile private var snapshot: OverlaySnapshot? = null

    /** 所有状态行统一从快照取叠加层：绿框/降级/原图尺寸随行，阶段行不再抹掉 UI 绿框 */
    private fun status(text: String, shot: ShotResult? = null) = PipelineStatus(
        text = text,
        quad = snapshot?.quad,
        degraded = snapshot?.degraded == true,
        shot = shot,
        imageW = snapshot?.w,
        imageH = snapshot?.h,
    )

    /** 最近一页的信号缓存，避免每张都解码上一张图 */
    private var lastSignals: ImageSignals? = null

    fun setPaused(paused: Boolean) {
        this.paused = paused
        if (!paused) stability.reset()
    }

    /** 最近一次发出的阶段文案：仅在阶段切换时上报（真机验收 H2：判定过程必须可见，但绝不逐帧刷屏） */
    private var lastStage: String? = null

    fun analyze(grid: FrameGrid, tsMs: Long) {
        if (paused || inFlight) return
        val verdict = stability.onFrame(grid, tsMs)
        if (verdict is Verdict.CaptureNow) {
            inFlight = true
            onPictureRequest()
            return
        }
        emitStage(verdict)
    }

    /** 阶段判定 → 状态文案；连续同阶段不重复上报，CaptureNow 本身不发阶段行（拍摄状态另发） */
    private fun emitStage(verdict: Verdict) {
        val text = when (verdict) {
            Verdict.Initialising -> "正在观察画面…"
            Verdict.Stabilising -> "画面有变化，等待稳定…"
            Verdict.NoChange -> "画面已稳定，等待翻页"
            Verdict.CaptureNow -> return
        }
        if (text == lastStage) return
        lastStage = text
        onStatus(status(text))
    }

    /** 手动快门：跳过稳定判定直接拍 */
    fun forceCapture() {
        if (paused || inFlight) return
        inFlight = true
        onPictureRequest()
    }

    fun onPicture(bitmap: Bitmap?) {
        if (bitmap == null) {
            inFlight = false
            // 拍摄失败时屏幕还在原位：沿用当前快照，绿框保持可见
            onStatus(status("拍摄失败，继续监测…"))
            return
        }
        scope.launch(ioContext) {
            try {
                process(bitmap)
            } catch (t: Throwable) {
                onStatus(status("处理失败，继续监测…"))
            } finally {
                inFlight = false
            }
        }
    }

    private suspend fun process(source: Bitmap) {
        // 1. 文档矫正（OpenCV 不可用或找不到屏幕边时返回原图 + degraded）
        val correction = correct(source)
        detectFailures = if (correction.degraded) detectFailures + 1 else 0
        val degraded = detectFailures >= DEGRADE_AFTER
        // 叠加层快照：本帧矫正结果即当前屏幕四边形。后续所有状态行（含质量门拒绝、
        // 成功、失败）都从这里取叠加层，阶段行不会把 UI 绿框抹掉（真机验收审查裁定）
        snapshot = OverlaySnapshot(correction.quad, degraded, source.width, source.height)
        val image = correction.bitmap

        // 2. 质量门：不过就丢弃，但必须可见（spec §12 禁止静默阻断），并带上原图尺寸供绿框换算
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        val gray = QualityGate.grayOf(pixels)
        val aspect = correction.quad?.let { QuadMath.aspectRatio(it) }
            ?: image.width.toFloat() / image.height
        val lap = QualityGate.laplacianVariance(gray, image.width, image.height)
        if (!QualityGate.accept(lap, QualityGate.meanLuma(gray), aspect)) {
            // 画面模糊/过亮/比例异常：丢弃候选、继续监测，让拍摄者知道这帧没算数
            onStatus(status("画面模糊或过亮，已跳过，继续监测…"))
            return
        }

        // 3. 同页判定
        val signals = SignalBuilder.fromPixels(pixels, image.width, image.height)
        val previous = cachedLastSignals()
        val sim = previous?.let { SlideDeduplicator.compare(it, signals) }
        val isSamePage = sim?.isSamePage == true

        // 调参日志（spec §15）：每张抓拍输出关键指标，供 adb logcat -s Dedupe 阈值调参
        android.util.Log.d(
            "Dedupe",
            buildString {
                append("index=")
                append(repository.pageCount(meetingId))
                if (sim != null) {
                    append(" recall="); append("%.3f".format(sim.recall))
                    append(" fg="); append("%.3f".format(sim.fgOldSim))
                    append(" cov="); append("%.3f".format(sim.coverage))
                    append(" score="); append("%.3f".format(sim.score))
                    append(" same="); append(sim.isSamePage)
                } else {
                    append(" firstPage=true")
                }
                append(" lap="); append("%.1f".format(lap))
                append(" mean="); append("%.1f".format(QualityGate.meanLuma(gray)))
            },
        )

        // 4. 写文件 + 入库
        val target = repository.targetFileFor(meetingId, isSamePage)
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out ->
            image.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        val result = repository.registerShot(
            meetingId = meetingId,
            imageFile = target,
            width = image.width,
            height = image.height,
            isSamePage = isSamePage,
        )
        lastSignals = signals

        onStatus(
            status(
                text = if (result.overwritten) "已更新第 ${result.index} 页"
                else "已拍第 ${result.index} 页",
                shot = result,
            ),
        )
    }

    /** 取上一页信号：优先内存缓存；缓存为空（中途进页/进程重启）则解码磁盘文件 */
    private suspend fun cachedLastSignals(): ImageSignals? {
        lastSignals?.let { return it }
        val last = repository.observePages(meetingId).first().lastOrNull() ?: return null
        val bmp = android.graphics.BitmapFactory.decodeFile(last.filePath) ?: return null
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val signals = SignalBuilder.fromPixels(px, bmp.width, bmp.height)
        bmp.recycle()
        lastSignals = signals
        return signals
    }

    private companion object {
        /** 连续这么多次检测失败后进入降级提示（spec §6.3） */
        const val DEGRADE_AFTER = 3
        const val JPEG_QUALITY = 92
    }
}
