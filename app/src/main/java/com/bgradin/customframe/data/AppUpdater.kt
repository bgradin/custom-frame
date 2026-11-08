package com.bgradin.customframe.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Checks and downloads only; installation always requires the platform confirmation UI. */
class AppUpdater(private val context: Context) {
    @Suppress("DEPRECATION")
    fun download(): File? {
        val server = FrameServer(context)
        val config = server.refresh(force = true) ?: throw IOException("Configure a server first")
        check(server.updatesAuthorized()) { "Correct the auth key first" }
        val installed = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        val installedCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        if (config.code <= installedCode) { server.setStatus("App is up to date"); return null }
        val origin = server.origin
        val catalog = FrameServer.http.newCall(Request.Builder().url("$origin/api/custom-frame/v1/releases").build()).execute().use {
            check(it.code == 200) { "Release catalog unavailable" }
            JSONObject(String(requireNotNull(it.body).byteStream().use { stream -> stream.readBytesBounded(1024 * 1024) }, Charsets.UTF_8))
        }
        val releases = catalog.getJSONArray("releases")
        val records = (0 until releases.length()).map { releases.getJSONObject(it) }
        check(records.any { it.positiveInt("appVersionCode") == config.code && it.requiredText("appVersion") == config.version }) { "Recommended release is missing" }
        val candidate = records.filter {
            it.positiveInt("appVersionCode").toLong() in (installedCode + 1)..config.code.toLong() && compatible(it, context.packageName, Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT)
        }.maxByOrNull { it.positiveInt("appVersionCode") } ?: throw IOException("No compatible update for this device")
        val metadata = candidate.getJSONObject("download")
        val url = metadata.requiredText("url")
        check(java.net.URI(url).scheme == "https" && java.net.URI(url).rawUserInfo == null) { "Invalid download URL" }
        val size = metadata.positiveLong("sizeBytes")
        val hash = metadata.requiredText("sha256")
        check(size > 0 && hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid download metadata" }
        val directory = File(context.filesDir, "updates").apply { mkdirs() }
        val temp = File(directory, "update.part")
        val ready = File(directory, "update.apk")
        temp.delete(); ready.delete()
        check(size < directory.usableSpace - 32L * 1024 * 1024) { "Insufficient update storage" }
        try {
            FrameServer.http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.code == 200) { "APK unavailable" }
                check(response.header("Content-Type").orEmpty().substringBefore(';').trim() == "application/vnd.android.package-archive") { "Unexpected APK content type" }
                requireNotNull(response.body).byteStream().use { input -> temp.outputStream().use { output ->
                    copyVerifiedApk(input, output, size, hash)
                } }
            }
            val archive = context.packageManager.getPackageArchiveInfo(temp.path, PackageManager.GET_SIGNATURES)
                ?: throw IOException("Invalid APK")
            check(archive.packageName == context.packageName && archive.versionCode == candidate.positiveInt("appVersionCode") &&
                archive.versionName == candidate.getString("appVersion") && archive.applicationInfo!!.minSdkVersion <= Build.VERSION.SDK_INT) { "APK identity mismatch" }
            val expected = installed.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
            check(expected.isNotEmpty() && archive.signatures?.map { it.toCharsString() }?.toSet() == expected) { "APK signing identity mismatch" }
            check(temp.renameTo(ready)) { "Unable to save verified APK" }
            server.setStatus("Update verified; confirm installation")
            return ready
        } finally { temp.delete() }
    }

    companion object {
        internal fun compatible(release: JSONObject, packageName: String, manufacturer: String, model: String, sdk: Int): Boolean = runCatching {
            if (release.requiredText("applicationId") != packageName || release.positiveInt("minSdk") > sdk) return false
            val rules = release.getJSONArray("supportedDevices")
            val parsed = (0 until rules.length()).map {
                val rule = rules.getJSONObject(it)
                Triple(rule.requiredText("manufacturer"), rule.requiredText("model"), rule.positiveInt("minSdk"))
            }
            parsed.any { it.first == manufacturer && it.second == model && sdk >= it.third }
        }.getOrDefault(false)
    }
}

internal fun copyVerifiedApk(input: java.io.InputStream, output: java.io.OutputStream, size: Long, hash: String) {
    val digest = MessageDigest.getInstance("SHA-256")
    var count = 0L
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        check(n.toLong() <= size - count) { "APK exceeds declared size" }
        count += n
        output.write(buffer, 0, n)
        digest.update(buffer, 0, n)
    }
    check(count == size && digest.digest().joinToString("") { "%02x".format(it) } == hash) { "APK integrity check failed" }
}
