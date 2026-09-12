package com.authorss81.noteflow

import com.authorss81.noteflow.utils.AgslGate
import com.authorss81.noteflow.utils.DeviceTier
import com.authorss81.noteflow.utils.DeviceTierPolicy
import com.authorss81.noteflow.utils.MemoryTrimPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 269 (compat: AGSL gate + OOM cull + trim + permissions).
 *
 *  - `AgslGate` is the single truth (SDK >= 33 AND tier != LOW_END).
 *  - `DeviceTierPolicy` recalibration (Go-class = 2 GB, 8x-little needs RAM
 *    for flagship, 6c/8 GB is flagship, unknown hardware fails closed).
 *  - `MemoryTrimPolicy` clears at every level >= RUNNING_LOW (10).
 *  - Source pins: single-gate wiring, trim threshold, paged graph load with
 *    tier-before-decrypt ordering, permission + manifest fixes.
 */
class Phase269CompatTest {

    private fun mainSource(rel: String): String {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            val d: File = dir
            File(d, "src/main/kotlin/com/authorss81/noteflow/$rel").takeIf { it.isFile }?.let { return it.readText() }
            File(d, "src/main/kotlin/$rel").takeIf { it.isFile }?.let { return it.readText() }
            File(d, "app/src/main/kotlin/com/authorss81/noteflow/$rel").takeIf { it.isFile }?.let { return it.readText() }
            File(d, "app/src/main/$rel").takeIf { it.isFile }?.let { return it.readText() }
            dir = d.parentFile
        }
        throw AssertionError("could not locate app/src/main/$rel from ${start.path}")
    }

    // --- AgslGate truth table (pure JVM) ---

    @Test
    fun `AgslGate allows API33+ non-low-end only`() {
        assertFalse(AgslGate.isSupported(32, DeviceTier.FLAGSHIP))
        assertFalse(AgslGate.isSupported(32, DeviceTier.MID_RANGE))
        assertFalse(AgslGate.isSupported(32, DeviceTier.LOW_END))
        assertFalse(AgslGate.isSupported(33, DeviceTier.LOW_END))
        assertFalse(AgslGate.isSupported(34, DeviceTier.LOW_END))
        assertTrue(AgslGate.isSupported(33, DeviceTier.MID_RANGE))
        assertTrue(AgslGate.isSupported(33, DeviceTier.FLAGSHIP))
        assertTrue(AgslGate.isSupported(34, DeviceTier.FLAGSHIP))
    }

    @Test
    fun `AgslGate SDK floor is API 33`() {
        assertEquals(33, AgslGate.AGSL_MIN_SDK)
        assertFalse(AgslGate.sdkCapable(32))
        assertTrue(AgslGate.sdkCapable(33))
    }

    // --- DeviceTierPolicy recalibration (pure JVM) ---

    @Test
    fun `Go signal and tiny hardware are LOW_END`() {
        assertEquals(DeviceTier.LOW_END, DeviceTierPolicy.classifyTier(true, 8.0, 8))
        assertEquals(DeviceTier.LOW_END, DeviceTierPolicy.classifyTier(false, 2.0, 8))
        assertEquals(DeviceTier.LOW_END, DeviceTierPolicy.classifyTier(false, 1.5, 4))
        assertEquals(DeviceTier.LOW_END, DeviceTierPolicy.classifyTier(false, 8.0, 2))
    }

    @Test
    fun `3GB phones are mid-range, not LOW_END`() {
        assertEquals(DeviceTier.MID_RANGE, DeviceTierPolicy.classifyTier(false, 3.0, 4))
        assertEquals(DeviceTier.MID_RANGE, DeviceTierPolicy.classifyTier(false, 3.0, 8))
    }

    @Test
    fun `8x little cores without RAM escape to MID, not flagship`() {
        assertEquals(DeviceTier.MID_RANGE, DeviceTierPolicy.classifyTier(false, 3.0, 8))
        assertEquals(DeviceTier.MID_RANGE, DeviceTierPolicy.classifyTier(false, 4.0, 8))
    }

    @Test
    fun `6c 8GB is flagship`() {
        assertEquals(DeviceTier.FLAGSHIP, DeviceTierPolicy.classifyTier(false, 8.0, 6))
        assertEquals(DeviceTier.FLAGSHIP, DeviceTierPolicy.classifyTier(false, 6.0, 6))
        assertEquals(DeviceTier.FLAGSHIP, DeviceTierPolicy.classifyTier(false, 12.0, 8))
    }

    @Test
    fun `unknown hardware fails closed to LOW_END`() {
        assertEquals(DeviceTier.LOW_END, DeviceTierPolicy.classifyTier(false, Double.NaN, 8))
        assertEquals(DeviceTier.LOW_END, DeviceTierPolicy.classifyTier(false, 0.0, 8))
        assertEquals(DeviceTier.LOW_END, DeviceTierPolicy.classifyTier(false, 4.0, 0))
        assertEquals(DeviceTier.LOW_END, DeviceTierPolicy.classifyTier(false, -1.0, -4))
    }

    @Test
    fun `ordinary mid phones stay MID`() {
        assertEquals(DeviceTier.MID_RANGE, DeviceTierPolicy.classifyTier(false, 4.0, 4))
        assertEquals(DeviceTier.MID_RANGE, DeviceTierPolicy.classifyTier(false, 6.0, 4))
        assertEquals(DeviceTier.MID_RANGE, DeviceTierPolicy.classifyTier(false, 8.0, 4))
    }

    // --- MemoryTrimPolicy (pure JVM) ---

    @Test
    fun `caches clear at RUNNING_LOW and above, never below`() {
        assertEquals(10, MemoryTrimPolicy.CLEAR_AT_LEVEL)
        assertFalse(MemoryTrimPolicy.shouldClearCaches(5)) // RUNNING_MODERATE
        assertTrue(MemoryTrimPolicy.shouldClearCaches(10)) // RUNNING_LOW
        assertTrue(MemoryTrimPolicy.shouldClearCaches(15)) // RUNNING_CRITICAL
        assertTrue(MemoryTrimPolicy.shouldClearCaches(20)) // UI_HIDDEN
        assertTrue(MemoryTrimPolicy.shouldClearCaches(40)) // BACKGROUND
        assertTrue(MemoryTrimPolicy.shouldClearCaches(60)) // MODERATE
        assertTrue(MemoryTrimPolicy.shouldClearCaches(80)) // COMPLETE
    }

    // --- Source pins ---

    @Test
    fun `single AGSL truth - both helpers delegate to AgslGate`() {
        val manager = mainSource("utils/DeviceCompatibilityManager.kt")
        assertTrue(
            "DeviceCompatibilityManager.isAgslSupported must delegate to AgslGate",
            manager.contains("AgslGate.isSupported")
        )
        val helper = mainSource("ui/components/ShaderCapabilityHelper.kt")
        assertTrue(
            "ShaderCapabilityHelper must share the AgslGate truth",
            helper.contains("AgslGate")
        )
        assertTrue(
            "ShaderCapabilityHelper must expose the tier-aware overload",
            helper.contains("fun agslSupportedFor(sdkInt: Int, tier: DeviceTier)")
        )
    }

    @Test
    fun `canvas allocates and uses the shader behind the tier-aware gate only`() {
        val src = mainSource("ui/components/AnnotationCanvas.kt")
        assertTrue(
            "canvas must gate on AgslGate",
            src.contains("AgslGate.isSupported")
        )
        assertFalse(
            "canvas must not allocate/use behind the SDK-only check (LOW_END re-enable path)",
            src.contains("ShaderCapabilityHelper.isAgslSupported &&") ||
                src.contains("if (ShaderCapabilityHelper.isAgslSupported)")
        )
        assertTrue(
            "shader construction must survive fragile drivers (try/catch fallback)",
            src.contains("AgslShaders.WetMixingEffect()") && src.contains("catch (e: Exception)")
        )
        assertTrue(
            "grain gate must honor the tier override (no direct detectDeviceTier)",
            !src.contains("detectDeviceTier(") && src.contains("canvasDeviceTier")
        )
    }

    @Test
    fun `trim clears pool and grain at RUNNING_LOW via policy`() {
        val src = mainSource("MainActivity.kt")
        assertTrue(
            "onTrimMemory must route through MemoryTrimPolicy",
            src.contains("MemoryTrimPolicy.shouldClearCaches(level)")
        )
        assertTrue(
            "trim must also drop the grain tiles",
            src.contains("PaperGrainTileCache.clear()")
        )
    }

    @Test
    fun `graph loads capped pages with tier resolved before any decrypt`() {
        val src = mainSource("ui/screens/KnowledgeGraphScreen.kt")
        assertTrue(
            "screen must use the capped load",
            src.contains("loadCappedActivePages(")
        )
        assertTrue(
            "screen must read the cheap COUNT for its honest culled notice",
            src.contains("loadActivePageCount()")
        )
        val tierAt = src.indexOf("getDeviceTier(")
        val countAt = src.indexOf("loadActivePageCount()")
        val cappedAt = src.indexOf("loadCappedActivePages(")
        assertTrue("tier + count + capped load must all be present", tierAt >= 0 && countAt >= 0 && cappedAt >= 0)
        assertTrue(
            "tier cap must resolve BEFORE any vault read (no decrypt-then-cull)",
            tierAt < countAt && countAt < cappedAt
        )
        assertFalse(
            "screen must not full-vault decrypt before the cull",
            src.contains("loadAllActivePages()")
        )
    }

    @Test
    fun `permission flow has rationale, settings redirect and revoke honesty`() {
        val editor = mainSource("ui/screens/EditorScreen.kt")
        assertTrue(
            "mic request must check the rationale signal first",
            editor.contains("shouldShowRequestPermissionRationale")
        )
        assertTrue(
            "permanent denial must carry the Open Settings action",
            editor.contains("SNACKBAR_ACTION_OPEN_APP_SETTINGS")
        )
        val vm = mainSource("ui/viewmodel/NoteflowViewModel.kt")
        assertTrue(
            "pipeline must support one-shot snackbar actions",
            vm.contains("actionId: String?") && vm.contains("SNACKBAR_ACTION_OPEN_APP_SETTINGS")
        )
        val activity = mainSource("MainActivity.kt")
        assertTrue(
            "root collector must route the Open Settings tap",
            activity.contains("SNACKBAR_ACTION_OPEN_APP_SETTINGS")
        )
        val voice = mainSource("services/VoiceNoteManager.kt")
        assertTrue(
            "mid-record revoke must be named explicitly",
            voice.contains("revoked mid-recording")
        )
    }

    @Test
    fun `deprecated fingerprint permission is gone`() {
        val manifest = mainSource("AndroidManifest.xml")
        assertFalse(
            "USE_FINGERPRINT is deprecated — USE_BIOMETRIC is the declaration",
            manifest.contains("USE_FINGERPRINT")
        )
        assertTrue(manifest.contains("USE_BIOMETRIC"))
    }

    @Test
    fun `tier remembers are keyed on the override`() {
        val glass = mainSource("theme/GlassSurfaces.kt")
        assertTrue(
            "glass tier must re-resolve on override change",
            glass.contains("glassSettings.deviceTierOverride")
        )
        val editor = mainSource("ui/screens/EditorScreen.kt")
        assertTrue(
            "low-end effect must re-run on tier change, not once per process",
            editor.contains("LaunchedEffect(editorDeviceTier)")
        )
    }
}
