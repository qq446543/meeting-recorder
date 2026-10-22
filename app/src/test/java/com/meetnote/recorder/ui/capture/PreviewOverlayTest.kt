package com.meetnote.recorder.ui.capture

import com.meetnote.recorder.capture.Point2
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewOverlayTest {

    private val eps = 0.75f

    @Test
    fun `rotation 90 maps image corner to rotated corner`() {
        // 后置摄像头竖拍：图像需顺时针转 90° 才是正的，左上角 → 右上角
        val v = PreviewOverlay.mapImagePointToView(
            p = Point2(0f, 0f), imageW = 1280, imageH = 720,
            rotationDeg = 90, viewW = 720f, viewH = 1280f,
        )
        assertEquals(720f, v.x, eps)
        assertEquals(0f, v.y, eps)
    }

    @Test
    fun `rotation 90 maps bottom right to bottom left`() {
        val v = PreviewOverlay.mapImagePointToView(
            p = Point2(1279f, 719f), imageW = 1280, imageH = 720,
            rotationDeg = 90, viewW = 720f, viewH = 1280f,
        )
        assertEquals(1f, v.x, eps)
        assertEquals(1279f, v.y, eps)
    }

    @Test
    fun `identity rotation keeps position`() {
        val v = PreviewOverlay.mapImagePointToView(
            Point2(100f, 200f), 640, 480, rotationDeg = 0, viewW = 640f, viewH = 480f,
        )
        assertEquals(100f, v.x, eps)
        assertEquals(200f, v.y, eps)
    }

    @Test
    fun `center crop upscales to fill the larger axis`() {
        // 旋转后 720x1280 要塞进 720x1600 的视图：按高放大 1.25 倍，宽出现负偏移
        val v = PreviewOverlay.mapImagePointToView(
            p = Point2(360f, 360f), imageW = 1280, imageH = 720,
            rotationDeg = 90, viewW = 720f, viewH = 1600f,
        )
        val scale = 1600f / 1280f          // 1.25
        val offsetX = (720f - 720f * scale) / 2f   // -90
        assertEquals(360f * scale + offsetX, v.x, eps)
        assertEquals(360f * scale, v.y, eps)
    }

    @Test
    fun `center crop letterboxes when view is wider`() {
        // 视图 1000x1280：宽方向放大更多，高度出现负偏移
        val v = PreviewOverlay.mapImagePointToView(
            p = Point2(0f, 719f), imageW = 1280, imageH = 720,
            rotationDeg = 90, viewW = 1000f, viewH = 1280f,
        )
        val rotW = 720f
        val rotH = 1280f
        val scale = 1000f / rotW
        val offsetY = (1280f - rotH * scale) / 2f
        // 图像点 (0,719) → 旋转后 (720-719, 0) = (1, 0)
        assertEquals(1f * scale, v.x, eps)
        assertEquals(0f * scale + offsetY, v.y, eps)
    }
}
