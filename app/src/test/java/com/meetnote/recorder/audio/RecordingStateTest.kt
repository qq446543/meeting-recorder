package com.meetnote.recorder.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class RecordingStateTest {

    @Before
    fun setUp() = RecordingState.clear()

    @After
    fun tearDown() = RecordingState.clear()

    @Test
    fun `initial state is idle`() {
        val s = RecordingState.status.value
        assertFalse(s.running)
        assertEquals(0L, s.meetingId)
        assertNull(s.error)
    }

    @Test
    fun `start marks running with meeting id`() {
        RecordingState.bind(meetingId = 42, durationMs = 0)
        RecordingState.onStart()

        val s = RecordingState.status.value
        assertTrue(s.running)
        assertEquals(42, s.meetingId)
        assertEquals(0L, s.elapsedMs)
    }

    @Test
    fun `ticks accumulate elapsed time`() {
        RecordingState.bind(7, 0)
        RecordingState.onStart()
        RecordingState.onTick(1_000)
        RecordingState.onTick(65_000)

        assertEquals(65_000L, RecordingState.status.value.elapsedMs)
    }

    @Test
    fun `stop freezes elapsed and clears running`() {
        RecordingState.bind(7, 0)
        RecordingState.onStart()
        RecordingState.onTick(12_000)
        RecordingState.onStop(durationMs = 12_500)

        val s = RecordingState.status.value
        assertFalse(s.running)
        assertEquals(12_500L, s.elapsedMs)
        assertEquals(7, s.meetingId)   // 详情页还要用它显示录音时长
    }

    @Test
    fun `error is surfaced without stopping elapsed`() {
        RecordingState.bind(3, 0)
        RecordingState.onStart()
        RecordingState.onTick(5_000)
        RecordingState.onError("麦克风被占用")

        val s = RecordingState.status.value
        assertEquals("麦克风被占用", s.error)
        assertFalse(s.running)
        assertEquals(5_000L, s.elapsedMs)
    }

    @Test
    fun `recorder fake follows contract`() {
        val rec = FakeRecorder()
        val file = File(System.getProperty("java.io.tmpdir"), "a.m4a")
        rec.start(file)
        assertTrue(rec.isRunning())
        rec.tick(3_000)
        assertEquals(3_000L, rec.elapsedMs())
        assertEquals(3_000L, rec.stop())
        assertFalse(rec.isRunning())
        assertEquals(3_000L, rec.stop()) // 重复 stop 幂等
    }
}

/** 测试专用假实现：不碰 MediaRecorder，手动推进时间 */
class FakeRecorder : Recorder {
    private var running = false
    private var elapsed = 0L
    private var file: File? = null

    override fun start(file: File) {
        this.file = file
        running = true
        elapsed = 0L
    }

    override fun stop(): Long {
        if (!running) return elapsed
        running = false
        return elapsed
    }

    override fun elapsedMs(): Long = elapsed

    override fun isRunning(): Boolean = running

    fun tick(ms: Long) {
        elapsed += ms
    }
}
