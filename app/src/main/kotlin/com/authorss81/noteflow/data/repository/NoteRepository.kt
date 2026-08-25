package com.authorss81.noteflow.data.repository

import androidx.room.withTransaction
import com.authorss81.noteflow.data.db.NoteflowDatabase
import com.authorss81.noteflow.data.model.*
import com.authorss81.noteflow.services.AttachmentIngestPolicy
import com.authorss81.noteflow.services.ReferenceImagePolicy
import com.authorss81.noteflow.services.DatabaseSecurityHelper
import com.authorss81.noteflow.services.DecryptFailurePolicy
import com.authorss81.noteflow.services.EncryptionService
import com.authorss81.noteflow.services.LayerRenderBudgetPolicy
import com.authorss81.noteflow.services.NoteBodyVaultPolicy
import com.authorss81.noteflow.services.NoteVersionRetentionPolicy
import com.authorss81.noteflow.services.SourceFilePathPolicy
import com.authorss81.noteflow.services.StrokeGeometryGateResult
import com.authorss81.noteflow.services.StrokeGeometryPolicy
import com.authorss81.noteflow.services.VaultSearchPolicy
import com.authorss81.noteflow.services.VaultKeyHolder
import com.authorss81.noteflow.services.VaultWriteGate
import com.authorss81.noteflow.services.VoiceNoteCrypto
import com.authorss81.noteflow.services.VoiceRecordingPolicy
import com.authorss81.noteflow.services.WaveformPeakMath
import com.authorss81.noteflow.services.WikiLinkParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NoteRepository(private var db: NoteflowDatabase, private val importsRoot: File) {
    var encryptionKey: ByteArray?
        get() = VaultKeyHolder.dek
        set(value) {
            VaultKeyHolder.dek = value
            clearPlaintextCaches()
        }

    fun zeroizeKey() {
        VaultKeyHolder.zeroize()
        clearPlaintextCaches()
    }

    /**
     * B2-UI-1 (phase-49): fail-closed key for EVERY encrypted-field write. When
     * the vault is locked (the DEK was zeroized by `lock()`/auto-lock), the write
     * THROWS [com.authorss81.noteflow.services.VaultLockedWriteException] instead
     * of silently storing plaintext — the old `if (encryptionKey != null) encrypt
     * else raw` fallback that let a post-lock autosave/dispose-flush coroutine
     * persist stroke textContent/pointsJson, embed textContent and note_versions
     * bodies as plaintext rows is gone. Callers that may still be racing a lock
     * (EditorScreen flushes) catch this and re-queue via the ViewModel's
     * [EditorFlushPolicy] stash for after the next unlock.
     */
    private fun requireEncryptionKey(): ByteArray = VaultWriteGate.requireKey(encryptionKey)

    // -----------------------------------------------------------------------
    // B1-DB-8 (phase-88): decrypt-failure safety.
    // -----------------------------------------------------------------------

    /**
     * Distinct NOTES with ciphertext that failed to decrypt THIS SESSION
     * (deduped on `note:<pageId>` — one note counts once no matter how many of
     * its rows/fields fail, so a single corrupted note can never trip the
     * threshold, while a whole-vault re-key/restore mismatch still does; the
     * review of phase-88 changed the key from `table:recordId:field`, which let
     * one note's several strokes/embeds cross [DecryptFailurePolicy.PERSISTENT_FAILURE_THRESHOLD]
     * on their own).
     * When [DecryptFailurePolicy.PERSISTENT_FAILURE_THRESHOLD] DISTINCT NOTES
     * fail, the failure is judged PERSISTENT (the DEK is present and correct by
     * construction on the read path, so this is a re-key/restore mismatch or a
     * manipulated DB, not an isolated note) and [decryptFailureListener] fires
     * so the ViewModel can escalate to the corruption/restore event (the
     * existing recovery screen offers restore/re-key instead of silently
     * degrading the whole vault to markers).
     *
     * `synchronizedSet(LinkedHashSet)` keeps add/clear atomic across the
     * IO/Default readers; the bought listener may then run on one of those
     * threads and must only perform thread-safe work (StateFlow writes /
     * snackbar emit / SharedPreferences commit).
     */
    private val decryptFailureRecordIds: MutableSet<String> =
        Collections.synchronizedSet(java.util.LinkedHashSet<String>())

    /**
     * Called exactly once per session, on the repository reader thread, when
     * the persistent-failure threshold is first crossed. The ViewModel wires
     * this to raise the corruption flag (recovery screen) + surface the
     * restoration promotion.
     */
    var decryptFailureListener: (() -> Unit)? = null

    val decryptFailuresPersistent: Boolean
        get() = DecryptFailurePolicy.isPersistent(decryptFailureRecordIds.size)

    val decryptFailureRecordCount: Int
        get() = decryptFailureRecordIds.size

    /** B1-DB-8: start a fresh per-session ledger (lock, unlock, re-key, restore). */
    fun resetDecryptFailures() {
        decryptFailureRecordIds.clear()
    }

    private fun recordDecryptFailure(noteId: String) {
        val key = "note:$noteId"
        if (decryptFailureRecordIds.add(key) && decryptFailuresPersistent) {
            decryptFailureListener?.invoke()
        }
    }

    /**
     * B1-DB-8: the ONLY field-decrypt path for display. Follows the single
     * [DecryptFailurePolicy.render] decision table:
     *  - a stored value that is NOT structurally ciphertext is legacy plaintext
     *    and renders verbatim (never the marker — a pre-field-encryption row
     *    must not become "Unreadable");
     *  - a genuine ciphertext that authenticates yields its plaintext;
     *  - a genuine ciphertext whose auth fails (or a locked-vault read with no
     *    DEK) renders [DecryptFailurePolicy.UNREADABLE_MARKER], NEVER the raw
     *    blob — the pre-fix `catch { rawText }`/`catch { text }`/`?: v.title`
     *    fallbacks are gone. A locked read is reported (recorded) only when a
     *    DEK is actually present, so a locked vault can never inflate the
     *    persistent ledger.
     *
     * [noteId] keys the persistent ledger (one entry per note, so a single
     * corrupted note cannot trip the threshold by itself); [recordFailures]
     * disables ledger recording for non-display reads (search corpus) that must
     * never render markers nor inflate the persistent count.
     */
    private fun decryptFieldForDisplay(
        storedValue: String,
        table: String,
        recordId: String,
        fieldName: String,
        noteId: String,
        recordFailures: Boolean = true
    ): String {
        if (storedValue.isBlank()) return storedValue
        val structural = DecryptFailurePolicy.isStructuralCiphertext(storedValue)
        val key = encryptionKey
        val decrypted = if (structural && key != null) {
            try {
                String(EncryptionService.decryptField(storedValue, key, table, recordId, fieldName))
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        if (structural && decrypted == null && key != null && recordFailures) {
            recordDecryptFailure(noteId)
        }
        return DecryptFailurePolicy.render(storedValue, decrypted, structural)
    }

    /**
     * B1-DB-8: the stroke-geometry decrypt path. Geometry can never render as
     * the marker (there is no text surface), so a failed row yields an EMPTY
     * payload — nothing is parsed (never raw ciphertext into
     * [EncryptionService.deserializeStrokes]), so an unreadable row produces no
     * phantom ink, while still recording the failure (keyed on [noteId]) for the
     * persistent ledger.
     */
    private fun decryptStoredGeometryOrBlank(storedValue: String, recordId: String, noteId: String): String {
        if (storedValue.isBlank()) return storedValue
        if (!DecryptFailurePolicy.isStructuralCiphertext(storedValue)) return storedValue
        val key = encryptionKey ?: return ""
        return try {
            String(EncryptionService.decryptField(storedValue, key, "strokes", recordId, "pointsJson"))
        } catch (e: Exception) {
            recordDecryptFailure(noteId)
            ""
        }
    }

    /**
     * In-memory cache of decrypted active pages (title + extractedText) used by
     * vault search. Loaded once and reused across keystrokes so per-keystroke
     * search does not decrypt the entire vault; invalidated on any page mutation
     * or when the vault is locked/re-keyed.
     *
     * Phase-207: mutations no longer NULL this window — they only flip
     * [searchCorpusDirty] (lazy invalidation), and the rebuild reuses every row
     * whose ciphertext is unchanged via [corpusPageCache]. The committed window
     * itself still lives here; it is nulled ONLY at the key epoch boundary
     * ([clearPlaintextCaches]) or replaced on a successful rebuild.
     */
    @Volatile
    private var cachedSearchCorpus: List<NotePageEntity>? = null

    private val searchCorpusLock = Any()

    @Volatile
    private var searchCorpusGeneration = 0L

    /**
     * Phase-207: lazy-corpus-invalidation flag. Set by EVERY page mutation (the
     * pre-fix behavior nulled [cachedSearchCorpus] eagerly, forcing the next
     * query to re-decrypt the whole capped window); cleared ONLY together with a
     * successful rebuild under [searchCorpusLock]. While true, the fast path in
     * [loadSearchCorpus] never serves the stale committed window — search can
     * never see a stale body, it just defers paying for the rebuild until a
     * query actually arrives.
     */
    @Volatile
    private var searchCorpusDirty = true

    // Phase-207: per-row decrypt memoization. Two ISOLATED instances — the
    // display path records B1-DB-8 ledger failures on its misses, the corpus
    // path never does (phase-88 review semantics); sharing one instance would
    // let a corpus-first read suppress a display-side failure report.
    private val displayPageCache = DecryptedPageCache()
    private val corpusPageCache = DecryptedPageCache()
    private val corpusReuse = SearchCorpusReuse(corpusPageCache)

    /**
     * B2-DOS-02 (phase-78): whether the cached search window was CAPPED because
     * the vault exceeds [VaultSearchPolicy.SEARCH_CORPUS_CAP] active pages. The
     * UI reads this after a search to surface the one-time, non-alarming
     * "refine" affordance (search all pages) instead of silently narrowing
     * results. Recomputed on every corpus load; false until the first load.
     */
    val searchCorpusCapped: Boolean
        get() = searchCorpusIsCapped

    @Volatile
    private var searchCorpusIsCapped = false

    /**
     * Monotonic generation bumped by every page mutation / lock / re-key.
     * Consumers (Phase 38 command palette) compare against this to know when
     * their in-memory index is stale without re-scanning.
     */
    val currentSearchCorpusGeneration: Long
        get() = synchronized(searchCorpusLock) { searchCorpusGeneration }

    private fun invalidateSearchCorpus() {
        synchronized(searchCorpusLock) {
            searchCorpusGeneration++
            // Phase-207: LAZY invalidation. The committed window stays in memory
            // (its per-row plaintext remains hash-valid for unchanged rows) and
            // is simply marked stale; [loadSearchCorpus] rebuilds — reusing every
            // unchanged row — on the next actual query. A mutation followed by
            // no query costs nothing, and a query after a single-row edit pays
            // one row's decrypt instead of the whole capped window.
            searchCorpusDirty = true
            searchCorpusIsCapped = false
        }
        // B2-DOS-11: the WikiLink/tag builders must not serve a scan from a previous
        // unlock epoch — this hook fires on lock, key replacement and every page
        // mutation, which is exactly the "per unlock epoch" cache boundary.
        WikiLinkParser.invalidateCaches()
    }

    /**
     * Phase-207: the key-epoch security boundary (lock / key replacement /
     * re-key). Every cache holding DECRYPTED PLAINTEXT is dropped here — the
     * display memoization, the corpus memoization AND the committed corpus
     * window — so no note content can outlive the key it was decrypted with.
     * This replaces what [invalidateSearchCorpus] used to do on `zeroizeKey()`/
     * the `encryptionKey` setter; plain mutations must NOT route through here or
     * they would defeat the memoization entirely.
     */
    private fun clearPlaintextCaches() {
        displayPageCache.clear()
        corpusPageCache.clear()
        synchronized(searchCorpusLock) {
            searchCorpusGeneration++
            cachedSearchCorpus = null
            searchCorpusDirty = true
            searchCorpusIsCapped = false
        }
        WikiLinkParser.invalidateCaches()
    }

    /**
     * B2-DOS-02 (phase-78): load (once per epoch) the CAPPED decrypted search
     * window. The window is ALWAYS cached — bounded at
     * [VaultSearchPolicy.SEARCH_CORPUS_CAP] rows — so a keystroke search never
     * re-decrypts the vault. Pre-fix, a vault larger than the cap deliberately
     * skipped the cache and every keystroke re-ran a full-vault AES-GCM.
     * Vaults over the cap report via [searchCorpusCapped] so the UI can offer
     * the explicit, user-approved deep-scan (refine) path instead.
     */
    private suspend fun loadSearchCorpus(): List<NotePageEntity> {
        while (true) {
            // Phase-207 fast path: only a CLEAN window is served as-is. A dirty
            // flag (any mutation since the last rebuild) always rebuilds — the
            // generation re-check below keeps that honest across races.
            if (!searchCorpusDirty) {
                synchronized(searchCorpusLock) {
                    if (!searchCorpusDirty) cachedSearchCorpus?.let { return it }
                }
            }
            val generationAtStart = synchronized(searchCorpusLock) { searchCorpusGeneration }
            val window = corpusReuse.assemble(
                // B2-DOS-02 (phase-78): the window read is SQL-bounded at the cap.
                db.pageDao().getAllActivePagesBounded(VaultSearchPolicy.SEARCH_CORPUS_CAP)
            ) { page ->
                // B1-DB-8 (phase-88 review fix): undecryptable pages are dropped
                // from the search corpus (never a rankable "Unreadable" marker)
                // and their failures are NOT recorded against the persistent
                // ledger here (the Home-list/display reads already count them).
                decryptPageOrNullForCorpus(page)
            }
            val totalActive = db.pageDao().getActivePageCountOnce()
            synchronized(searchCorpusLock) {
                if (generationAtStart == searchCorpusGeneration) {
                    searchCorpusIsCapped = VaultSearchPolicy.exceedsCorpusCap(totalActive)
                    cachedSearchCorpus = window
                    searchCorpusDirty = false
                    return window
                }
                // else: invalidated (e.g. re-key) while decrypting — loop retries
                // with the current key instead of serving a stale snapshot.
            }
        }
    }

    val notebooks: Flow<List<NotebookEntity>> = db.notebookDao().getAllNotebooks()

    suspend fun getAllNotebooks(): List<NotebookEntity> = withContext(Dispatchers.IO) {
        db.notebookDao().getAllNotebooksOnce()
    }

    suspend fun ensureDefaultNotebookAndSection(): Pair<NotebookEntity, SectionEntity> {
        val existingNotebooks = db.notebookDao().getNotebookById("default_nb")
        val nb = if (existingNotebooks == null) {
            val newNb = NotebookEntity(id = "default_nb", name = "My Notebook")
            db.notebookDao().insertNotebook(newNb)
            newNb
        } else {
            existingNotebooks
        }

        val existingSection = db.sectionDao().getSectionById("default_sec")
        val sec = if (existingSection == null) {
            val newSec = SectionEntity(id = "default_sec", notebookId = nb.id, name = "Quick Notes")
            db.sectionDao().insertSection(newSec)
            newSec
        } else {
            existingSection
        }

        return Pair(nb, sec)
    }

    fun getSectionsForNotebook(notebookId: String): Flow<List<SectionEntity>> =
        db.sectionDao().getSectionsForNotebook(notebookId).flowOn(Dispatchers.IO)

    fun getAllSections(): Flow<List<SectionEntity>> =
        db.sectionDao().getAllSections().flowOn(Dispatchers.IO)

    fun getPagesForSection(sectionId: String): Flow<List<NotePageEntity>> =
        db.pageDao().getPagesForSection(sectionId).map { pages ->
            pages.map { page -> decryptPageIfNeeded(page) }
        }.flowOn(Dispatchers.Default)

    fun getAllActivePagesFlow(): Flow<List<NotePageEntity>> =
        db.pageDao().getAllActivePagesFlow().map { pages ->
            pages.map { page -> decryptPageIfNeeded(page) }
        }.flowOn(Dispatchers.Default)

    fun getRecentPages(): Flow<List<NotePageEntity>> =
        db.pageDao().getRecentPages().map { pages ->
            pages.map { page -> decryptPageIfNeeded(page) }
        }.flowOn(Dispatchers.Default)

    fun getTrashedPages(): Flow<List<NotePageEntity>> =
        db.pageDao().getTrashedPages().map { pages ->
            pages.map { page -> decryptPageIfNeeded(page) }
        }.flowOn(Dispatchers.Default)

    suspend fun getAllActivePages(): List<NotePageEntity> = withContext(Dispatchers.IO) {
        db.pageDao().getAllActivePages().map { decryptPageIfNeeded(it) }
    }

    /**
     * Phase 38: the cached, decrypted corpus backing palette/quick-switcher
     * searches. Same cache as [searchPages] — loaded once per epoch, invalidated
     * on mutation/lock — so a keystroke never re-decrypts the vault.
     */
    suspend fun cachedCorpus(): List<NotePageEntity> = loadSearchCorpus()

    suspend fun checkpointWal() = withContext(Dispatchers.IO) {
        db.query("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    // Fully step the cursor to execute the WAL checkpoint fully
                }
            }
        }
    }

    fun stampDatabaseChecksum(context: android.content.Context) {
        DatabaseSecurityHelper.updateStoredChecksum(context)
    }

    /**
     * B2-CRYPTO-09 (phase-107): one-time re-encryption of every existing field
     * ciphertext under its per-record AAD. Pre-phase-107 rows were authenticated
     * ONLY by the global FIELD_AAD constant (all four field tables), making them
     * transplantable between records. Reading them is still possible via
     * [EncryptionService.decryptField]'s legacy fallback, but this pass binds
     * them to `table|recordId|fieldName` so a relocated ciphertext fails its tag.
     * Plaintext/blank rows are left untouched (that is the reencryptPlaintextFields
     * concern, B2-CRYPTO-10 phase-108). Idempotent: bound rows are skipped.
     */
    suspend fun migrateFieldRecordAad(dek: ByteArray) = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.pageDao().getAllPagesForReencrypt().forEach { page ->
                var title = page.title
                var extracted = page.extractedText
                var dirty = false
                if (title.isNotBlank() && !EncryptionService.isFieldBoundToRecord(title, dek, "pages", page.id, "title")) {
                    val plain = try { EncryptionService.decrypt(title, dek) } catch (e: Exception) { null }
                    if (plain != null) {
                        title = EncryptionService.encryptField(plain, dek, "pages", page.id, "title")
                        dirty = true
                    }
                }
                if (!extracted.isNullOrBlank() && !EncryptionService.isFieldBoundToRecord(extracted, dek, "pages", page.id, "extractedText")) {
                    val plain = try { EncryptionService.decrypt(extracted, dek) } catch (e: Exception) { null }
                    if (plain != null) {
                        extracted = EncryptionService.encryptField(plain, dek, "pages", page.id, "extractedText")
                        dirty = true
                    }
                }
                if (dirty) db.pageDao().updateEncryptedFields(page.id, title, extracted)
            }
            db.strokeDao().getAllStrokesForReencrypt().forEach { stroke ->
                var text = stroke.textContent
                var points = stroke.pointsJson
                var dirty = false
                if (text?.isNotBlank() == true && !EncryptionService.isFieldBoundToRecord(text, dek, "strokes", stroke.id, "textContent")) {
                    val plain = try { EncryptionService.decrypt(text, dek) } catch (e: Exception) { null }
                    if (plain != null) {
                        text = EncryptionService.encryptField(plain, dek, "strokes", stroke.id, "textContent")
                        dirty = true
                    }
                }
                if (points.isNotBlank() && !EncryptionService.isFieldBoundToRecord(points, dek, "strokes", stroke.id, "pointsJson")) {
                    val plain = try { EncryptionService.decrypt(points, dek) } catch (e: Exception) { null }
                    if (plain != null) {
                        points = EncryptionService.encryptField(plain, dek, "strokes", stroke.id, "pointsJson")
                        dirty = true
                    }
                }
                if (dirty) db.strokeDao().updateStrokeFields(stroke.id, text, points)
            }
            db.mediaEmbedDao().getAllEmbedsForReencrypt().forEach { embed ->
                val text = embed.textContent
                if (text?.isNotBlank() == true && !EncryptionService.isFieldBoundToRecord(text, dek, "media_embeds", embed.id, "textContent")) {
                    val plain = try { EncryptionService.decrypt(text, dek) } catch (e: Exception) { null }
                    if (plain != null) {
                        db.mediaEmbedDao().updateTextContent(embed.id, EncryptionService.encryptField(plain, dek, "media_embeds", embed.id, "textContent"))
                    }
                }
            }
            // R2-b2b4-DOS-01 (phase-149): the re-key sweep covers the whole
            // table but pages through it — never one all-row heap materialization.
            var versionOffset = 0
            while (true) {
                val versionBatch = db.noteVersionDao().getVersionsForReencryptPaged(NoteVersionRetentionPolicy.REENCRYPT_BATCH_SIZE, versionOffset)
                if (versionBatch.isEmpty()) break
                versionBatch.forEach { version ->
                    var title = version.title
                    var extracted = version.extractedText
                    var dirty = false
                    if (title.isNotBlank() && !EncryptionService.isFieldBoundToRecord(title, dek, "note_versions", version.id, "title")) {
                        val plain = try { EncryptionService.decrypt(title, dek) } catch (e: Exception) { null }
                        if (plain != null) {
                            title = EncryptionService.encryptField(plain, dek, "note_versions", version.id, "title")
                            dirty = true
                        }
                    }
                    if (!extracted.isNullOrBlank() && !EncryptionService.isFieldBoundToRecord(extracted, dek, "note_versions", version.id, "extractedText")) {
                        val plain = try { EncryptionService.decrypt(extracted, dek) } catch (e: Exception) { null }
                        if (plain != null) {
                            extracted = EncryptionService.encryptField(plain, dek, "note_versions", version.id, "extractedText")
                            dirty = true
                        }
                    }
                    if (dirty) db.noteVersionDao().updateVersionFields(version.id, title, extracted)
                }
                if (versionBatch.size < NoteVersionRetentionPolicy.REENCRYPT_BATCH_SIZE) break
                versionOffset += versionBatch.size
            }
        }
    }

    /**
     * One-time pass: encrypts any plaintext title/extractedText/textContent rows
     * with the given DEK. Called after setting a master password so pre-existing
     * data is not left in the clear (previously only new writes were encrypted).
     * B2-CRYPTO-10 (phase-108): blank rows are NOW swept too — an empty string is
     * stored as a real AEAD payload (`[1][12-byte IV][16-byte GCM tag]`, 29
     * bytes) so a blank column carries an authenticated tag over its blank-ness
     * instead of sitting raw and tag-less in the vault. NULL columns are stored
     * as an encrypted `""` as well, so a legacy NULL blank is tagged exactly like
     * a newer written row (phase-108 review fix, finding #4). Every candidate is
     * gated by [EncryptionService.shouldReencryptField]: an already-encrypted
     * value and a corrupt/transplanted ciphertext are both left untouched
     * (never re-encrypted as if they were plaintext — review fix, finding #2).
     * Idempotent: stamped rows are skipped on re-runs.
     */
    suspend fun reencryptPlaintextFields(dek: ByteArray) = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.pageDao().getAllPagesForReencrypt().forEach { page ->
                var title = page.title
                var extracted = page.extractedText ?: ""
                var dirty = false
                if (EncryptionService.shouldReencryptField(title, dek, "pages", page.id, "title")) {
                    title = EncryptionService.encryptField(title.toByteArray(), dek, "pages", page.id, "title")
                    dirty = true
                }
                if (EncryptionService.shouldReencryptField(extracted, dek, "pages", page.id, "extractedText")) {
                    extracted = EncryptionService.encryptField(extracted.toByteArray(), dek, "pages", page.id, "extractedText")
                    dirty = true
                }
                if (dirty) db.pageDao().updateEncryptedFields(page.id, title, extracted)
            }
            db.strokeDao().getAllStrokesForReencrypt().forEach { stroke ->
                var text = stroke.textContent
                var points = stroke.pointsJson
                var dirty = false
                if (EncryptionService.shouldReencryptField(text, dek, "strokes", stroke.id, "textContent")) {
                    text = EncryptionService.encryptField(text.toByteArray(), dek, "strokes", stroke.id, "textContent")
                    dirty = true
                }
                if (EncryptionService.shouldReencryptField(points, dek, "strokes", stroke.id, "pointsJson")) {
                    points = EncryptionService.encryptField(points.toByteArray(), dek, "strokes", stroke.id, "pointsJson")
                    dirty = true
                }
                if (dirty) {
                    db.strokeDao().updateStrokeFields(stroke.id, text, points)
                }
            }
            db.mediaEmbedDao().getAllEmbedsForReencrypt().forEach { embed ->
                val text = embed.textContent ?: ""
                if (EncryptionService.shouldReencryptField(text, dek, "media_embeds", embed.id, "textContent")) {
                    val encrypted = EncryptionService.encryptField(text.toByteArray(), dek, "media_embeds", embed.id, "textContent")
                    db.mediaEmbedDao().updateTextContent(embed.id, encrypted)
                }
            }
            // C1 (phase-09): note_versions.title/extractedText are field-encrypted
            // at write (createNoteVersion) and re-keyed on cross-device restore
            // (fieldEncryptedColumns), but the local plaintext sweep was missing
            // them — any legacy plaintext version snapshot written before a master
            // password existed stayed in the clear at rest.
            // R2-b2b4-DOS-01 (phase-149): the C1 sweep covers the whole table but
            // runs in bounded pages — a vault that accumulated thousands of
            // snapshots before the retention cap can never be loaded wholesale.
            var versionOffset = 0
            while (true) {
                val versionBatch = db.noteVersionDao().getVersionsForReencryptPaged(NoteVersionRetentionPolicy.REENCRYPT_BATCH_SIZE, versionOffset)
                if (versionBatch.isEmpty()) break
                versionBatch.forEach { version ->
                    var title = version.title
                    var extracted = version.extractedText ?: ""
                    var dirty = false
                    if (EncryptionService.shouldReencryptField(title, dek, "note_versions", version.id, "title")) {
                        title = EncryptionService.encryptField(title.toByteArray(), dek, "note_versions", version.id, "title")
                        dirty = true
                    }
                    if (EncryptionService.shouldReencryptField(extracted, dek, "note_versions", version.id, "extractedText")) {
                        extracted = EncryptionService.encryptField(extracted.toByteArray(), dek, "note_versions", version.id, "extractedText")
                        dirty = true
                    }
                    if (dirty) {
                        db.noteVersionDao().updateVersionFields(version.id, title, extracted)
                    }
                }
                if (versionBatch.size < NoteVersionRetentionPolicy.REENCRYPT_BATCH_SIZE) break
                versionOffset += versionBatch.size
            }
        }
    }

    fun closeDatabase() {
        NoteflowDatabase.dispose()
    }

    /**
     * H1 (phase-09): after a restore closes the live DB, a failure must never
     * leave the app with a dead Room instance. dispose() closes + forgets the
     * instance and this rebuilds it so the next repository call opens a fresh
     * connection to the (still intact, pre-swap) vault file.
     */
    fun reopenDatabase(context: android.content.Context) {
        NoteflowDatabase.dispose()
        db = NoteflowDatabase.getDatabase(context)
    }

    suspend fun getPageById(id: String): NotePageEntity? = withContext(Dispatchers.Default) {
        val page = db.pageDao().getPageById(id) ?: return@withContext null
        decryptPageIfNeeded(page)
    }

    suspend fun getNotebookById(id: String): NotebookEntity? = withContext(Dispatchers.IO) {
        db.notebookDao().getNotebookById(id)
    }

    suspend fun getSectionById(id: String): SectionEntity? = withContext(Dispatchers.IO) {
        db.sectionDao().getSectionById(id)
    }

    /**
     * B2-DOS-02 (phase-78): per-keystroke search over the CACHED, CAPPED window
     * ([loadSearchCorpus]) — never a full-vault decrypt. Vaults over the cap are
     * narrowed to the [VaultSearchPolicy.SEARCH_CORPUS_CAP] most recent pages;
     * [deepSearchPages] is the explicit, user-approved full-vault refinement.
     */
    suspend fun searchPages(query: String): List<NotePageEntity> = withContext(Dispatchers.IO) {
        if (VaultSearchPolicy.isBlankQuery(query)) return@withContext emptyList()
        val allPages = loadSearchCorpus()
        val q = query.trim()
        // Phase 209: typo-tolerant tier — exact-substring hits keep the corpus
        // (recency) order AHEAD of fuzzy subsequence hits (stable within tiers).
        VaultSearchPolicy.exactFirst(allPages.filter { VaultSearchPolicy.pageMatches(it, q) }, q)
    }

    /**
     * B2-DOS-02 (phase-78): the EXPLICIT refine path — the user asked to search
     * the WHOLE vault after being shown the capped-window notice. Decrypts in
     * bounded [VaultSearchPolicy.DEEP_SCAN_BATCH_SIZE] batches (memory + per-step
     * work stay bounded; only matches are retained, the full corpus is never
     * pinned) and is cancellable by the ViewModel's shared search Job, so a new
     * keystroke can always pre-empt it. A one-time cost per explicit request,
     * NEVER per keystroke.
     */
    suspend fun deepSearchPages(query: String): List<NotePageEntity> = withContext(Dispatchers.IO) {
        if (VaultSearchPolicy.isBlankQuery(query)) return@withContext emptyList()
        val q = query.trim()
        val matches = ArrayList<NotePageEntity>()
        var offset = 0
        while (true) {
            val batch = db.pageDao().getAllActivePagesPaged(VaultSearchPolicy.DEEP_SCAN_BATCH_SIZE, offset)
                .map { decryptPageIfNeeded(it) }
            if (batch.isEmpty()) break
            matches += batch.filter { page -> VaultSearchPolicy.pageMatches(page, q) }
            if (batch.size < VaultSearchPolicy.DEEP_SCAN_BATCH_SIZE) break
            offset += VaultSearchPolicy.DEEP_SCAN_BATCH_SIZE
        }
        // Phase 209: same exact-first ordering as the capped keystroke path.
        VaultSearchPolicy.exactFirst(matches, q)
    }

    suspend fun updatePageTemplate(id: String, template: String) = withContext(Dispatchers.Default) {
        db.pageDao().updatePageTemplate(id, template)
    }

    suspend fun updatePagePaperColor(id: String, paperColor: String?) = withContext(Dispatchers.Default) {
        db.pageDao().updatePagePaperColor(id, paperColor)
    }

    suspend fun updatePageSource(id: String, sourceFilePath: String?, sourceFileType: String?) = withContext(Dispatchers.Default) {
        // B1-AUTH-05 (phase-69): a stored sourceFilePath may only ever point at a
        // file confined under the app-private imports root — any other value
        // (relative, `..`-traversing, absolute-outside-root) is dropped to null.
        val confined = SourceFilePathPolicy.confine(sourceFilePath, importsRoot)
        db.pageDao().updatePageSource(id, confined, sourceFileType)
    }

    /**
     * B1-DB-4 (phase-44): the SINGLE write path for a markdown/text note body.
     * The body is stored ONLY in the field-encrypted `pages.extractedText`
     * column (AES-256-GCM under the DEK) — never in a plaintext `.md`/`.txt`
     * file. Callers that previously persisted text to `filesDir/noteflow/imports`
     * (MainActivity's file save, text/markdown imports, journal/daily/wiki page
     * creation) must route here instead. A blank body is still stored as a real
     * AEAD payload so even an empty note carries an integrity tag.
     */
    suspend fun updatePageBody(id: String, body: String) = withContext(Dispatchers.Default) {
        // Phase-169: never persist the fail-closed render marker as real content.
        // The marker only ever appears when GCM authentication failed, so writing
        // it would permanently replace the (still-recoverable) original ciphertext
        // with the marker text. Refuse loudly and keep the encrypted bytes intact.
        // Trimmed so a trailing newline (a common editor ending) cannot bypass it.
        if (com.authorss81.noteflow.services.DecryptFailurePolicy.isUnreadableMarker(body.trim())) {
            throw com.authorss81.noteflow.services.UnreadableContentWriteException()
        }
        // B2-UI-1 (phase-49): fail closed — the note body column is encrypted at
        // rest, so a locked vault must throw rather than store the raw body.
        val storedBody = EncryptionService.encryptField(body.toByteArray(), requireEncryptionKey(), "pages", id, "extractedText")
        db.pageDao().updatePageBody(id, storedBody)
        invalidateSearchCorpus()
    }

    /**
     * B1-DB-4 (phase-44): one-time sweep of legacy plaintext note-body files.
     * Pre-phase-44 builds kept the authoritative body of a text page as a
     * plaintext `.md`/`.txt` file under `filesDir/noteflow/imports` (the DB
     * column was field-encrypted but could be stale). Runs once at unlock: for
     * every text page with a surviving file, the FILE's body is written into the
     * encrypted column FIRST, then the plaintext file is deleted. Safe:
     *  - a page whose column cannot be decrypted is skipped and its file is left
     *    in place (the migration flag stays unset → retried on the next unlock);
     *  - if any delete fails, [LegacyBodyMigrationResult.filesRemainingCount] is
     *    > 0 and the flag stays unset so the sweep re-runs later.
     */
    suspend fun migrateLegacyPlaintextNoteBodies(): LegacyBodyMigrationResult = withContext(Dispatchers.IO) {
        var rowsMigrated = 0
        var filesDeleted = 0
        var filesRemaining = 0
        val key = encryptionKey
        db.pageDao().getAllPagesForReencrypt().forEach { page ->
            if (!NoteBodyVaultPolicy.isNoteTextBodySource(page.sourceFilePath, page.sourceFileType)) return@forEach
            // B1-AUTH-05 (phase-69): only a legacy body file CONFINED under the
            // imports root may be read (and later deleted) — a stored path that
            // escapes the subtree (relative, `..`, absolute-outside-root) is
            // never read into the column nor deleted.
            val readPath = SourceFilePathPolicy.confine(page.sourceFilePath, importsRoot) ?: return@forEach
            val file = File(readPath)
            if (!file.exists()) return@forEach
            // B2-DOS-05 (phase-81 review fix): the unlock-time migration must never
            // pin a whole legacy body in heap with an unbounded file.readText().
            // A file within the ingest cap is read HEAD-BOUNDED (readTextHead
            // yields the full content for files <= cap); a file LARGER than the cap
            // is left untouched — never read into the column, never deleted — so an
            // oversized legacy file can neither OOM the migration NOR be silently
            // truncated into the column before its (delete) migration.
            if (!file.canRead() || file.length() <= 0L) {
                filesRemaining++
                return@forEach
            }
            if (file.length() > AttachmentIngestPolicy.MAX_ATTACHMENT_BYTES) {
                filesRemaining++
                return@forEach
            }

            val dbBody = when {
                key == null -> page.extractedText
                page.extractedText.isNullOrEmpty() -> ""
                else -> try {
                    String(EncryptionService.decryptField(page.extractedText, key, "pages", page.id, "extractedText"))
                } catch (e: Exception) {
                    null
                }
            }
            if (dbBody == null) {
                // Undecryptable column — never overwrite, never delete the file.
                filesRemaining++
                return@forEach
            }

            // B2-DOS-05 (phase-81 review fix): the legacy body is read through the
            // same bounded policy decision table as the display path — never a raw
            // file.readText() that slurps the whole file into heap.
            // Phase 204: readTextHead now returns null when the read FAILS
            // mid-way. Pre-fix it swallowed I/O errors into "", so this flow
            // overwrote the GOOD encrypted column with an encrypted "" and then
            // deleted the only plaintext copy — a transient read error during
            // unlock-time migration was permanent silent data loss. Null means
            // content UNKNOWN: skip BOTH the overwrite and the delete, count the
            // file as remaining, and let the sweep retry on the next unlock
            // (the migration flag stays unset whenever filesRemaining > 0).
            val fileBody = AttachmentIngestPolicy.readTextHead(file)
            // Phase 204 (+review fix): null = the read FAILED mid-way. "" here can
            // only mean content became UNKNOWN between THIS sweep's pre-checks and
            // the read itself (the checks above already guaranteed length > 0,
            // readable, within cap — a readable non-empty file can never yield ""),
            // i.e. the same vanished/raced-file window. Either way: skip BOTH the
            // overwrite and the delete, count the file as remaining, and let the
            // sweep retry on the next unlock (the migration flag stays unset
            // whenever filesRemaining > 0).
            if (fileBody.isNullOrEmpty()) {
                filesRemaining++
                return@forEach
            }
            if (fileBody != dbBody) {
                val storedBody = key?.let {
                    EncryptionService.encryptField(fileBody.toByteArray(), it, "pages", page.id, "extractedText")
                } ?: fileBody
                db.pageDao().updatePageBody(page.id, storedBody)
                rowsMigrated++
            }
            if (try { file.delete() } catch (e: Exception) { false }) {
                filesDeleted++
            } else {
                filesRemaining++
            }
        }
        if (rowsMigrated + filesDeleted > 0) invalidateSearchCorpus()
        LegacyBodyMigrationResult(rowsMigrated, filesDeleted, filesRemaining)
    }

    suspend fun getPagesForSectionOnce(sectionId: String): List<NotePageEntity> = withContext(Dispatchers.IO) {
        db.pageDao().getPagesForSectionOnce(sectionId).map { decryptPageIfNeeded(it) }
    }

    suspend fun getPagesForNotebookOnce(notebookId: String): List<NotePageEntity> = withContext(Dispatchers.IO) {
        db.pageDao().getPagesForNotebookOnce(notebookId).map { decryptPageIfNeeded(it) }
    }

    suspend fun createNotebook(name: String, tags: String = ""): NotebookEntity {
        val nb = NotebookEntity(id = UUID.randomUUID().toString(), name = name.trim(), tags = tags.trim())
        db.notebookDao().insertNotebook(nb)
        val sec = SectionEntity(id = UUID.randomUUID().toString(), notebookId = nb.id, name = "Quick Notes")
        db.sectionDao().insertSection(sec)
        return nb
    }

    suspend fun renameNotebook(id: String, name: String) {
        db.notebookDao().renameNotebook(id, name.trim())
    }

    suspend fun updateNotebookTags(id: String, tags: String) {
        db.notebookDao().updateNotebookTags(id, tags.trim())
    }

    suspend fun updateNotebookNameAndTags(id: String, name: String, tags: String) {
        db.notebookDao().updateNotebookNameAndTags(id, name.trim(), tags.trim())
    }

    suspend fun deleteNotebook(id: String) {
        db.withTransaction {
            val sectionIds = db.sectionDao().getSectionIdsForNotebook(id)
            for (sectionId in sectionIds) {
                val pageIds = db.pageDao().getPageIdsForSection(sectionId)
                for (pageId in pageIds) {
                    deletePagePermanently(pageId)
                }
                db.sectionDao().deleteSection(sectionId)
            }
            db.notebookDao().deleteNotebook(id)
        }
    }

    suspend fun createSection(notebookId: String, name: String): SectionEntity {
        val sec = SectionEntity(id = UUID.randomUUID().toString(), notebookId = notebookId, name = name.trim())
        db.sectionDao().insertSection(sec)
        return sec
    }

    suspend fun renameSection(id: String, name: String) {
        db.sectionDao().renameSection(id, name.trim())
    }

    suspend fun deleteSection(id: String) {
        db.withTransaction {
            val pageIds = db.pageDao().getPageIdsForSection(id)
            for (pageId in pageIds) {
                deletePagePermanently(pageId)
            }
            db.sectionDao().deleteSection(id)
        }
    }

    suspend fun createPage(
        sectionId: String,
        title: String,
        sourceFilePath: String? = null,
        sourceFileType: String? = null,
        pageIndex: Int = 0,
        template: String? = "blank",
        paperColor: String? = null,
        extractedText: String? = "",
        tags: String = ""
    ): NotePageEntity = withContext(Dispatchers.Default) {
        val rawTitle = title.trim()
        val pageId = UUID.randomUUID().toString()
        // B1-AUTH-05 (phase-69): a newly-created page's source file may only
        // point inside the app-private imports root — anything else is dropped.
        val storedSrc = SourceFilePathPolicy.confine(sourceFilePath, importsRoot)
        // B2-UI-1 (phase-49): the DEK is required for the encrypted columns —
        // a locked (zeroized) vault can never fall back to a plaintext title/body.
        val dek = requireEncryptionKey()
        val storedTitle = EncryptionService.encryptField(rawTitle.toByteArray(), dek, "pages", pageId, "title")
        val rawExtracted = extractedText ?: ""
        // B2-CRYPTO-10 (phase-108): a blank extractedText is stored as a real AEAD
        // payload too (never raw ""), so even an empty body carries an integrity
        // tag that a zeroed column cannot fake.
        val storedExtracted = EncryptionService.encryptField(rawExtracted.toByteArray(), dek, "pages", pageId, "extractedText")
        val page = NotePageEntity(
            id = pageId,
            sectionId = sectionId,
            title = storedTitle,
            sourceFilePath = storedSrc,
            sourceFileType = sourceFileType,
            pageIndex = pageIndex,
            template = template,
            paperColor = paperColor,
            extractedText = storedExtracted,
            tags = tags.trim()
        )
        db.pageDao().insertPage(page)
        invalidateSearchCorpus()
        page.copy(title = rawTitle, extractedText = rawExtracted)
    }

    suspend fun renamePage(id: String, title: String) = withContext(Dispatchers.Default) {
        val rawTitle = title.trim()
        // Phase-169: never persist the fail-closed render marker as a real title
        // (the rename dialogs pre-fill the rendered title, which is the marker
        // when the page title failed to decrypt — saving it unchanged would
        // permanently replace the recoverable original). Same guard as
        // [updatePageTitleAndTags].
        if (com.authorss81.noteflow.services.DecryptFailurePolicy.isUnreadableMarker(rawTitle)) {
            throw com.authorss81.noteflow.services.UnreadableContentWriteException()
        }
        // B2-UI-1 (phase-49): fail closed — a locked vault can never store a plaintext title.
        val storedTitle = EncryptionService.encryptField(rawTitle.toByteArray(), requireEncryptionKey(), "pages", id, "title")
        db.pageDao().renamePage(id, storedTitle)
        invalidateSearchCorpus()
    }

    suspend fun updatePageTags(id: String, tags: String) = withContext(Dispatchers.Default) {
        db.pageDao().updatePageTags(id, tags.trim())
        invalidateSearchCorpus()
    }

    suspend fun updatePageTitleAndTags(id: String, title: String, tags: String) = withContext(Dispatchers.Default) {
        val rawTitle = title.trim()
        // Phase-169: never persist the fail-closed render marker as a real title —
        // same rationale as the body guard in [updatePageBody].
        if (com.authorss81.noteflow.services.DecryptFailurePolicy.isUnreadableMarker(rawTitle)) {
            throw com.authorss81.noteflow.services.UnreadableContentWriteException()
        }
        // B2-UI-1 (phase-49): fail closed — a locked vault can never store a plaintext title.
        val storedTitle = EncryptionService.encryptField(rawTitle.toByteArray(), requireEncryptionKey(), "pages", id, "title")
        db.pageDao().updatePageTitleAndTags(id, storedTitle, tags.trim())
        invalidateSearchCorpus()
    }

    suspend fun togglePin(id: String, pinned: Boolean) {
        db.pageDao().togglePin(id, pinned)
    }

    suspend fun trashPage(id: String) {
        db.pageDao().trashPage(id)
        invalidateSearchCorpus()
    }

    suspend fun restorePage(id: String) {
        db.pageDao().restorePage(id)
        invalidateSearchCorpus()
    }

    suspend fun movePage(id: String, targetSectionId: String) {
        db.pageDao().movePage(id, targetSectionId)
        invalidateSearchCorpus()
    }

    suspend fun updatePageIndex(id: String, pageIndex: Int) {
        db.pageDao().updatePageIndex(id, pageIndex)
    }

    suspend fun deletePagePermanently(id: String) {
        val page = db.pageDao().getPageById(id)
        page?.sourceFilePath?.let { path ->
            if (path.contains("imports/") || path.contains("exports/")) {
                try { File(path).delete() } catch (e: Exception) {}
            }
        }
        // B1-DB-3 (phase-54): a page delete must also destroy its voice
        // recordings. Deleted pages previously left orphaned plaintext audio
        // under filesDir/voice_notes (neither encrypted, backed up, nor ever
        // removed) — now the AUDIO_NOTE embeds' `.enc` blobs (and any surviving
        // legacy plaintext) are removed with the page. PHOTO/STICKER/CODE embeds
        // are intentionally untouched (their contentUrlOrPath is not audio).
        val embeds = db.mediaEmbedDao().getMediaEmbedsForPage(id)
        for (embed in embeds) {
            if (embed.typeName == MediaEmbedType.AUDIO_NOTE.name) {
                val audioPath = embed.contentUrlOrPath
                if (audioPath != null && VoiceNoteCrypto.isEncryptedBlobName(File(audioPath).name)) {
                    try { File(audioPath).delete() } catch (e: Exception) {}
                }
            }
        }
        db.strokeDao().deleteStrokesForPage(id)
        db.layerDao().deleteLayersForPage(id)
        db.mediaEmbedDao().deleteMediaEmbedsForPage(id)
        db.pageDao().deletePagePermanently(id)
        invalidateSearchCorpus()
    }

    /**
     * B1-DB-3 (phase-54): one-time sweep of legacy PLAINTEXT voice recordings.
     *
     * Pre-phase-54 builds recorded to `filesDir/voice_notes` as raw MPEG-4/AAC
     * `.m4a` files with no encryption. For every AUDIO_NOTE embed whose
     * `contentUrlOrPath` still points at a `.m4a`, the audio is encrypted to
     * `<name>.enc` with the DEK,
     * the embed row is retargeted to the blob path, and the plaintext is
     * deleted (never before the encryption completed). Legacy recordings keep
     * playing[^1] and no private memo remains at rest in the clear. Orphaned
     * plaintext `.m4a` files with no DB row (pre-fix crash leftovers) are
     * deleted too. The flag (`SettingsManager.voiceNotesEncryptedMigrated`)
     * only sets when every referenced legacy file is gone.
     *
     * [^1]: playback path = [com.authorss81.noteflow.services.VoiceNoteManager]
     * decrypts `.enc` blobs transparently.
     *
     * Returns a [VoiceNoteMigrationResult]; `isComplete` == no plaintext `.m4a`
     * survived.
     */
    suspend fun migrateLegacyPlaintextVoiceNotes(): VoiceNoteMigrationResult = withContext(Dispatchers.IO) {
        var rowsMigrated = 0
        val retainedPlaintext = mutableSetOf<String>()
        val dek = encryptionKey

        val legacyEmbeds = db.mediaEmbedDao().getAllEmbedsForReencrypt()
            .filter { embed ->
                embed.typeName == MediaEmbedType.AUDIO_NOTE.name &&
                    VoiceNoteCrypto.isPlaintextRecordingName(File(embed.contentUrlOrPath ?: "").name)
            }

        for (embed in legacyEmbeds) {
            val legacyPath = embed.contentUrlOrPath ?: continue
            val legacyFile = File(legacyPath)
            if (!legacyFile.isFile) continue // dead path — nothing plaintext on disk
            val blob = if (dek == null) {
                retainedPlaintext.add(legacyPath)
                null
            } else {
                VoiceNoteCrypto.migrateLegacyRecordingFile(legacyFile, dek) ?: run {
                    retainedPlaintext.add(legacyPath)
                    null
                }
            }
            if (blob != null) {
                db.mediaEmbedDao().updateContentUrlOrPath(embed.id, blob.absolutePath)
                rowsMigrated++
            }
        }

        // Orphan sweep — delete leftover plaintext with no row (only the dirs we
        // actually looked at, and never a retained/failed file).
        val dirs = legacyEmbeds
            .mapNotNull { File(it.contentUrlOrPath ?: "").parentFile }
            .distinct()
        val orphansDeleted = dirs.sumOf { VoiceNoteCrypto.deleteOrphanPlaintext(it, retainedPlaintext) }

        if (rowsMigrated > 0 || orphansDeleted > 0) invalidateSearchCorpus()
        VoiceNoteMigrationResult(
            rowsMigrated = rowsMigrated,
            orphansDeleted = orphansDeleted,
            plaintextRemaining = retainedPlaintext.size
        )
    }

    suspend fun emptyTrash() {
        val trashed = db.pageDao().getTrashedPagesOnce()
        for (page in trashed) {
            deletePagePermanently(page.id)
        }
    }

    suspend fun getStrokesForPage(pageId: String): List<Stroke> = withContext(Dispatchers.Default) {
        // B2-DOS-01 (phase-50): the load path is BOUNDED. Previously the entire
        // page's stroke rows were pulled at once and every pointsJson was
        // decrypted + Gson-materialized (a crafted/hostile page with millions of
        // points OOMed or ANRed the process). Now:
        //  1. the DAO filters out rows whose stored (encrypted) pointsJson is
        //     already over the budget and pages the rest (LIMIT/OFFSET);
        //  2. each decrypted point payload goes through StrokeGeometryPolicy's
        //     plaintext guard BEFORE parsing (belt + braces to deserializeStrokes);
        //  3. per-stroke point lists are capped (legacy rows that over-specified
        //     keep only their head);
        //  4. the whole page stops loading once MAX_STROKES_PER_PAGE strokes or
        //     MAX_POINTS_PER_PAGE points are consumed.
        val loaded = ArrayList<Stroke>()
        var pagePoints = 0
        var offset = 0
        val pageSize = StrokeGeometryPolicy.MAX_STROKES_LOAD_BATCH
        while (loaded.size < StrokeGeometryPolicy.MAX_STROKES_PER_PAGE) {
            val batch = db.strokeDao().getStrokesForPageBounded(
                pageId,
                StrokeGeometryPolicy.MAX_STORED_POINTS_JSON_CHARS,
                pageSize,
                offset
            )
            if (batch.isEmpty()) break

            var budgetExhausted = false
            for (entity in batch) {
                if (loaded.size >= StrokeGeometryPolicy.MAX_STROKES_PER_PAGE) {
                    budgetExhausted = true
                    break
                }

                val rawText = entity.textContent
                // B1-DB-8 (phase-88): a stroke text that fails to decrypt renders
                // the explicit UNREADABLE_MARKER (via the single policy decision),
                // never the raw base64 ciphertext blob the old catch returned.
                val decryptedText = if (rawText.isNotBlank()) {
                    decryptFieldForDisplay(rawText, "strokes", entity.id, "textContent", pageId)
                } else {
                    rawText
                }

                val rawPointsJson = entity.pointsJson
                // B1-DB-8 (phase-88): unreadable geometry yields an EMPTY payload
                // (no phantom ink from ciphertext, nothing fed to deserializeStrokes),
                // and every failure is recorded for the persistent ledger.
                val decryptedPointsJson = if (rawPointsJson.isNotBlank()) {
                    decryptStoredGeometryOrBlank(rawPointsJson, entity.id, pageId)
                } else {
                    rawPointsJson
                }
                if (StrokeGeometryPolicy.plaintextPointsJsonOverBudget(decryptedPointsJson.length)) {
                    // Oversized legacy/planted payload — do not parse, skip the row.
                    continue
                }
                val deserializedStrokes = EncryptionService.deserializeStrokes(decryptedPointsJson)
                val firstDeserialized = deserializedStrokes.firstOrNull()
                val rawPoints = deserializedStrokes.flatMap { it.points }
                val points = StrokeGeometryPolicy.capLoadedPoints(rawPoints)

                // Page budget: total-points envelope. A page already at budget is
                // not grown further, so load work is always bounded.
                if (loaded.isNotEmpty() && pagePoints + points.size > StrokeGeometryPolicy.MAX_POINTS_PER_PAGE) {
                    budgetExhausted = true
                    break
                }
                pagePoints += points.size

                val start = if (entity.startX != null && entity.startY != null) PointF(entity.startX, entity.startY) else null
                val end = if (entity.endX != null && entity.endY != null) PointF(entity.endX, entity.endY) else null
                val isAdvanced = firstDeserialized?.isAdvanced ?: false
                // Phase 27: color-mode fields round-trip through the stroke's serialized
                // payload (pointsJson). Missing on old strokes => SOLID / seed 0, which is
                // bit-identical to the pre-phase-27 behaviour.
                val colorMode = com.authorss81.noteflow.data.model.StrokeColorMode.fromKey(firstDeserialized?.colorMode?.persistenceKey)
                val colorSeed = firstDeserialized?.colorSeed ?: 0
                val gradientToColorInt = firstDeserialized?.gradientToColorInt

                loaded += Stroke(
                    id = entity.id,
                    tool = try { StrokeTool.valueOf(entity.toolName) } catch (e: Exception) { StrokeTool.PEN },
                    colorInt = entity.colorInt,
                    width = entity.strokeWidth,
                    filled = entity.filled,
                    text = decryptedText,
                    points = points,
                    start = start,
                    end = end,
                    pdfPage = entity.pdfPage,
                    timestampMs = entity.timestampMs,
                    isAdvanced = isAdvanced,
                    layerId = entity.layerId,
                    colorMode = colorMode,
                    colorSeed = colorSeed,
                    gradientToColorInt = gradientToColorInt
                )
            }
            if (budgetExhausted || batch.size < pageSize) break
            offset += pageSize
        }
        loaded.forEach { lastSavedStrokeHash[it.id] = strokeContentHash(it) }
        loaded
    }

    /**
     * In-memory snapshot of the last content hash per stroke id, so debounced
     * saves only write strokes that actually changed (single insert/update per
     * new or edited stroke, plus targeted deletes) instead of the previous
     * delete-all + re-insert-all rewrite of the whole page.
     *
     * B2-DOS-10 (phase 100): the map is LRU-bounded so a long editing session
     * touching many pages never grows it without limit. Evicted entries are
     * simply re-saved on the next write — a redundant write, never lost data.
     *
     * B2-UI-3 (phase-73): the map is now THREAD-SAFE. The plain [LruBoundedMap]
     * is an access-order `LinkedHashMap`, whose bookkeeping is not safe to
     * mutate from two coroutines at once — and it IS mutated from both the
     * stroke-load path ([getStrokesForPage], no transaction) and the
     * stroke-save path ([saveStrokesForPage], inside `withTransaction`), so a
     * concurrent load + save (or two saves) could corrupt the internal link
     * list or lose an entry. `Collections.synchronizedMap` makes every
     * individual get/put/remove atomic while preserving the LRU bound; the
     * compound read-modify-write inside [saveStrokesForPage] is additionally
     * serialized by the per-page [pageSaveLocks] mutex + Room's `withTransaction`
     * writer lock, so no interleaved hash read/commit can ever land stale.
     */
    private val lastSavedStrokeHash: MutableMap<String, Int> =
        Collections.synchronizedMap(LruBoundedMap<String, Int>(MAX_LAST_SAVED_STROKE_HASH_ENTRIES))

    /**
     * Upper bound for [lastSavedStrokeHash]. A stroke UUID key + int hash is a
     * few tens of bytes; 10k entries keep the diff cache a few hundred KB even
     * for vaults with tens of thousands of strokes, and eviction only forces a
     * redundant re-write the next time a cold stroke is saved.
     */
    private companion object {
        const val MAX_LAST_SAVED_STROKE_HASH_ENTRIES = 10_000
    }

    /**
     * B2-UI-3 (phase-73): per-page write serialization. Every full-page write
     * for a page — strokes ([saveStrokesForPage]), stickies/embeds
     * ([saveMediaEmbedsForPage]) and layers ([saveLayersForPage]) — acquires
     * the SAME per-page [Mutex], so two concurrent saves for ONE page can never
     * interleave their Room transactions nor the [lastSavedStrokeHash]
     * read-modify-write (a stale snapshot's hash commit can never land last —
     * the exploit's data-loss + `ConcurrentModificationException` paths).
     * Different pages stay fully concurrent.
     *
     * kotlinx [Mutex] is fair (FIFO), and every write carries the caller's full
     * latest page snapshot, so a later-issued (newer) save is enqueued after an
     * earlier one and commits last — the newest state always wins.
     *
     * The map grows only with the distinct pages edited in a session (each
     * entry is a ~100-byte [Mutex], naturally bounded by the vault's B2-DOS
     * budgets); it is deliberately never evicted, because removing a lock that
     * an in-flight save still holds would let two saves share the same page
     * under different mutexes and reopen the race.
     */
    private val pageSaveLocks = ConcurrentHashMap<String, Mutex>()

    private fun strokeContentHash(s: Stroke): Int {
        var h = s.tool.name.hashCode()
        h = 31 * h + s.colorInt
        h = 31 * h + s.width.hashCode()
        h = 31 * h + if (s.filled) 1 else 0
        h = 31 * h + s.text.hashCode()
        h = 31 * h + s.points.hashCode()
        h = 31 * h + (s.start?.hashCode() ?: 0)
        h = 31 * h + (s.end?.hashCode() ?: 0)
        h = 31 * h + s.pdfPage
        h = 31 * h + (s.timestampMs?.hashCode() ?: 0)
        h = 31 * h + (s.layerId?.hashCode() ?: 0)
        // Phase 27: color mode + seed + gradient end color are content — a mode
        // change must dirty the row so it gets re-encrypted/rewritten.
        h = 31 * h + s.colorMode.name.hashCode()
        h = 31 * h + s.colorSeed
        h = 31 * h + (s.gradientToColorInt ?: 0)
        return h
    }

    /**
     * Persists a page's stroke rows. Returns a [StrokeGeometryGateResult]
     * metering whether the write was bounded by B2-DOS-01 (phase-50) caps —
     * callers surface a non-alarming notice when geometry was truncated/dropped
     * instead of letting a page grow without bound.
     *
     * B2-UI-3 (phase-73): the whole save runs under the page's per-page
     * [pageSaveLocks] mutex, so two concurrent same-page saves are serialized —
     * their [lastSavedStrokeHash] read-modify-write and their delete+upsert
     * transaction can never interleave (older snapshot landing last / dropping
     * rows / a corrupted diff cache).
     */
    suspend fun saveStrokesForPage(pageId: String, strokes: List<Stroke>): StrokeGeometryGateResult = withContext(Dispatchers.Default) {
        // B2-DOS-01 (phase-50): the ONLY place stroke geometry may be written.
        // Everything that reaches the encrypted pointsJson column already passed
        // StrokeGeometryPolicy: per-stroke point caps + the page total envelope.
        // A 2M-point stroke is truncated to its head; a page beyond
        // MAX_POINTS_PER_PAGE/MAX_STROKES_PER_PAGE has its overflowing strokes
        // dropped. The metering result lets the UI tell the user AT MOST once,
        // non-alarmingly, that the page was bounded.
        val gate = StrokeGeometryPolicy.applySaveGate(strokes)
        val lock = pageSaveLocks.computeIfAbsent(pageId) { Mutex() }
        lock.withLock {
            val gatedStrokes = gate.kept
            db.withTransaction {
                val storedIds = db.strokeDao().getStrokeIdsForPage(pageId).toHashSet()
                val incomingIds = HashSet<String>(gatedStrokes.size).apply { addAll(gatedStrokes.map { it.id }) }

                val removedIds = storedIds - incomingIds
                if (removedIds.isNotEmpty()) {
                    db.strokeDao().deleteStrokesByIds(removedIds.toList())
                    removedIds.forEach(lastSavedStrokeHash::remove)
                }

                val changed = gatedStrokes.filter { stroke ->
                    strokeContentHash(stroke) != lastSavedStrokeHash[stroke.id]
                }
                if (changed.isEmpty()) return@withTransaction

                val entities = changed.map { stroke ->
                    val rawText = stroke.text
                    // B2-CRYPTO-10 (phase-108): blank text is stored as a real AEAD
                    // payload, never raw "" — the row's blank-ness is always tagged.
                    // B2-UI-1 (phase-49): the DEK is grabbed once PER STROKE here
                    // (inside the change loop); a lock that fires mid-write throws
                    // fail-closed instead of storing plaintext, and the surrounding
                    // Room transaction rolls the whole page's write back.
                    val dek = requireEncryptionKey()
                    val storedText = EncryptionService.encryptField(rawText.toByteArray(), dek, "strokes", stroke.id, "textContent")

                    val dummyStroke = stroke.copy(text = "")
                    val pointsJson = EncryptionService.serializeStrokes(listOf(dummyStroke))
                    val storedPointsJson = EncryptionService.encryptField(pointsJson.toByteArray(), dek, "strokes", stroke.id, "pointsJson")

                    StrokeEntity(
                        id = stroke.id,
                        pageId = pageId,
                        toolName = stroke.tool.name,
                        colorInt = stroke.colorInt,
                        strokeWidth = stroke.width,
                        filled = stroke.filled,
                        textContent = storedText,
                        pointsJson = storedPointsJson,
                        startX = stroke.start?.x,
                        startY = stroke.start?.y,
                        endX = stroke.end?.x,
                        endY = stroke.end?.y,
                        pdfPage = stroke.pdfPage,
                        timestampMs = stroke.timestampMs,
                        layerId = stroke.layerId
                    )
                }
                db.strokeDao().insertStrokes(entities)
                val hashes = gatedStrokes.associateBy({ it.id }, ::strokeContentHash)
                entities.forEach { lastSavedStrokeHash[it.id] = hashes[it.id]!! }
            }
        }
        gate
    }

    private fun parseWaveformJson(json: String): List<Float> {
        if (json.isBlank() || json == "[]") return emptyList()
        // B2-DOS-03 (phase-79): the re-parse side of the finding must be BOUNDED
        // too — the recorder now only ever emits ≤ `recordingLiveBuckets` entries,
        // but a legacy vault or a crafted backup (B1-DB-7 restore path) can carry
        // an arbitrarily long `waveformJson`; never materialize more than the
        // stored-waveform ceiling into a List. Downstream (CanvasMediaEmbed,
        // render) therefore stays bounded regardless of the column length.
        //
        // R2-b2b5-FEA-06 (phase-152): org.json's getDouble() accepts `NaN`/
        // `Infinity` literals from a crafted stored `waveformJson`, and
        // {Float}toFloatOrNull also parses them — every non-finite sample is
        // replaced with 0.0f at PARSE time so it can never propagate into
        // geometry (`barHeight = canvasHeight * NaN` fed to drawRoundRect).
        return try {
            val arr = org.json.JSONArray(json)
            val n = minOf(arr.length(), VoiceRecordingPolicy.MAX_STORED_WAVEFORM_ENTRIES)
            List(n) { index -> WaveformPeakMath.finiteOrZero(arr.getDouble(index).toFloat()) }
        } catch (e: Exception) {
            json.removePrefix("[").removeSuffix("]").split(",")
                .take(VoiceRecordingPolicy.MAX_STORED_WAVEFORM_ENTRIES)
                .mapNotNull { it.trim().toFloatOrNull() }
                .map { WaveformPeakMath.finiteOrZero(it) }
        }
    }

    suspend fun getMediaEmbedsForPage(pageId: String): List<CanvasMediaEmbed> = withContext(Dispatchers.Default) {
        val entities = db.mediaEmbedDao().getMediaEmbedsForPage(pageId)
        if (entities.isEmpty()) return@withContext emptyList()

        entities.map { entity ->
            val type = try { MediaEmbedType.valueOf(entity.typeName) } catch (e: Exception) { MediaEmbedType.PHOTO }
            val waveformList = parseWaveformJson(entity.waveformJson)

            val text = entity.textContent ?: ""
            // B1-DB-8 (phase-88): an embed text that fails to decrypt renders the
            // explicit UNREADABLE_MARKER (never the raw ciphertext `catch { text }`
            // returned) and is recorded for the persistent ledger.
            val decryptedText = if (text.isNotBlank()) {
                decryptFieldForDisplay(text, "media_embeds", entity.id, "textContent", pageId)
            } else {
                text
            }

            CanvasMediaEmbed(
                id = entity.id,
                pageId = entity.pageId,
                type = type,
                x = entity.x,
                y = entity.y,
                width = entity.width,
                height = entity.height,
                contentUrlOrPath = entity.contentUrlOrPath,
                textContent = decryptedText,
                codeLanguage = entity.codeLanguage,
                durationMs = entity.durationMs,
                waveformAmplitudes = waveformList,
                pdfPage = entity.pdfPage,
                rotationDegrees = entity.rotationDegrees
            )
        }
    }

    suspend fun getNotebookCounts(notebookId: String): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val secCount = db.sectionDao().getSectionCountForNotebook(notebookId)
        val pageCount = db.pageDao().getPageCountForNotebook(notebookId)
        Pair(secCount, pageCount)
    }

    suspend fun getSectionCounts(sectionId: String): Int = withContext(Dispatchers.IO) {
        db.pageDao().getPageCountForSection(sectionId)
    }

    suspend fun getCanvasItemsForPage(pageId: String): Pair<List<CanvasStickyNote>, List<CanvasMediaEmbed>> = withContext(Dispatchers.Default) {
        val allEmbeds = getMediaEmbedsForPage(pageId)
        val stickyNotes = mutableListOf<CanvasStickyNote>()
        val mediaEmbeds = mutableListOf<CanvasMediaEmbed>()

        for (embed in allEmbeds) {
            if (embed.type == MediaEmbedType.STICKY_NOTE) {
                stickyNotes.add(
                    CanvasStickyNote(
                        id = embed.id,
                        x = embed.x,
                        y = embed.y,
                        width = if (embed.width > 0) embed.width else 220f,
                        height = if (embed.height > 0) embed.height else 180f,
                        text = embed.textContent ?: "",
                        colorHex = embed.codeLanguage ?: "#FEF08A",
                        pdfPage = embed.pdfPage,
                        isCollapsed = embed.contentUrlOrPath == "collapsed",
                        rotationDegrees = embed.rotationDegrees
                    )
                )
            } else if (embed.type != MediaEmbedType.REFERENCE_IMAGE) {
                // Phase 178: the reference-image underlay is NOT part of the
                // draggable canvas embed set — it is loaded on its own via
                // getReferenceImageForPage and rendered as the bottom canvas layer.
                mediaEmbeds.add(embed)
            }
        }
        Pair(stickyNotes, mediaEmbeds)
    }

    /**
     * Phase 178: reads the page's reference-image underlay row (at most one). The
     * row's `textContent` holds the field-encrypted opacity config (see
     * ReferenceImagePolicy.encodeConfig); failures render the UNREADABLE_MARKER
     * path like every other embed, and the caller falls back to the in-range
     * default opacity.
     */
    suspend fun getReferenceImageForPage(pageId: String): CanvasMediaEmbed? = withContext(Dispatchers.Default) {
        val entity = db.mediaEmbedDao().getReferenceImageForPage(pageId) ?: return@withContext null
        val stored = entity.textContent ?: ""
        val config = if (stored.isNotBlank()) {
            decryptFieldForDisplay(stored, "media_embeds", entity.id, "textContent", pageId)
        } else {
            stored
        }
        CanvasMediaEmbed(
            id = entity.id,
            pageId = entity.pageId,
            type = MediaEmbedType.REFERENCE_IMAGE,
            x = entity.x,
            y = entity.y,
            width = entity.width,
            height = entity.height,
            contentUrlOrPath = entity.contentUrlOrPath,
            textContent = config,
            pdfPage = entity.pdfPage
        )
    }

    /**
     * Phase 178: persists (or, with a null [embed], removes) the page's
     * reference-image underlay row. [embed] must carry the saved path in
     * [CanvasMediaEmbed.contentUrlOrPath] and the stored geometry in x/y/width/
     * height; the opacity is field-encrypted into textContent. A null [embed]
     * only ever deletes the row — the file itself is removed by the caller's
     * delete of the referenced artwork.
     */
    suspend fun saveReferenceImageForPage(pageId: String, embed: CanvasMediaEmbed?) = withContext(Dispatchers.Default) {
        db.withTransaction {
            db.mediaEmbedDao().deleteReferenceImagesForPage(pageId)
            if (embed == null) return@withTransaction
            val opacity = ReferenceImagePolicy.decodeOpacity(embed.textContent)
            val dek = requireEncryptionKey()
            val storedConfig = EncryptionService.encryptField(
                ReferenceImagePolicy.encodeConfig(opacity).toByteArray(),
                dek, "media_embeds", embed.id, "textContent"
            )
            db.mediaEmbedDao().insertMediaEmbeds(
                listOf(
                    MediaEmbedEntity(
                        id = embed.id,
                        pageId = pageId,
                        typeName = MediaEmbedType.REFERENCE_IMAGE.name,
                        x = embed.x,
                        y = embed.y,
                        width = embed.width,
                        height = embed.height,
                        contentUrlOrPath = embed.contentUrlOrPath,
                        textContent = storedConfig,
                        codeLanguage = null,
                        durationMs = 0L,
                        waveformJson = "[]",
                        pdfPage = embed.pdfPage,
                        rotationDegrees = 0f
                    )
                )
            )
        }
    }

    suspend fun saveCanvasItemsForPage(pageId: String, stickyNotes: List<CanvasStickyNote>, embeds: List<CanvasMediaEmbed>) = withContext(Dispatchers.Default) {
        val stickyAsEmbeds = stickyNotes.map { note ->
            CanvasMediaEmbed(
                id = note.id,
                pageId = pageId,
                type = MediaEmbedType.STICKY_NOTE,
                x = note.x,
                y = note.y,
                width = note.width,
                height = note.height,
                textContent = note.text,
                codeLanguage = note.colorHex,
                pdfPage = note.pdfPage,
                contentUrlOrPath = if (note.isCollapsed) "collapsed" else "expanded",
                rotationDegrees = note.rotationDegrees
            )
        }
        saveMediaEmbedsForPage(pageId, stickyAsEmbeds + embeds)
    }

    /**
     * Persists a page's stickies + media embeds (delete + reinsert inside one
     * transaction). B2-UI-3 (phase-73): runs under the page's per-page
     * [pageSaveLocks] mutex so two concurrent same-page saves can never
     * interleave their delete+reinsert rounds (one dropping the other's rows);
     * the full-page flush writes strokes → embeds → layers through the SAME lock,
     * keeping a page's whole snapshot atomic.
     */
    suspend fun saveMediaEmbedsForPage(pageId: String, embeds: List<CanvasMediaEmbed>) = withContext(Dispatchers.Default) {
        val lock = pageSaveLocks.computeIfAbsent(pageId) { Mutex() }
        lock.withLock {
            db.withTransaction {
                // Phase 178: the reference-image underlay is NOT in the editor's
                // embed set, so this delete-then-reinsert must carry the current
                // reference row forward or every page save would erase it. It is
                // re-inserted as a RAW entity pass-through (its textContent is the
                // already-encrypted config — re-encrypting it here would double-gauze
                // the ciphertext) AFTER the regular embeds re-insert their rows.
                val reference = db.mediaEmbedDao().getReferenceImageForPage(pageId)
                db.mediaEmbedDao().deleteMediaEmbedsForPage(pageId)
                if (embeds.isEmpty()) {
                    if (reference != null) db.mediaEmbedDao().insertMediaEmbeds(listOf(reference))
                    return@withTransaction
                }

                val entities = embeds.map { embed ->
                    val rawText = embed.textContent ?: ""
                    // B2-CRYPTO-10 (phase-108): blank embed text is tagged too.
                    // B2-UI-1 (phase-49): fail closed — never a plaintext embed body.
                    val dek = requireEncryptionKey()
                    val storedText = EncryptionService.encryptField(rawText.toByteArray(), dek, "media_embeds", embed.id, "textContent")

                    val waveformJson = embed.waveformAmplitudes.joinToString(prefix = "[", postfix = "]")

                    MediaEmbedEntity(
                        id = embed.id,
                        pageId = pageId,
                        typeName = embed.type.name,
                        x = embed.x,
                        y = embed.y,
                        width = embed.width,
                        height = embed.height,
                        contentUrlOrPath = embed.contentUrlOrPath,
                        textContent = storedText,
                        codeLanguage = embed.codeLanguage,
                        durationMs = embed.durationMs,
                        waveformJson = waveformJson,
                        pdfPage = embed.pdfPage,
                        rotationDegrees = embed.rotationDegrees
                    )
                }
                db.mediaEmbedDao().insertMediaEmbeds(entities)
                if (reference != null) db.mediaEmbedDao().insertMediaEmbeds(listOf(reference))
            }
        }
    }

    val allPaletteItems: Flow<List<PaletteItemEntity>> = db.paletteDao().getAllPaletteItems()

    suspend fun insertPaletteItem(item: PaletteItemEntity) = withContext(Dispatchers.IO) {
        db.paletteDao().insertPaletteItem(item)
    }

    suspend fun deletePaletteItem(id: String) = withContext(Dispatchers.IO) {
        db.paletteDao().deletePaletteItem(id)
    }

    suspend fun clearPaletteItemsByType(type: String) = withContext(Dispatchers.IO) {
        db.paletteDao().deletePaletteItemsByType(type)
    }

    suspend fun getLayersForPage(pageId: String): List<LayerEntity> = withContext(Dispatchers.IO) {
        // R2-b2b4-DOS-02 (phase-150): the LIVE read is bounded. The renderer
        // materializes one full-page ARGB_8888 bitmap per visible layer (~10.4 MB
        // at 1080x2400), so a crafted restore's `layers` table can never be handed
        // to the canvas whole. Keeps only the TOP
        // LayerRenderBudgetPolicy.MAX_LIVE_LAYER_COUNT layers per page by zOrder
        // (ties by rowid) and returns them in ascending zOrder so the editor sees
        // the same ordering as the pre-fix ASC read. Callers that care whether
        // layers were dropped use [getLayerCountForPage].
        val layers = db.layerDao()
            .getTopLayersForPageBounded(
                pageId,
                LayerRenderBudgetPolicy.MAX_LIVE_LAYER_COUNT
            )
            // Phase-150 review fix 2: run the pure policy model over the bounded
            // read so `capToLiveLimit` is LIVE code (exercised on every page load)
            // instead of a test-only mirror. The DAO query already limited to the
            // TOP-MAX_LIVE_LAYER_COUNT by zOrder/rowid, so for any ≤16-row page this
            // is a pass-through; for a crafted/legacy 40-row page both the model and
            // the SQL prune to the same deterministic keep-set (never reintroduces a
            // full-table read into the renderer path).
            .let { LayerRenderBudgetPolicy.capToLiveLimit(it) }
            .sortedBy { it.zOrder }
        if (layers.isEmpty()) {
            val defaultLayerId = "layer_$pageId"
            val defaultLayer = LayerEntity(
                id = defaultLayerId,
                pageId = pageId,
                name = "Layer 1",
                zOrder = 0,
                opacity = 1.0f,
                blendMode = "NORMAL",
                visible = true,
                locked = false
            )
            db.layerDao().insertLayer(defaultLayer)
            listOf(defaultLayer)
        } else {
            layers
        }
    }

    /**
     * R2-b2b4-DOS-02 (phase-150): raw `layers` row count for the one-time
     * non-alarming notice — a page whose count exceeds
     * [LayerRenderBudgetPolicy.MAX_LIVE_LAYER_COUNT] had crafted/legacy layers
     * folded onto the retained stack at load.
     */
    suspend fun getLayerCountForPage(pageId: String): Int = withContext(Dispatchers.IO) {
        db.layerDao().countLayersForPage(pageId)
    }

    /**
     * Persists a page's layers (delete + reinsert inside one transaction).
     * B2-UI-3 (phase-73): like the stroke + embed saves, this runs under the
     * page's per-page [pageSaveLocks] mutex so a concurrent same-page save can
     * never interleave its delete+reinsert round with another one.
     */
    suspend fun saveLayersForPage(pageId: String, layers: List<LayerEntity>) = withContext(Dispatchers.IO) {
        val lock = pageSaveLocks.computeIfAbsent(pageId) { Mutex() }
        lock.withLock {
            db.withTransaction {
                db.layerDao().deleteLayersForPage(pageId)
                db.layerDao().insertLayers(layers)
            }
        }
    }

    suspend fun saveLayer(layer: LayerEntity) = withContext(Dispatchers.IO) {
        db.layerDao().insertLayer(layer)
    }

    suspend fun deleteLayer(id: String) = withContext(Dispatchers.IO) {
        db.layerDao().deleteLayer(id)
    }

    suspend fun createNoteVersion(pageId: String, title: String, extractedText: String?, versionNote: String = "Saved version") = withContext(Dispatchers.IO) {
        // Phase-182: the marker overwrite guard extends to the VERSION SNAPSHOT.
        // The editor captures the DISPLAYED title/body of an unreadable page into
        // the version history (and from there into backup/HTML/Obsidian export
        // metadata), so persisting the literal render marker as a version's real
        // title/body would be the same data-loss path phase-169 closed for the
        // live page — the marker becomes real, legitimately-decryptable content.
        // Refuse loudly (trimmed, matching the updatePageBody guard) and leave the
        // original ciphertext intact.
        if (com.authorss81.noteflow.services.DecryptFailurePolicy.isUnreadableMarker(title.trim()) ||
            (extractedText != null && com.authorss81.noteflow.services.DecryptFailurePolicy.isUnreadableMarker(extractedText.trim()))
        ) {
            throw com.authorss81.noteflow.services.UnreadableContentWriteException()
        }
        val versionId = UUID.randomUUID().toString()
        val rawExtracted = extractedText ?: ""
        // B2-UI-1 (phase-49): fail closed — a note_versions body is encrypted at
        // rest; a locked vault throws rather than storing the raw plaintext body.
        val dek = requireEncryptionKey()
        val storedTitle = EncryptionService.encryptField(title.toByteArray(), dek, "note_versions", versionId, "title")
        // B2-CRYPTO-10 (phase-108): blank version snapshots are tagged too.
        val storedExtracted = EncryptionService.encryptField(rawExtracted.toByteArray(), dek, "note_versions", versionId, "extractedText")
        val version = NoteVersionEntity(
            id = versionId,
            pageId = pageId,
            title = storedTitle,
            extractedText = storedExtracted,
            timestampMs = System.currentTimeMillis(),
            versionNote = versionNote
        )
        // R2-b2b4-DOS-01 (phase-149): snapshots are written on every manual
        // save / autosave / before-translation-replace — the insert AND the
        // retention-cap prune must be atomic, so a rapid-fire save loop can
        // never accumulate an unbounded version table. The INSERT is the new
        // newest row; when the page has outgrown the cap the prune drops the
        // oldest past-cap rows, keeping only
        // NoteVersionRetentionPolicy.MAX_VERSIONS_PER_PAGE newest per page
        // (the COUNT keeps the DELETE cheap below the cap).
        db.withTransaction {
            db.noteVersionDao().insertVersion(version)
            if (NoteVersionRetentionPolicy.exceedsCap(db.noteVersionDao().countVersionsForPage(pageId))) {
                db.noteVersionDao().pruneVersionsForPage(pageId, NoteVersionRetentionPolicy.MAX_VERSIONS_PER_PAGE)
            }
        }
    }

    suspend fun getNoteVersions(pageId: String): List<NoteVersionEntity> = withContext(Dispatchers.IO) {
        // R2-b2b4-DOS-01 (phase-149): the history read is NEVER whole-table. The
        // initial window is one bounded LIMIT/OFFSET read
        // (NoteVersionRetentionPolicy.DECRYPT_BATCH_SIZE rows), decrypted row by
        // row; the Version History bottom sheet streams further windows via
        // [getNoteVersionsPaged] as the list scrolls, so no path ever decrypts
        // the whole history in one heap read. A crafted page with thousands of
        // snapshots can no longer OOM the process on history open.
        db.noteVersionDao().getVersionsForPagePaged(
            pageId,
            NoteVersionRetentionPolicy.DECRYPT_BATCH_SIZE,
            0
        ).map { v -> decryptVersionForDisplay(v) }
    }

    /**
     * R2-b2b4-DOS-01 (phase-149): a single bounded newest-first window of a
     * page's version history. The lazily-materializing VersionHistoryBottomSheet
     * fetches the initial window via [getNoteVersions] (the pinned guarded read)
     * and streams further windows here as the list scrolls — only the visible
     * window is decrypted at any moment.
     */
    suspend fun getNoteVersionsPaged(pageId: String, limit: Int, offset: Int): List<NoteVersionEntity> = withContext(Dispatchers.IO) {
        db.noteVersionDao().getVersionsForPagePaged(pageId, limit, offset).map { v ->
            decryptVersionForDisplay(v)
        }
    }

    /**
     * R2-b2b4-DOS-01 (phase-149): the single decrypt decision for one version
     * row, shared by the paged full read and the lazy window read.
     * B1-DB-8 (phase-88): version title/body decrypt failures render the
     * explicit UNREADABLE_MARKER via the single policy decision.
     */
    private fun decryptVersionForDisplay(v: NoteVersionEntity): NoteVersionEntity {
        val decTitle = if (v.title.isNotBlank()) {
            decryptFieldForDisplay(v.title, "note_versions", v.id, "title", v.pageId)
        } else {
            v.title
        }
        val decText = if (!v.extractedText.isNullOrBlank()) {
            decryptFieldForDisplay(v.extractedText, "note_versions", v.id, "extractedText", v.pageId)
        } else {
            v.extractedText
        }
        return v.copy(title = decTitle, extractedText = decText)
    }

    /**
     * B1-DB-8 (phase-88): rewired page decrypt. The pre-fix body threw the whole
     * read and returned the page UNCHANGED — i.e. `title`/`extractedText`
     * rendered as raw base64 AES-GCM blobs. Now the title and extractedText
     * follow the single policy decision table: genuine ciphertext that fails to
     * authenticate renders [DecryptFailurePolicy.UNREADABLE_MARKER] (never the
     * blob), and the failure is recorded for the persistent ledger. This applies
     * even with a zeroized DEK (phase-88 review fix): the old `encryptionKey ==
     * null` early-return passed the raw encrypted page through, unlike the
     * strokes/embeds/versions sinks, which already rendered the marker.
     *
     * Phase-207: the result is MEMOIZED in [displayPageCache], keyed by
     * `(pageId, sha256(title ciphertext), sha256(extracted ciphertext))`. Room's
     * invalidation tracker is TABLE-granular, so every debounced keystroke save
     * re-emits all four page flows at once; without this each emission
     * re-decrypted title+body of EVERY row. A hit requires BOTH ciphertext keys
     * to match the memoized ones, so any rewritten field misses and re-decrypts
     * — stale plaintext can never be served. Entries hold decrypted content and
     * are dropped wholesale by [clearPlaintextCaches] at the lock/re-key key
     * boundary (no plaintext survives a lock). Ledger note: a cached marker
     * skips only a redundant add of an already-recorded note id (the ledger is a
     * dedup set), so escalation semantics are unchanged.
     */
    private fun decryptPageIfNeeded(page: NotePageEntity): NotePageEntity {
        val titleKey = DecryptedPageCache.fieldKeyOf(page.title)
        val extractedKey = DecryptedPageCache.fieldKeyOf(page.extractedText)
        val memoized = displayPageCache.lookup(page.id, titleKey, extractedKey)
        if (memoized != null) return page.copy(title = memoized.decryptedTitle, extractedText = memoized.decryptedExtracted)
        val decryptedTitle = decryptFieldForDisplay(page.title, "pages", page.id, "title", page.id)
        val decryptedExtracted = if (!page.extractedText.isNullOrBlank()) {
            decryptFieldForDisplay(page.extractedText, "pages", page.id, "extractedText", page.id)
        } else {
            page.extractedText
        }
        displayPageCache.put(page.id, titleKey, extractedKey, decryptedTitle, decryptedExtracted)
        return page.copy(title = decryptedTitle, extractedText = decryptedExtracted)
    }

    /**
     * B1-DB-8 (phase-88 review fix): decrypt for the SEARCH CORPUS only — a page
     * that fails to authenticate is dropped from the corpus (null), so an
     * undecryptable note can never surface as a searchable/rankable
     * "Unreadable (decryption failed)" marker, and — because this is a
     * filter/rank context, not a display sink — the failure is NOT recorded in
     * the persistent ledger (it inflates the count a second time on top of the
     * Home-list read and would double-trip the threshold). Legacy plaintext and
     * authenticated ciphertext render exactly as in [decryptPageIfNeeded].
     */
    private fun decryptPageOrNullForCorpus(page: NotePageEntity): NotePageEntity? {
        val decryptedTitle = decryptFieldForDisplay(page.title, "pages", page.id, "title", page.id, recordFailures = false)
        if (decryptedTitle == DecryptFailurePolicy.UNREADABLE_MARKER) return null
        val decryptedExtracted = if (!page.extractedText.isNullOrBlank()) {
            decryptFieldForDisplay(page.extractedText, "pages", page.id, "extractedText", page.id, recordFailures = false)
        } else {
            page.extractedText
        }
        if (decryptedExtracted == DecryptFailurePolicy.UNREADABLE_MARKER) return null
        return page.copy(title = decryptedTitle, extractedText = decryptedExtracted)
    }
}

/**
 * B1-DB-4 (phase-44): outcome of the one-time legacy plaintext-body sweep
 * ([NoteRepository.migrateLegacyPlaintextNoteBodies]). [rowsMigrated] counts
 * bodies copied from a plaintext file into the encrypted column, [filesDeleted]
 * the plaintext source files removed, [filesRemaining] files that could not be
 * deleted (or whose column was undecryptable) — the migration flag stays unset
 * while this is > 0 so the sweep re-runs on a later unlock.
 */
data class LegacyBodyMigrationResult(
    val rowsMigrated: Int,
    val filesDeleted: Int,
    val filesRemaining: Int
) {
    val isComplete: Boolean get() = filesRemaining == 0
}

/**
 * B1-DB-3 (phase-54): outcome of the one-time legacy plaintext voice-note sweep
 * ([NoteRepository.migrateLegacyPlaintextVoiceNotes]) and the record path that
 * produced it. [rowsMigrated] counts embeds retargeted `.m4a` → `.enc`,
 * [orphansDeleted] plaintext recordings with no DB row that were removed,
 * [plaintextRemaining] referenced `.m4a` files that could not yet be encrypted
 * (locked vault / IO failure) — the migration flag stays unset while this is >
 * 0 so the sweep re-runs on a later unlock.
 */
data class VoiceNoteMigrationResult(
    val rowsMigrated: Int,
    val orphansDeleted: Int,
    val plaintextRemaining: Int
) {
    val isComplete: Boolean get() = plaintextRemaining == 0
}
