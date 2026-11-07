package com.bgradin.customframe.diagnostics

/** Exception messages often contain credentials or remote paths. */
internal fun errorDetails(error: Throwable): String = buildString {
    var cause: Throwable? = error
    repeat(8) {
        val current = cause ?: return@buildString
        append(current.javaClass.name).append('\n')
        current.stackTrace.take(60).forEach { append("  at ").append(it).append('\n') }
        cause = current.cause.takeUnless { it === current }
    }
}
