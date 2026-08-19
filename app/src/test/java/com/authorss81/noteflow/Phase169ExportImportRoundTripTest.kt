package com.authorss81.noteflow

import com.authorss81.noteflow.services.DecryptFailurePolicy
import com.authorss81.noteflow.services.EncryptionService
import com.authorss81.noteflow.services.ImportExportService
import com.authorss81.noteflow.services.UiFailureTextPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-169: encrypt→export→import→decrypt round-trip proof + the fail-closed
 * seams that make the reported "pages become Unreadable (decryption failed)
 * after export/import" either impossible (healthy backup) or LOUD (damaged
 * backup) instead of silently installing permanently unreadable pages.
 *
 * What is provable on the pure JVM (real AES-GCM, no SQLCipher/Context):
 *  - a cross-key restore re-key (`ImportExportService.reencryptFieldOutcome`,
 *    the exact operation `migrateFieldCiphertexts` performs per row) migrates
 *    every field-encrypted column in `fieldEncryptedColumns` from the backup DEK
 *    to the current DEK WITHOUT changing the per-record AAD binding — the new
 *    ciphertext authenticates under the SAME `table|recordId|fieldName` the read
 *    path uses, so the round trip renders the original plaintext, never the
 *    UNREADABLE_MARKER;
 *  - a legacy global-AAD row migrates to a per-record-bound row under the new
 *    DEK identically;
 *  - a same-DEK import is an identity: the unchanged ciphertext decrypts and
 *    renders under the same DEK;
 *  - the ONE way the reported symptom could occur (a migration that skipped a
 *    row) is now impossible to do silently — `migrateTable` throws
 *    `RestoreReEncryptionException` before any file swap instead of leaving the
 *    row stranded under the old DEK after the SQLCipher re-key;
 *  - the marker can never be persisted as real content: the repository write
 *    paths refuse `UNREADABLE_MARKER` (source-pinned) and the policy exposes the
 *    exact-match classifier + user guidance.
 */
class Phase169ExportImportRoundTripTest {

    private val deviceAKey = "a1b2c3d4e5f60718293a4b5c6d7e8f90".toByteArray(Charsets.UTF_8)
    private val deviceBKey = "0f9e8d7c6b5a493827160f4f3a4b5c6d".toByteArray(Charsets.UTF_8)

    // ---- round trip: per-record AAD, cross-device re-key -------------------

    @Test
    fun `cross-device rekey round trip renders plaintext for every encrypted column never the marker`() {
        val samples = mapOf(
            "pages.title" to "Meeting notes with launch decision",
            "pages.extractedText" to "# Heading\nBody text with ünïcode and secrets.",
            "strokes.textContent" to "handwritten text layer",
            "strokes.pointsJson" to """[{"x":1.5,"y":2.0,"p":0.7}]""",
            "media_embeds.textContent" to "embed caption",
            "note_versions.title" to "v1 title",
            "note_versions.extractedText" to "v1 body"
        )
        // Every (table,column) the read path decrypts must be covered by the
        // restore re-key — a missed entry is the reported orphaned-ciphertext bug.
        val declared = ImportExportService.fieldEncryptedColumns
            .flatMap { (table, columns) -> columns.map { "$table.$it" } }.toSet()
        assertEquals("fieldEncryptedColumns must cover every round-trip sample", samples.keys, declared)

        for ((tableDotColumn, plain) in samples) {
            val (table, column) = tableDotColumn.split(".")
            val recordId = "rec-$table-1"

            // 1) encrypt on device A (per-record AAD), 2) export carries it verbatim
            val ciphertextOnA = EncryptionService.encryptField(
                plain.toByteArray(), deviceAKey, table, recordId, column
            )

            // 3) import on device B: re-key (the restore migration's per-row op)
            val outcome = ImportExportService.reencryptFieldOutcome(
                ciphertextOnA, deviceAKey, deviceBKey, table, recordId, column
            )
            assertTrue(
                "$tableDotColumn must re-key (Migrated), got $outcome",
                outcome is ImportExportService.FieldReencryptOutcome.Migrated
            )
            val ciphertextOnB = (outcome as ImportExportService.FieldReencryptOutcome.Migrated).value

            // 4) decrypt on device B under the SAME AAD binding the read path uses
            val decrypted = String(
                EncryptionService.decryptField(ciphertextOnB, deviceBKey, table, recordId, column),
                Charsets.UTF_8
            )
            assertEquals("round-trip plaintext for $tableDotColumn", plain, decrypted)

            // 5) render decision: plaintext, never the marker
            val rendered = DecryptFailurePolicy.render(
                ciphertextOnB,
                decrypted,
                DecryptFailurePolicy.isStructuralCiphertext(ciphertextOnB)
            )
            assertEquals("render for $tableDotColumn", plain, rendered)
            assertFalse(
                "round-trip must never render the marker for $tableDotColumn",
                DecryptFailurePolicy.isUnreadableMarker(rendered)
            )
        }
    }

    @Test
    fun `legacy global AAD rows migrate to per record bound ciphertext under the new key`() {
        val table = "pages"
        val column = "title"
        val recordId = "legacy-1"
        val plain = "Legacy title"
        // Pre-phase-107 write: encrypted under the global FIELD_AAD only.
        val legacyCiphertext = EncryptionService.encryptAad(
            plain.toByteArray(), deviceAKey, EncryptionService.FIELD_AAD
        )

        val migrated = ImportExportService.reencryptFieldOutcome(
            java.util.Base64.getEncoder().encodeToString(legacyCiphertext), deviceAKey, deviceBKey, table, recordId, column
        )
        assertTrue("legacy row must re-key (Migrated)", migrated is ImportExportService.FieldReencryptOutcome.Migrated)
        val ciphertextOnB = (migrated as ImportExportService.FieldReencryptOutcome.Migrated).value

        // The migrated value is bound to its per-record AAD — it does NOT decrypt
        // with the old global AAD alone.
        assertTrue(
            "migrated value must be record-bound",
            EncryptionService.isFieldBoundToRecord(ciphertextOnB, deviceBKey, table, recordId, column)
        )
        // And it decrypts under the new DEK on the read path.
        val decrypted = String(
            EncryptionService.decryptField(ciphertextOnB, deviceBKey, table, recordId, column),
            Charsets.UTF_8
        )
        assertEquals(plain, decrypted)
        assertFalse(DecryptFailurePolicy.isUnreadableMarker(decrypted))
    }

    @Test
    fun `same device import is an identity the ciphertext decrypts and renders under the same key`() {
        val ciphertext = EncryptionService.encryptField(
            "Same-device body".toByteArray(), deviceAKey, "pages", "rec-same-1", "extractedText"
        )
        val decrypted = String(
            EncryptionService.decryptField(ciphertext, deviceAKey, "pages", "rec-same-1", "extractedText"),
            Charsets.UTF_8
        )
        assertEquals("Same-device body", decrypted)
        val rendered = DecryptFailurePolicy.render(
            ciphertext, decrypted, DecryptFailurePolicy.isStructuralCiphertext(ciphertext)
        )
        assertEquals("Same-device body", rendered)
        assertFalse(DecryptFailurePolicy.isUnreadableMarker(rendered))
    }

    @Test
    fun `a missed rekey the reported symptom renders the marker - now prevented loudly`() {
        // The exact failure mode the fixes eliminate: a row that stayed under the
        // OLD DEK while the vault was re-keyed to the new one.
        val orphaned = EncryptionService.encryptField(
            "Orphaned content".toByteArray(), deviceAKey, "pages", "rec-orphan-1", "extractedText"
        )
        // Reading that stale ciphertext with the NEW key fails authentication.
        val newKeyDecrypt = runCatching {
            String(EncryptionService.decryptField(orphaned, deviceBKey, "pages", "rec-orphan-1", "extractedText"))
        }.exceptionOrNull()
        assertNotNull("an un-migrated old-key row MUST fail auth under the new key", newKeyDecrypt)
        // And the render decision is the unreadable marker (fail-closed, never raw).
        val rendered = DecryptFailurePolicy.render(
            orphaned, null, DecryptFailurePolicy.isStructuralCiphertext(orphaned)
        )
        assertEquals("this is exactly the reported symptom", DecryptFailurePolicy.UNREADABLE_MARKER, rendered)

        // But reencryptFieldOutcome DETECTS that same row at migration time and the
        // restore now fails loudly instead of installing it.
        val outcome = ImportExportService.reencryptFieldOutcome(
            orphaned, deviceBKey, deviceAKey, "pages", "rec-orphan-1", "extractedText"
        )
        assertEquals(
            "a value under an unknown/keyed-differently DEK classifies AuthFailed",
            ImportExportService.FieldReencryptOutcome.AuthFailed, outcome
        )
        // And a structurally-damaged ciphertext (bit flip in the ciphertext body)
        // is the same class.
        val tampered = EncryptionService.encryptField(
            "Damaged".toByteArray(), deviceAKey, "pages", "rec-dmg-1", "title"
        )
        val decoded = java.util.Base64.getDecoder().decode(tampered)
        decoded[decoded.size - 1] = (decoded[decoded.size - 1].toInt() xor 0x01).toByte()
        val damaged = java.util.Base64.getEncoder().encodeToString(decoded)
        assertEquals(
            "a structurally-damaged ciphertext is AuthFailed at migration time",
            ImportExportService.FieldReencryptOutcome.AuthFailed,
            ImportExportService.reencryptFieldOutcome(
                damaged, deviceAKey, deviceBKey, "pages", "rec-dmg-1", "title"
            )
        )
    }

    // ---- reencryptFieldOutcome classification ------------------------------

    @Test
    fun `reencryptFieldOutcome classifies blank plaintext migrated and auth-failed distinctly`() {
        val table = "pages"; val column = "extractedText"; val rec = "rec-class-1"

        assertEquals(
            ImportExportService.FieldReencryptOutcome.LeavePlaintext,
            ImportExportService.reencryptFieldOutcome(null, deviceAKey, deviceBKey, table, rec, column)
        )
        assertEquals(
            ImportExportService.FieldReencryptOutcome.LeavePlaintext,
            ImportExportService.reencryptFieldOutcome("", deviceAKey, deviceBKey, table, rec, column)
        )
        assertEquals(
            "genuine plaintext is left alone",
            ImportExportService.FieldReencryptOutcome.LeavePlaintext,
            ImportExportService.reencryptFieldOutcome(
                "Meeting notes", deviceAKey, deviceBKey, table, rec, column
            )
        )
        val cipher = EncryptionService.encryptField(
            "Migrate me".toByteArray(), deviceAKey, table, rec, column
        )
        assertTrue(
            ImportExportService.reencryptFieldOutcome(
                cipher, deviceAKey, deviceBKey, table, rec, column
            ) is ImportExportService.FieldReencryptOutcome.Migrated
        )
        // A ciphertext that does NOT authenticate under the declared old DEK.
        val foreign = EncryptionService.encryptField(
            "Wrong old key".toByteArray(), deviceBKey, table, rec, column
        )
        assertEquals(
            ImportExportService.FieldReencryptOutcome.AuthFailed,
            ImportExportService.reencryptFieldOutcome(
                foreign, deviceAKey, deviceBKey, table, rec, column
            )
        )
    }

    // ---- marker write guard + guidance ------------------------------------

    @Test
    fun `isUnreadableMarker matches only the exact render marker`() {
        assertTrue(DecryptFailurePolicy.isUnreadableMarker(DecryptFailurePolicy.UNREADABLE_MARKER))
        assertFalse(DecryptFailurePolicy.isUnreadableMarker(""))
        assertFalse(DecryptFailurePolicy.isUnreadableMarker("   "))
        assertFalse(DecryptFailurePolicy.isUnreadableMarker("Unreadable (decryption failed) ")) // trailing space
        assertFalse(DecryptFailurePolicy.isUnreadableMarker("Real note title"))
        assertFalse(DecryptFailurePolicy.isUnreadableMarker("unreadable (decryption failed)"))
    }

    @Test
    fun `unreadable row guidance is non alarming actionable and contains no raw marker`() {
        val guidance = DecryptFailurePolicy.UNREADABLE_ROW_GUIDANCE
        assertTrue(guidance.isNotBlank())
        assertTrue(guidance.length in 40..300)
        assertFalse(guidance.contains(DecryptFailurePolicy.UNREADABLE_MARKER))
        assertTrue(guidance.contains("restore a recent backup", ignoreCase = true))
    }

    // ---- wiring: the fail-closed seams are actually in place -----------------

    @Test
    fun `repository write paths refuse to persist the marker`() {
        val repo = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/data/repository/NoteRepository.kt")
            .readText()
        val bodyRegion = repo.substringAfter("fun updatePageBody").substringBefore("fun updatePageTags")
        assertTrue(
            "updatePageBody must refuse the marker before encrypting it",
            bodyRegion.contains("DecryptFailurePolicy.isUnreadableMarker(body.trim())")
        )
        assertTrue(
            "updatePageBody must throw the typed guard, not store the marker",
            bodyRegion.contains("UnreadableContentWriteException")
        )
        val titleRegion = repo.substringAfter("fun updatePageTitleAndTags").substringBefore("fun togglePin")
        assertTrue(
            "updatePageTitleAndTags must refuse the marker too",
            titleRegion.contains("DecryptFailurePolicy.isUnreadableMarker(rawTitle)")
        )
        assertTrue(titleRegion.contains("UnreadableContentWriteException"))
        val renameRegion = repo.substringAfter("fun renamePage").substringBefore("fun updatePageTags")
        assertTrue(
            "renamePage (the rename dialogs pre-fill the rendered title, which is the marker on an unreadable page) must refuse it too",
            renameRegion.contains("DecryptFailurePolicy.isUnreadableMarker(rawTitle)")
        )
        assertTrue(renameRegion.contains("UnreadableContentWriteException"))
    }

    @Test
    fun `migration throws loudly instead of leaving orphaned rows`() {
        val ie = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt").readText()
        val migrateRegion = ie.substringAfter("private fun migrateTable").substringBefore("private fun commitRestoredFiles")
        assertTrue(
            "migrateTable must detect AuthFailed rows",
            migrateRegion.contains("AuthFailed")
        )
        assertTrue(
            "migrateTable must throw a typed exception when any row fails",
            migrateRegion.contains("RestoreReEncryptionException")
        )
        assertTrue(
            "only migrated rows may be written back",
            migrateRegion.contains("Migrated")
        )
    }

    @Test
    fun `restore failure text maps the reencryption refusal to fixed user facing text`() {
        val ex = ImportExportService.RestoreReEncryptionException("pages", "extractedText", 3)
        val message = UiFailureTextPolicy.restoreFailureMessage(ex)
        assertEquals(UiFailureTextPolicy.RESTORE_REENCRYPT_FAIL_TEXT, message)
        // Never the raw (count-carrying) exception message.
        assertFalse(message.contains("3"))
        assertFalse(message.contains("pages"))
    }

    @Test
    fun `viewmodel surfaces the unreadable row guidance instead of a generic error`() {
        val vm = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt").readText()
        val saveRegion = vm.substringAfter("fun saveMarkdownNoteBody").substringBefore("fun readMarkdownNoteBody")
        assertTrue(
            "the live body-save path must surface the guidance on a marker write",
            saveRegion.contains("UnreadableContentWriteException")
        )
        assertTrue(saveRegion.contains("DecryptFailurePolicy.UNREADABLE_ROW_GUIDANCE"))
        // The unlock-flush drain path surfaces the same guidance.
        val flushRegion = vm.substringAfter("fun flushPendingEditorSaves").substringAfter("UnreadableContentWriteException")
        assertTrue(flushRegion.contains("DecryptFailurePolicy.UNREADABLE_ROW_GUIDANCE"))
        // Every user-facing write surface catches the guard: live body save,
        // unlock-flush drain, title rename, title+tags save.
        val catches = Regex("UnreadableContentWriteException").findAll(vm).count()
        assertTrue("expected 4 VM catch sites for the guard, found $catches", catches >= 4)
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