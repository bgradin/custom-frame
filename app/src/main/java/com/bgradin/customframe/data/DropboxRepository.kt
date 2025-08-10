package com.bgradin.customframe.data

import android.content.Context
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
        val dir = File(ctx.filesDir, "photos").apply { mkdirs() }

        val outFiles = mutableListOf<File>()
        for (m in chosen) {
            val local = File(dir, m.id.replace(":", "_") + "_" + m.name)
            ctx.openFileOutput(local.name, Context.MODE_PRIVATE).use { fos ->
                client.files().download(m.pathLower).download(fos)
            }
            outFiles += local
            dao.upsert(PhotoEntity(m.id, m.pathLower, existingLastShownAt(dao, m.id), System.currentTimeMillis()))
        }
        return outFiles
    }

    private fun existingLastShownAt(dao: PhotoDao, id: String): Long? = dao.all().find { it.id == id }?.lastShownAt
}
