package com.authorss81.noteflow

import com.authorss81.noteflow.services.GalleryTitleDisplayPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 183 — gallery display-title policy (pure JVM).
 *
 * The policy is display-side only: it must never change a stored DB title, only
 * produce the STRING shown in the compact gallery card.
 */
class GalleryTitleDisplayPolicyTest {

    @Test
    fun `md extension is stripped`() {
        assertEquals("Meeting", GalleryTitleDisplayPolicy.displayTitle("Meeting.md"))
        assertEquals("2026-08-19", GalleryTitleDisplayPolicy.displayTitle("2026-08-19.md"))
    }

    @Test
    fun `markdown extension is stripped`() {
        assertEquals("Readme", GalleryTitleDisplayPolicy.displayTitle("Readme.markdown"))
    }

    @Test
    fun `txt extension is stripped`() {
        assertEquals("Shopping list", GalleryTitleDisplayPolicy.displayTitle("Shopping list.txt"))
    }

    @Test
    fun `extension matching is case-insensitive`() {
        assertEquals("Upper", GalleryTitleDisplayPolicy.displayTitle("Upper.MD"))
        assertEquals("Mixed", GalleryTitleDisplayPolicy.displayTitle("Mixed.MarkDown"))
        assertEquals("Text", GalleryTitleDisplayPolicy.displayTitle("Text.TXT"))
    }

    @Test
    fun `other extensions are untouched`() {
        assertEquals("Photo.jpg", GalleryTitleDisplayPolicy.displayTitle("Photo.jpg"))
        assertEquals("slides.pdf", GalleryTitleDisplayPolicy.displayTitle("slides.pdf"))
        assertEquals("Archive.zip", GalleryTitleDisplayPolicy.displayTitle("Archive.zip"))
    }

    @Test
    fun `names without any extension are untouched`() {
        assertEquals("Groceries", GalleryTitleDisplayPolicy.displayTitle("Groceries"))
        assertEquals("Ink canvas", GalleryTitleDisplayPolicy.displayTitle("Ink canvas"))
    }

    @Test
    fun `double extension keeps one suffix`() {
        assertEquals("foo.md", GalleryTitleDisplayPolicy.displayTitle("foo.md.md"))
    }

    @Test
    fun `a bare extension is never fully stripped`() {
        assertEquals(".md", GalleryTitleDisplayPolicy.displayTitle(".md"))
        assertEquals(".txt", GalleryTitleDisplayPolicy.displayTitle(".txt"))
    }

    @Test
    fun `blank input round-trips untouched`() {
        assertEquals("", GalleryTitleDisplayPolicy.displayTitle(""))
        assertEquals("   ", GalleryTitleDisplayPolicy.displayTitle("   "))
    }

    @Test
    fun `surrounding whitespace is trimmed in the display string only`() {
        assertEquals("Note", GalleryTitleDisplayPolicy.displayTitle("  Note.md  "))
        // The same trim applies to extension-less names — a padded title is never
        // passed through to the card verbatim (display-side only).
        assertEquals("Groceries", GalleryTitleDisplayPolicy.displayTitle("  Groceries  "))
    }

    @Test
    fun `dot inside a name without a trailing extension is untouched`() {
        assertEquals("project.v1 notes", GalleryTitleDisplayPolicy.displayTitle("project.v1 notes"))
    }
}