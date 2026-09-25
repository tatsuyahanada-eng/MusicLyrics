package dev.hanada.tubevault.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CategoryEntity::class, MediaItemEntity::class, LyricsCacheEntity::class],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun categoryDao(): CategoryDao

    abstract fun mediaDao(): MediaDao

    abstract fun lyricsCacheDao(): LyricsCacheDao

    companion object {
        /** Adds folder nesting. Existing folders default to top-level (null). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN parentId INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_categories_parentId ON categories(parentId)")
            }
        }

        /** Adds the user's artist/title correction for lyrics lookup. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_items ADD COLUMN lyricsArtist TEXT")
                db.execSQL("ALTER TABLE media_items ADD COLUMN lyricsTitle TEXT")
            }
        }

        /**
         * Lets a download exist in more than one folder. The old (videoId,
         * kind) index enforced exactly one row per video across the whole
         * library, which is what made "copy to another folder" impossible —
         * inserting the second row would just replace the first. Scoping the
         * uniqueness to (categoryId, videoId, kind) keeps a folder from
         * holding the same video twice while letting two different folders
         * each hold their own copy.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_media_items_videoId_kind")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_media_items_categoryId_videoId_kind " +
                        "ON media_items(categoryId, videoId, kind)",
                )
            }
        }

        /**
         * A device-local record of lyrics that were already found once, so a
         * flaky connection or an outage at the lyrics source never takes away
         * something that used to display fine.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS lyrics_cache (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        artistKey TEXT NOT NULL,
                        titleKey TEXT NOT NULL,
                        synced INTEGER NOT NULL,
                        text TEXT NOT NULL,
                        cachedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_lyrics_cache_artistKey_titleKey " +
                        "ON lyrics_cache(artistKey, titleKey)",
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "tubevault.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}
