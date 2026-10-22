package com.meetnote.recorder.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class FileStoreTest {

    private lateinit var root: File
    private lateinit var store: FileStore

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "fs-${System.nanoTime()}").apply { mkdirs() }
        store = FileStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `page file is zero padded so lexical order equals page order`() {
        val f1 = store.pageFile(5, 1)
        val f12 = store.pageFile(5, 12)
        assertTrue(f1.name.endsWith("page_0001.jpg"))
        assertTrue(f12.name.endsWith("page_0012.jpg"))
        assertTrue("page_0001.jpg" < "page_0012.jpg")
        assertEquals(f1.parentFile, f12.parentFile)
        assertEquals(File(root, "meetings/5"), f1.parentFile)
    }

    @Test
    fun `audio file lives beside pages`() {
        val audio = store.audioFile(9)
        assertEquals(File(root, "meetings/9"), audio.parentFile)
        assertEquals("audio.m4a", audio.name)
    }

    @Test
    fun `pageFile creates meeting directory`() {
        val f = store.pageFile(3, 1)
        assertTrue(f.parentFile!!.isDirectory)
    }

    @Test
    fun `deleteMeeting removes whole directory`() {
        store.pageFile(7, 1).writeBytes(byteArrayOf(1))
        store.audioFile(7).writeBytes(byteArrayOf(2))

        assertTrue(store.deleteMeeting(7))
        assertFalse(File(root, "meetings/7").exists())
        assertFalse(store.deleteMeeting(7))  // 幂等：目录已删，重复调用是安全 no-op
    }

    @Test
    fun `freeBytes is positive on temp volume`() {
        assertTrue(store.freeBytes() > 0L)
    }
}
