package com.meetnote.recorder.data

/**
 * 录音 + 抓拍的空间预算：2 小时录音约 85MB，40 页幻灯片约 40×300KB≈12MB，
 * 再留出导出 PDF 与系统缓存的余量，取 500MB 作为开拍前的最低水位。
 */
object SpaceCheck {
    const val MIN_FREE_BYTES: Long = 500L * 1024 * 1024

    fun hasEnough(freeBytes: Long): Boolean = freeBytes >= MIN_FREE_BYTES

    fun warning(freeBytes: Long): String? {
        if (hasEnough(freeBytes)) return null
        val freeMb = freeBytes / (1024L * 1024L)
        return "可用空间仅 ${freeMb}MB，低于 500MB。" +
            "继续录音与拍照可能中途失败；建议先清理空间，" +
            "并尽快导出已完成的会议纪要。"
    }
}
