package com.meetnote.recorder.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 相机三用例绑定（Preview + ImageAnalysis + ImageCapture）。
 * 只做接线：稳定判定、矫正、去重全部在 [CapturePipeline]（Task 10）。
 */
class CameraBinder(
    private val lifecycleOwner: LifecycleOwner,
    private val context: Context,
) {

    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null
    private val binding = AtomicBoolean(false)

    /** 分析分辨率 1280x720（spec §6.1），KEEP_ONLY_LATEST 绝不堆积帧 */
    private val analysisUseCase = ImageAnalysis.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    ),
                )
                .build(),
        )
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        .build()

    private val captureUseCase = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    ),
                ).build(),
        )
        .build()

    /**
     * 绑定 Preview + 分析 + 抓拍三用例，返回绑定的 [Preview]（失败返回 null）。
     * 重复 bind 时只更新 surfaceProvider 并返回已有 Preview。
     */
    fun bind(surfaceProvider: Preview.SurfaceProvider): Preview? {
        if (binding.getAndSet(true)) {
            preview?.setSurfaceProvider(surfaceProvider)
            return preview
        }
        val p = Preview.Builder().build().apply { setSurfaceProvider(surfaceProvider) }
        preview = p
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                cameraProvider.unbindAll()

                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    p,
                    analysisUseCase,
                    captureUseCase,
                )
                imageCapture = captureUseCase
            } catch (t: Throwable) {
                binding.set(false)
                preview = null
                imageCapture = null
            }
        }, ContextCompat.getMainExecutor(context))
        return p
    }

    fun setAnalyzer(analyzer: ImageAnalysis.Analyzer?) {
        analysisUseCase.setAnalyzer(
            ContextCompat.getMainExecutor(context),
            analyzer ?: ImageAnalysis.Analyzer { proxy -> proxy.close() },
        )
    }

    /**
     * 抓一张全分辨率图。同一时刻只允许一张在飞（稳定判定后 400ms 冷却，
     * 这里是第二道保险，避免 ImageCapture 排队）。
     */
    fun takePicture(executor: Executor, onBitmap: (Bitmap?) -> Unit) {
        val cap = imageCapture ?: run { onBitmap(null); return }
        if (!inFlight.compareAndSet(false, true)) { onBitmap(null); return }

        val tmp = File(context.cacheDir, "shot-${System.nanoTime()}.jpg")
        cap.takePicture(
            ImageCapture.OutputFileOptions.Builder(tmp).build(),
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val bmp = BitmapFactory.decodeFile(tmp.absolutePath)
                    tmp.delete()
                    inFlight.set(false)
                    onBitmap(bmp)
                }

                override fun onError(exception: ImageCaptureException) {
                    tmp.delete()
                    inFlight.set(false)
                    onBitmap(null)
                }
            },
        )
    }

    fun unbind() {
        if (!binding.getAndSet(false)) return
        runCatching { provider?.unbindAll() }
        provider = null
        preview = null
        imageCapture = null
    }

    companion object {
        private val inFlight = AtomicBoolean(false)
    }
}
