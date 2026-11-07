package com.bgradin.customframe.diagnostics

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RotatingLogTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun rotatesAndKeepsOnlyFourBoundedFiles() {
        val dir = temp.newFolder()
        val log = RotatingLog(dir, 128)
        repeat(100) { log.append("event-$it " + "x".repeat(20)) }
        assertEquals(4, dir.listFiles()!!.size)
        assertTrue(dir.listFiles()!!.all { it.length() <= 128 })
        assertTrue(File(dir, "events.log").readText().contains("event-99"))
        assertFalse(dir.listFiles()!!.any { it.readText().contains("event-0 ") })
    }

    @Test fun survivesRecreationAndBoundsOversizedEntries() {
        val dir = temp.newFolder()
        RotatingLog(dir, 128).append("first")
        RotatingLog(dir, 128).append("second")
        assertEquals("first\nsecond\n", File(dir, "events.log").readText())
        RotatingLog(dir, 128).append("界".repeat(1000))
        assertTrue(dir.listFiles()!!.all { it.length() <= 128 })
    }

    @Test fun concurrentWritersKeepCompleteRecords() {
        val dir = temp.newFolder()
        val log = RotatingLog(dir)
        val threads = (1..8).map { worker -> Thread { repeat(100) { log.append("$worker:$it") } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val lines = File(dir, "events.log").readLines()
        assertEquals(800, lines.size)
        assertEquals(800, lines.toSet().size)
    }
}
