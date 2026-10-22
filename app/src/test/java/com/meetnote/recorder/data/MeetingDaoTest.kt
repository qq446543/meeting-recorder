package com.meetnote.recorder.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeetingDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MeetingDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.meetingDao()
    }

    @After
    fun tearDown() = db.close()

    private fun meeting(title: String) = Meeting(title = title, createdAt = 1_000L)

    private fun page(meetingId: Long, index: Int) = SlidePage(
        meetingId = meetingId, index = index,
        filePath = "/tmp/page_$index.jpg", width = 1600, height = 900,
        createdAt = 2_000L, updatedAt = 2_000L,
    )

    @Test
    fun `insert meeting then stats carries page count`() = runBlocking {
        val id = dao.insertMeeting(meeting("周会"))
        dao.insertPage(page(id, 1))
        dao.insertPage(page(id, 2))

        val stats = dao.observeStats().first().single()
        assertEquals(id, stats.id)
        assertEquals("周会", stats.title)
        assertEquals(2, stats.pageCount)
        assertEquals(0L, stats.audioDurationMs)
    }

    @Test
    fun `lastPage returns highest index page`() = runBlocking {
        val id = dao.insertMeeting(meeting("评审"))
        dao.insertPage(page(id, 1))
        dao.insertPage(page(id, 7))

        assertEquals(7, dao.lastPage(id)!!.index)
    }

    @Test
    fun `lastPage is null for empty meeting`() = runBlocking {
        val id = dao.insertMeeting(meeting("空会"))
        assertNull(dao.lastPage(id))
    }

    @Test
    fun `updatePage increments captureCount in place`() = runBlocking {
        val id = dao.insertMeeting(meeting("更新"))
        val p = page(id, 3).copy(captureCount = 1)
        val pid = dao.insertPage(p)

        dao.updatePage(p.copy(id = pid, captureCount = 4, updatedAt = 9_999L))

        val rows = dao.observePages(id).first()
        assertEquals(1, rows.size)          // 仍是同一页，页数不增
        assertEquals(4, rows[0].captureCount)
        assertEquals(9_999L, rows[0].updatedAt)
    }

    @Test
    fun `finishMeeting writes audio fields`() = runBlocking {
        val id = dao.insertMeeting(meeting("录音"))
        dao.finishMeeting(id, audioPath = "/a/audio.m4a", audioDurationMs = 123_456L, endedAt = 7_777L)

        val m = dao.meeting(id)!!
        assertEquals("/a/audio.m4a", m.audioPath)
        assertEquals(123_456L, m.audioDurationMs)
        assertEquals(7_777L, m.endedAt)
    }

    @Test
    fun `deleteMeeting cascades pages`() = runBlocking {
        val id = dao.insertMeeting(meeting("级联"))
        dao.insertPage(page(id, 1))
        dao.insertPage(page(id, 2))

        dao.deleteMeeting(id)

        assertEquals(0, dao.pageCount(id))
        assertTrue(dao.observeStats().first().isEmpty())
    }

    @Test
    fun `stats ordered by createdAt descending`() = runBlocking {
        dao.insertMeeting(Meeting(title = "早", createdAt = 100L))
        dao.insertMeeting(Meeting(title = "晚", createdAt = 900L))

        val titles = dao.observeStats().first().map { it.title }
        assertEquals(listOf("晚", "早"), titles)
    }
}
