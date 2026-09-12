package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase265PerfTest {

    @Test
    fun `frame pump starts on all API levels - no TIRAMISU guard`() {
        val src = readSource("app/src/main/kotlin/com/authorss81/noteflow/ui/components/WetBrushFramePump.kt")
        val startBody = funBody(src, "fun start()")
        assertTrue("WetBrushFramePump.kt must still define start()", startBody.isNotEmpty())
        assertFalse(
            "start() must not early-return below TIRAMISU (Choreographer exists since API 16, minSdk 26)",
            startBody.contains("TIRAMISU")
        )
        assertFalse(
            "start() must not gate on SDK_INT at all",
            startBody.contains("SDK_INT")
        )
        assertTrue(
            "start() must still arm exactly once via compareAndSet",
            startBody.contains("active.compareAndSet(false, true)")
        )
        assertTrue(
            "start() must still post the frame callback",
            startBody.contains("postFrameCallback(frameCallback)")
        )
        assertTrue(
            "the gated re-post must survive (idle editor costs zero wakes)",
            src.contains("if (!active.get()) return")
        )
        assertTrue(
            "thermal re-evaluation must stay at <=1 Hz, not per frame",
            src.contains("THERMAL_SAMPLE_INTERVAL_MS")
        )
    }

    @Test
    fun `profile pipeline stays guarded with an unsigned mapping proof runbook`() {
        val build = readSource("app/build.gradle.kts")
        assertTrue(
            "the compileArtProfile disable must stay GUARDED on committed profiles",
            build.contains("hasCommittedBaselineProfiles")
        )
        assertTrue(
            "the consumer must stay wired to the producer module",
            build.contains("baselineProfile {") && build.contains("from(project(\":baselineprofile\"))")
        )
        assertTrue(
            "phase-265 runbook must document the unsigned mapping proof",
            build.contains("minifyReleaseWithR8") && build.contains("mapping.txt")
        )
        assertTrue(
            "R8 fullMode + shrinkResources proof must stay on",
            build.contains("isMinifyEnabled = true") && build.contains("isShrinkResources = true")
        )
        assertTrue(
            "the release signing gate must stay fail-closed (no keystore fallback)",
            build.contains("RELEASE_SIGNING_TASK_NAMES")
        )
    }

    @Test
    fun `memory pressure and lock boundaries recycle the bitmap pool`() {
        val activity = readSource("app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt")
        val trimBody = funBody(activity, "override fun onTrimMemory")
        assertTrue(
            "onTrimMemory must clear the pool (memory-pressure path)",
            trimBody.contains("BitmapPool.clear()")
        )
        val lowBody = funBody(activity, "override fun onLowMemory")
        assertTrue(
            "onLowMemory must clear the pool",
            lowBody.contains("BitmapPool.clear()")
        )
        val vm = readSource("app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt")
        assertTrue(
            "lock() must clear the pool beside DEK zeroization (rendered ink is plaintext)",
            vm.contains("BitmapPool.clear()")
        )
        val policy = readSource("app/src/main/kotlin/com/authorss81/noteflow/services/LayerRenderBudgetPolicy.kt")
        assertTrue(
            "the two-tier guarantee must stay: active page never evicted mid-draw",
            policy.contains("resolveProtectedEviction") && policy.contains("MAX_RESIDENT_BITMAP_BYTES")
        )
        assertTrue(
            "the live layer cap must stay at the PSD export number",
            policy.contains("MAX_LIVE_LAYER_COUNT = 16")
        )
    }

    @Test
    fun `configuration cache flag has no drift`() {
        val props = readSource("gradle.properties")
        assertTrue(
            "gradle.properties must keep configuration-cache=true (phase-211 validated)",
            props.contains("org.gradle.configuration-cache=true")
        )
        val build = readSource("app/build.gradle.kts")
        assertTrue(
            "the producer exception must stay documented as --no-configuration-cache",
            build.contains("--no-configuration-cache")
        )
    }

    private fun funBody(src: String, signature: String): String {
        val sig = src.indexOf(signature)
        if (sig < 0) return ""
        val open = src.indexOf('{', sig)
        if (open < 0) return ""
        var depth = 0
        for (i in open until src.length) {
            if (src[i] == '{') depth++
            if (src[i] == '}') {
                depth--
                if (depth == 0) return src.substring(open, i + 1)
            }
        }
        return ""
    }

    private fun readSource(relativePath: String): String {
        val file = File(repoRoot(), relativePath)
        assertTrue("$relativePath must exist", file.isFile)
        return file.readText()
    }

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile && File(dir, "app").isDirectory) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}
