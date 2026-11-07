package com.bgradin.customframe.diagnostics

import org.junit.Assert.*
import org.junit.Test

class ErrorDetailsTest {
    @Test fun preservesTypesAndFramesWithoutSecretMessages() {
        val error = IllegalStateException("Bearer SECRET_TOKEN /private/photo.jpg",
            java.io.IOException("password=SECRET_PASSWORD"))
        val details = errorDetails(error)
        assertTrue(details.contains("java.lang.IllegalStateException"))
        assertTrue(details.contains("java.io.IOException"))
        assertTrue(details.contains("ErrorDetailsTest"))
        assertFalse(details.contains("SECRET"))
        assertFalse(details.contains("photo.jpg"))
    }

    @Test fun boundsCircularCauseChains() {
        val first = Exception("secret-one")
        val second = Exception("secret-two", first)
        first.initCause(second)
        assertEquals(8, errorDetails(first).lineSequence().count { it == "java.lang.Exception" })
    }
}
