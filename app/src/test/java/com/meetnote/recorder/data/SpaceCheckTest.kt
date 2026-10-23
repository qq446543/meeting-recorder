package com.meetnote.recorder.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceCheckTest {

    @Test
    fun `plenty of space passes silently`() {
        assertTrue(SpaceCheck.hasEnough(8L * 1024 * 1024 * 1024))
        assertNull(SpaceCheck.warning(8L * 1024 * 1024 * 1024))
    }

    @Test
    fun `exactly the minimum is enough`() {
        assertTrue(SpaceCheck.hasEnough(SpaceCheck.MIN_FREE_BYTES))
        assertNull(SpaceCheck.warning(SpaceCheck.MIN_FREE_BYTES))
    }

    @Test
    fun `below the minimum warns`() {
        val free = SpaceCheck.MIN_FREE_BYTES - 1
        assertFalse(SpaceCheck.hasEnough(free))
        assertNotNull(SpaceCheck.warning(free))
    }

    @Test
    fun `warning message is human readable chinese`() {
        val msg = SpaceCheck.warning(100L * 1024 * 1024)!!
        assertTrue("应说明剩余空间：$msg", msg.contains("100") || msg.contains("可用空间"))
        assertTrue("应给出建议：$msg", msg.contains("导出") || msg.contains("清理"))
    }

    @Test
    fun `zero or unknown space warns`() {
        assertNotNull(SpaceCheck.warning(0L))
    }
}
