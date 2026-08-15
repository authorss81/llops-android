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

/**
 * Single source of truth for the Room schema version. Referenced by the
 * [Database] annotation (which needs a compile-time constant) and by
 * [NoteflowDatabase.SCHEMA_VERSION] for the import/restore guard — so the two
 * can never drift apart.
 */
const val NOTEFLOW_DATABASE_SCHEMA_VERSION = 9

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
    version = NOTEFLOW_DATABASE_SCHEMA_VERSION,
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
        const val SCHEMA_VERSION = NOTEFLOW_DATABASE_SCHEMA_VERSION

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

        // Phase 13 (rich canvas content): item rotation. One additive, nullable
        // column with a constant default — ALTER TABLE ... ADD COLUMN with a
        // DEFAULT is a no-copy, backfilling change on SQLite, so this is fully
        // migration-safe; existing rows read back 0 (no rotation).
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_embeds ADD COLUMN rotationDegrees REAL NOT NULL DEFAULT 0")
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
            private val configuration: SupportSQLiteOpenHelper.Configuration
        ) : SupportSQLiteOpenHelper {

            override val databaseName: String? get() = delegate.databaseName

            override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
                delegate.setWriteAheadLoggingEnabled(enabled)
            }

            override val writableDatabase: SupportSQLiteDatabase
                get() {
                    throwIfVaultQuarantined()
                    return try {
                        delegate.writableDatabase
                    } catch (e: Exception) {
                        // B1-DB-1 (phase-43): only GENUINE corruption drives the
                        // quarantine path. Transient open failures ("database is
                        // locked", disk I/O, ENOSPC…) are rethrown untouched — they
                        // must never displace a healthy vault (the old classifier
                        // treated the whole SQLiteException family as corruption and
                        // auto-created an EMPTY replacement DB here).
                        if (isDatabaseCorruptException(e)) {
                            // H2 (phase-09): NEVER delete the user's vault on an open
                            // failure. The corrupt files are quarantined (renamed to
                            // *.corrupt-<timestamp> so bytes survive for recovery) and a
                            // persistent flag routes the user to a recovery screen.
                            // Phase-43: NO replacement DB is created here — the original
                            // exception propagates so the open FAILS. The recovery screen
                            // surfaces (same-session + across restart via the flag) and only
                            // the user's explicit "start fresh" choice creates an empty vault.
                            quarantineCorruptDatabase(context, configuration.name ?: "noteflow.sqlite")
                        }
                        throw e
                    }
                }

            override val readableDatabase: SupportSQLiteDatabase
                get() {
                    throwIfVaultQuarantined()
                    return try {
                        delegate.readableDatabase
                    } catch (e: Exception) {
                        if (isDatabaseCorruptException(e)) {
                            quarantineCorruptDatabase(context, configuration.name ?: "noteflow.sqlite")
                        }
                        throw e
                    }
                }

            override fun close() {
                delegate.close()
            }

            /**
             * B1-DB-1 (phase-43): once the vault has been quarantined (persistent
             * flag), ANY further open must FAIL instead of silently creating a
             * fresh empty database behind the user's back (Room openByName and
             * SQLCipher would happily mmap/create a missing file). The recovery
             * screen is shown while the flag is set; clearing it (restore success
             * or explicit "start fresh") re-arms normal opens.
             */
            private fun throwIfVaultQuarantined() {
                if (com.authorss81.noteflow.services.DatabaseSecurityHelper.hasCorruptionDetected(context)) {
                    throw IllegalStateException(
                        "Vault database is quarantined — restore from a backup or start fresh."
                    )
                }
            }

            /**
             * H2 (phase-09): the old implementation DELETED db + wal + shm +
             * journal and instantly re-created an empty vault — a wrong key,
             * torn write or transient I/O error became irreversible total data
             * loss with no banner and no recovery path. This implementation
             * RENAMES the files to *.corrupt-<timestamp> (bytes preserved for
             * offline recovery) and records the event so the UI can offer a
             * restore-from-backup or an explicit start-fresh decision.
             */
            private fun quarantineCorruptDatabase(context: Context, dbName: String) {
                val timestamp = System.currentTimeMillis()
                val suffix = ".corrupt-$timestamp"
                val baseFile = context.getDatabasePath(dbName)
                val dir = baseFile.parentFile
                if (dir == null) return
                val names = listOf(dbName, "$dbName-wal", "$dbName-shm", "$dbName-journal")
                for (name in names) {
                    val source = File(dir, name)
                    if (source.exists()) {
                        val target = File(dir, name + suffix)
                        try {
                            // Rename preserves the bytes; NEVER delete the source on a
                            // corrupt open — the whole point is that nothing is destroyed.
                            // (Timestamp collisions are impossible in practice; a failed
                            // rename just leaves the file for the next quarantine attempt.)
                            source.renameTo(target)
                        } catch (_: Exception) {
                        }
                    }
                }
                com.authorss81.noteflow.services.DatabaseSecurityHelper.setCorruptionDetected(context, timestamp)
            }
        }

        private class NoteflowSqlcipherFactory(private val context: Context) : SupportSQLiteOpenHelper.Factory {
            override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
                System.loadLibrary("sqlcipher")
                var dek = VaultKeyHolder.dek
                if (dek == null) {
                    val security = com.authorss81.noteflow.services.SecurityService.forDevice(context)
                    dek = security.getOrCreateDek()
                    if (dek != null) {
                        VaultKeyHolder.dek = dek
                    }
                }
                val passphrase = dek?.toHexString() ?: throw IllegalStateException("Vault is locked: database key not available")
                migratePlaintextIfNeeded(context, passphrase)
                val factory = net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase.toByteArray(Charsets.UTF_8))
                val delegate = factory.create(configuration)
                return SafeSupportSQLiteOpenHelper(context, delegate, configuration)
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                .fallbackToDestructiveMigration()
                .openHelperFactory(NoteflowSqlcipherFactory(context))
                .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * H1 (phase-09): closes and forgets the current Room instance so a later
         * [getDatabase] builds a fresh one. Used by the restore paths so a failed
         * (or successful) restore never leaves the app with a closed live DB — the
         * old code closed the database and then bricked every DB call on failure.
         */
        fun dispose() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}

/**
 * B1-DB-1 (phase-43): the single source of truth for "is this an open failure we
 * should quarantine as genuine corruption?"
 *
 * Matches ONLY:
 *  - the platform `SQLiteDatabaseCorruptException` (raised for malformed page /
 *    header states),
 *  - SQLCipher's own `SQLiteNotADatabaseException` (raised when SQLCipher cannot
 *    recognize the file as a database — i.e. a wrong passphrase or a genuinely
 *    corrupt/crypted-over file),
 *  - the specific diagnostic messages "file is not a database", "malformed" and
 *    "database disk image is malformed".
 *
 * NEVER matches the transient, recoverable open failures that are ALSO
 * `SQLiteException` subclasses: "database is locked" (SQLiteDatabaseLockedException),
 * "disk I/O error" (SQLiteDiskIOException), "database or disk is full" (ENOSPC,
 * SQLiteFullException), "unable to open database file" (SQLiteCantOpenDatabaseException).
 * Under the old classifier those healthy-vault failures were quarantined and silently
 * replaced with an empty database — permanent data loss on a routine hiccup.
 */
internal fun isDatabaseCorruptException(e: Throwable?): Boolean {
    if (e == null) return false
    val msg = e.message ?: ""
    return e is android.database.sqlite.SQLiteDatabaseCorruptException ||
        e is net.zetetic.database.sqlcipher.SQLiteNotADatabaseException ||
        msg.contains("file is not a database", ignoreCase = true) ||
        msg.contains("database disk image is malformed", ignoreCase = true) ||
        msg.contains("malformed", ignoreCase = true)
}
