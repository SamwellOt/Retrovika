package com.retrovika.app.core.library

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.retrovika.app.core.dat.DatDao
import com.retrovika.app.core.dat.DatEntry

@Database(entities = [Game::class, DatEntry::class], version = 3, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun games(): GameDao
    abstract fun dats(): DatDao

    companion object {
        /** v2: identificação por DAT (tabela dat_entries + colunas datName/verified em games). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `dat_entries` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `systemId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `cleanTitle` TEXT NOT NULL,
                        `crc32` TEXT,
                        `md5` TEXT,
                        `size` INTEGER NOT NULL,
                        `region` TEXT,
                        `revision` TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dat_entries_systemId_crc32` ON `dat_entries` (`systemId`, `crc32`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dat_entries_systemId_md5` ON `dat_entries` (`systemId`, `md5`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dat_entries_systemId_cleanTitle` ON `dat_entries` (`systemId`, `cleanTitle`)")
                db.execSQL("ALTER TABLE `games` ADD COLUMN `datName` TEXT")
                db.execSQL("ALTER TABLE `games` ADD COLUMN `verified` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3: índices de addedAt e favorite (prateleiras "Adicionados" e "Favoritos" do início). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_games_addedAt` ON `games` (`addedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_games_favorite` ON `games` (`favorite`)")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "retrovika.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
