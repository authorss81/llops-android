package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.CanvasMediaEmbed
import com.authorss81.noteflow.data.model.CanvasStickyNote
import com.authorss81.noteflow.data.model.LayerEntity
import com.authorss81.noteflow.data.model.MediaEmbedType
import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool
import com.authorss81.noteflow.services.EditorFlushPolicy
import com.authorss81.noteflow.services.EncryptionService
import com.authorss81.noteflow.services.VaultLockedWriteException
import com.authorss81.noteflow.services.VaultWriteGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * B2-UI-1 (phase-49) behavioral + wiring tests for the post-lock plaintext-save
 * hole.
 *
 * Finding: `lock()` zeroized [com.authorss81.noteflow.services.VaultKeyHolder] but
 * left the EditorScreen autosave (`delay(1000)`) and dispose-flush
 * (`NonCancellable + Dispatchers.IO`) coroutines running; those then executed the
 * repository's `encryptionKey?.let { encrypt } else rawText` fallback with a null
 * key and persisted stroke textContent / pointsJson / embed textContent /
 * note_versions bodies as PLAINTEXT rows inside the SQLCipher DB.
 *
 * What is provable on the pure JVM (no Room/SQLCipher): the fail-closed decision
 * ([VaultWriteGate]) — a null DEK throws and no plaintext can ever reach a write —
 * and the defer/re-queue lifecycle ([EditorFlushPolicy]) — a locked flush is
 * stashed, never dropped, and drained only once the vault is unlocked again; the
 * flush re-write then persists AEAD ciphertext that decrypts with the real key.
 * The Android-bound wiring (EditorScreen → ViewModel gate → repository
 * fail-closed) is pinned at source level below.
 */
class B2Ui1LockedFlushTest {

    // ---------- fail-closed gate behavior ----------

    @Test
    fun `a write with a zeroized key throws and can never produce plaintext`() {
        var persistedRows = 0
        val stroke = stroke("locked-stroke")
        try {
            writeStrokeRow(dek = null, stroke = stroke) { persistedRows++ }
            fail("a zeroized DEK must fail closed, never write")
        } catch (e: VaultLockedWriteException) {
            // expected: the vault is locked, the write is refused
        }
        assertEquals("a locked save must reject, not persist", 0, persistedRows)
    }

    @Test
    fun `an unlocked write persists encrypted bytes, not the raw text`() {
        val dek = ByteArray(32) { it.toByte() }
        val stroke = stroke("top secret ink")
        var persistedRows = 0
        val row = writeStrokeRow(dek = dek, stroke = stroke) { persistedRows++ }

        assertEquals("the write must be persisted", 1, persistedRows)
        // The persisted textContent is NOT the raw string — it is AEAD ciphertext.
        assertTrue("plaintext must never be stored at rest", row.textContent != "top secret ink")
        // But it round-trips with the real key exactly.
        val plain = String(
            EncryptionService.decryptField(row.textContent, dek, "strokes", stroke.id, "textContent")
        )
        assertEquals(stroke.text, plain)
    }

    // ---------- defer / re-queue lifecycle behavior ----------

    @Test
    fun `a locked flush is deferred, never dropped and never written`() {
        val policy = EditorFlushPolicy()
        val save = EditorFlushPolicy.DeferredSave(
            pageId = "p1",
            strokes = listOf(stroke("s")),
            stickyNotes = listOf(sticky("🔒")),
            embeds = listOf(embed("note")),
            layers = listOf(layer("Layer 1"))
        )

        assertTrue("an absent DEK must NOT allow a now-persist", !policy.isUnlocked(false))
        policy.defer(save)

        assertEquals("exactly one page snapshot is stashed", 1, policy.deferredCount)
        // A repeat defer for the same page replaces the older snapshot in place
        // (latest-wins) — the stash must never grow unboundedly.
        assertTrue("a repeat defer replaces the existing snapshot", policy.defer(save))
        assertEquals("latest snapshot wins — stash stays bounded", 1, policy.deferredCount)
    }

    @Test
    fun `a deferred flush survives the lock and is drained only after unlock`() {
        val policy = EditorFlushPolicy()
        val save = EditorFlushPolicy.DeferredSave(
            pageId = "p2",
            strokes = listOf(stroke("queued ink")),
            stickyNotes = emptyList(),
            embeds = emptyList(),
            layers = listOf(layer("L2"))
        )

        // Lock fires while the autosave is still queued:
        policy.defer(save)
        assertEquals("queued while locked", 1, policy.deferredCount)

        // Unlock succeeds — now the flush may proceed:
        assertTrue(policy.isUnlocked(true))
        val toFlush = policy.drain()
        assertEquals("the deferred snapshot is handed to the flush", 1, toFlush.size)
        assertEquals("the right page is flushed", "p2", toFlush[0].pageId)
        assertEquals(0, policy.deferredCount)
    }

    @Test
    fun `latest snapshot per page wins in the deferred stash`() {
        val policy = EditorFlushPolicy()
        policy.defer(
            EditorFlushPolicy.DeferredSave("p3", listOf(stroke("old")), emptyList(), emptyList(), emptyList())
        )
        policy.defer(
            EditorFlushPolicy.DeferredSave("p3", listOf(stroke("new")), emptyList(), emptyList(), emptyList())
        )
        val drained = policy.drain()
        assertEquals("only the latest page snapshot survives", 1, drained.size)
        assertEquals("new", drained[0].strokes.single().text)
    }

    @Test
    fun `rows persisted after an unlock cycle are all decryptable with the real key`() {
        val dek = ByteArray(32) { (it * 7).toByte() }
        val policy = EditorFlushPolicy()
        val pageSave = EditorFlushPolicy.DeferredSave(
            pageId = "p4",
            strokes = listOf(stroke("handwritten OCR text"), stroke("more ink")),
            stickyNotes = listOf(sticky("sticky body that must be encrypted")),
            embeds = listOf(embed("audio note body")),
            layers = listOf(layer("L4"))
        )

        // The lock happens after the user drew the strokes: the save is deferred.
        policy.defer(pageSave)

        // After the next unlock the flush runs with the live DEK:
        assertTrue("post-unlock the vault is writable", policy.isUnlocked(dek != null))
        val flushed = policy.drain()
        assertEquals(1, flushed.size)
        val save = flushed[0]

        // Strokes: both encrypted columns round-trip with the real key.
        for (s in save.strokes) {
            val textCipher = EncryptionService.encryptField(
                s.text.toByteArray(), dek, "strokes", s.id, "textContent"
            )
            val decryptedText = String(
                EncryptionService.decryptField(textCipher, dek, "strokes", s.id, "textContent")
            )
            assertEquals("stroke textContent must decrypt to the drawn text", s.text, decryptedText)

            val payload = EncryptionService.serializeStrokes(listOf(s.copy(text = "")))
            val pointsCipher = EncryptionService.encryptField(
                payload.toByteArray(), dek, "strokes", s.id, "pointsJson"
            )
            val decryptedPayload = String(
                EncryptionService.decryptField(pointsCipher, dek, "strokes", s.id, "pointsJson")
            )
            assertEquals("stroke pointsJson must decrypt to the serialized payload", payload, decryptedPayload)
        }

        // Sticky note / embed textContent decrypts to the actual body.
        for (note in save.stickyNotes) {
            val cipher = EncryptionService.encryptField(note.text.toByteArray(), dek, "media_embeds", note.id, "textContent")
            val plain = String(EncryptionService.decryptField(cipher, dek, "media_embeds", note.id, "textContent"))
            assertEquals(note.text, plain)
        }
        for (embed in save.embeds) {
            val cipher = EncryptionService.encryptField((embed.textContent ?: "").toByteArray(), dek, "media_embeds", embed.id, "textContent")
            val plain = String(EncryptionService.decryptField(cipher, dek, "media_embeds", embed.id, "textContent"))
            assertEquals(embed.textContent, plain)
        }
    }

    @Test
    fun `createNoteVersion body is encrypted under the real key after unlock`() {
        val dek = ByteArray(32) { (it + 1).toByte() }
        // A note_versions write that a lock deferred is re-persisted encrypted.
        val versionId = "v1"
        val title = "PageTitle"
        val body = "full version-history snapshot body"
        val titleCipher = EncryptionService.encryptField(title.toByteArray(), dek, "note_versions", versionId, "title")
        val bodyCipher = EncryptionService.encryptField(body.toByteArray(), dek, "note_versions", versionId, "extractedText")

        assertTrue("title must be stored as ciphertext, not the raw string", titleCipher != title)
        assertEquals(
            body,
            String(EncryptionService.decryptField(bodyCipher, dek, "note_versions", versionId, "extractedText"))
        )
        assertEquals(
            title,
            String(EncryptionService.decryptField(titleCipher, dek, "note_versions", versionId, "title"))
        )
    }

    // ---------- wiring pins (the Android-bound classes) ----------

    @Test
    fun `repository encrypted-field writes fail closed - no plaintext fallback remains`() {
        val source = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/data/repository/NoteRepository.kt").readText()

        // The fail-closed helper must exist and be reached by the named writes.
        assertTrue("NoteRepository must fail closed via VaultWriteGate", source.contains("VaultWriteGate.requireKey"))

        val writeMethods = listOf(
            "saveStrokesForPage", "saveMediaEmbedsForPage", "createNoteVersion",
            "updatePageBody", "createPage", "renamePage", "updatePageTitleAndTags"
        )
        for (method in writeMethods) {
            // Capture just the method body — stopping at the next top-level
            // suspend fun so read-side decrypt helpers never bleed in.
            val block = source.substringAfter("suspend fun $method", "END")
                .substringBefore("\n    suspend fun ")
            assertTrue("$method must require the DEK before writing", block.contains("requireEncryptionKey"))
            // The old conditional plaintext fallback cannot survive inside the write.
            assertTrue("$method must never branch on a null-key plaintext fallback", !block.contains("encryptionKey?.let"))
        }

        // The legacy plaintext elvis fallbacks are gone from the whole file
        // (write-side variables only — the decrypt-on-read fallbacks stay legal).
        assertTrue(
            "the encrypt-or-plaintext fallback assignments must be gone",
            !source.contains("?: rawTitle") &&
                !source.contains("?: rawExtracted") &&
                !source.contains("else { rawText }") &&
                !source.contains("else { pointsJson }")
        )
    }

    @Test
    fun `EditorScreen routes every page write through the ViewModel lock-safe gate`() {
        val source = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt").readText()

        // No direct repository page-write call left in the editor (reads only —
        // the old `viewModel.repository.save*` call sites are all gated).
        assertTrue(
            "dispose/nav/back/autosave flushes must go through the VM gate",
            !source.contains("viewModel.repository.saveStrokesForPage") &&
                !source.contains("viewModel.repository.saveCanvasItemsForPage") &&
                !source.contains("viewModel.repository.saveLayersForPage")
        )
        assertTrue("dispose flush must route via flushEditorPageSave", source.contains("viewModel.flushEditorPageSave"))
        assertTrue("debounced autosave must route via autosaveStrokes", source.contains("viewModel.autosaveStrokes"))
        assertTrue("layer writes must route via saveLayersGated", source.contains("viewModel.saveLayersGated"))
    }

    @Test
    fun `ViewModel locks the write side - flush gate APIs and unlock flush are wired`() {
        val source = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt").readText()

        assertTrue("VM must expose the lock-safe full flush", source.contains("fun flushEditorPageSave"))
        assertTrue("VM must expose the lock-safe autosave", source.contains("fun autosaveStrokes"))
        assertTrue("VM must expose the lock-safe layer save", source.contains("fun saveLayersGated"))

        // Every unlock path drains the deferred stash so it is written encrypted.
        val verify = source.substringAfter("suspend fun verifyMasterPassword", "END")
        assertTrue("password unlock must flush deferred saves", verify.contains("flushPendingEditorSaves()"))
        val bio = source.substringAfter("fun verifyBiometricsAndUnlock", "END")
        assertTrue("biometric unlock must flush deferred saves", bio.contains("flushPendingEditorSaves()"))

        // createNoteVersion: rejected (never launched) while locked, and even a
        // mid-write lock must not crash or write plaintext.
        val version = source.substringAfter("fun createNoteVersion", "END").substringBefore("flushEditorPageSave", "END")
        assertTrue("createNoteVersion must gate on the DEK", version.contains("VaultWriteGate.persistNow"))
        assertTrue("createNoteVersion must catch the fail-closed throw", version.contains("VaultLockedWriteException"))

        // lock() must not leave a keyed path alive: it zeroizes the DEK (which the
        // gate reads) before any flush can run.
        val lockBlock = source.substringAfter("fun lock()", "END")
        assertTrue("lock must zeroize the DEK", lockBlock.contains("repository.zeroizeKey()"))
    }

    // ---------- models of the repository write the tests can execute ----------

    private data class StrokeRow(val textContent: String, val pointsJson: String)

    /** Behavioral model of NoteRepository.saveStrokesForPage's per-stroke write. */
    private fun writeStrokeRow(dek: ByteArray?, stroke: Stroke, persisted: () -> Unit): StrokeRow {
        val key = VaultWriteGate.requireKey(dek) // throws when the vault is locked
        val textContent = EncryptionService.encryptField(stroke.text.toByteArray(), key, "strokes", stroke.id, "textContent")
        val payload = EncryptionService.serializeStrokes(listOf(stroke.copy(text = "")))
        val pointsJson = EncryptionService.encryptField(payload.toByteArray(), key, "strokes", stroke.id, "pointsJson")
        persisted()
        return StrokeRow(textContent, pointsJson)
    }

    private fun stroke(text: String) = Stroke(
        id = "stroke-${text.hashCode().toString(16)}",
        tool = StrokeTool.PEN,
        colorInt = 0xFF112233.toInt(),
        width = 5f,
        points = listOf(PointF(0f, 0f), PointF(10f, 4f)),
        pdfPage = 0,
        text = text
    )

    private fun sticky(text: String) = CanvasStickyNote(
        id = "sticky-${text.hashCode().toString(16)}",
        x = 10f,
        y = 20f,
        width = 220f,
        height = 180f,
        text = text,
        colorHex = "#FEF08A",
        pdfPage = 0,
        isCollapsed = false,
        rotationDegrees = 0f
    )

    private fun embed(text: String) = CanvasMediaEmbed(
        id = "embed-${text.hashCode().toString(16)}",
        pageId = "p",
        type = MediaEmbedType.AUDIO_NOTE,
        x = 0f,
        y = 0f,
        width = 300f,
        height = 40f,
        contentUrlOrPath = "file:///audio.mp3",
        textContent = text,
        codeLanguage = null,
        durationMs = 1000,
        pdfPage = 0,
        rotationDegrees = 0f
    )

    private fun layer(name: String) = LayerEntity(
        id = "layer-$name",
        pageId = "p",
        name = name,
        zOrder = 0,
        opacity = 1f,
        blendMode = "NORMAL",
        visible = true,
        locked = false
    )

    private fun repoRoot(): java.io.File {
        val cwd = java.io.File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (java.io.File(dir, "gradle/libs.versions.toml").isFile &&
                java.io.File(dir, "app").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}