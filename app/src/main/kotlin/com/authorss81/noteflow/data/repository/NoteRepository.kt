package com.authorss81.noteflow.data.repository

import androidx.room.withTransaction
import com.authorss81.noteflow.data.db.NoteflowDatabase
import com.authorss81.noteflow.data.model.*
import com.authorss81.noteflow.services.DatabaseSecurityHelper
import com.authorss81.noteflow.services.EncryptionService
import com.authorss81.noteflow.services.VaultKeyHolder
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

    private fun invalidateSearchCorpus() {
        synchronized(searchCorpusLock) {
            searchCorpusGeneration++
            cachedSearchCorpus = null
        }
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

    private fun isFieldEncrypted(value: String, key: ByteArray): Boolean {
        if (value.isBlank()) return true // nothing to encrypt
        return try {
            EncryptionService.decrypt(value, key)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * One-time pass: encrypts any plaintext title/extractedText/textContent rows
     * with the given DEK. Called after setting a master password so pre-existing
     * data is not left in the clear (previously only new writes were encrypted).
     */
    suspend fun reencryptPlaintextFields(dek: ByteArray) = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.pageDao().getAllPagesForReencrypt().forEach { page ->
                var title = page.title
                var extracted = page.extractedText
                var dirty = false
                if (title.isNotBlank() && !isFieldEncrypted(title, dek)) {
                    title = EncryptionService.encrypt(title.toByteArray(), dek)
                    dirty = true
                }
                if (!extracted.isNullOrBlank() && !isFieldEncrypted(extracted, dek)) {
                    extracted = EncryptionService.encrypt(extracted.toByteArray(), dek)
                    dirty = true
                }
                if (dirty) db.pageDao().updateEncryptedFields(page.id, title, extracted)
            }
            db.strokeDao().getAllStrokesForReencrypt().forEach { stroke ->
                val text = stroke.textContent
                val points = stroke.pointsJson
                var dirty = false
                var newText = text
                var newPoints = points
                if (!text.isNullOrBlank() && !isFieldEncrypted(text, dek)) {
                    newText = EncryptionService.encrypt(text.toByteArray(), dek)
                    dirty = true
                }
                if (!points.isNullOrBlank() && !isFieldEncrypted(points, dek)) {
                    newPoints = EncryptionService.encrypt(points.toByteArray(), dek)
                    dirty = true
                }
                if (dirty) {
                    db.strokeDao().updateStrokeFields(stroke.id, newText, newPoints)
                }
            }
            db.mediaEmbedDao().getAllEmbedsForReencrypt().forEach { embed ->
                val text = embed.textContent
                if (!text.isNullOrBlank() && !isFieldEncrypted(text, dek)) {
                    val encrypted = EncryptionService.encrypt(text.toByteArray(), dek)
                    db.mediaEmbedDao().updateTextContent(embed.id, encrypted)
                }
            }
            // C1 (phase-09): note_versions.title/extractedText are field-encrypted
            // at write (createNoteVersion) and re-keyed on cross-device restore
            // (fieldEncryptedColumns), but the local plaintext sweep was missing
            // them — any legacy plaintext version snapshot written before a master
            // password existed stayed in the clear at rest.
            db.noteVersionDao().getAllVersionsForReencrypt().forEach { version ->
                val title = version.title
                val extracted = version.extractedText
                var dirty = false
                var newTitle = title
                var newExtracted = extracted
                if (title.isNotBlank() && !isFieldEncrypted(title, dek)) {
                    newTitle = EncryptionService.encrypt(title.toByteArray(), dek)
                    dirty = true
                }
                if (!extracted.isNullOrBlank() && !isFieldEncrypted(extracted, dek)) {
                    newExtracted = EncryptionService.encrypt(extracted.toByteArray(), dek)
                    dirty = true
                }
                if (dirty) {
                    db.noteVersionDao().updateVersionFields(version.id, newTitle, newExtracted)
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
        val storedTitle = encryptionKey?.let { EncryptionService.encrypt(rawTitle.toByteArray(), it) } ?: rawTitle
        val rawExtracted = extractedText ?: ""
        val storedExtracted = if (encryptionKey != null && rawExtracted.isNotBlank()) {
            EncryptionService.encrypt(rawExtracted.toByteArray(), encryptionKey!!)
        } else {
            rawExtracted
        }
        val page = NotePageEntity(
            id = UUID.randomUUID().toString(),
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
        val storedTitle = encryptionKey?.let { EncryptionService.encrypt(rawTitle.toByteArray(), it) } ?: rawTitle
        db.pageDao().renamePage(id, storedTitle)
        invalidateSearchCorpus()
    }

    suspend fun updatePageTags(id: String, tags: String) = withContext(Dispatchers.Default) {
        db.pageDao().updatePageTags(id, tags.trim())
        invalidateSearchCorpus()
    }

    suspend fun updatePageTitleAndTags(id: String, title: String, tags: String) = withContext(Dispatchers.Default) {
        val rawTitle = title.trim()
        val storedTitle = encryptionKey?.let { EncryptionService.encrypt(rawTitle.toByteArray(), it) } ?: rawTitle
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
                    String(EncryptionService.decrypt(rawText, encryptionKey!!))
                } catch (e: Exception) {
                    rawText
                }
            } else {
                rawText
            }

            val rawPointsJson = entity.pointsJson
            val decryptedPointsJson = if (encryptionKey != null && rawPointsJson.isNotBlank()) {
                try {
                    String(EncryptionService.decrypt(rawPointsJson, encryptionKey!!))
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
                layerId = entity.layerId
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
     */
    private val lastSavedStrokeHash = mutableMapOf<String, Int>()

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
                val storedText = if (encryptionKey != null && rawText.isNotBlank()) {
                    EncryptionService.encrypt(rawText.toByteArray(), encryptionKey!!)
                } else {
                    rawText
                }

                val dummyStroke = stroke.copy(text = "")
                val pointsJson = EncryptionService.serializeStrokes(listOf(dummyStroke))
                val storedPointsJson = if (encryptionKey != null && pointsJson.isNotBlank()) {
                    EncryptionService.encrypt(pointsJson.toByteArray(), encryptionKey!!)
                } else {
                    pointsJson
                }

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
                    String(EncryptionService.decrypt(text, encryptionKey!!))
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
                pdfPage = entity.pdfPage
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
                        isCollapsed = embed.contentUrlOrPath == "collapsed"
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
                contentUrlOrPath = if (note.isCollapsed) "collapsed" else "expanded"
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
                val storedText = if (encryptionKey != null && rawText.isNotBlank()) {
                    EncryptionService.encrypt(rawText.toByteArray(), encryptionKey!!)
                } else {
                    rawText
                }

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
                    pdfPage = embed.pdfPage
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
        val storedTitle = encryptionKey?.let { EncryptionService.encrypt(title.toByteArray(), it) } ?: title
        val rawExtracted = extractedText ?: ""
        val storedExtracted = if (encryptionKey != null && rawExtracted.isNotBlank()) {
            EncryptionService.encrypt(rawExtracted.toByteArray(), encryptionKey!!)
        } else {
            rawExtracted
        }
        val version = NoteVersionEntity(
            id = UUID.randomUUID().toString(),
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
            val decTitle = encryptionKey?.let { key -> EncryptionService.decryptOrNull(v.title, key) } ?: v.title
            val decText = if (encryptionKey != null && !v.extractedText.isNullOrBlank()) {
                EncryptionService.decryptOrNull(v.extractedText, encryptionKey!!) ?: v.extractedText
            } else {
                v.extractedText
            }
            v.copy(title = decTitle, extractedText = decText)
        }
    }

    private fun decryptPageIfNeeded(page: NotePageEntity): NotePageEntity {

        if (encryptionKey == null) return page
        return try {
            val decryptedTitle = String(EncryptionService.decrypt(page.title, encryptionKey!!))
            val decryptedExtracted = if (!page.extractedText.isNullOrBlank()) {
                try {
                    String(EncryptionService.decrypt(page.extractedText, encryptionKey!!))
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
