package com.bgradin.customframe.data

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** Private, origin-scoped configuration. Never log responses or transport exceptions. */
class FrameServer internal constructor(
    private val store: android.content.SharedPreferences,
    private val fetchConfig: (String, String) -> String
) {
    constructor(context: Context) : this(context.getSharedPreferences("frame_server", Context.MODE_PRIVATE), ::fetch)
    val origin: String get() = store.getString("origin", "").orEmpty()
    val status: String get() = store.getString("status", "Server not configured").orEmpty()
    val authKey: String get() = store.getString("key:$origin", "").orEmpty()
    val source: String get() = store.getString("source:$origin", "").orEmpty()
    class Config(val version: String, val code: Int, val name: String?, val token: String?)
    class Unauthorized : IOException("Auth key rejected. Correct it in settings.")

    fun cached(): Config? = store.getString("config:$origin", null)?.let { runCatching { parse(it) }.getOrNull() }

    fun save(domain: String, key: String): Config? = synchronized(lock) {
        if (domain.isBlank()) {
            store.edit().putString("origin", "").putString("status", "Manual Dropbox configuration").commit()
            return@synchronized null
        }
        val next = normalizeOrigin(domain)
        require(key.isNotBlank()) { "Enter an auth key for this server" }
        val configText = try { fetchConfig(next, key) } catch (e: Unauthorized) {
            if (next == origin) store.edit().putBoolean("unauthorized:$next", true)
                .putString("status", "Auth key rejected. Correct it in settings.").commit()
            throw e
        }
        val config = parse(configText)
        store.edit().putString("origin", next).putString("key:$next", key)
            .putString("config:$next", configText).putBoolean("unauthorized:$next", false)
            .putLong("retry:$next", 0).putString("status", "Configuration refreshed")
            .apply { if (!store.contains("source:$next") && config.token != null) putString("source:$next", "dropbox") }
            .commit()
        config
    }

    fun refresh(force: Boolean = false): Config? = synchronized(lock) {
        val current = origin
        if (current.isEmpty()) return@synchronized null
        if (!force && (store.getBoolean("unauthorized:$current", false) || System.currentTimeMillis() < store.getLong("retry:$current", 0))) return@synchronized cached()
        try {
            val text = fetchConfig(current, authKey)
            val config = parse(text)
            store.edit().putString("config:$current", text).putBoolean("unauthorized:$current", false)
                .putLong("retry:$current", 0).putString("status", "Configuration refreshed")
                .apply { if (!store.contains("source:$current") && config.token != null) putString("source:$current", "dropbox") }.commit()
            config
        } catch (e: Exception) {
            store.edit().putLong("retry:$current", System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15))
                .putBoolean("unauthorized:$current", e is Unauthorized || store.getBoolean("unauthorized:$current", false))
                .putString("status", if (e is Unauthorized) "Auth key rejected. Correct it in settings." else "Refresh failed; retaining saved configuration. Check server and network.").commit()
            if (force) throw e
            cached()
        }
    }

    fun photoToken(force: Boolean = false): String = synchronized(lock) {
        if (origin.isEmpty()) throw IOException("Server not configured")
        val config = refresh(force) ?: throw IOException("Refresh configuration in settings")
        if (source != "dropbox" || config.token == null) {
            store.edit().putString("status", "Photo source unavailable. Refresh configuration in settings.").commit()
            throw IOException("Photo source unavailable")
        }
        config.token
    }

    fun updatesAuthorized() = !store.getBoolean("unauthorized:$origin", false)
    fun setStatus(message: String) { store.edit().putString("status", message).apply() }

    companion object {
        private val lock = Any()
        val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.MINUTES).build()
        fun normalizeOrigin(input: String): String {
            val uri = URI(input.trim())
            require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.orEmpty() in listOf("", "/") &&
                (uri.port == -1 || uri.port in 1..65535)) { "Use an HTTPS server origin without a path, query, or credentials" }
            return "https://${uri.host.lowercase()}" + if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
        }
        fun parse(text: String): Config {
            val v = JSONObject(text).getJSONObject("v1")
            val version = v.requiredText("appVersion").also { require(it.isNotBlank()) }
            val code = v.positiveInt("appVersionCode").also { require(it > 0) }
            val sources = v.getJSONObject("sources")
            val dropbox = if (sources.has("dropbox")) sources.getJSONObject("dropbox") else null
            val name = dropbox?.requiredText("name")?.also { require(it.isNotBlank()) }
            val token = dropbox?.requiredText("accessToken")?.also { require(it.isNotBlank()) }
            return Config(version, code, name, token)
        }
        private fun fetch(origin: String, key: String): String {
            http.newCall(Request.Builder().url("$origin/api/custom-frame/v1/config").header("AuthKey", key).build()).execute().use {
                if (it.code == 401) throw Unauthorized()
                if (it.code != 200) throw IOException("Configuration unavailable")
                require(it.header("Content-Type").orEmpty().substringBefore(';').trim() == "application/json") { "Expected JSON" }
                val body = it.body ?: throw IOException("Missing configuration")
                body.byteStream().use { stream ->
                    val bytes = stream.readBytesBounded(1024 * 1024)
                    return String(bytes, Charsets.UTF_8).also { text -> parse(text) }
                }
            }
        }
    }
}

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(out.size().toLong() + count <= limit) { "Response too large" }
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}

internal fun JSONObject.requiredText(key: String): String = (get(key) as? String)
    ?.also { require(it.isNotBlank()) } ?: throw IllegalArgumentException("Invalid text field")

internal fun JSONObject.positiveLong(key: String): Long {
    val value = get(key)
    require(value is Int || value is Long) { "Expected integer" }
    return (value as Number).toLong().also { require(it > 0) }
}

internal fun JSONObject.positiveInt(key: String): Int = positiveLong(key)
    .also { require(it <= Int.MAX_VALUE) }.toInt()
