package com.authorss81.noteflow

import com.authorss81.noteflow.services.GalleryTagRowPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 188 — GalleryView robustness source pins (risks #1 + #4).
 *
 * Risk #1 (perf): grid items MUST NEVER rasterize/deserialize real stroke
 * geometry — previews come from `NotePageEntity` metadata only
 * (title/extractedText/tags/pinned/sourceFileType/updatedAt). Any path that
 * calls the stroke store (`getStrokesForPage`/`strokesForPage`/`loadStrokes`/
 * `deserializeStrokes`/… or mentions `pointsJson`) inside the gallery is a
 * jank regression on 50+ notes.
 *
 * Risk #4 (tag overflow): the chip row is a single-line `Row` capped by the
 * pure-JVM `GalleryTagRowPolicy` at [GalleryTagRowPolicy.MAX_VISIBLE_TAGS] chips
 * + a "+N" badge — no wrapping `FlowRow`, no `.take(3)`, no inline chip math.
 *
 * Mechanical pins on purpose — they make a regression fail the build.
 */
class Phase188GalleryRobustnessTest {

    private fun mainSource(rel: String): String {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            val d: File = dir
            File(d, "src/main/kotlin/com/authorss81/noteflow/$rel").takeIf { it.isFile }?.let { return it.readText() }
            File(d, "src/main/kotlin/$rel").takeIf { it.isFile }?.let { return it.readText() }
            File(d, "app/src/main/kotlin/com/authorss81/noteflow/$rel").takeIf { it.isFile }?.let { return it.readText() }
            dir = d.parentFile
        }
        throw AssertionError("could not locate app/src/main/kotlin/$rel from ${start.path}")
    }

    // ---- Risk #1: no stroke deserialization/rasterization in the gallery ----

    @Test
    fun `gallery never references stroke geometry or a thumbnail rasterizer`() {
        val gallery = mainSource("ui/components/GalleryView.kt")
        val strokeTokens = listOf(
            "pointsJson",
            "pointsJsonForPage",
            "getStrokesForPage",
            "strokesForPage",
            "StrokeDao",
            "deserializeStrokes",
            "serializeStrokes",
            "loadStrokes",
            "saveStrokesForPage",
            "thumbnail",
            "rasterize",
            "StrokeEntity"
        )
        val hits = strokeTokens.filter { gallery.contains(it) }
        assertTrue(
            "grid items must never touch stroke geometry/thumbnails — found: $hits",
            hits.isEmpty()
        )
    }

    @Test
    fun `gallery preview derives only from NotePageEntity metadata fields`() {
        val gallery = mainSource("ui/components/GalleryView.kt")
        // Every preview input used by the card must be a light metadata field on
        // the already-loaded NotePageEntity list.
        assertTrue(gallery.contains("page.extractedText?.trim().orEmpty()"))
        assertTrue(gallery.contains("page.title"))
        assertTrue(gallery.contains("page.tags"))
        assertTrue(gallery.contains("page.pinned"))
        assertTrue(gallery.contains("page.sourceFileType"))
        assertTrue(gallery.contains("page.updatedAt"))
        // And the grid item is built from the entity alone (no repository/DAO).
        assertFalse("no repository call in the grid item", gallery.contains("repository."))
        assertFalse("no DAO call in the grid item", gallery.contains(".dao"))
        assertFalse("no view-model stroke fetch in the grid item", gallery.contains("loadPageStrokes"))
    }

    @Test
    fun `grid is a lazy grid keyed by id so big galleries stay memory-bounded`() {
        val gallery = mainSource("ui/components/GalleryView.kt")
        assertTrue(gallery.contains("LazyVerticalGrid("))
        assertTrue(gallery.contains("GridCells.Adaptive(minSize = 168.dp)"))
        assertTrue(gallery.contains("items(pages, key = { it.id }) { page ->"))
    }

    // ---- Risk #4: bounded single-line tag row ----

    @Test
    fun `tag row is capped by the pure-JVM policy`() {
        val gallery = mainSource("ui/components/GalleryView.kt")
        assertTrue("parsing must go through the policy", gallery.contains("GalleryTagRowPolicy.parseTags(page.tags)"))
        assertTrue("chip cap must go through the policy", gallery.contains("GalleryTagRowPolicy.visibleChips(tags)"))
        assertTrue("hidden count must go through the policy", gallery.contains("GalleryTagRowPolicy.hiddenChipCount(tags)"))
        assertTrue("badge text must go through the policy", gallery.contains("GalleryTagRowPolicy.hiddenBadgeText("))
        assertTrue("chip labels must go through the policy", gallery.contains("GalleryTagRowPolicy.chipText(tag)"))
        assertFalse("no inline .take( may survive in the composable (cap lives in the policy)", gallery.contains(".take("))
        // The cap constant itself lives in the pure-JVM policy.
        assertEquals(2, GalleryTagRowPolicy.MAX_VISIBLE_TAGS)
    }

    @Test
    fun `tag row is single-line and never wraps`() {
        val gallery = mainSource("ui/components/GalleryView.kt")
        assertFalse("the wrapping FlowRow must be gone", gallery.contains("FlowRow"))
        assertTrue("no inline tag math - visible chips come from the policy", gallery.contains("GalleryTagRowPolicy.visibleChips(tags)"))
        assertTrue("chip text must be single-line + ellipsized", gallery.contains("maxLines = 1,") && gallery.contains("overflow = TextOverflow.Ellipsis"))
        assertTrue(
            "chips are weighted so long tags ellipsize instead of pushing the badge out",
            gallery.contains("modifier = Modifier.weight(1f, fill = false)")
        )
        assertFalse("no lingering ExperimentalLayoutApi opt-in (FlowRow removed)", gallery.contains("ExperimentalLayoutApi"))
    }

    @Test
    fun `badge renders only for hidden tags and the date row stays below`() {
        val gallery = mainSource("ui/components/GalleryView.kt")
        assertTrue(
            "badge is conditional on hidden tags via the policy",
            gallery.contains("GalleryTagRowPolicy.hiddenBadgeText(hiddenTagCount)?.let")
        )
        // The update timestamp text block must remain directly under the chip row.
        assertTrue(gallery.contains("text = dateFormat.format(Date(page.updatedAt))"))
        assertTrue(gallery.contains("maxLines = 1,") && gallery.contains("overflow = TextOverflow.Ellipsis"))
    }
}