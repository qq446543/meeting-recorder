package com.meetnote.recorder.capture

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class Point2(val x: Float, val y: Float)

/** 四点已排序：左上、右上、右下、左下 */
data class Quad(val tl: Point2, val tr: Point2, val br: Point2, val bl: Point2)

object QuadMath {

    /**
     * 用"和/差"法把乱序四点排成 TL/TR/BR/BL：
     *   x+y 最小 = 左上，最大 = 右下；x−y 最大 = 右上，最小 = 左下。
     * 对"相机斜拍屏幕"这类近似轴对齐的四边形足够稳；不支持绕轴旋转接近 45° 的极端取向。
     */
    fun orderPoints(points: List<Point2>): Quad {
        require(points.size == 4) { "需要 4 个点，实际 ${points.size}" }
        var tl = points[0]; var br = points[0]; var tr = points[0]; var bl = points[0]
        var minSum = Float.MAX_VALUE; var maxSum = -Float.MAX_VALUE
        var maxDiff = -Float.MAX_VALUE; var minDiff = Float.MAX_VALUE

        for (p in points) {
            val sum = p.x + p.y
            val diff = p.x - p.y
            if (sum < minSum) { minSum = sum; tl = p }
            if (sum > maxSum) { maxSum = sum; br = p }
            if (diff > maxDiff) { maxDiff = diff; tr = p }
            if (diff < minDiff) { minDiff = diff; bl = p }
        }
        return Quad(tl, tr, br, bl)
    }

    /** 鞋带公式，返回像素面积 */
    fun quadArea(q: Quad): Float {
        val pts = listOf(q.tl, q.tr, q.br, q.bl)
        var acc = 0f
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            acc += a.x * b.y - b.x * a.y
        }
        return abs(acc) / 2f
    }

    /** 上下边平均长 ÷ 左右边平均长 */
    fun aspectRatio(q: Quad): Float {
        val top = dist(q.tl, q.tr)
        val bottom = dist(q.bl, q.br)
        val left = dist(q.tl, q.bl)
        val right = dist(q.tr, q.br)
        val width = (top + bottom) / 2f
        val height = (left + right) / 2f
        if (height <= 0.001f) return Float.MAX_VALUE
        return width / height
    }

    /**
     * 该四边形是否像一块投影幕布：够大、比例不离谱。
     * 用来剔除误检到的课桌、白纸、人影。
     */
    fun isPlausible(
        q: Quad,
        frameW: Int,
        frameH: Int,
        minAreaRatio: Float = 0.12f,
        minAspect: Float = 0.5f,
        maxAspect: Float = 3.0f,
    ): Boolean {
        if (frameW <= 0 || frameH <= 0) return false
        val ratio = quadArea(q) / (frameW.toFloat() * frameH)
        if (ratio < minAreaRatio) return false
        val aspect = aspectRatio(q)
        return aspect in minAspect..maxAspect
    }

    /** 按四边形实际边长换算输出尺寸，宽不超过 [maxWidth]，不放大 */
    fun targetSize(q: Quad, maxWidth: Int = 1600): Pair<Int, Int> {
        val width = (dist(q.tl, q.tr) + dist(q.bl, q.br)) / 2f
        val height = (dist(q.tl, q.bl) + dist(q.tr, q.br)) / 2f
        val scale = if (width > maxWidth) maxWidth / width else 1f
        val outW = max(1, (width * scale).roundToInt())
        val outH = max(1, (height * scale).roundToInt())
        return outW to outH
    }

    private fun dist(a: Point2, b: Point2): Float {
        val dx = (a.x - b.x).toDouble()
        val dy = (a.y - b.y).toDouble()
        return sqrt(dx * dx + dy * dy).toFloat()
    }
}
