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

    /** 最近一页的信号缓存，避免每张都解码上一张图 */
    private var lastSignals: ImageSignals? = null

    fun setPaused(paused: Boolean) {
        this.paused = paused
        if (!paused) stability.reset()
    }

    fun analyze(grid: FrameGrid, tsMs: Long) {
        if (paused || inFlight) return
        val verdict = stability.onFrame(grid, tsMs)
        if (verdict is Verdict.CaptureNow) {
            inFlight = true
            onPictureRequest()
        }
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
            onStatus(PipelineStatus("拍摄失败，继续监测…"))
            return
        }
        scope.launch(ioContext) {
            try {
                process(bitmap)
            } catch (t: Throwable) {
                onStatus(PipelineStatus("处理失败，继续监测…"))
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
        val image = correction.bitmap

        // 2. 质量门：不过就静默丢弃（spec §12）
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        val gray = QualityGate.grayOf(pixels)
        val aspect = correction.quad?.let { QuadMath.aspectRatio(it) }
            ?: image.width.toFloat() / image.height
        val lap = QualityGate.laplacianVariance(gray, image.width, image.height)
        if (!QualityGate.accept(lap, QualityGate.meanLuma(gray), aspect)) {
            return   // 画面模糊/过亮/比例异常：丢弃候选，继续监测
        }

        // 3. 同页判定
        val signals = SignalBuilder.fromPixels(pixels, image.width, image.height)
        val previous = cachedLastSignals()
        val isSamePage = previous != null && SlideDeduplicator.compare(previous, signals).isSamePage

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
            PipelineStatus(
                text = if (result.overwritten) "已更新第 ${result.index} 页"
                else "已拍第 ${result.index} 页",
                quad = correction.quad,
                degraded = degraded,
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
