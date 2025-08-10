package com.bgradin.customframe.data

import android.content.Context
import androidx.room.*

@Entity(tableName = "photos")
data class PhotoEntity(
    @PrimaryKey val id: String, // Dropbox file id (or path)
    val pathLower: String,
    val lastShownAt: Long?,
    val lastDownloadedAt: Long?
)

@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos") fun all(): List<PhotoEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsertAll(items: List<PhotoEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(item: PhotoEntity)
}

@Database(entities = [PhotoEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "app.db").build().also { inst = it }
        }
    }
}
