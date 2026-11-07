package com.bgradin.customframe.util

import java.util.Calendar

/** Local wall-clock schedule. The end is exclusive; equal endpoints mean all day. */
object WakeWindow {
    fun isAwake(minute: Int, start: Int, end: Int): Boolean = when {
        start == end -> true
        start < end -> minute >= start && minute < end
        else -> minute >= start || minute < end
    }

    fun nextEnd(now: Calendar, end: Int): Long {
        val boundary = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, end / 60)
            set(Calendar.MINUTE, end % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (boundary.timeInMillis <= now.timeInMillis) boundary.add(Calendar.DAY_OF_YEAR, 1)
        return boundary.timeInMillis
    }
}
