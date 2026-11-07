package com.bgradin.customframe.util

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class PhotoStorageTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun automaticLimitHandlesLowSpaceRoundingAndCap() {
        assertEquals(0, PhotoStorage.automaticLimit(-1))
        assertEquals(0, PhotoStorage.automaticLimit(104_999_999))
        assertEquals(1, PhotoStorage.automaticLimit(105_000_000))
        assertEquals(42, PhotoStorage.automaticLimit(310_000_000))
        assertEquals(200, PhotoStorage.automaticLimit(Long.MAX_VALUE))
    }

    @Test fun actualImageSizeMustFitAboveReserve() {
        assertTrue(PhotoStorage.canDownload(110_000_000, 10_000_000))
        assertFalse(PhotoStorage.canDownload(109_999_999, 10_000_000))
        assertFalse(PhotoStorage.canDownload(99_999_999, 0))
        assertFalse(PhotoStorage.canDownload(Long.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test fun completedBatchRemovesPreviousRenamedAndPartialFiles() {
        val dir = temp.newFolder()
        listOf("old.jpg", "renamed.jpg", "abandoned.part", "current.png").forEach {
            dir.resolve(it).writeText("photo")
        }
        PhotoStorage.cleanup(dir, setOf("current.png"))
        assertEquals(listOf("current.png"), dir.list()!!.toList())
    }

    @Test fun successfulEmptyBatchClearsCollection() {
        val dir = temp.newFolder()
        dir.resolve("old.jpg").writeText("photo")
        PhotoStorage.cleanup(dir, emptySet())
        assertTrue(dir.list()!!.isEmpty())
    }
}
