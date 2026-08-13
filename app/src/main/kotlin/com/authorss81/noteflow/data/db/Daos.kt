package com.authorss81.noteflow.data.db

import androidx.room.*
import com.authorss81.noteflow.data.model.MediaEmbedEntity
import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.data.model.NotebookEntity
import com.authorss81.noteflow.data.model.SectionEntity
import com.authorss81.noteflow.data.model.StrokeEntity
import com.authorss81.noteflow.data.model.PaletteItemEntity
import com.authorss81.noteflow.data.model.LayerEntity
import com.authorss81.noteflow.data.model.NoteVersionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NotebookDao {
    @Query("SELECT * FROM notebooks ORDER BY createdAt ASC")
    fun getAllNotebooks(): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks ORDER BY createdAt ASC")
    suspend fun getAllNotebooksOnce(): List<NotebookEntity>

    @Query("SELECT * FROM notebooks WHERE id = :id LIMIT 1")
    suspend fun getNotebookById(id: String): NotebookEntity?

    @Upsert
    suspend fun insertNotebook(notebook: NotebookEntity)

    @Query("UPDATE notebooks SET name = :name WHERE id = :id")
    suspend fun renameNotebook(id: String, name: String)

    @Query("UPDATE notebooks SET tags = :tags WHERE id = :id")
    suspend fun updateNotebookTags(id: String, tags: String)

    @Query("UPDATE notebooks SET name = :name, tags = :tags WHERE id = :id")
    suspend fun updateNotebookNameAndTags(id: String, name: String, tags: String)

    @Query("DELETE FROM notebooks WHERE id = :id")
    suspend fun deleteNotebook(id: String)
}

@Dao
interface SectionDao {
    @Query("SELECT * FROM sections WHERE notebookId = :notebookId ORDER BY createdAt ASC")
    fun getSectionsForNotebook(notebookId: String): Flow<List<SectionEntity>>

    @Query("SELECT * FROM sections ORDER BY createdAt ASC")
    fun getAllSections(): Flow<List<SectionEntity>>

    @Query("SELECT id FROM sections WHERE notebookId = :notebookId")
    suspend fun getSectionIdsForNotebook(notebookId: String): List<String>

    @Query("SELECT * FROM sections WHERE id = :id LIMIT 1")
    suspend fun getSectionById(id: String): SectionEntity?

    @Upsert
    suspend fun insertSection(section: SectionEntity)

    @Query("UPDATE sections SET name = :name WHERE id = :id")
    suspend fun renameSection(id: String, name: String)

    @Query("DELETE FROM sections WHERE id = :id")
    suspend fun deleteSection(id: String)

    @Query("DELETE FROM sections WHERE notebookId = :notebookId")
    suspend fun deleteSectionsByNotebook(notebookId: String)

    @Query("SELECT COUNT(*) FROM sections WHERE notebookId = :notebookId")
    suspend fun getSectionCountForNotebook(notebookId: String): Int
}

@Dao
interface NotePageDao {
    @Query("SELECT * FROM pages WHERE deleted = 0 ORDER BY pinned DESC, updatedAt DESC")
    fun getAllActivePagesFlow(): Flow<List<NotePageEntity>>

    @Query("SELECT COUNT(*) FROM pages WHERE sectionId = :sectionId AND deleted = 0")
    suspend fun getPageCountForSection(sectionId: String): Int

    @Query("SELECT COUNT(*) FROM pages WHERE sectionId IN (SELECT id FROM sections WHERE notebookId = :notebookId) AND deleted = 0")
    suspend fun getPageCountForNotebook(notebookId: String): Int

    @Query("SELECT * FROM pages WHERE sectionId = :sectionId AND deleted = 0 ORDER BY pinned DESC, updatedAt DESC")
    fun getPagesForSection(sectionId: String): Flow<List<NotePageEntity>>

    @Query("SELECT id FROM pages WHERE sectionId = :sectionId")
    suspend fun getPageIdsForSection(sectionId: String): List<String>

    @Query("SELECT * FROM pages WHERE deleted = 0 ORDER BY updatedAt DESC LIMIT 20")
    fun getRecentPages(): Flow<List<NotePageEntity>>

    @Query("SELECT * FROM pages WHERE deleted = 1 ORDER BY updatedAt DESC")
    fun getTrashedPages(): Flow<List<NotePageEntity>>

    @Query("SELECT * FROM pages WHERE deleted = 1")
    suspend fun getTrashedPagesOnce(): List<NotePageEntity>

    @Query("SELECT * FROM pages WHERE id = :id LIMIT 1")
    suspend fun getPageById(id: String): NotePageEntity?

    @Query("SELECT * FROM pages WHERE deleted = 0 ORDER BY updatedAt DESC")
    suspend fun getAllActivePages(): List<NotePageEntity>

    @Query("SELECT * FROM pages WHERE sectionId = :sectionId AND deleted = 0 ORDER BY pageIndex ASC, createdAt ASC")
    suspend fun getPagesForSectionOnce(sectionId: String): List<NotePageEntity>

    @Query("SELECT * FROM pages WHERE sectionId IN (SELECT id FROM sections WHERE notebookId = :notebookId) AND deleted = 0 ORDER BY updatedAt DESC")
    suspend fun getPagesForNotebookOnce(notebookId: String): List<NotePageEntity>

    @Query("UPDATE pages SET sourceFilePath = :sourceFilePath, sourceFileType = :sourceFileType, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePageSource(id: String, sourceFilePath: String?, sourceFileType: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM pages WHERE deleted = 0 AND title LIKE '%' || :query || '%' ORDER BY updatedAt DESC")
    suspend fun searchPages(query: String): List<NotePageEntity>

    @Query("UPDATE pages SET template = :template, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePageTemplate(id: String, template: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE pages SET paperColor = :paperColor, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePagePaperColor(id: String, paperColor: String?, updatedAt: Long = System.currentTimeMillis())

    @Upsert
    suspend fun insertPage(page: NotePageEntity)

    @Query("UPDATE pages SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun renamePage(id: String, title: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE pages SET tags = :tags, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePageTags(id: String, tags: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE pages SET title = :title, tags = :tags, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePageTitleAndTags(id: String, title: String, tags: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE pages SET pinned = :pinned WHERE id = :id")
    suspend fun togglePin(id: String, pinned: Boolean)

    @Query("UPDATE pages SET deleted = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun trashPage(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE pages SET deleted = 0, updatedAt = :updatedAt WHERE id = :id")
    suspend fun restorePage(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE pages SET sectionId = :sectionId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun movePage(id: String, sectionId: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE pages SET pageIndex = :pageIndex, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePageIndex(id: String, pageIndex: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM pages WHERE id = :id")
    suspend fun deletePagePermanently(id: String)

    @Query("DELETE FROM pages WHERE deleted = 1")
    suspend fun emptyTrash()

    @Query("DELETE FROM pages WHERE sectionId = :sectionId")
    suspend fun deletePagesBySection(sectionId: String)

    @Query("SELECT * FROM pages")
    suspend fun getAllPagesForReencrypt(): List<NotePageEntity>

    @Query("UPDATE pages SET title = :title, extractedText = :extractedText WHERE id = :id")
    suspend fun updateEncryptedFields(id: String, title: String, extractedText: String?)
}

@Dao
interface StrokeDao {
    @Query("SELECT * FROM strokes WHERE pageId = :pageId ORDER BY ROWID ASC")
    suspend fun getStrokesForPage(pageId: String): List<StrokeEntity>

    @Query("SELECT id FROM strokes WHERE pageId = :pageId")
    suspend fun getStrokeIdsForPage(pageId: String): List<String>

    @Upsert
    suspend fun insertStrokes(strokes: List<StrokeEntity>)

    @Query("DELETE FROM strokes WHERE pageId = :pageId")
    suspend fun deleteStrokesForPage(pageId: String)

    @Query("DELETE FROM strokes WHERE id IN (:ids)")
    suspend fun deleteStrokesByIds(ids: List<String>)

    @Query("SELECT * FROM strokes")
    suspend fun getAllStrokesForReencrypt(): List<StrokeEntity>

    @Query("UPDATE strokes SET textContent = :textContent WHERE id = :id")
    suspend fun updateTextContent(id: String, textContent: String?)

    @Query("UPDATE strokes SET textContent = :textContent, pointsJson = :pointsJson WHERE id = :id")
    suspend fun updateStrokeFields(id: String, textContent: String?, pointsJson: String)
}

@Dao
interface MediaEmbedDao {
    @Query("SELECT * FROM media_embeds WHERE pageId = :pageId")
    suspend fun getMediaEmbedsForPage(pageId: String): List<MediaEmbedEntity>

    @Upsert
    suspend fun insertMediaEmbeds(embeds: List<MediaEmbedEntity>)

    @Query("DELETE FROM media_embeds WHERE pageId = :pageId")
    suspend fun deleteMediaEmbedsForPage(pageId: String)

    @Query("SELECT * FROM media_embeds")
    suspend fun getAllEmbedsForReencrypt(): List<MediaEmbedEntity>

    @Query("UPDATE media_embeds SET textContent = :textContent WHERE id = :id")
    suspend fun updateTextContent(id: String, textContent: String?)
}
@Dao
interface PaletteDao {
    @Query("SELECT * FROM palette_items ORDER BY timestampMs ASC")
    fun getAllPaletteItems(): Flow<List<PaletteItemEntity>>

    @Upsert
    suspend fun insertPaletteItem(item: PaletteItemEntity)

    @Query("DELETE FROM palette_items WHERE id = :id")
    suspend fun deletePaletteItem(id: String)

    @Query("DELETE FROM palette_items WHERE type = :type")
    suspend fun deletePaletteItemsByType(type: String)
}

@Dao
interface LayerDao {
    @Query("SELECT * FROM layers WHERE pageId = :pageId ORDER BY zOrder ASC")
    suspend fun getLayersForPage(pageId: String): List<LayerEntity>

    @Upsert
    suspend fun insertLayers(layers: List<LayerEntity>)

    @Upsert
    suspend fun insertLayer(layer: LayerEntity)

    @Query("DELETE FROM layers WHERE id = :id")
    suspend fun deleteLayer(id: String)

    @Query("DELETE FROM layers WHERE pageId = :pageId")
    suspend fun deleteLayersForPage(pageId: String)
}

@Dao
interface NoteVersionDao {
    @Query("SELECT * FROM note_versions WHERE pageId = :pageId ORDER BY timestampMs DESC")
    suspend fun getVersionsForPage(pageId: String): List<NoteVersionEntity>

    @Upsert
    suspend fun insertVersion(version: NoteVersionEntity)

    @Query("DELETE FROM note_versions WHERE pageId = :pageId")
    suspend fun deleteVersionsForPage(pageId: String)
}

