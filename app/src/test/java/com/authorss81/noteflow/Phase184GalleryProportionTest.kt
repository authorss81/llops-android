package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 184 — source-regression pins for the gallery card proportion fix.
 *
 * The user visual review: the fixed `aspectRatio(10f / 16f)` card left >60% of the
 * tile empty for short notes and could clip the footer at large font scales. The
 * fix removes the strict ratio and applies a font-scale-scaled `heightIn` floor
 * from the pure-JVM `GalleryCardLayoutPolicy`. These pins make the fix structural
 * so a reviewer cannot reintroduce a rigid ratio on the gallery card.
 */
class Phase184GalleryProportionTest {

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

    @Test
    fun `gallery card no longer uses a rigid aspect ratio`() {
        val src = mainSource("ui/components/GalleryView.kt")
        assertFalse(
            "the fixed 10:16 portrait ratio must be gone (it caused >60% dead band)",
            src.contains("aspectRatio(10f / 16f)")
        )
        assertFalse(
            "no Modifier.aspectRatio may size the gallery card any more",
            src.contains(".aspectRatio(")
        )
        assertTrue(
            "the card must use a heightIn floor instead",
            src.contains(".heightIn(min = minCardHeight)")
        )
    }

    @Test
    fun `card min height is derived from the pure JVM policy and font scale`() {
        val src = mainSource("ui/components/GalleryView.kt")
        assertTrue(
            "card floor must come from GalleryCardLayoutPolicy",
            src.contains("GalleryCardLayoutPolicy.minCardHeightDp(")
        )
        assertTrue(
            "the policy input is the user's font scale (accessibility rule)",
            src.contains("LocalDensity.current.fontScale")
        )
    }

    @Test
    fun `policy defines the 180dp floor and the 288dp cap`() {
        val pol = mainSource("services/GalleryCardLayoutPolicy.kt")
        assertTrue("base floor 180f", pol.contains("const val BASE_MIN_HEIGHT_DP = 180f"))
        assertTrue("hard cap 288f", pol.contains("const val MAX_MIN_HEIGHT_DP = 288f"))
        assertTrue(
            "the floor must be a clamped minimum, never an unbounded ratio",
            pol.contains("coerceIn(")
        )
    }

    @Test
    fun `preview stays capped and footer stays non-clipping`() {
        val src = mainSource("ui/components/GalleryView.kt")
        assertTrue("preview text stays 3-line capped", src.contains("maxLines = 3"))
        assertTrue(
            "footer date stays single-line ellipsized (phase-183 pin preserved)",
            src.contains("text = dateFormat.format(Date(page.updatedAt)),")
        )
        assertTrue(
            "footer date maxLines preserved",
            src.contains("maxLines = 1,") && src.contains("overflow = TextOverflow.Ellipsis")
        )
    }

    @Test
    fun `gallery signature and grid stay unchanged for HomeScreen`() {
        val src = mainSource("ui/components/GalleryView.kt")
        assertTrue(
            "public GalleryView API must stay (HomeScreen.kt:1343 call site)",
            src.contains("fun GalleryView(")
        )
        assertTrue(
            "grid stays Adaptive LazyVerticalGrid (memory bounded, staggered not required)",
            src.contains("GridCells.Adaptive(minSize = 168.dp)")
        )
    }
}