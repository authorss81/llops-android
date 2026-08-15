package com.authorss81.noteflow.data.repository

import androidx.room.withTransaction
import com.authorss81.noteflow.data.db.NoteflowDatabase
import com.authorss81.noteflow.data.model.*
import com.authorss81.noteflow.services.DatabaseSecurityHelper
import com.authorss81.noteflow.services.EncryptionService
import com.authorss81.noteflow.services.NoteBodyVaultPolicy
import com.authorss81.noteflow.services.VaultKeyHolder
import com.authorss81.noteflow.services.VaultWriteGate
import com.authorss81.noteflow.services.WikiLinkParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class NoteRepository(private var db: NoteflowDatabase) {
    var encryptionKey: ByteArray?
        get() = VaultKeyHolder.dek
        set(value) {
            VaultKeyHolder.dek = value
            invalidateSearchCorpus()
        }

    fun zeroizeKey() {
        VaultKeyHolder.zeroize()
        invalidateSearchCorpus()
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

    /**
     * In-memory cache of decrypted active pages (title + extractedText) used by
     * vault search. Loaded once and reused across keystrokes so per-keystroke
     * search does not decrypt the entire vault; invalidated on any page mutation
     * or when the vault is locked/re-keyed.
     */
    @Volatile
    private var cachedSearchCorpus: List<NotePageEntity>? = null

    private val searchCorpusLock = Any()

    @Volatile
    private var searchCorpusGeneration = 0L

    /**
     * Loading the full decrypted corpus is only cached below this size. Above it,
     * search decrypts per query so a huge vault can't pin an unbounded plaintext
     * snapshot in memory for the whole unlocked session.
     */
    private val searchCorpusMaxPages = 1500

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
            cachedSearchCorpus = null
        }
        // B2-DOS-11: the WikiLink/tag builders must not serve a scan from a previous
        // unlock epoch — this hook fires on lock, key replacement and every page
        // mutation, which is exactly the "per unlock epoch" cache boundary.
        WikiLinkParser.invalidateCaches()
    }

    private suspend fun loadSearchCorpus(): List<NotePageEntity> {
        while (true) {
            synchronized(searchCorpusLock) {
                cachedSearchCorpus?.let { return it }
            }
            val generationAtStart = synchronized(searchCorpusLock) { searchCorpusGeneration }
            val corpus = db.pageDao().getAllActivePages().map { decryptPageIfNeeded(it) }
            synchronized(searchCorpusLock) {
                if (generationAtStart == searchCorpusGeneration) {
                    if (corpus.size <= searchCorpusMaxPages) {
                        cachedSearchCorpus = corpus
                    }
                    return corpus
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
            db.noteVersionDao().getAllVersionsForReencrypt().forEach { version ->
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
            db.noteVersionDao().getAllVersionsForReencrypt().forEach { version ->
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

    suspend fun searchPages(query: String): List<NotePageEntity> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val allPages = loadSearchCorpus()
        val q = query.trim()
        allPages.filter { page ->
            page.title.contains(q, ignoreCase = true) ||
            (page.extractedText?.contains(q, ignoreCase = true) == true)
        }
    }

    suspend fun updatePageTemplate(id: String, template: String) = withContext(Dispatchers.Default) {
        db.pageDao().updatePageTemplate(id, template)
    }

    suspend fun updatePagePaperColor(id: String, paperColor: String?) = withContext(Dispatchers.Default) {
        db.pageDao().updatePagePaperColor(id, paperColor)
    }

    suspend fun updatePageSource(id: String, sourceFilePath: String?, sourceFileType: String?) = withContext(Dispatchers.Default) {
        db.pageDao().updatePageSource(id, sourceFilePath, sourceFileType)
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
            val path = page.sourceFilePath ?: return@forEach
            val file = File(path)
            if (!file.exists()) return@forEach

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

            val fileBody = try { file.readText() } catch (e: Exception) { return@forEach }
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
            sourceFilePath = sourceFilePath,
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
        db.strokeDao().deleteStrokesForPage(id)
        db.layerDao().deleteLayersForPage(id)
        db.mediaEmbedDao().deleteMediaEmbedsForPage(id)
        db.pageDao().deletePagePermanently(id)
        invalidateSearchCorpus()
    }

    suspend fun emptyTrash() {
        val trashed = db.pageDao().getTrashedPagesOnce()
        for (page in trashed) {
            deletePagePermanently(page.id)
        }
    }

    suspend fun getStrokesForPage(pageId: String): List<Stroke> = withContext(Dispatchers.Default) {
        val strokeEntities = db.strokeDao().getStrokesForPage(pageId)
        if (strokeEntities.isEmpty()) return@withContext emptyList()

        val loaded = strokeEntities.map { entity ->
            val rawText = entity.textContent
            val decryptedText = if (encryptionKey != null && rawText.isNotBlank()) {
                try {
                    String(EncryptionService.decryptField(rawText, encryptionKey!!, "strokes", entity.id, "textContent"))
                } catch (e: Exception) {
                    rawText
                }
            } else {
                rawText
            }

            val rawPointsJson = entity.pointsJson
            val decryptedPointsJson = if (encryptionKey != null && rawPointsJson.isNotBlank()) {
                try {
                    String(EncryptionService.decryptField(rawPointsJson, encryptionKey!!, "strokes", entity.id, "pointsJson"))
                } catch (e: Exception) {
                    rawPointsJson
                }
            } else {
                rawPointsJson
            }
            val deserializedStrokes = EncryptionService.deserializeStrokes(decryptedPointsJson)
            val firstDeserialized = deserializedStrokes.firstOrNull()
            val points = deserializedStrokes.flatMap { it.points }
            val start = if (entity.startX != null && entity.startY != null) PointF(entity.startX, entity.startY) else null
            val end = if (entity.endX != null && entity.endY != null) PointF(entity.endX, entity.endY) else null
            val isAdvanced = firstDeserialized?.isAdvanced ?: false
            // Phase 27: color-mode fields round-trip through the stroke's serialized
            // payload (pointsJson). Missing on old strokes => SOLID / seed 0, which is
            // bit-identical to the pre-phase-27 behaviour.
            val colorMode = com.authorss81.noteflow.data.model.StrokeColorMode.fromKey(firstDeserialized?.colorMode?.persistenceKey)
            val colorSeed = firstDeserialized?.colorSeed ?: 0
            val gradientToColorInt = firstDeserialized?.gradientToColorInt

            Stroke(
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
     */
    private val lastSavedStrokeHash = LruBoundedMap<String, Int>(MAX_LAST_SAVED_STROKE_HASH_ENTRIES)

    /**
     * Upper bound for [lastSavedStrokeHash]. A stroke UUID key + int hash is a
     * few tens of bytes; 10k entries keep the diff cache a few hundred KB even
     * for vaults with tens of thousands of strokes, and eviction only forces a
     * redundant re-write the next time a cold stroke is saved.
     */
    private companion object {
        const val MAX_LAST_SAVED_STROKE_HASH_ENTRIES = 10_000
    }

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

    suspend fun saveStrokesForPage(pageId: String, strokes: List<Stroke>) = withContext(Dispatchers.Default) {
        db.withTransaction {
            val storedIds = db.strokeDao().getStrokeIdsForPage(pageId).toHashSet()
            val incomingIds = HashSet<String>(strokes.size).apply { addAll(strokes.map { it.id }) }

            val removedIds = storedIds - incomingIds
            if (removedIds.isNotEmpty()) {
                db.strokeDao().deleteStrokesByIds(removedIds.toList())
                removedIds.forEach(lastSavedStrokeHash::remove)
            }

            val changed = strokes.filter { stroke ->
                strokeContentHash(stroke) != lastSavedStrokeHash[stroke.id]
            }
            if (changed.isEmpty()) return@withTransaction

            val entities = changed.map { stroke ->
                val rawText = stroke.text
                // B2-CRYPTO-10 (phase-108): blank text is stored as a real AEAD
                // payload, never raw "" — the row's blank-ness is always tagged.
                // B2-UI-1 (phase-49): the DEK is grabbed ONCE here; a lock that
                // fires mid-write throws fail-closed instead of storing plaintext.
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
            val hashes = strokes.associateBy({ it.id }, ::strokeContentHash)
            entities.forEach { lastSavedStrokeHash[it.id] = hashes[it.id]!! }
        }
    }

    private fun parseWaveformJson(json: String): List<Float> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            List(arr.length()) { index -> arr.getDouble(index).toFloat() }
        } catch (e: Exception) {
            json.removePrefix("[").removeSuffix("]").split(",")
                .mapNotNull { it.trim().toFloatOrNull() }
        }
    }

    suspend fun getMediaEmbedsForPage(pageId: String): List<CanvasMediaEmbed> = withContext(Dispatchers.Default) {
        val entities = db.mediaEmbedDao().getMediaEmbedsForPage(pageId)
        if (entities.isEmpty()) return@withContext emptyList()

        entities.map { entity ->
            val type = try { MediaEmbedType.valueOf(entity.typeName) } catch (e: Exception) { MediaEmbedType.PHOTO }
            val waveformList = parseWaveformJson(entity.waveformJson)

            val text = entity.textContent ?: ""
            val decryptedText = if (encryptionKey != null && text.isNotBlank()) {
                try {
                    String(EncryptionService.decryptField(text, encryptionKey!!, "media_embeds", entity.id, "textContent"))
                } catch (e: Exception) { text }
            } else text

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
            } else {
                mediaEmbeds.add(embed)
            }
        }
        Pair(stickyNotes, mediaEmbeds)
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

    suspend fun saveMediaEmbedsForPage(pageId: String, embeds: List<CanvasMediaEmbed>) = withContext(Dispatchers.Default) {
        db.withTransaction {
            db.mediaEmbedDao().deleteMediaEmbedsForPage(pageId)
            if (embeds.isEmpty()) return@withTransaction

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
        val layers = db.layerDao().getLayersForPage(pageId)
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

    suspend fun saveLayersForPage(pageId: String, layers: List<LayerEntity>) = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.layerDao().deleteLayersForPage(pageId)
            db.layerDao().insertLayers(layers)
        }
    }

    suspend fun saveLayer(layer: LayerEntity) = withContext(Dispatchers.IO) {
        db.layerDao().insertLayer(layer)
    }

    suspend fun deleteLayer(id: String) = withContext(Dispatchers.IO) {
        db.layerDao().deleteLayer(id)
    }

    suspend fun createNoteVersion(pageId: String, title: String, extractedText: String?, versionNote: String = "Saved version") = withContext(Dispatchers.IO) {
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
        db.noteVersionDao().insertVersion(version)
    }

    suspend fun getNoteVersions(pageId: String): List<NoteVersionEntity> = withContext(Dispatchers.IO) {
        val versions = db.noteVersionDao().getVersionsForPage(pageId)
        versions.map { v ->
            val decTitle = encryptionKey?.let { key -> EncryptionService.decryptFieldOrNull(v.title, key, "note_versions", v.id, "title") } ?: v.title
            val decText = if (encryptionKey != null && !v.extractedText.isNullOrBlank()) {
                EncryptionService.decryptFieldOrNull(v.extractedText, encryptionKey!!, "note_versions", v.id, "extractedText") ?: v.extractedText
            } else {
                v.extractedText
            }
            v.copy(title = decTitle, extractedText = decText)
        }
    }

    private fun decryptPageIfNeeded(page: NotePageEntity): NotePageEntity {

        if (encryptionKey == null) return page
        return try {
            val decryptedTitle = String(EncryptionService.decryptField(page.title, encryptionKey!!, "pages", page.id, "title"))
            val decryptedExtracted = if (!page.extractedText.isNullOrBlank()) {
                try {
                    String(EncryptionService.decryptField(page.extractedText, encryptionKey!!, "pages", page.id, "extractedText"))
                } catch (e: Exception) {
                    page.extractedText
                }
            } else {
                page.extractedText
            }
            page.copy(title = decryptedTitle, extractedText = decryptedExtracted)
        } catch (e: Exception) {
            page
        }
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
