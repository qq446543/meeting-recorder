package com.meetnote.recorder.capture

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** 矫正结果 */
data class Correction(
    /** 矫正后的图；降级时即原图 */
    val bitmap: Bitmap,
    /** 检测到的屏幕四边形（原图坐标系）；未检测到为 null */
    val quad: Quad?,
    /** true = 走了降级路径（OpenCV 不可用 或 找不到屏幕边） */
    val degraded: Boolean,
    /** 降级原因，非降级时为 null */
    val reason: String?,
)

/**
 * 文档模式矫正：灰度 → 高斯 → Canny → 找四边形 → 透视拉正 → 裁切（spec §6.3）。
 * 只负责把 OpenCV 的调用串起来，"像不像屏幕"由 [QuadMath.isPlausible] 判定。
 */
class DocumentCorrector(
    private val maxOutputWidth: Int = 1600,
) {

    fun correct(source: Bitmap): Correction {
        if (!OpenCvLoader.ensure()) {
            return Correction(source, null, degraded = true, reason = "OpenCV 不可用")
        }

        // 先降采样再检测：12MP 原图直接跑 Canny 太慢，四点检测完换算回原图坐标
        val detectBitmap = if (source.width > DETECT_MAX_WIDTH) {
            val h = (source.height.toLong() * DETECT_MAX_WIDTH / source.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(source, DETECT_MAX_WIDTH, h, true)
        } else source
        val scaleFactor = source.width.toFloat() / detectBitmap.width

        val full = Mat()
        val detect = Mat()
        try {
            Utils.bitmapToMat(detectBitmap, detect)

            val gray = Mat()
            val blurred = Mat()
            val edges = Mat()
            val hierarchy = Mat()
            try {
                Imgproc.cvtColor(detect, gray, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
                Imgproc.Canny(blurred, edges, 50.0, 150.0)

                val contours = ArrayList<MatOfPoint>()
                Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

                val frameW = detect.cols()
                val frameH = detect.rows()
                val found = contours
                    .sortedByDescending { Imgproc.contourArea(it) }
                    .firstNotNullOfOrNull { contour -> detectQuad(contour, frameW, frameH) }

                if (found == null) {
                    return Correction(source, null, degraded = true, reason = "未检测到屏幕边缘")
                }

                // 四点换算回原图坐标系
                val quadInSource = Quad(
                    tl = scale(found.tl, scaleFactor),
                    tr = scale(found.tr, scaleFactor),
                    br = scale(found.br, scaleFactor),
                    bl = scale(found.bl, scaleFactor),
                )

                Utils.bitmapToMat(source, full)
                val warped = warp(full, quadInSource) ?: run {
                    return Correction(source, quadInSource, degraded = true, reason = "透视变换失败")
                }
                try {
                    val (outW, outH) = QuadMath.targetSize(quadInSource, maxOutputWidth)
                    val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                    Utils.matToBitmap(warped, out)
                    return Correction(out, quadInSource, degraded = false, reason = null)
                } finally {
                    warped.release()
                }
            } finally {
                gray.release()
                blurred.release()
                edges.release()
                hierarchy.release()
            }
        } catch (t: Throwable) {
            // native 调用中任何异常都不能让拍摄流程崩溃
            return Correction(source, null, degraded = true, reason = "矫正异常：${t.javaClass.simpleName}")
        } finally {
            full.release()
            detect.release()
            if (detectBitmap !== source) detectBitmap.recycle()
        }
    }

    /** 在检测用小图上找第一个"像屏幕"的四边形；返回检测坐标系下的 Quad */
    private fun detectQuad(contour: MatOfPoint, frameW: Int, frameH: Int): Quad? {
        val asFloat = MatOfPoint2f(*contour.toArray())
        val peri = Imgproc.arcLength(asFloat, true)
        val approx = MatOfPoint2f()
        try {
            Imgproc.approxPolyDP(asFloat, approx, 0.02 * peri, true)
            val pts = approx.toArray()
            if (pts.size != 4) return null

            val quad = QuadMath.orderPoints(pts.map { Point2(it.x.toFloat(), it.y.toFloat()) })
            if (!Imgproc.isContourConvex(MatOfPoint(*pts))) return null
            if (!QuadMath.isPlausible(quad, frameW, frameH)) return null
            return quad
        } finally {
            asFloat.release()
            approx.release()
        }
    }

    /** 透视拉正；返回的 Mat 由调用方负责 release */
    private fun warp(sourceMat: Mat, quad: Quad): Mat? {
        val (outW, outH) = QuadMath.targetSize(quad, maxOutputWidth)
        val src = MatOfPoint2f(
            Point(quad.tl.x.toDouble(), quad.tl.y.toDouble()),
            Point(quad.tr.x.toDouble(), quad.tr.y.toDouble()),
            Point(quad.br.x.toDouble(), quad.br.y.toDouble()),
            Point(quad.bl.x.toDouble(), quad.bl.y.toDouble()),
        )
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(outW.toDouble(), 0.0),
            Point(outW.toDouble(), outH.toDouble()),
            Point(0.0, outH.toDouble()),
        )
        val m = Imgproc.getPerspectiveTransform(src, dst)
        val out = Mat()
        try {
            Imgproc.warpPerspective(sourceMat, out, m, Size(outW.toDouble(), outH.toDouble()))
            return out
        } catch (t: Throwable) {
            out.release()
            return null
        } finally {
            src.release()
            dst.release()
            m.release()
        }
    }

    private fun scale(p: Point2, factor: Float): Point2 =
        Point2(p.x * factor, p.y * factor)

    companion object {
        /** 检测前把原图降采样到该宽度，够找边又足够快 */
        const val DETECT_MAX_WIDTH = 1280
    }
}
