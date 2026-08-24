package com.authorss81.noteflow.baselineprofile

import android.content.Intent
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PERF 2.2 (phase-199): OPEN NOTE → FIRST STROKE baseline profile scenario.
 *
 * Walks the post-startup hot path a note-taking user hits within the first
 * minute: home → editor open → canvas warm-up (first ink stroke). The classes
 * behind this path — EditorScreen/AnnotationCanvas composition, WetBrushEngine,
 * StrokeStabilizer, Room page reads, Gson stroke round-trip — are exactly what
 * feels janky on low-end hardware without an AOT profile.
 *
 * Determinism contract: instead of scraping note lists, this scenario uses the
 * app's OWN supported deep navigation — the quick-capture intent extra
 * (`WidgetLaunchPolicy.EXTRA_QUICK_CAPTURE`) which MainActivity consumes by
 * creating and opening a new page right after authentication.
 *
 * Requirements for a meaningful run:
 *  - passwordless test vault (the harness NEVER types passwords; with a locked
 *    vault the flow below degrades to startup-only, honestly),
 *  - enough free storage for the new page it creates per iteration (each run
 *    makes one "New Page" — delete them after profiling).
 */
@RunWith(AndroidJUnit4::class)
class NoteOpenAndStrokeBaselineProfile {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun openNoteAndFirstStroke() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Include),
        iterations = 5,
        setupBlock = {
            pressHome()
        },
    ) {
        // Cold start WITH the quick-capture extra: Application → MainActivity
        // → vault → addPage("New Page") → EditorScreen, all driven by the host.
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(
                android.content.ComponentName(PACKAGE_NAME, MAIN_ACTIVITY),
            )
            .putExtra(EXTRA_QUICK_CAPTURE, true)
        startActivityAndWait(intent)

        // Give the editor surface time to settle, then exercise the canvas.
        val editorReady = device.wait(
            Until.hasObject(By.descContains("Stroke Width")),
            STEP_TIMEOUT_MS,
        )

        if (editorReady != true) {
            // Honest degradation: a password-gated vault (or a slow first run)
            // never reached the editor. Nothing is faked — this iteration just
            // contributes no editor-path rules; the log line documents why so
            // the maintainer sees it in the generation output.
            println(
                "NoteOpenAndStrokeBaselineProfile: editor not reachable " +
                    "(locked vault or slow cold start) — iteration recorded " +
                    "startup-path rules only."
            )
            return@measureRepeated
        }

        // First-stroke path: three short pen gestures across the central
        // canvas band. The editor is fullscreen; toolbars hug the top/bottom,
        // so 35%..65% height is canvas. This warms WetBrushEngine, the
        // stabilizer, pressure handling, live-stroke preview and the first
        // committed-layer raster.
        repeat(3) { index ->
            val y = (device.displayHeight * (0.40f + 0.08f * index)).toInt()
            device.swipe(
                (device.displayWidth * 0.30f).toInt(),
                y,
                (device.displayWidth * 0.70f).toInt(),
                y + (device.displayHeight * 0.05f).toInt(),
                24, // steps — enough points for the stabilizer to engage
            )
            device.waitForIdle(STEP_TIMEOUT_MS)
        }

        // Leave through Back so the next iteration starts from a clean stack;
        // the save path (encrypted stroke write) runs during this teardown.
        device.pressBack()
        device.waitForIdle(STEP_TIMEOUT_MS)
    }

    private companion object {
        const val MAIN_ACTIVITY = "com.authorss81.noteflow.MainActivity"
    }
}
