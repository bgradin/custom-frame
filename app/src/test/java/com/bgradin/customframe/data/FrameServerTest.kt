package com.bgradin.customframe.data

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class FrameServerTest {
    @Test fun originsNormalizeAndRejectCredentialLeakPaths() {
        assertEquals("https://example.com", FrameServer.normalizeOrigin("https://EXAMPLE.com:443/"))
        assertEquals("https://example.com:8443", FrameServer.normalizeOrigin("https://example.com:8443"))
        listOf("http://example.com", "https://user:pass@example.com", "https://example.com/api", "https://example.com?q=x", "https://example.com#x", "https://example.com:99999").forEach {
            assertTrue(it, runCatching { FrameServer.normalizeOrigin(it) }.isFailure)
        }
    }
    @Test fun configurationIgnoresFutureGroupsAndUnknownProviders() {
        val config = FrameServer.parse("""{"v1":{"appVersion":"next build","appVersionCode":2,"sources":{"future":{},"dropbox":{"name":"Photos","accessToken":"test-token"}},"future":true},"v2":{}}""")
        assertEquals("Photos", config.name)
        assertEquals("test-token", config.token)
        assertEquals(2, config.code)
    }
    @Test fun emptySourcesIsValidButMalformedSupportedSourceIsNot() {
        assertNull(FrameServer.parse("""{"v1":{"appVersion":"1","appVersionCode":1,"sources":{}}}""").token)
        listOf("{}", """{"v1":{"appVersion":"1","appVersionCode":1,"sources":{"dropbox":{}}}}""",
            """{"v1":{"appVersion":1,"appVersionCode":1,"sources":{}}}""",
            """{"v1":{"appVersion":"1","appVersionCode":1.5,"sources":{}}}""",
            """{"v1":{"appVersion":"1","appVersionCode":"1","sources":{}}}""").forEach {
            assertTrue(it, runCatching { FrameServer.parse(it) }.isFailure)
        }
    }
    @Test fun unsupportedAndMalformedDevicePoliciesFailClosed() {
        fun release(rules: String) = JSONObject("""{"applicationId":"com.bgradin.customframe","minSdk":25,"supportedDevices":$rules}""")
        fun matches(json: JSONObject) = AppUpdater.compatible(json, "com.bgradin.customframe", "rockchip", "w10f12", 25)
        assertTrue(matches(release("""[{"manufacturer":"rockchip","model":"w10f12","minSdk":25}]""")))
        listOf("[]", "null", "{}", """[{"manufacturer":"Rockchip","model":"w10f12","minSdk":25}]""",
            """[{"manufacturer":"rockchip","model":"w10f12","minSdk":26}]""",
            """[{"manufacturer":"rockchip","model":"w10f12","minSdk":25},{}]""").forEach { assertFalse(it, matches(release(it))) }
        assertFalse(matches(JSONObject("""{"applicationId":"com.bgradin.customframe","minSdk":25}""")))
        assertFalse(matches(release("[]").put("applicationId", "other.package")))
    }
    @Test fun oversizedResponsesAreRejected() {
        assertArrayEquals(byteArrayOf(1,2), byteArrayOf(1,2).inputStream().readBytesBounded(2))
        assertTrue(runCatching { byteArrayOf(1,2,3).inputStream().readBytesBounded(2) }.isFailure)
    }

    private fun memoryPreferences(): android.content.SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        val loader = javaClass.classLoader
        val editor = java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(android.content.SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString", "putLong", "putBoolean", "putInt" -> { values[args!![0] as String] = args[1]; proxy }
                "commit" -> true
                "apply" -> null
                else -> error(method.name)
            }
        }
        return java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(android.content.SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString", "getBoolean", "getLong", "getInt" -> values[args!![0]] ?: args[1]
                "contains" -> values.containsKey(args!![0])
                "edit" -> editor
                else -> error(method.name)
            }
        } as android.content.SharedPreferences
    }
    private fun config(token: String = "token") = """{"v1":{"appVersion":"1","appVersionCode":1,"sources":{"dropbox":{"name":"Dropbox","accessToken":"$token"}}}}"""

    @Test fun failedFirstSetupStaysManualAndOriginsNeverShareSnapshotsOrKeys() {
        val seen = mutableListOf<Pair<String, String>>()
        val server = FrameServer(memoryPreferences()) { origin, key ->
            seen += origin to key
            if (key == "bad") throw FrameServer.Unauthorized()
            config(key)
        }
        assertTrue(runCatching { server.save("https://one.test", "bad") }.isFailure)
        assertEquals("", server.origin)
        assertNull(server.cached())
        server.save("https://one.test", "one-key")
        assertEquals("one-key", server.photoToken())
        server.save("https://two.test", "two-key")
        assertEquals("two-key", server.authKey)
        assertEquals("two-key", server.photoToken())
        assertEquals("dropbox", server.source)
        server.save("", "")
        assertNull(server.cached())
        assertEquals("", server.authKey)
        assertEquals("https://two.test" to "two-key", seen.last())
    }
    @Test fun offlineRetainsSnapshotButUnauthorizedBlocksUpdateAuthorizationUntilCorrected() {
        var failure: Exception? = null
        var token = "old"
        var calls = 0
        val server = FrameServer(memoryPreferences()) { _, _ ->
            calls++
            failure?.let { throw it }
            config(token)
        }
        server.save("https://one.test", "key")
        failure = java.io.IOException("offline")
        assertEquals("old", server.refresh()!!.token)
        val afterFailure = calls
        assertEquals("old", server.photoToken())
        assertEquals(afterFailure, calls) // Backoff avoids another request.
        failure = FrameServer.Unauthorized()
        assertTrue(runCatching { server.refresh(force = true) }.isFailure)
        assertFalse(server.updatesAuthorized())
        assertEquals("old", server.cached()!!.token)
        failure = null
        token = "rotated"
        server.save("https://one.test", "new-key")
        assertTrue(server.updatesAuthorized())
        assertEquals("rotated", server.photoToken())
    }
    @Test fun removedSourceStopsDownloadsAndUnknownSourcesDoNotReplaceIt() {
        var response = config()
        val server = FrameServer(memoryPreferences()) { _, _ -> response }
        server.save("https://one.test", "key")
        response = """{"v1":{"appVersion":"1","appVersionCode":1,"sources":{"future":{"name":"Other"}}}}"""
        assertTrue(runCatching { server.photoToken(force = true) }.isFailure)
        assertEquals("dropbox", server.source)
        assertNull(server.cached()!!.token)
    }

    @Test fun downloadRejectsTruncationExcessBytesWrongHashAndInterruptedStreams() {
        val bytes = "apk bytes".toByteArray()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val output = java.io.ByteArrayOutputStream()
        copyVerifiedApk(bytes.inputStream(), output, bytes.size.toLong(), hash)
        assertArrayEquals(bytes, output.toByteArray())
        for ((size, digest) in listOf(bytes.size + 1L to hash, bytes.size - 1L to hash, bytes.size.toLong() to "0".repeat(64))) {
            assertTrue(runCatching { copyVerifiedApk(bytes.inputStream(), java.io.ByteArrayOutputStream(), size, digest) }.isFailure)
        }
        val interrupted = object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("interrupted")
        }
        assertTrue(runCatching { copyVerifiedApk(interrupted, java.io.ByteArrayOutputStream(), bytes.size.toLong(), hash) }.isFailure)
    }
}
