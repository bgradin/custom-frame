package com.bgradin.customframe.util

import kotlin.math.exp

/**
 * Weighted sampling that prefers items with older lastShownAt.
 * alpha controls how strongly we prefer older items: higher = stronger.
 */
data class PhotoMeta(val id: String, val lastShownAt: Long?)

fun weight(lastShownAt: Long?, now: Long, alpha: Double): Double {
    val ageSec = if (lastShownAt == null) 1e9 else (now - lastShownAt).coerceAtLeast(0) / 1000.0
    // More age => larger weight. Exponential scaled by alpha.
    return exp(alpha * ageSec / (24*3600.0))
}

fun weightedPick(ids: List<PhotoMeta>, count: Int, alpha: Double, now: Long): List<String> {
    if (ids.isEmpty()) return emptyList()
    val mutable = ids.toMutableList()
    val out = mutableListOf<String>()
    repeat(count.coerceAtMost(mutable.size)) {
        val w = mutable.map { weight(it.lastShownAt, now, alpha) }
        val total = w.sum()
        var r = Math.random() * total
        var chosenIdx = 0
        for (i in w.indices) {
            r -= w[i]
            if (r <= 0) { chosenIdx = i; break }
        }
        out += mutable[chosenIdx].id
        mutable.removeAt(chosenIdx)
    }
    return out
}
