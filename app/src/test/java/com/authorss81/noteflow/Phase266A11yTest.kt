package com.authorss81.noteflow

import com.authorss81.noteflow.services.A11yPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 266 (WCAG 2.2 AA pass 1): accessibility regression guard.
 *
 * Pure-JVM policy behavior + source pins over the edited files. Several
 * PROMPT claims verified FALSE during the phase (documented per-test) — the
 * pins hold the verified-good state so a future edit cannot regress it.
 */
class Phase266A11yTest {

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

    // --- Pure-JVM policy --------------------------------------------

    @Test
    fun `motion runs only without reduce-motion`() {
        assertTrue(A11yPolicy.shouldAnimate(false))
        assertFalse(A11yPolicy.shouldAnimate(true))
    }

    @Test
    fun `decorative null allowed only with text sibling or illustration context`() {
        assertTrue(A11yPolicy.nullAllowedForDecorative(hasTextSibling = true))
        assertTrue(A11yPolicy.nullAllowedForDecorative(hasTextSibling = false, isPureIllustration = true))
        assertFalse(A11yPolicy.nullAllowedForDecorative(hasTextSibling = false))
    }

    @Test
    fun `small glyphs pass when the hit area reaches 48dp`() {
        assertTrue(A11yPolicy.meetsTouchTarget(glyphDp = 16, hitAreaDp = 48))
        assertTrue(A11yPolicy.meetsTouchTarget(glyphDp = 26, hitAreaDp = 48))
        assertTrue(A11yPolicy.meetsTouchTarget(glyphDp = 32, hitAreaDp = 48))
        assertFalse(A11yPolicy.meetsTouchTarget(glyphDp = 16, hitAreaDp = 32))
        assertFalse(A11yPolicy.meetsTouchTarget(glyphDp = 0, hitAreaDp = 48))
    }

    @Test
    fun `label floor rejects 7sp captions`() {
        assertFalse(A11yPolicy.meetsLabelSize(7))
        assertTrue(A11yPolicy.meetsLabelSize(A11yPolicy.MIN_LABEL_TEXT_SP))
        assertEquals(10, A11yPolicy.MIN_LABEL_TEXT_SP)
    }

    @Test
    fun `ambient motion budget rejects the old 1800ms confetti flight`() {
        assertFalse(A11yPolicy.meetsMotionBudget(1800))
        assertTrue(A11yPolicy.meetsMotionBudget(500))
        assertEquals(48, A11yPolicy.MIN_TOUCH_TARGET_DP)
    }

    @Test
    fun `unpinned pin alpha raised off the 0-4 legacy value`() {
        assertTrue(A11yPolicy.UNPINNED_ICON_ALPHA > A11yPolicy.LEGACY_UNPINNED_ICON_ALPHA)
        assertEquals(0.6f, A11yPolicy.UNPINNED_ICON_ALPHA)
    }

    @Test
    fun `canvas descriptions carry counts never content`() {
        assertEquals("Drawing canvas", A11yPolicy.canvasContentDescription(3))
        assertEquals("12 strokes", A11yPolicy.canvasStateDescription(12))
        assertEquals("Drawing canvas", A11yPolicy.canvasContentDescription(0))
    }

    // --- Source pins --------------------------------------------------

    @Test
    fun `ConfettiOverlay returns early under reduce-motion`() {
        val src = mainSource("ui/components/ConfettiOverlay.kt")
        assertTrue(
            "confetti must read the reduce-motion composition local",
            src.contains("LocalReduceMotion.current")
        )
        assertTrue(
            "confetti must gate on A11yPolicy.shouldAnimate",
            src.contains("A11yPolicy.shouldAnimate")
        )
    }

    @Test
    fun `graph link pulse is gated through A11yPolicy`() {
        val src = mainSource("ui/screens/KnowledgeGraphScreen.kt")
        assertTrue(
            "pulse draw must branch on A11yPolicy.shouldAnimate(reduceMotion)",
            src.contains("A11yPolicy.shouldAnimate(reduceMotion)")
        )
        // The settle tweens stay manually gated (no MotionSystem.spec — the
        // branch snaps); the pin holds the gate so motion is never unconditional.
        assertTrue(
            "900ms settle tween must stay inside a reduce-motion branch",
            src.contains("if (!reduceMotion)")
        )
    }

    @Test
    fun `graph selected card announces politely`() {
        val src = mainSource("ui/screens/KnowledgeGraphScreen.kt")
        assertTrue(
            "selected-node card must set a polite live region",
            src.contains("liveRegion = LiveRegionMode.Polite")
        )
    }

    @Test
    fun `unpinned pin glyph uses the policy alpha`() {
        val src = mainSource("ui/screens/HomeScreen.kt")
        assertTrue(
            "pin tint must come from A11yPolicy.UNPINNED_ICON_ALPHA",
            src.contains("A11yPolicy.UNPINNED_ICON_ALPHA")
        )
        assertFalse(
            "legacy 0.4f pin alpha must be gone",
            src.contains("onSurfaceVariant.copy(alpha = 0.4f)")
        )
    }

    @Test
    fun `no 7sp captions remain in EditorScreen`() {
        val src = mainSource("ui/screens/EditorScreen.kt")
        assertFalse(
            "7sp dock captions must stay floored at 10sp",
            src.contains("7.sp")
        )
    }

    @Test
    fun `canvas surface exposes TalkBack description without per-sample reads`() {
        val src = mainSource("ui/components/AnnotationCanvas.kt")
        assertTrue(
            "canvas root must set a contentDescription via the policy",
            src.contains("A11yPolicy.canvasContentDescription(strokes.size)")
        )
        assertTrue(
            "canvas root must set a stateDescription via the policy",
            src.contains("A11yPolicy.canvasStateDescription(strokes.size)")
        )
        val semanticsBlock = src.substring(
            src.indexOf("A11yPolicy.canvasContentDescription"),
            src.indexOf("A11yPolicy.canvasStateDescription") + 60
        )
        assertFalse(
            "semantics must not subscribe to per-sample activePoints (recomposition storm)",
            semanticsBlock.contains("activePoints")
        )
    }

    @Test
    fun `pre-existing 48dp hit areas stay pinned`() {
        // Verified already-good in the phase audit: the LockScreen biometric
        // glyph (32dp visual) and the EditorScreen 28/26/36dp glyphs ride
        // minimumInteractiveComponentSize. A removal fails the suite.
        val lock = mainSource("ui/screens/LockScreen.kt")
        assertTrue(
            "biometric IconButton must keep its 48dp hit area",
            lock.contains("minimumInteractiveComponentSize()")
        )
        val editor = mainSource("ui/screens/EditorScreen.kt")
        assertTrue(
            "28dp glyph must keep minimumInteractiveComponentSize",
            editor.contains("Modifier.size(28.dp).minimumInteractiveComponentSize()")
        )
        assertTrue(
            "26dp glyph must keep minimumInteractiveComponentSize",
            editor.contains("Modifier.size(26.dp).minimumInteractiveComponentSize()")
        )
        assertTrue(
            "36dp control must keep minimumInteractiveComponentSize",
            editor.contains("Modifier.size(36.dp).minimumInteractiveComponentSize()")
        )
    }
}
