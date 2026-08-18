package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2-B1C-03 (INFO, phase-145) — DEK hex hygiene:
 *
 * "DEK(hex) is materialized as an immutable String, survives the exec context /
 * rename_table / voice re-key, and is never zeroized" (the DEK's SQLCipher
 * passphrase is stored as ASCII-hex `String`s on every vault open and through
 * the restore pipeline; Java Strings are immutable and can only be freed by
 * GC, never zeroized).
 *
 * The fix:
 *  - [toSqlcipherPassphraseBytes] builds the SQLCipher passphrase directly as
 *    ASCII-hex BYTES (byte-identical to the old lower-case hex String) and the
 *    factory/zeroize both live in the same try/finally;
 *  - the restore pipeline carries the backup + current DEKs as zeroizable
 *    ByteArrays end-to-end; hex Strings exist ONLY inside
 *    validateAndPrepareRestoredDb (the smallest function still touching the
 *    SQLCipher String API) and every byte copy is zeroized before it returns.
 *
 * All assertions are source-level (the pure JVM cannot exercise Room/SQLCipher).
 */
class R2B1C03DekHexScopingTest {

    private fun source(rel: String): String = File(repoRoot(), rel).readText()

    private val dbSource by lazy {
        source("app/src/main/kotlin/com/authorss81/noteflow/data/db/NoteflowDatabase.kt")
    }
    private val ieSource by lazy {
        source("app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt")
    }

    // ---- NoteflowDatabase: open-time passphrase is bytes, zeroized ----------

    @Test
    fun `vault open builds the passphrase as zeroizable bytes with no hex String`() {
        assertTrue(
            "the byte-builder must exist",
            dbSource.contains("private fun ByteArray.toSqlcipherPassphraseBytes(): ByteArray")
        )
        assertTrue(
            "the open must hand the byte[] to the factory (never a String clone)",
            dbSource.contains("SupportOpenHelperFactory(passphraseBytes)")
        )
        assertTrue(
            "the passphrase bytes must be zeroized after the factory is built",
            dbSource.contains("passphraseBytes.fill(0.toByte())")
        )
        assertTrue(
            "the plaintext migration open must also take the byte[]",
            dbSource.contains("openOrCreateDatabase(\n                    tempFile, passphrase, null, null, null")
        )
        // The pre-fix immutable-hex call sequence is gone from the production path.
        assertTrue(
            "no DEK may be hex-String-ed for the SQLCipher passphrase",
            !dbSource.contains("dek?.toHexString()")
        )
    }

    // ---- ImportExportService: bytes end-to-end, hex scoped + zeroized --------

    @Test
    fun `the backup DEK is carried as ByteArray and hex only exists in the validator`() {
        val payloadRegion = ieSource.substringAfter("internal data class BackupV2Payload")
            .substringBefore(")")
        assertTrue(
            "the wrapped DEK unwraps to zeroizable BYTES, not an immutable hex String",
            payloadRegion.contains("val dek: ByteArray?")
        )

        val importRegion = ieSource.substringAfter("suspend fun importBackup")
            .substringBefore("private fun restoreFromZip")
        assertTrue(
            "the v2 restore threads the byte[] DEKs through",
            importRegion.contains("restoreFromZip(context, v2.zipFile, v2.offsetBytes, v2.dek, currentDek, allowEmptyVault)")
        )
        assertTrue(
            "the v2 restore zeroizes the backup DEK on every outcome",
            importRegion.contains("v2.dek?.fill(0.toByte())")
        )
        assertTrue(
            "the legacy device-keyed restore passes the key bytes with no hex",
            importRegion.contains("restoreFromZip(context, stagingZip, 0, null, key, allowEmptyVault)")
        )
        assertTrue(
            "the legacy path has no currentDekHex wrapper anymore",
            !importRegion.contains("currentDekHex")
        )

        // The validator is the ONLY place hex Strings may exist.
        assertTrue(
            "the validator signature takes zeroizable bytes",
            ieSource.contains("private fun validateAndPrepareRestoredDb(context: Context, tempDb: File, backupDek: ByteArray?, currentDek: ByteArray?")
        )
        val validator = ieSource.substringAfter("private fun validateAndPrepareRestoredDb")
            .substringBefore("private fun rekeyVoiceNoteBlobs")
        assertTrue(
            "the DEK is COPIED before hex so the repository's live array is never zeroized",
            validator.contains("val backupDekOwned = backupDek?.copyOf()") &&
                validator.contains("val currentDekOwned = currentDek?.copyOf()")
        )
        assertTrue(
            "hex is confined to the validator entry",
            validator.contains("val backupDekHex = backupDekOwned?.toHexString()") &&
                validator.contains("val currentDekHex = currentDekOwned?.toHexString()")
        )
        assertTrue(
            "the validator opens the DB with ASCII-hex BYTES (zeroized after the open)",
            validator.contains("tempDb, candidateBytes, null, null, null") &&
                validator.contains("val candidateBytes = candidate.toAsciiBytes()")
        )
        assertTrue(
            "every owned byte copy of a DEK is zeroized before the validator returns",
            validator.contains("backupDekOwned?.fill(0.toByte())") &&
                validator.contains("currentDekOwned?.fill(0.toByte())")
        )

        // No hex String may escape the validator: the ONLY toHexString sites in the
        // file are the helper definition + the two inside-validator calls.
        val hexUses = Regex("\\.toHexString\\(\\)").findAll(ieSource).count()
        assertTrue(
            "toHexString must be defined once and called ONLY inside the validator (found $hexUses)",
            hexUses == 3
        )
    }

    @Test
    fun `the rekey helpers feed SQLCipher bytes and zeroize them`() {
        val rekeySource = ieSource.substringAfter("private fun rekeySqlcipherDb")
            .substringBefore("private fun sanitizeRestoredStrokeGeometry")
        assertTrue(
            "the rekey open uses the old passphrase as ASCII bytes",
            rekeySource.contains("oldDekHex.toAsciiBytes()") &&
                rekeySource.contains("dbFile, oldKeyBytes, null, null, null")
        )
        assertTrue(
            "the rekey passphrase bytes are zeroized",
            rekeySource.contains("oldKeyBytes.fill(0.toByte())")
        )

        val migrate = ieSource.substringAfter("private fun migrateFieldCiphertexts")
            .substringBefore("private fun migrateTable")
        assertTrue(
            "the field-migration open feeds the new DEK as ASCII bytes",
            migrate.contains("newDekHex.toAsciiBytes()") &&
                migrate.contains("tempDb, newDekBytes, null, null, null")
        )
        assertTrue(
            "the field-migration passphrase bytes are zeroized",
            migrate.contains("newDekBytes.fill(0.toByte())")
        )

        // The helper that converts a hex String to zeroizable bytes must exist.
        assertTrue(
            "the explicit hex → zeroizable-bytes helper must be declared",
            ieSource.contains("private fun String.toAsciiBytes(): ByteArray")
        )
    }

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile &&
                File(dir, "app").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}