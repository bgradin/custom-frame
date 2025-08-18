package com.bgradin.customframe.data

import android.content.Context
import android.util.Log
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.ListFolderErrorException
import com.dropbox.core.v2.files.ListFolderResult
import com.bgradin.customframe.util.PhotoMeta
import com.bgradin.customframe.util.weightedPick
import java.io.File

class DropboxRepository(private val ctx: Context, token: String) {
    private val client = DbxClientV2(DbxRequestConfig.newBuilder("CustomFrame/1.0").build(), token)

    fun listAllPhotosRecursively(path: String): List<FileMetadata> {
        val photos = mutableListOf<FileMetadata>()
        fun collect(res: ListFolderResult) {
            for (m in res.entries) {
                when (m) {
                    is FileMetadata -> {
                        if (m.name.endsWith(".jpg", true) || m.name.endsWith(".jpeg", true) || m.name.endsWith(".png", true)) {
                            photos += m
                        }
                    }
                    else -> {}
                }
            }
        }
        try {
            var result = client.files().listFolderBuilder(path).withRecursive(true).start()
            collect(result)
            while (result.hasMore) {
                result = client.files().listFolderContinue(result.cursor)
                collect(result)
            }
        } catch (e: ListFolderErrorException) {
            throw e
        }
        return photos
    }

    fun chooseAndDownloadBatch(all: List<FileMetadata>, alpha: Double, count: Int = 200): List<File> {
        val dao = AppDb.get(ctx).photoDao()
        val now = System.currentTimeMillis()
        val metas = all.map { f ->
            val id = f.id
            val existing = dao.all().find { it.id == id }
            PhotoMeta(id, existing?.lastShownAt)
        }
        val chosenIds = weightedPick(metas, count, alpha, now).toSet()
        val chosen = all.filter { it.id in chosenIds }
        val dir = photosDir(ctx)
        Log.i("DropboxRepository", "Saving files to ${dir.absolutePath}...")

        val outFiles = mutableListOf<File>()
        for ((index, m) in chosen.withIndex()) {
            val safe = safeName("${m.id}_${m.name}")
            val local = File(dir, safe)
            local.parentFile?.mkdirs()
            Log.i("DropboxRepository", "Downloading image $index of $count -> ${local.absolutePath}")

            val tmp = File(local.parentFile!!, local.name + ".part")
            tmp.outputStream().use { fos ->
                client.files().download(m.pathLower).download(fos)
            }
            if (!tmp.renameTo(local)) {
                // Fallback if rename fails on some filesystems
                tmp.copyTo(local, overwrite = true)
                tmp.delete()
            }

            outFiles += local
            dao.upsert(PhotoEntity(m.id, m.pathLower, existingLastShownAt(dao, m.id), System.currentTimeMillis()))
        }
        return outFiles
    }

    private fun photosDir(ctx: Context) = File(ctx.filesDir, "photos").apply { mkdirs() }

    private fun safeName(raw: String): String =
        raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("""[^A-Za-z0-9._-]"""), "_")

    private fun existingLastShownAt(dao: PhotoDao, id: String): Long? = dao.all().find { it.id == id }?.lastShownAt
}
