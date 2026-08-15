package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.ClipKind
import com.authorss81.noteflow.plugins.SharedClip
import com.authorss81.noteflow.plugins.SharedStream
import com.authorss81.noteflow.plugins.clipshare.ClipShareConfirmNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-PLAT-2 (phase-58): the confirmation-dialog copy that is shown BEFORE any
 * shared bytes are copied. Keeping the copy pure-JVM means the "Clip into
 * InkFlow?" gate semantics are testable without a device.
 */
class ClipShareConfirmNoticeTest {

    private fun clip(
        kind: ClipKind,
        text: String? = null,
        streams: List<SharedStream> = emptyList()
    ) = SharedClip(kind = kind, text = text, streams = streams)

    @Test
    fun `a text share is summarised as shared text`() {
        assertEquals(
            "Shared text",
            ClipShareConfirmNotice.summary(clip(ClipKind.TEXT, text = "hello"))
        )
    }

    @Test
    fun `a single image is described and a single file too`() {
        assertEquals(
            "Shared image",
            ClipShareConfirmNotice.summary(
                clip(ClipKind.IMAGES, streams = listOf(SharedStream("content://a/1.png", "image/png")))
            )
        )
        assertEquals(
            "Shared file",
            ClipShareConfirmNotice.summary(
                clip(ClipKind.FILES, streams = listOf(SharedStream("content://a/plan.pdf", "application/pdf")))
            )
        )
    }

    @Test
    fun `multiple streams are counted`() {
        assertEquals(
            "3 shared items",
            ClipShareConfirmNotice.summary(
                clip(ClipKind.IMAGES, streams = listOf(SharedStream("a"), SharedStream("b"), SharedStream("c")))
            )
        )
    }

    @Test
    fun `the body announces what will be created`() {
        val body = ClipShareConfirmNotice.body(
            clip(ClipKind.TEXT, text = "Keep this line!")
        )
        assertTrue(body.contains("create a new note"))
        assertTrue(body.contains("Keep this line!"))
    }

    @Test
    fun `the body preview is capped and ellipsised`() {
        val longText = "x".repeat(ClipShareConfirmNotice.TEXT_PREVIEW_CHARS + 200)
        val body = ClipShareConfirmNotice.body(clip(ClipKind.TEXT, text = longText))
        val preview = body.substringAfter("\"")
        assertTrue(preview.length <= ClipShareConfirmNotice.TEXT_PREVIEW_CHARS + 2)
        assertTrue(body.contains("…"))
    }

    @Test
    fun `an images-only share counts its items without a text preview`() {
        val body = ClipShareConfirmNotice.body(
            clip(ClipKind.IMAGES, streams = listOf(SharedStream("content://a/1.png", "image/png")))
        )
        assertTrue(body.contains("1 shared item"))
        assertEquals(1, body.split("\n\n").size)
    }
}