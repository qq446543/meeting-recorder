package com.meetnote.recorder.data

import kotlinx.coroutines.flow.Flow
import java.io.File

/** 一次抓拍的落库结果 */
data class ShotResult(
    val index: Int,
    val overwritten: Boolean,
    val captureCount: Int,
)

/**
 * 仓库：编排 Room 与文件系统。
 *
 * 是否同页由调用方交给 [com.meetnote.recorder.dedupe.SlideDeduplicator]
 * 判定后通过 [isSamePage] 传入，阈值判定完全归算法所有，仓库只按布尔值分支，
 * 这样仓库逻辑可以脱离 Bitmap/图像算法单独测试。
 * 默认实现在 [DefaultMeetingRepository]。
 */
interface MeetingRepository {

    fun observeMeetings(): Flow<List<MeetingStats>>

    fun observePages(meetingId: Long): Flow<List<SlidePage>>

    suspend fun meeting(id: Long): Meeting?

    suspend fun pageCount(id: Long): Int

    suspend fun createMeeting(title: String): Long

    suspend fun finishMeeting(id: Long, audioPath: String?, durationMs: Long)

    suspend fun deleteMeeting(id: Long)

    suspend fun deletePage(pageId: Long)

    /**
     * 记录一次抓拍结果。调用方负责：把 [imageFile] 写到对应的
     * 覆盖路径或新页路径上（见 [targetFileFor]），本方法只负责入库与返回结果。
     *
     * @param isSamePage 是否与上一页为同一页（由 SlideDeduplicator 判定）；无上一页时传 false
     */
    suspend fun registerShot(
        meetingId: Long,
        imageFile: File,
        width: Int,
        height: Int,
        isSamePage: Boolean,
    ): ShotResult

    /** 本次抓拍该写到哪个文件：覆盖上一页 → 上一页路径；新页 → 下一页路径 */
    suspend fun targetFileFor(meetingId: Long, isSamePage: Boolean): File

    fun meetingAudioFile(meetingId: Long): File

    fun freeBytes(): Long
}
