package com.meetnote.recorder.ui.capture

import com.meetnote.recorder.capture.Point2
import kotlin.math.max

/**
 * 把分析帧坐标系里的点映射到 PreviewView 上，用于画检测到的屏幕绿框。
 *
 * 假设（Task 15 真机验收项）：
 *  - 后置摄像头竖拍时 [rotationDeg] = 90，图像需顺时针旋转 90° 才与预览一致；
 *  - PreviewView 使用默认的 FILL_CENTER（居中裁切）缩放。
 * 若真机上绿框与屏幕错位，只需要调整 [rotate] 中的分支。
 */
object PreviewOverlay {

    fun mapImagePointToView(
        p: Point2,
        imageW: Int,
        imageH: Int,
        rotationDeg: Int,
        viewW: Float,
        viewH: Float,
    ): Point2 {
        val (rx, ry, rotW, rotH) = rotate(p.x, p.y, imageW, imageH, rotationDeg)

        val scale = max(viewW / rotW, viewH / rotH)     // FILL_CENTER
        val dx = (viewW - rotW * scale) / 2f
        val dy = (viewH - rotH * scale) / 2f
        return Point2(rx * scale + dx, ry * scale + dy)
    }

    /** 旋转后的坐标与旋转后画幅尺寸 */
    private fun rotate(
        x: Float,
        y: Float,
        imageW: Int,
        imageH: Int,
        rotationDeg: Int,
    ): Rotated {
        val h = imageH.toFloat()
        val w = imageW.toFloat()
        // 连续坐标约定（非整数像素索引）：四角精确映射到旋转后画幅的四角，
        // 与测试注释一致——(0,0) 顺时针 90° → (h, 0)，(w-1,h-1) → (1, w-1)。
        return when (((rotationDeg % 360) + 360) % 360) {
            90  -> Rotated(h - y, x, h, w)              // 顺时针 90°
            180 -> Rotated(w - x, h - y, w, h)
            270 -> Rotated(y, w - x, h, w)              // 逆时针 90°
            else -> Rotated(x, y, w, h)
        }
    }

    private data class Rotated(val x: Float, val y: Float, val w: Float, val h: Float)
}
