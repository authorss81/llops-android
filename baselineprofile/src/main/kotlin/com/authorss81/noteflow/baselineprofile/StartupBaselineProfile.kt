package com.authorss81.noteflow.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * PERF 2.2 (phase-199): COLD-START baseline profile scenario.
 *
 * Covers Application construction → MainActivity → vault open/decryption →
 * first home frame. This is the single highest-value profile for a 2-core /
 * low-RAM device, and it is the path every user walks on every launch.
 *
 * Generation: run with a device attached:
 *
 *     gradle :app:generateBaselineProfile
 *
 * The [CompilationMode.Partial(BaselineProfileMode.Include)] run below is what
 * the baseline-profile Gradle plugin executes against the NON-minified release
 * build to record startup rules; the [BaselineProfileMode.Require] variant is
 * the A/B validation run that proves the generated profile actually speeds the
 * same flow up.
 *
 * Vault note: if the test device holds a PASSWORD-protected vault, startup
 * stops at the unlock screen. That screen IS part of cold start, so its rules
 * are still captured; the interactive scenarios in
 * [NoteOpenAndStrokeBaselineProfile] additionally require a passwordless test
 * vault (or a freshly installed emulator) so the home surface can be reached.
 */
@RunWith(Parameterized::class)
class StartupBaselineProfile(
    private val compilationMode: CompilationMode,
) {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilationMode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = {
            pressHome()
        },
    ) {
        startActivityAndWait()

        // Wait for either the unlocked home surface or the lock/unlock gate —
        // BOTH are legitimate cold-start end states; StartupTimingMetric has
        // already reported by first-frame. The wait only makes the iteration
        // deterministic before the next pressHome().
        device.wait(
            Until.hasObject(By.textContains("InkFlow")),
            STEP_TIMEOUT_MS,
        )
    }

    companion object {
        @Parameterized.Parameters(name = "compilation={0}")
        @JvmStatic
        fun parameters(): List<Array<out Any?>> = listOf(
            arrayOf<Any?>(CompilationMode.None()),
            arrayOf<Any?>(CompilationMode.Partial(BaselineProfileMode.Require)),
        )
    }
}

/**
 * Generation-only twin of [coldStartup] — executed by
 * `gradle :app:generateBaselineProfile` against `nonMinifiedRelease` so the
 * startup path's classes/methods are RECORDED into the baseline profile that
 * ships inside the release APK.
 */
@RunWith(AndroidJUnit4::class)
class StartupProfileGenerator {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun generateColdStartupProfile() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Include),
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = {
            pressHome()
        },
    ) {
        startActivityAndWait()
        device.wait(Until.hasObject(By.textContains("InkFlow")), STEP_TIMEOUT_MS)
    }
}
