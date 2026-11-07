package com.bgradin.customframe.util

import org.junit.Assert.*
import org.junit.Test

class WeightedSamplerTest {
    @Test fun unseenAndVeryOldPhotosHaveFiniteWeights() {
        assertTrue(weight(null, Long.MAX_VALUE, 2.0).isFinite())
        assertTrue(weight(0, Long.MAX_VALUE, 2.0).isFinite())
    }

    @Test fun sampleContainsEveryItemOnceWhenCountExceedsPool() {
        val photos = listOf(PhotoMeta("new", null), PhotoMeta("old", 0), PhotoMeta("recent", 1000))
        val selected = weightedPick(photos, 20, 2.0, 2000)
        assertEquals(setOf("new", "old", "recent"), selected.toSet())
        assertEquals(3, selected.size)
    }

    @Test fun nonPositiveCountReturnsEmptySample() {
        assertTrue(weightedPick(listOf(PhotoMeta("photo", null)), -1, 2.0, 0).isEmpty())
    }

    @Test fun olderPhotosHaveHigherWeight() {
        assertTrue(weight(0, 86400000, 2.0) > weight(86400000, 86400000, 2.0))
    }
}
