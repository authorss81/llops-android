package com.authorss81.noteflow

import com.authorss81.noteflow.services.DecryptFailurePolicy
import com.authorss81.noteflow.services.EncryptionService
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * B1-DB-8 (phase-88) behavioral + wiring tests for the decrypt-failure fallback
 * fix.
 *
 * Finding: every decrypt-failure fallback in [com.authorss81.noteflow.data.repository.NoteRepository]
 * returned the RAW base64 AES-GCM blob as if it were genuine note content — the
 * stroke text fallback (`catch { rawText }`), the page title/body fallback
 * (`decryptPageIfNeeded` returning the page unchanged), the embed text fallback
 * (`catch { text }`) and the version title/body fallback (`?: v.title`). After a
 * re-key, a mismatched-DEK cross-device restore or partial DB manipulation the
 * user was silently shown ciphertext garbage as note titles/text — a decrypt
 * failure that looked exactly like legitimate content, so it was never surfaced
 * as the re-key/tamper problem it is.
 *
 * What is provable on the pure JVM (real AES-GCM, no Room/SQLCipher/Context):
 * (a) a genuine payload that fails authentication renders
 * [DecryptFailurePolicy.UNREADABLE_MARKER] — NEVER the raw blob (this is the
 * exact decision table the repository now routes every display field through);
 * (b) legacy plaintext still renders verbatim (never "fixed" into an
 * unreadable marker — the regression the structural classifier exists to avoid);
 * (c) an authenticated payload still renders its plaintext; (d) the persistent
 * failure threshold decision. The Android-bound wiring — that every repository
 * decrypt site goes through the policy and that the ViewModel escalates a
 * persistent failure to the corruption/restore event — is pinned at source
 * level below.
 */
class B1Db08DecryptFailureTest {

    private val key = "0123456789abcdef0123456789abcdef".toByteArray(Charsets.UTF_8)
    private val rekeyedKey = "fedcba9876543210fedcba9876543210".toByteArray(Charsets.UTF_8)

    // ---- pure policy: render decision table (real AES-GCM) -----------------

    @Test
    fun `a genuine ciphertext that fails authentication renders the marker never the raw blob`() {
        // The exact exploit: a row encrypted under the OLD DEK, read after a
        // re-key (or mismatched-DEK restore) authenticates under the NEW key.
        val payload = EncryptionService.encryptField("Secret note title".toByteArray(), key, "pages", "page-1", "title")

        // Re-keyed reader: encryptField succeeded, decryptField must fail auth.
        val reKeyedDecrypt = runCatching {
            String(EncryptionService.decryptField(payload, rekeyedKey, "pages", "page-1", "title"))
        }.exceptionOrNull()
        assertTrue("re-keyed decrypt must fail authentication", reKeyedDecrypt != null)

        // WRONG outcome (the pre-fix bug): the raw base64 blob rendered as the title.
        // The repository previously returned `payload` here in THREE separate sinks.
        assertFalse(
            "the raw ciphertext blob must NEVER be rendered as note content",
            payload == DecryptFailurePolicy.render(payload, null, true)
        )

        // RIGHT outcome via the single policy decision.
        assertEquals(
            DecryptFailurePolicy.UNREADABLE_MARKER,
            DecryptFailurePolicy.render(payload, null, DecryptFailurePolicy.isStructuralCiphertext(payload))
        )
        assertEquals(
            "Unreadable (decryption failed)",
            DecryptFailurePolicy.UNREADABLE_MARKER
        )
    }

    @Test
    fun `a locked vault read of a genuine payload renders the marker not the blob`() {
        // A locked vault has no DEK: every genuine payload renders the marker —
        // the user sees "Unreadable", NOT base64 garbage, before any unlock.
        val payload = EncryptionService.encryptField("Private body".toByteArray(), key, "pages", "page-2", "body")

        assertEquals(
            DecryptFailurePolicy.UNREADABLE_MARKER,
            DecryptFailurePolicy.render(payload, null, DecryptFailurePolicy.isStructuralCiphertext(payload))
        )
    }

    @Test
    fun `an authenticated ciphertext still renders its plaintext`() {
        val payload = EncryptionService.encryptField("Tuesday standup".toByteArray(), key, "pages", "page-3", "title")
        val decrypted: String? = EncryptionService.decryptFieldOrNull(payload, key, "pages", "page-3", "title")

        assertEquals(
            "Tuesday standup",
            DecryptFailurePolicy.render(payload, decrypted, DecryptFailurePolicy.isStructuralCiphertext(payload))
        )
    }

    @Test
    fun `legacy plaintext renders verbatim and is never misclassified as ciphertext`() {
        // A pre-field-encryption row carries real plaintext in the column. The
        // structural classifier must keep it out of the "decrypt" branch so it
        // renders as-is — replacing valid old notes with the marker would be a
        // data-display regression worse than the bug being fixed.
        assertFalse(DecryptFailurePolicy.isStructuralCiphertext("My perfect title"))
        assertFalse(DecryptFailurePolicy.isStructuralCiphertext(""))
        assertFalse(DecryptFailurePolicy.isStructuralCiphertext("NotARealBase64Payload!!!"))

        assertEquals("My perfect title", DecryptFailurePolicy.render("My perfect title", null, false))

        // A blank column with no stored bytes: render returns the blank, never garbage.
        assertEquals("", DecryptFailurePolicy.render("", null, false))
    }

    @Test
    fun `a tampered payload still classifies structurally as ciphertext so the marker applies`() {
        val payload = EncryptionService.encryptField("untouched".toByteArray(), key, "pages", "page-4", "title")
        // Flip the final base64 char deterministically — any mutation breaks the tag.
        val tampered = payload.substring(0, payload.length - 1) +
            (if (payload.last() == 'A') 'B' else 'A')
        assertTrue("a tampered payload is still structurally ciphertext (tag flipped)",
            DecryptFailurePolicy.isStructuralCiphertext(tampered))
        // Its re-keyed/tamper read fails auth -> null -> marker, never the blob.
        assertNull(EncryptionService.decryptFieldOrNull(tampered, key, "pages", "page-4", "title"))
        assertEquals(DecryptFailurePolicy.UNREADABLE_MARKER, DecryptFailurePolicy.render(tampered, null, true))
    }

    // ---- pure policy: persistent-failure classification ---------------------

    @Test
    fun `persistent classification fires only at the distinct-record threshold`() {
        assertFalse(DecryptFailurePolicy.isPersistent(0))
        assertFalse(DecryptFailurePolicy.isPersistent(9))
        assertTrue("a session with 10 distinct failed records is persistent",
            DecryptFailurePolicy.isPersistent(10))
        assertTrue(DecryptFailurePolicy.isPersistent(25))
        assertEquals(10, DecryptFailurePolicy.PERSISTENT_FAILURE_THRESHOLD)
    }

    // ---- source pins: repository decrypt sinks route through the policy -----

    private val repoSource by lazy {
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/data/repository/NoteRepository.kt").readText()
    }
    private val viewModelSource by lazy {
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt").readText()
    }

    @Test
    fun `the strokes decrypt path routes both fields through the policy`() {
        val region = repoSource
            .substringAfter("fun getStrokesForPage")
            .substringBefore("fun saveStrokesForPage")

        assertTrue("stroke text must go through the single render decision, keyed on the note",
            region.contains("decryptFieldForDisplay(rawText, \"strokes\", entity.id, \"textContent\", pageId)"))
        assertTrue("stroke geometry must go through the geometry path (never raw ciphertext into a parser)",
            region.contains("decryptStoredGeometryOrBlank(rawPointsJson, entity.id, pageId)"))
        assertFalse("the raw-ciphertext text fallback must be gone",
            region.contains("catch (e: Exception) {\n                        rawText\n                    }"))
        assertFalse("the raw-ciphertext points fallback must be gone",
            region.contains("catch (e: Exception) {\n                        rawPointsJson\n                    }"))
    }

    @Test
    fun `the media embed text path renders the marker instead of the raw blob`() {
        val region = repoSource
            .substringAfter("fun getMediaEmbedsForPage")
            .substringBefore("fun getNotebookCounts")
        assertTrue("embed text must go through the single render decision, keyed on the note",
            region.contains("decryptFieldForDisplay(text, \"media_embeds\", entity.id, \"textContent\", pageId)"))
        assertFalse("the old embed text `catch { text }` raw fallback must be gone",
            region.contains("catch (e: Exception) { text }"))
    }

    @Test
    fun `the version title and body paths render the marker instead of the raw blob`() {
        val region = repoSource
            .substringAfter("fun getNoteVersions")
            .substringBefore("private fun decryptPageIfNeeded")
        assertTrue("version title must go through the single render decision, keyed on the note",
            region.contains("decryptFieldForDisplay(v.title, \"note_versions\", v.id, \"title\", v.pageId)"))
        assertTrue("version body must go through the single render decision, keyed on the note",
            region.contains("decryptFieldForDisplay(v.extractedText, \"note_versions\", v.id, \"extractedText\", v.pageId)"))
        assertFalse("the `?: v.title` raw fallback (code usage) must be gone",
            region.contains("EncryptionService.decryptFieldOrNull(v.title"))
        assertFalse("the `?: v.extractedText` raw fallback (code usage) must be gone",
            region.contains("EncryptionService.decryptFieldOrNull(v.extractedText"))
    }

    @Test
    fun `the page decrypt no longer returns the ciphertext page on failure`() {
        val region = repoSource
            .substringAfter("private fun decryptPageIfNeeded")
            .substringBefore("}")
        assertTrue("page title must go through the single render decision, keyed on the note id",
            region.contains("decryptFieldForDisplay(page.title, \"pages\", page.id, \"title\", page.id)"))
        assertTrue("page body must go through the single render decision, keyed on the note id",
            region.contains("decryptFieldForDisplay(page.extractedText, \"pages\", page.id, \"extractedText\", page.id)"))
        assertTrue("the locked-vault early-return that passed raw ciphertext through must be gone",
            !region.contains("if (key == null) return page"))
        assertFalse("the pre-fix catch-all that returned the page (ciphertext) unchanged must be gone",
            region.contains("catch (e: Exception) {\n            page\n        }"))
    }

    @Test
    fun `the policy is the single source of truth declared in services`() {
        assertTrue(
            "repository docs must reference the phase-88 policy",
            repoSource.contains("import com.authorss81.noteflow.services.DecryptFailurePolicy")
        )
    }

    // ---- source pins: viewmodel escalation to the corruption/restore event ---

    @Test
    fun `the viewmodel wires the persistent-failure listener on every initialize`() {
        // Scoped to the B1-DB-8 block at the top of initializeDataCore — bounded
        // on the NEXT statement's boundary (below the comment is the migration
        // block), not on a function name that also appears earlier in the file.
        val region = viewModelSource
            .substringAfter("private suspend fun initializeDataCore()")
            .substringBefore("\n            if (!settings.fieldAadMigrated)")

        assertTrue("a fresh per-session ledger must be reset at initialize",
            region.contains("repository.resetDecryptFailures()"))
        assertTrue("the repository listener must be wired",
            region.contains("repository.decryptFailureListener = {"))
        assertTrue("persistence is checked before escalating",
            region.contains("repository.decryptFailuresPersistent"))
        assertTrue("a persistent failure must raise the corruption flag (recovery screen)",
            region.contains("DatabaseSecurityHelper.setCorruptionDetected(appContext)"))
        assertTrue("escalation surfaces the non-alarming restoration promotion",
            region.contains("DecryptFailurePolicy.PERSISTENT_DECRYPT_FAILURE_NOTICE"))
    }

    @Test
    fun `the viewmodel resets the ledger at every legitimate session boundary`() {
        // lock(): the reset sits at the top of the has-master-password teardown,
        // immediately after the DEK zeroization and before the data-layer
        // connection drop (B1-AUTH-02 / phase-47). Phase 181: the ENTIRE lock()
        // session teardown — DEK zeroization, ledger reset, selection clears —
        // now lives inside `if (settings.hasMasterPassword)` so a passwordless
        // lock() is a session-preserving no-op (no lock boundary by design).
        // Bound on stable code tokens, never on comment text.
        val lockGate = viewModelSource
            .substringAfter("\n        if (settings.hasMasterPassword) {")
            .substringBefore("NoteflowDatabase.dispose()")
        assertTrue("lock() zeroizes the DEK before resetting the ledger",
            lockGate.contains("repository.zeroizeKey()"))
        assertTrue("lock()'s reset sits at the top of the teardown, before the connection drop",
            lockGate.contains("repository.resetDecryptFailures()"))
        assertFalse("the lock-region reset must precede the connection drop, not follow it",
            lockGate.contains("NoteflowDatabase.dispose()"))
        // Phase 181: the selection/content StateFlow clears must be gated on the
        // same has-master-password boundary (passwordless ON_STOP keeps its
        // last-used notebook open across the SAF export picker).
        assertTrue("the selection clears live inside the same gate",
            lockGate.contains("_selectedNotebook.value = null"))

        val rekeyRegion = viewModelSource
            .substringAfter("fun changeMasterPassword")
            .substringBefore("suspend fun verifyMasterPassword")
        assertTrue("re-key resets the session ledger",
            rekeyRegion.contains("repository.resetDecryptFailures()"))

        val restoreRegion = viewModelSource
            .substringAfter("fun restoreEncryptedBackupFromZip")
            .substringBefore("// Phase 38")
        assertTrue("a successful WebDAV restore resets the session ledger",
            restoreRegion.contains("repository.resetDecryptFailures()"))
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