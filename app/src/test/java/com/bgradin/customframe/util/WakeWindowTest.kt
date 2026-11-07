package com.bgradin.customframe.util

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class WakeWindowTest {
    @Test fun defaultWindowHasExclusiveEnd() {
        assertFalse(WakeWindow.isAwake(479, 480, 1320))
        assertTrue(WakeWindow.isAwake(480, 480, 1320))
        assertTrue(WakeWindow.isAwake(1319, 480, 1320))
        assertFalse(WakeWindow.isAwake(1320, 480, 1320))
    }

    @Test fun overnightAndAllDay() {
        assertTrue(WakeWindow.isAwake(1380, 1320, 480))
        assertTrue(WakeWindow.isAwake(0, 1320, 480))
        assertFalse(WakeWindow.isAwake(480, 1320, 480))
        assertFalse(WakeWindow.isAwake(800, 1320, 480))
        assertTrue(WakeWindow.isAwake(800, 480, 480))
    }

    @Test fun overrideExpiresAtNextEndIncludingAcrossMidnight() {
        val now = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles")).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 6, 23, 0)
        }
        val end = (now.clone() as Calendar).apply { timeInMillis = WakeWindow.nextEnd(now, 1320) }
        assertEquals(7, end.get(Calendar.DAY_OF_MONTH))
        assertEquals(22, end.get(Calendar.HOUR_OF_DAY))
        now.set(Calendar.HOUR_OF_DAY, 21)
        assertEquals(6, (now.clone() as Calendar).apply {
            timeInMillis = WakeWindow.nextEnd(now, 1320)
        }.get(Calendar.DAY_OF_MONTH))
    }
}
