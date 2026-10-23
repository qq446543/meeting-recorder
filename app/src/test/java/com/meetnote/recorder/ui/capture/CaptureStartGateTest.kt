package com.meetnote.recorder.ui.capture

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHOLE-BRANCH C1：自动开录两级权限门。
 * 阻断集合必须只含相机+麦克风；POST_NOTIFICATIONS 缺失绝不能阻断 MeetingService.start
 * （通知权限仍在同一权限流程里请求，但只是"请通知"级别，spec §10 非阻断、§12 禁止静默阻断）。
 */
class CaptureStartGateTest {

    @Test
    fun `camera and audio granted but notifications denied - gate empty can start`() {
        // 判别用例（C1）：相机+麦克风已授权、通知被拒 → 阻断集合必须为空（可开录）
        assertTrue(
            blockingMissingPermissions(
                cameraGranted = true,
                audioGranted = true,
                notificationsGranted = false,
            ).isEmpty(),
        )
    }

    @Test
    fun `all three granted - gate empty can start`() {
        assertTrue(blockingMissingPermissions(true, true, true).isEmpty())
    }

    @Test
    fun `camera denied - gate blocks start`() {
        assertFalse(blockingMissingPermissions(false, true, true).isEmpty())
    }

    @Test
    fun `audio denied - gate blocks start`() {
        assertFalse(blockingMissingPermissions(true, false, true).isEmpty())
    }

    @Test
    fun `all three denied - blocking set is exactly camera and audio`() {
        assertEquals(
            listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            blockingMissingPermissions(false, false, false),
        )
    }
}
