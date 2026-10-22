package com.meetnote.recorder.data

import kotlinx.coroutines.flow.Flow
import java.io.File

class DefaultMeetingRepository(
    private val dao: MeetingDao,
    private val fileStore: FileStore,
) : MeetingRepository {

    override fun observeMeetings(): Flow<List<MeetingStats>> = dao.observeStats()

    override fun observePages(meetingId: Long): Flow<List<SlidePage>> = dao.observePages(meetingId)

    override suspend fun meeting(id: Long): Meeting? = dao.meeting(id)

    override suspend fun pageCount(id: Long): Int = dao.pageCount(id)

    override suspend fun createMeeting(title: String): Long =
        dao.insertMeeting(Meeting(title = title, createdAt = System.currentTimeMillis()))

    override suspend fun finishMeeting(id: Long, audioPath: String?, durationMs: Long) {
        dao.finishMeeting(
            id = id,
            audioPath = audioPath,
            audioDurationMs = durationMs,
            endedAt = System.currentTimeMillis(),
        )
    }

    override suspend fun deleteMeeting(id: Long) {
        fileStore.deleteMeeting(id)
        dao.deleteMeeting(id)
    }

    override suspend fun deletePage(pageId: Long) {
        dao.deletePage(pageId)
    }

    /**
     * 记录一次抓拍结果。调用方负责：把 [imageFile] 写到 [fileStore.pageFile] 对应的
     * 覆盖路径或新页路径上（见 [targetFileFor]），本方法只负责入库与返回结果。
     *
     * @param similarityToLast 与上一页的相似度；无上一页时传 0f
     */
    override suspend fun registerShot(
        meetingId: Long,
        imageFile: File,
        width: Int,
        height: Int,
        similarityToLast: Float,
    ): ShotResult {
        val last = dao.lastPage(meetingId)
        val now = System.currentTimeMillis()

        return if (last != null && decide(similarityToLast) == PageChoice.OVERWRITE) {
            // 同页新增内容：页码不变，只换图并累加拍摄次数
            dao.updatePage(
                last.copy(
                    filePath = imageFile.absolutePath,
                    width = width,
                    height = height,
                    captureCount = last.captureCount + 1,
                    updatedAt = now,
                )
            )
            ShotResult(index = last.index, overwritten = true, captureCount = last.captureCount + 1)
        } else {
            val next = (last?.index ?: 0) + 1
            val id = dao.insertPage(
                SlidePage(
                    meetingId = meetingId,
                    index = next,
                    filePath = imageFile.absolutePath,
                    width = width,
                    height = height,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            require(id > 0)
            ShotResult(index = next, overwritten = false, captureCount = 1)
        }
    }

    /** 本次抓拍该写到哪个文件：覆盖上一页 → 上一页路径；新页 → 下一页路径 */
    override suspend fun targetFileFor(meetingId: Long, similarityToLast: Float): File {
        val last = dao.lastPage(meetingId)
        return if (last != null && decide(similarityToLast) == PageChoice.OVERWRITE) {
            File(last.filePath)
        } else {
            fileStore.pageFile(meetingId, (last?.index ?: 0) + 1)
        }
    }

    override fun meetingAudioFile(meetingId: Long): File = fileStore.audioFile(meetingId)

    override fun freeBytes(): Long = fileStore.freeBytes()

    companion object {
        /** 判定函数集中在此，阈值由 DedupeConfig 提供（Task 5 接入真实阈值） */
        fun decide(similarity: Float): PageChoice =
            if (similarity >= THRESHOLD_OVERWRITE) PageChoice.OVERWRITE else PageChoice.APPEND

        private const val THRESHOLD_OVERWRITE = 0.85f
    }
}

enum class PageChoice { OVERWRITE, APPEND }
