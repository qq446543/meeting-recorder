package com.meetnote.recorder.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "meeting")
data class Meeting(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val endedAt: Long? = null,
    val audioPath: String? = null,
    val audioDurationMs: Long = 0,
)

@Entity(
    tableName = "slide_page",
    foreignKeys = [ForeignKey(
        entity = Meeting::class,
        parentColumns = ["id"],
        childColumns = ["meetingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("meetingId")],
)
data class SlidePage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val meetingId: Long,
    /** 1 起始的页码 */
    val index: Int,
    val filePath: String,
    val width: Int,
    val height: Int,
    /** 该页被抓拍次数，去重后只保留最后一张 */
    val captureCount: Int = 1,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 列表页投影：会议 + 页数 */
data class MeetingStats(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val endedAt: Long?,
    val audioDurationMs: Long,
    val pageCount: Int,
)
