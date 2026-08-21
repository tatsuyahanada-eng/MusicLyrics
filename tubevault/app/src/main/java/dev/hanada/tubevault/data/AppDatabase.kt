package dev.hanada.tubevault.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CategoryEntity::class, MediaItemEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun categoryDao(): CategoryDao

    abstract fun mediaDao(): MediaDao

    companion object {
        /** Adds folder nesting. Existing folders default to top-level (null). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN parentId INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_categories_parentId ON categories(parentId)")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "tubevault.db",
            )
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
