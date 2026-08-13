package com.authorss81.noteflow.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "notebooks")
data class NotebookEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val tags: String = ""
)

@Entity(
    tableName = "sections",
    indices = [Index(value = ["notebookId"])]
)
data class SectionEntity(
    @PrimaryKey val id: String,
    val notebookId: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "pages",
    indices = [
        Index(value = ["sectionId"]),
        Index(value = ["deleted", "updatedAt"]),
        Index(value = ["pinned"])
    ]
)
data class NotePageEntity(
    @PrimaryKey val id: String,
    val sectionId: String,
    val title: String,
    val sourceFilePath: String? = null,
    val sourceFileType: String? = null, // "pdf", "image", "text", null
    val pageIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    val deleted: Boolean = false,
    val template: String? = "blank", // blank, lined, grid, dots
    val paperColor: String? = null,
    val extractedText: String? = "",
    val tags: String = ""
)

@Entity(
    tableName = "strokes",
    indices = [
        Index(value = ["pageId"]),
        Index(value = ["layerId"])
    ]
)
data class StrokeEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    val toolName: String,
    val colorInt: Int,
    val strokeWidth: Float,
    val filled: Boolean,
    val textContent: String,
    val pointsJson: String,
    val startX: Float?,
    val startY: Float?,
    val endX: Float?,
    val endY: Float?,
    val pdfPage: Int = 0,
    val timestampMs: Long? = null,
    val layerId: String? = null
)

@Entity(
    tableName = "layers",
    indices = [Index(value = ["pageId"])]
)
data class LayerEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    val name: String,
    val zOrder: Int,
    val opacity: Float = 1.0f,
    val blendMode: String = "NORMAL", // NORMAL, MULTIPLY, SCREEN, OVERLAY, etc.
    val visible: Boolean = true,
    val locked: Boolean = false
)

@Entity(
    tableName = "media_embeds",
    indices = [Index(value = ["pageId"])]
)
data class MediaEmbedEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    val typeName: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val contentUrlOrPath: String? = null,
    val textContent: String? = null,
    val codeLanguage: String? = null,
    val durationMs: Long = 0L,
    val waveformJson: String = "[]",
    val pdfPage: Int = 0
)

@Entity(tableName = "palette_items")
data class PaletteItemEntity(
    @PrimaryKey val id: String,
    val type: String, // "SWATCH" or "PRESET"
    val name: String, // name of preset, or custom label for swatch
    val colorInt: Int,
    val toolName: String?, // only for PRESET
    val strokeWidth: Float?, // only for PRESET
    val timestampMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "note_versions",
    indices = [Index(value = ["pageId"])]
)
data class NoteVersionEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    val title: String,
    val extractedText: String?,
    val timestampMs: Long = System.currentTimeMillis(),
    val versionNote: String = "Saved version"
)

