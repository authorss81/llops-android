package com.authorss81.noteflow

import com.authorss81.noteflow.data.db.isDatabaseCorruptException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-DB-1 (phase-43): the database-open "corruption" classifier must match ONLY
 * genuine corruption and must NEVER classify the transient, fully recoverable
 * open failures (database locked, disk I/O, disk full, can't open) as corruption.
 *
 * The old implementation returned true for the ENTIRE SQLiteException family
 * (including SQLiteDatabaseLockedException, SQLiteCantOpenDatabaseException,
 * SQLiteFullException, SQLiteDiskIOException) and for android.database.SQLException,
 * which made a routine hiccup quarantine a HEALTHY vault and silently replace it
 * with a brand-new empty database.
 */
class CorruptionClassifierTest {

    // ---- GENUINE CORRUPTION must be detected -------------------------------

    @Test
    fun platformCorruptException_isCorruption() {
        assertTrue(isDatabaseCorruptException(android.database.sqlite.SQLiteDatabaseCorruptException("database disk image is malformed")))
    }

    @Test
    fun sqlcipherNotADatabase_isCorruption() {
        assertTrue(isDatabaseCorruptException(net.zetetic.database.sqlcipher.SQLiteNotADatabaseException("file is not a database")))
    }

    @Test
    fun messageFileIsNotADatabase_isCorruption() {
        assertTrue(isDatabaseCorruptException(RuntimeException("file is not a database")))
        assertTrue(isDatabaseCorruptException(RuntimeException("noteflow.sqlite: file is not a database")))
    }

    @Test
    fun messageMalformed_isCorruption() {
        assertTrue(isDatabaseCorruptException(RuntimeException("database disk image is malformed")))
        assertTrue(isDatabaseCorruptException(RuntimeException("malformed database schema")))
        assertTrue(isDatabaseCorruptException(android.database.sqlite.SQLiteDatabaseCorruptException("malformed")))
    }

    // ---- TRANSIENT / RECOVERABLE failures must NEVER be corruption ---------

    @Test
    fun databaseLocked_notCorruption() {
        assertFalse(isDatabaseCorruptException(android.database.sqlite.SQLiteDatabaseLockedException("database is locked")))
        // Even message-only: "database is locked" must never trip the guard.
        assertFalse(isDatabaseCorruptException(RuntimeException("database is locked")))
        assertFalse(isDatabaseCorruptException(RuntimeException("database table is locked: main")))
    }

    @Test
    fun diskIoError_notCorruption() {
        assertFalse(isDatabaseCorruptException(android.database.sqlite.SQLiteDiskIOException("disk I/O error")))
        assertFalse(isDatabaseCorruptException(RuntimeException("disk I/O error")))
    }

    @Test
    fun diskFull_notCorruption() {
        assertFalse(isDatabaseCorruptException(android.database.sqlite.SQLiteFullException("database or disk is full")))
        assertFalse(isDatabaseCorruptException(RuntimeException("database or disk is full")))
    }

    @Test
    fun cannotOpen_notCorruption() {
        assertFalse(isDatabaseCorruptException(android.database.sqlite.SQLiteCantOpenDatabaseException("unable to open database file")))
        assertFalse(isDatabaseCorruptException(RuntimeException("unable to open database file")))
    }

    @Test
    fun genericSqliteException_notCorruption() {
        // THE core B1-DB-1 regression: a plain SQLiteException (supertype of the
        // locked/I-O/ENOSPC set) used to trigger the quarantine path.
        assertFalse(isDatabaseCorruptException(android.database.sqlite.SQLiteException("SQL logic error")))
        assertFalse(isDatabaseCorruptException(android.database.SQLException("SQL error")))
    }

    @Test
    fun unrelatedAndBlank_notCorruption() {
        assertFalse(isDatabaseCorruptException(IllegalStateException("Vault is locked: database key not available")))
        assertFalse(isDatabaseCorruptException(RuntimeException("")))
        assertFalse(isDatabaseCorruptException(RuntimeException("not a database text file")))
        assertFalse(isDatabaseCorruptException(null))
    }
}