package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool
import com.authorss81.noteflow.data.model.BrushEngine
import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.services.EncryptionService
import com.authorss81.noteflow.services.ImportExportService
import org.junit.Assert.*
import org.junit.Test

class EncryptionAndServiceTest {

    @Test
    fun testFileExtensionDetection() {
        assertEquals("pdf", ImportExportService.extensionOf("document.pdf"))
        assertEquals("png", ImportExportService.extensionOf("image.PNG"))
        assertEquals("docx", ImportExportService.extensionOf("report.docx"))
        assertEquals("", ImportExportService.extensionOf("noextension"))
    }

    @Test
    fun testIsPdfAndIsImage() {
        assertTrue(ImportExportService.isPdf("pdf"))
        assertFalse(ImportExportService.isPdf("txt"))

        assertTrue(ImportExportService.isImage("png"))
        assertTrue(ImportExportService.isImage("jpeg"))
        assertFalse(ImportExportService.isImage("pdf"))
    }

    @Test
    fun testSaltAndKeyDerivation() {
        val salt = EncryptionService.generateSalt()
        assertEquals(16, salt.size)

        val pass1 = "MasterPassword123!"
        val key1 = EncryptionService.deriveKey(pass1, salt)
        val key2 = EncryptionService.deriveKey(pass1, salt)

        assertArrayEquals(key1, key2)

        val pass2 = "WrongPassword"
        val key3 = EncryptionService.deriveKey(pass2, salt)
        assertFalse(key1.contentEquals(key3))
    }

    @Test
    fun testEncryptDecryptCycle() {
        val plainText = "Confidential Note Data"
        val dek = EncryptionService.generateDek()

        val encrypted = EncryptionService.encrypt(plainText.toByteArray(), dek)
        assertNotEquals(plainText, encrypted)

        val decryptedBytes = EncryptionService.decrypt(encrypted, dek)
        val decryptedText = String(decryptedBytes, Charsets.UTF_8)

        assertEquals(plainText, decryptedText)
    }

    @Test
    fun testNotePageEntityDefaults() {
        val page = NotePageEntity(
            id = "test_1",
            sectionId = "sec_1",
            title = "My Test Note"
        )
        assertEquals("blank", page.template)
        assertFalse(page.pinned)
        assertFalse(page.deleted)
    }

    @Test
    fun testStrokeSerialization() {
        val stroke = Stroke(
            id = "stroke_1",
            tool = StrokeTool.PEN,
            colorInt = -16777216,
            width = 4f
        )
        assertEquals(StrokeTool.PEN, stroke.tool)
        assertEquals("stroke_1", stroke.id)
    }

    @Test
    fun testStrokeSerializationWithAdvancedFields() {
        val p1 = PointF(10f, 20f, pressure = 0.75f, tilt = 45f, timestampMs = 123456L)
        val stroke = Stroke(
            id = "stroke_2",
            tool = StrokeTool.PENCIL,
            colorInt = -16777216,
            width = 4f,
            points = listOf(p1),
            start = p1,
            end = p1
        )
        val serialized = EncryptionService.serializeStrokes(listOf(stroke))
        val deserialized = EncryptionService.deserializeStrokes(serialized)

        assertEquals(1, deserialized.size)
        val dsStroke = deserialized[0]
        assertEquals("stroke_2", dsStroke.id)
        assertEquals(1, dsStroke.points.size)

        val dsPoint = dsStroke.points[0]
        assertEquals(10f, dsPoint.x)
        assertEquals(20f, dsPoint.y)
        assertEquals(0.75f, dsPoint.pressure ?: 0f, 0.01f)
        assertEquals(45f, dsPoint.tilt ?: 0f, 0.01f)
        assertEquals(123456L, dsPoint.timestampMs)
    }

    @Test
    fun testBrushEngineInterface() {
        val classic: BrushEngine = BrushEngine.CLASSIC
        val advanced: BrushEngine = BrushEngine.ADVANCED
        assertNotNull(classic)
        assertNotNull(advanced)
    }
}
