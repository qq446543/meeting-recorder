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
 * 相似度由调用方算好后通过 [similarityToLast] 传入，阈值判定在实现类的
 * `decide` 中完成，这样仓库逻辑可以脱离 Bitmap/图像算法单独测试。
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
     * @param similarityToLast 与上一页的相似度；无上一页时传 0f
     */
    suspend fun registerShot(
        meetingId: Long,
        imageFile: File,
        width: Int,
        height: Int,
        similarityToLast: Float,
    ): ShotResult

    /** 本次抓拍该写到哪个文件：覆盖上一页 → 上一页路径；新页 → 下一页路径 */
    suspend fun targetFileFor(meetingId: Long, similarityToLast: Float): File

    fun meetingAudioFile(meetingId: Long): File

    fun freeBytes(): Long
}
