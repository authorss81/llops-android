package com.authorss81.noteflow.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.authorss81.noteflow.data.model.NotebookEntity
import com.authorss81.noteflow.data.model.SectionEntity
import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.data.model.StrokeEntity
import com.authorss81.noteflow.data.model.MediaEmbedEntity
import com.authorss81.noteflow.data.model.PaletteItemEntity
import com.authorss81.noteflow.data.model.LayerEntity
import com.authorss81.noteflow.data.model.NoteVersionEntity
import com.authorss81.noteflow.services.VaultKeyHolder
import java.io.File

@Database(
    entities = [
        NotebookEntity::class,
        SectionEntity::class,
        NotePageEntity::class,
        StrokeEntity::class,
        MediaEmbedEntity::class,
        PaletteItemEntity::class,
        LayerEntity::class,
        NoteVersionEntity::class
    ],
    version = 8,
    exportSchema = true
)
abstract class NoteflowDatabase : RoomDatabase() {
    abstract fun notebookDao(): NotebookDao
    abstract fun sectionDao(): SectionDao
    abstract fun pageDao(): NotePageDao
    abstract fun strokeDao(): StrokeDao
    abstract fun mediaEmbedDao(): MediaEmbedDao
    abstract fun paletteDao(): PaletteDao
    abstract fun layerDao(): LayerDao
    abstract fun noteVersionDao(): NoteVersionDao

    companion object {
        const val SCHEMA_VERSION = 8

        @Volatile
        private var INSTANCE: NoteflowDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pages ADD COLUMN extractedText TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE strokes ADD COLUMN timestampMs INTEGER")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `media_embeds` (
                        `id` TEXT NOT NULL,
                        `pageId` TEXT NOT NULL,
                        `typeName` TEXT NOT NULL,
                        `x` REAL NOT NULL,
                        `y` REAL NOT NULL,
                        `width` REAL NOT NULL,
                        `height` REAL NOT NULL,
                        `contentUrlOrPath` TEXT,
                        `textContent` TEXT,
                        `codeLanguage` TEXT,
                        `durationMs` INTEGER NOT NULL,
                        `waveformJson` TEXT NOT NULL,
                        `pdfPage` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notebooks ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pages ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `palette_items` (
                        `id` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `colorInt` INTEGER NOT NULL,
                        `toolName` TEXT,
                        `strokeWidth` REAL,
                        `timestampMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `layers` (
                        `id` TEXT NOT NULL,
                        `pageId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `zOrder` INTEGER NOT NULL,
                        `opacity` REAL NOT NULL DEFAULT 1.0,
                        `blendMode` TEXT NOT NULL DEFAULT 'NORMAL',
                        `visible` INTEGER NOT NULL DEFAULT 1,
                        `locked` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("ALTER TABLE `strokes` ADD COLUMN `layerId` TEXT")
                // Migrate existing pages and strokes to a default 'Layer 1'
                db.execSQL("""
                    INSERT INTO `layers` (`id`, `pageId`, `name`, `zOrder`, `opacity`, `blendMode`, `visible`, `locked`)
                    SELECT 'layer_' || id, id, 'Layer 1', 0, 1.0, 'NORMAL', 1, 0 FROM pages
                """.trimIndent())
                db.execSQL("UPDATE `strokes` SET `layerId` = 'layer_' || pageId")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `note_versions` (
                        `id` TEXT NOT NULL,
                        `pageId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `extractedText` TEXT,
                        `timestampMs` INTEGER NOT NULL,
                        `versionNote` TEXT NOT NULL DEFAULT 'Saved version',
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_versions_pageId` ON `note_versions` (`pageId`)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pages ADD COLUMN paperColor TEXT")
            }
        }


        private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

        private fun isPlaintextSqlite(file: File): Boolean {
            return try {
                val header = ByteArray(16)
                file.inputStream().use { input ->
                    val read = input.read(header)
                    read == 16 && String(header).startsWith("SQLite format 3\u0000")
                }
            } catch (e: Exception) {
                false
            }
        }

        /**
         * One-time in-place migration: opens an existing PLAINTEXT database
         * (created by pre-SQLCipher builds) with an empty key and rekeys it to
         * the current DEK passphrase. No-op when the file is already encrypted.
         */
        private fun migratePlaintextIfNeeded(context: Context, passphrase: String) {
            val dbFile = context.getDatabasePath("noteflow.sqlite")
            if (dbFile.exists() && dbFile.length() == 0L) {
                dbFile.delete()
                return
            }
            if (!dbFile.exists() || !isPlaintextSqlite(dbFile)) return

            val tempFile = File(dbFile.parentFile, "noteflow_encrypted.sqlite")
            if (tempFile.exists()) tempFile.delete()

            try {
                System.loadLibrary("sqlcipher")
                val encryptedDb = net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(
                    tempFile, passphrase, null, null, null
                )
                try {
                    encryptedDb.rawExecSQL("ATTACH DATABASE '${dbFile.absolutePath}' AS plaintext KEY ''")
                    encryptedDb.rawExecSQL("SELECT sqlcipher_export('main', 'plaintext')")
                    encryptedDb.rawExecSQL("DETACH DATABASE plaintext")
                } finally {
                    encryptedDb.close()
                }

                val walFile = File(dbFile.path + "-wal")
                if (walFile.exists()) walFile.delete()
                val shmFile = File(dbFile.path + "-shm")
                if (shmFile.exists()) shmFile.delete()

                dbFile.delete()
                tempFile.renameTo(dbFile)

                com.authorss81.noteflow.services.DatabaseSecurityHelper.updateStoredChecksum(context)
            } catch (e: Exception) {
                if (tempFile.exists()) tempFile.delete()
                dbFile.delete()
                val walFile = File(dbFile.path + "-wal")
                if (walFile.exists()) walFile.delete()
                val shmFile = File(dbFile.path + "-shm")
                if (shmFile.exists()) shmFile.delete()
            }
        }

        private class SafeSupportSQLiteOpenHelper(
            private val context: Context,
            private var delegate: SupportSQLiteOpenHelper,
            private val configuration: SupportSQLiteOpenHelper.Configuration,
            private val passphrase: String
        ) : SupportSQLiteOpenHelper {

            override val databaseName: String? get() = delegate.databaseName

            override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
                delegate.setWriteAheadLoggingEnabled(enabled)
            }

            override val writableDatabase: SupportSQLiteDatabase
                get() {
                    return try {
                        delegate.writableDatabase
                    } catch (e: Exception) {
                        if (isDatabaseCorruptException(e)) {
                            cleanDatabaseFiles(context, configuration.name ?: "noteflow.sqlite")
                            delegate = net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase.toByteArray(Charsets.UTF_8)).create(configuration)
                            delegate.writableDatabase
                        } else {
                            throw e
                        }
                    }
                }

            override val readableDatabase: SupportSQLiteDatabase
                get() {
                    return try {
                        delegate.readableDatabase
                    } catch (e: Exception) {
                        if (isDatabaseCorruptException(e)) {
                            cleanDatabaseFiles(context, configuration.name ?: "noteflow.sqlite")
                            delegate = net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase.toByteArray(Charsets.UTF_8)).create(configuration)
                            delegate.readableDatabase
                        } else {
                            throw e
                        }
                    }
                }

            override fun close() {
                delegate.close()
            }

            private fun isDatabaseCorruptException(e: Exception): Boolean {
                val className = e.javaClass.name
                val msg = e.message ?: ""
                return e is android.database.sqlite.SQLiteException ||
                       e is android.database.SQLException ||
                       className.contains("SQLiteException", ignoreCase = true) ||
                       msg.contains("file is not a database", ignoreCase = true) ||
                       msg.contains("corrupt", ignoreCase = true) ||
                       msg.contains("malformed", ignoreCase = true)
            }

            private fun cleanDatabaseFiles(context: Context, dbName: String) {
                try {
                    val dbFile = context.getDatabasePath(dbName)
                    if (dbFile.exists()) dbFile.delete()
                    val walFile = File(dbFile.path + "-wal")
                    if (walFile.exists()) walFile.delete()
                    val shmFile = File(dbFile.path + "-shm")
                    if (shmFile.exists()) shmFile.delete()
                    val journalFile = File(dbFile.path + "-journal")
                    if (journalFile.exists()) journalFile.delete()
                } catch (_: Exception) {}
            }
        }

        private class NoteflowSqlcipherFactory(private val context: Context) : SupportSQLiteOpenHelper.Factory {
            override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
                System.loadLibrary("sqlcipher")
                var dek = VaultKeyHolder.dek
                if (dek == null) {
                    val security = com.authorss81.noteflow.services.SecurityService(context)
                    dek = security.getOrCreateDek()
                    if (dek != null) {
                        VaultKeyHolder.dek = dek
                    }
                }
                val passphrase = dek?.toHexString() ?: throw IllegalStateException("Vault is locked: database key not available")
                migratePlaintextIfNeeded(context, passphrase)
                val factory = net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase.toByteArray(Charsets.UTF_8))
                val delegate = factory.create(configuration)
                return SafeSupportSQLiteOpenHelper(context, delegate, configuration, passphrase)
            }
        }

        fun getDatabase(context: Context): NoteflowDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NoteflowDatabase::class.java,
                    "noteflow.sqlite"
                )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .fallbackToDestructiveMigration()
                .openHelperFactory(NoteflowSqlcipherFactory(context))
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
