package com.meetnote.recorder.export

import kotlin.math.max
import kotlin.math.min

/** 页面上的一个矩形（PDF 点，原点左上） */
data class Fitted(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * A4 竖版（72dpi → 595×842 点）上的图片排版：等比缩放、居中、不越界、不放大。
 */
object PdfLayout {
    const val PAGE_WIDTH = 595f
    const val PAGE_HEIGHT = 842f
    const val MARGIN = 24f

    fun fit(
        imgW: Int,
        imgH: Int,
        pageW: Float = PAGE_WIDTH,
        pageH: Float = PAGE_HEIGHT,
        margin: Float = MARGIN,
    ): Fitted {
        val availW = max(1f, pageW - 2 * margin)
        val availH = max(1f, pageH - 2 * margin)

        if (imgW <= 0 || imgH <= 0) {
            // 尺寸未知：留一块可见的占位框，不至于把 PDF 画成空白
            return Fitted(margin, margin, availW, min(availH, 200f))
        }

        val scale = min(availW / imgW, availH / imgH).let { if (it > 1f) 1f else it }
        val w = imgW * scale
        val h = imgH * scale
        val left = margin + (availW - w) / 2f
        val top = margin + (availH - h) / 2f
        return Fitted(left, top, w, h)
    }
}
