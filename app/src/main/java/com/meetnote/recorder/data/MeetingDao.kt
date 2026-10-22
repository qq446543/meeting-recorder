package com.meetnote.recorder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MeetingDao {

    @Query(
        """
        SELECT m.id AS id, m.title AS title, m.createdAt AS createdAt,
               m.endedAt AS endedAt, m.audioDurationMs AS audioDurationMs,
               (SELECT COUNT(*) FROM slide_page p WHERE p.meetingId = m.id) AS pageCount
        FROM meeting m
        ORDER BY m.createdAt DESC
        """
    )
    fun observeStats(): Flow<List<MeetingStats>>

    @Insert
    suspend fun insertMeeting(meeting: Meeting): Long

    @Query("SELECT * FROM meeting WHERE id = :id")
    suspend fun meeting(id: Long): Meeting?

    @Query(
        "UPDATE meeting SET audioPath = :audioPath, audioDurationMs = :audioDurationMs, " +
            "endedAt = :endedAt WHERE id = :id"
    )
    suspend fun finishMeeting(id: Long, audioPath: String?, audioDurationMs: Long, endedAt: Long)

    @Query("DELETE FROM meeting WHERE id = :id")
    suspend fun deleteMeeting(id: Long)

    @Query("SELECT * FROM slide_page WHERE meetingId = :meetingId ORDER BY `index` ASC")
    fun observePages(meetingId: Long): Flow<List<SlidePage>>

    @Query("SELECT * FROM slide_page WHERE meetingId = :meetingId ORDER BY `index` DESC LIMIT 1")
    suspend fun lastPage(meetingId: Long): SlidePage?

    @Insert
    suspend fun insertPage(page: SlidePage): Long

    @Update
    suspend fun updatePage(page: SlidePage)

    @Query("DELETE FROM slide_page WHERE id = :id")
    suspend fun deletePage(id: Long)

    @Query("SELECT COUNT(*) FROM slide_page WHERE meetingId = :meetingId")
    suspend fun pageCount(meetingId: Long): Int
}
