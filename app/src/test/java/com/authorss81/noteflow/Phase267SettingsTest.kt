package com.authorss81.noteflow

import com.authorss81.noteflow.services.AutoLockPolicy
import com.authorss81.noteflow.services.FakePrefs
import com.authorss81.noteflow.services.MasterPasswordCredential
import com.authorss81.noteflow.services.SettingsPrefsPolicy
import com.authorss81.noteflow.services.settingsOver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 267 (settings commit/clamp/keys/threads): the async-loss + unclamped /
 * corrupt prefs + KeyStore-race fixes, exercised against a REAL
 * [com.authorss81.noteflow.services.SettingsManager] over fake prefs plus
 * source pins on the security wipes and the MainActivity/ViewModel wiring.
 */
class Phase267SettingsTest {

    // ---------- security wipes use commit (source pin) ----------

    @Test
    fun `wipePluginState persists with commit not apply`() {
        val src = readSettingsManager()
        val body = functionBody(src, "fun wipePluginState")
        assertTrue("wipePluginState must exist", body != null)
        assertTrue("wipePluginState must disk-acknowledge via commit()", body!!.contains(".commit()"))
        assertFalse("wipePluginState must not use fire-and-forget apply()", body.contains(".apply()"))
        assertTrue("wipePluginState must return the commit result", body.contains("): Boolean"))
    }

    @Test
    fun `clearSecuritySettings persists with commit not apply`() {
        val src = readSettingsManager()
        val body = functionBody(src, "fun clearSecuritySettings")
        assertTrue("clearSecuritySettings must exist", body != null)
        assertTrue("clearSecuritySettings must disk-acknowledge via commit()", body!!.contains(".commit()"))
        assertFalse("clearSecuritySettings must not use fire-and-forget apply()", body.contains(".apply()"))
        assertTrue("clearSecuritySettings must return the commit result", body.contains("): Boolean"))
    }

    @Test
    fun `migration flags persist with commit not apply`() {
        val src = readSettingsManager()
        for (flag in listOf("fieldAadMigrated", "noteBodyPlaintextMigrated", "voiceNotesEncryptedMigrated")) {
            val body = functionBody(src, "var $flag")
            assertTrue("$flag must exist", body != null)
            assertTrue("$flag must disk-acknowledge via commit()", body!!.contains(".commit()"))
        }
    }

    @Test
    fun `removeMasterPassword aborts when the credential wipe fails`() {
        val vm = readNoteflowViewModel()
        assertTrue(
            "removeMasterPassword must abort before flipping state when clearSecuritySettings fails",
            vm.contains("if (!settings.clearSecuritySettings()) return false")
        )
    }

    @Test
    fun `MainActivity reuses the ViewModel singleton settings`() {
        val main = readMainActivity()
        assertFalse(
            "MainActivity must not construct a second SettingsManager per composition",
            main.contains("SettingsManager(this")
        )
        assertTrue(
            "FloatingWindowNoticeLauncher must receive viewModel.settings",
            main.contains("settingsManager = viewModel.settings")
        )
    }

    // ---------- wipe behavior (commit result is honest) ----------

    @Test
    fun `wipePluginState removes every namespaced key and reports true`() {
        val prefs = FakePrefs()
        val settings = settingsOver(prefs)
        settings.setPluginEnabled("p", true)
        settings.setPluginSetting("p", "k", "v")
        settings.setPluginDownloadConsented("p", true)

        assertTrue(settings.wipePluginState("p"))

        assertFalse(settings.isPluginEnabled("p"))
        assertNull(settings.getPluginSetting("p", "k"))
        assertFalse(settings.isPluginDownloadConsented("p"))
    }

    @Test
    fun `wipePluginState reports false and keeps keys when commit fails`() {
        val prefs = FakePrefs()
        val settings = settingsOver(prefs)
        settings.setPluginEnabled("p", true)
        settings.setPluginSetting("p", "k", "v")

        prefs.failNextCommit = true
        assertFalse("a failed disk write must surface, not silently drop state", settings.wipePluginState("p"))

        assertTrue("keys must survive a failed wipe", settings.isPluginEnabled("p"))
        assertEquals("v", settings.getPluginSetting("p", "k"))
    }

    @Test
    fun `clearSecuritySettings reports false and keeps the credential when commit fails`() {
        val prefs = FakePrefs()
        val settings = settingsOver(prefs)
        prefs.map["master_password_credential"] =
            MasterPasswordCredential.serialize(ByteArray(16) { it.toByte() }, "d3JhcHBlZA==")
        settings.biometricAuthEnabled = true

        prefs.failNextCommit = true
        assertFalse(settings.clearSecuritySettings())

        assertTrue("credential must survive a failed wipe", settings.hasMasterPassword)
        assertTrue(settings.biometricAuthEnabled)
    }

    // ---------- autoLock sanitize bounds (JVM) ----------

    @Test
    fun `AutoLockPolicy sanitize clamps to 0-86400`() {
        assertEquals(0, AutoLockPolicy.sanitize(-1))
        assertEquals(0, AutoLockPolicy.sanitize(0))
        assertEquals(300, AutoLockPolicy.sanitize(300))
        assertEquals(86400, AutoLockPolicy.sanitize(86400))
        assertEquals(86400, AutoLockPolicy.sanitize(Int.MAX_VALUE))
    }

    @Test
    fun `autoLockTimeoutSeconds read and write are sanitized`() {
        val prefs = FakePrefs()
        prefs.map["auto_lock_timeout_seconds"] = -1
        assertEquals("ADB -1 must not disable the lock", 0, settingsOver(prefs).autoLockTimeoutSeconds)

        prefs.map["auto_lock_timeout_seconds"] = Int.MAX_VALUE
        assertEquals(86400, settingsOver(prefs).autoLockTimeoutSeconds)

        val prefs2 = FakePrefs()
        val settings = settingsOver(prefs2)
        settings.autoLockTimeoutSeconds = -5
        assertEquals(0, settings.autoLockTimeoutSeconds)
        assertEquals("disk must hold the sanitized value", 0, prefs2.map["auto_lock_timeout_seconds"])
    }

    // ---------- corrupt enum falls back (JVM) ----------

    @Test
    fun `corrupt enum keys fall back to defaults on read`() {
        val prefs = FakePrefs()
        prefs.map["pressure_curve_key"] = "turbo"
        prefs.map["symmetry_mode_key"] = "diagonal"
        prefs.map["eraser_mode_key"] = "LASER"
        prefs.map["brush_color_mode_key"] = "NEON"
        prefs.map["device_tier_override"] = "TOASTER"
        val settings = settingsOver(prefs)

        assertEquals("linear", settings.pressureCurveKey)
        assertEquals("off", settings.symmetryModeKey)
        assertEquals("STROKE", settings.eraserModeKey)
        assertEquals("SOLID", settings.brushColorModeKey)
        assertNull("unknown tier override must read as auto-detect", settings.deviceTierOverride)
    }

    @Test
    fun `corrupt enum writes are normalized, never persisted`() {
        val settings = settingsOver(FakePrefs())
        settings.pressureCurveKey = "turbo"
        settings.symmetryModeKey = "diagonal"
        settings.eraserModeKey = "laser"
        settings.brushColorModeKey = "NEON"
        settings.deviceTierOverride = "TOASTER"

        assertEquals("linear", settings.pressureCurveKey)
        assertEquals("off", settings.symmetryModeKey)
        // Case-insensitive known value normalizes to canonical casing.
        assertEquals("STROKE", settings.eraserModeKey)
        assertEquals("SOLID", settings.brushColorModeKey)
        assertNull(settings.deviceTierOverride)

        settings.eraserModeKey = "partial"
        settings.deviceTierOverride = "LOW_END"
        assertEquals("PARTIAL", settings.eraserModeKey)
        assertEquals("LOW_END", settings.deviceTierOverride)
    }

    // ---------- counters / lockout ceilings ----------

    @Test
    fun `tutorial resume index is clamped on read and write`() {
        val prefs = FakePrefs()
        prefs.map["tutorial_resume_index"] = -3
        assertEquals(0, settingsOver(prefs).tutorialResumeIndex)
        prefs.map["tutorial_resume_index"] = Int.MAX_VALUE
        assertEquals(SettingsPrefsPolicy.MAX_TUTORIAL_RESUME_INDEX, settingsOver(prefs).tutorialResumeIndex)

        val settings = settingsOver(FakePrefs())
        settings.tutorialResumeIndex = -1
        assertEquals(0, settings.tutorialResumeIndex)
    }

    @Test
    fun `failed attempts and lockout deadline are capped`() {
        val prefs = FakePrefs()
        prefs.map["failed_unlock_attempts"] = Int.MAX_VALUE
        assertEquals(
            SettingsPrefsPolicy.MAX_FAILED_ATTEMPTS_TRACKED,
            settingsOver(prefs).failedUnlockAttempts
        )
        prefs.map["failed_unlock_attempts"] = -4
        assertEquals(0, settingsOver(prefs).failedUnlockAttempts)

        val farFuture = System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000
        prefs.map["lockout_until_epoch_ms"] = farFuture
        val capped = settingsOver(prefs).lockoutUntilEpochMs
        assertTrue(
            "ADB far-future lockout must not become permanent (capped at now + one max window)",
            capped <= System.currentTimeMillis() + SettingsPrefsPolicy.MAX_LOCKOUT_DELAY_MS
        )
    }

    // ---------- float dials ----------

    @Test
    fun `velocity and nib dials are clamped on read and write`() {
        val prefs = FakePrefs()
        prefs.map["brush_velocity_modulation_intensity"] = 99f
        prefs.map["brush_calligraphic_nib_angle_deg"] = 720f
        prefs.map["brush_chisel_nib_angle_deg"] = -999f
        val settings = settingsOver(prefs)

        assertEquals(1f, settings.velocityModulationIntensity)
        assertEquals(90f, settings.calligraphicNibAngleDeg)
        assertEquals(-45f, settings.chiselNibAngleDeg)

        settings.velocityModulationIntensity = -2f
        settings.calligraphicNibAngleDeg = Float.NaN
        assertEquals(0f, settings.velocityModulationIntensity)
        assertEquals(
            SettingsPrefsPolicy.DEFAULT_CALLIGRAPHIC_NIB_ANGLE_DEG,
            settings.calligraphicNibAngleDeg
        )
    }

    // ---------- unbounded-string budgets ----------

    @Test
    fun `over-budget template and preset JSON is refused on write and safe on read`() {
        val prefs = FakePrefs()
        val settings = settingsOver(prefs)
        settings.templatePrefsJson = "x".repeat(SettingsPrefsPolicy.MAX_TEMPLATE_PREFS_CHARS + 1)
        assertEquals("over-budget write must keep the old value", "{}", settings.templatePrefsJson)
        settings.importedBrushPresetsJson = "x".repeat(SettingsPrefsPolicy.MAX_IMPORTED_PRESETS_CHARS + 1)
        assertEquals("[]", settings.importedBrushPresetsJson)

        prefs.map["template_prefs_json"] = "y".repeat(SettingsPrefsPolicy.MAX_TEMPLATE_PREFS_CHARS + 1)
        prefs.map["imported_brush_presets_json"] = "y".repeat(SettingsPrefsPolicy.MAX_IMPORTED_PRESETS_CHARS + 1)
        val reread = settingsOver(prefs)
        assertEquals("{}", reread.templatePrefsJson)
        assertEquals("[]", reread.importedBrushPresetsJson)
    }

    @Test
    fun `absurd texture paths are refused`() {
        val settings = settingsOver(FakePrefs())
        settings.setPaperTexturePathForPage("page1", "ok/path.png")
        assertEquals("ok/path.png", settings.paperTexturePathForPage("page1"))
        settings.setPaperTexturePathForPage("page1", "z".repeat(SettingsPrefsPolicy.MAX_TEXTURE_PATH_CHARS + 1))
        assertEquals("old value must survive a refused write", "ok/path.png", settings.paperTexturePathForPage("page1"))
    }

    // ---------- credential structural check ----------

    @Test
    fun `garbage credential blob stays locked but is structurally flagged`() {
        val prefs = FakePrefs()
        prefs.map["master_password_credential"] = "garbage-no-pipes"
        val settings = settingsOver(prefs)

        assertTrue("fail closed: garbage must still count as protected", settings.hasMasterPassword)
        assertTrue("garbage must be structurally flagged", settings.hasCorruptMasterPasswordCredential)
    }

    @Test
    fun `parseable credential is not flagged corrupt`() {
        val prefs = FakePrefs()
        prefs.map["master_password_credential"] =
            MasterPasswordCredential.serialize(ByteArray(16) { it.toByte() }, "d3JhcHBlZA==")
        val settings = settingsOver(prefs)

        assertTrue(settings.hasMasterPassword)
        assertFalse(settings.hasCorruptMasterPasswordCredential)
    }

    // ---------- recent searches fail closed without a keystore (JVM) ----------

    @Test
    fun `recent searches fail closed when the keystore is unavailable`() {
        val prefs = FakePrefs()
        val settings = settingsOver(prefs)
        settings.setRecentSearches(listOf("hello", "world"))

        assertTrue(
            "no keystore on the JVM means nothing may be written (never plaintext)",
            prefs.map.keys.none { it.startsWith("search_recent_") }
        )
        assertTrue(settings.getRecentSearches().isEmpty())
    }

    // ---------- prefs version ----------

    @Test
    fun `stampPrefsVersion commits the current version`() {
        val prefs = FakePrefs()
        val settings = settingsOver(prefs)
        assertEquals(0, settings.prefsVersion)
        assertTrue(settings.stampPrefsVersion())
        assertEquals(SettingsPrefsPolicy.CURRENT_PREFS_VERSION, settings.prefsVersion)

        // Phase-267 review fix: the already-current stamp short-circuits, so
        // force a real write (downgrade the version) before failing the commit.
        prefs.map["prefs_version"] = 0
        prefs.failNextCommit = true
        assertFalse(settings.stampPrefsVersion())
    }

    // ---------- phase-267 review fixes ----------

    @Test
    fun `stampPrefsVersion skips the commit when already current`() {
        val prefs = FakePrefs()
        val settings = settingsOver(prefs)
        assertTrue(settings.stampPrefsVersion())
        assertEquals(1, prefs.commitCount)

        assertTrue("an already-current stamp must not cost a disk round-trip", settings.stampPrefsVersion())
        assertEquals(1, prefs.commitCount)
        assertEquals(SettingsPrefsPolicy.CURRENT_PREFS_VERSION, settings.prefsVersion)
    }

    @Test
    fun `failed-attempt increment reads max of flow and disk`() {
        val vm = readNoteflowViewModel()
        val body = functionBody(vm, "private fun recordFailedMasterPasswordVerification")
        assertTrue("recordFailed* must exist", body != null)
        assertTrue(
            "an ADB-inflated disk counter must not be written down from a stale in-memory flow",
            body!!.contains("maxOf(_failedUnlockAttempts.value, settings.failedUnlockAttempts)")
        )
    }

    @Test
    fun `over-budget texture path reads as null and is skipped by orphan sweep`() {
        val prefs = FakePrefs()
        prefs.map["paper_texture_p1"] = "ok/path.png"
        prefs.map["paper_texture_p2"] = "z".repeat(SettingsPrefsPolicy.MAX_TEXTURE_PATH_CHARS + 1)
        val settings = settingsOver(prefs)

        assertEquals("ok/path.png", settings.paperTexturePathForPage("p1"))
        assertNull("ADB multi-MB blob must read as absent", settings.paperTexturePathForPage("p2"))
        assertEquals(
            "orphan sweep must not treat the blob as referenced",
            listOf("ok/path.png"),
            settings.allPaperTexturePaths()
        )
    }

    @Test
    fun `empty texture path clears like null`() {
        val settings = settingsOver(FakePrefs())
        settings.setPaperTexturePathForPage("page1", "ok/path.png")
        assertEquals("ok/path.png", settings.paperTexturePathForPage("page1"))
        settings.setPaperTexturePathForPage("page1", "")
        assertNull("empty must clear, not silently keep the old texture", settings.paperTexturePathForPage("page1"))
    }

    @Test
    fun `uninstall refuses when the settings wipe fails`() {
        val registry = readPluginRegistry()
        val body = functionBody(registry, "fun uninstallPlugin")
        assertTrue("uninstallPlugin must exist", body != null)
        assertTrue(
            "the disk-acknowledged wipe must gate the uninstall",
            body!!.contains("if (!settingsStore.removeAll(pluginId))")
        )
        assertTrue(
            "a failed wipe must refuse, never report Uninstalled",
            body.contains("PluginUninstallResult.Refused")
        )

        val sdk = readPluginSettingsSdk()
        assertTrue(
            "PluginSettingsStore.removeAll must surface the wipe result",
            sdk.contains("fun removeAll(pluginId: String): Boolean")
        )
    }

    @Test
    fun `isMasterPasswordValid documents the no-burn-on-corrupt rule`() {
        val vm = readNoteflowViewModel()
        val body = functionBody(vm, "fun isMasterPasswordValid")
        assertTrue("isMasterPasswordValid must exist", body != null)
        assertTrue(
            "corrupt credential must return before any counter-touching line",
            body!!.contains("if (settings.masterPasswordCredentialOrLegacy == null) return false")
        )
    }

    @Test
    fun `auto-lock sanitize has a single implementation`() {
        assertEquals(0, SettingsPrefsPolicy.sanitizeAutoLockTimeoutSeconds(-1))
        assertEquals(86400, SettingsPrefsPolicy.sanitizeAutoLockTimeoutSeconds(Int.MAX_VALUE))
        val policy = readSettingsPrefsPolicy()
        assertTrue(
            "SettingsPrefsPolicy must delegate to AutoLockPolicy.sanitize so the window cannot diverge",
            policy.contains("AutoLockPolicy.sanitize(value)")
        )
    }

    // ---------- file readers ----------

    private fun readSettingsManager(): String {
        val file = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/SettingsManager.kt")
        assertTrue("SettingsManager.kt must exist", file.isFile)
        return file.readText()
    }

    private fun readNoteflowViewModel(): String {
        val file = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt")
        assertTrue("NoteflowViewModel.kt must exist", file.isFile)
        return file.readText()
    }

    private fun readMainActivity(): String {
        val file = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt")
        assertTrue("MainActivity.kt must exist", file.isFile)
        return file.readText()
    }

    private fun readPluginRegistry(): String {
        val file = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/plugins/PluginRegistry.kt")
        assertTrue("PluginRegistry.kt must exist", file.isFile)
        return file.readText()
    }

    private fun readPluginSettingsSdk(): String {
        val file = java.io.File(repoRoot(), "plugin-sdk/src/main/kotlin/com/authorss81/noteflow/plugins/PluginSettings.kt")
        assertTrue("PluginSettings.kt must exist", file.isFile)
        return file.readText()
    }

    private fun readSettingsPrefsPolicy(): String {
        val file = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/SettingsPrefsPolicy.kt")
        assertTrue("SettingsPrefsPolicy.kt must exist", file.isFile)
        return file.readText()
    }

    private fun functionBody(src: String, declaration: String): String? {
        val lines = src.lines()
        val start = lines.indexOfFirst { it.contains(declaration) }
        if (start < 0) return null
        // Take until the next member declaration at the same indent (never
        // bleed into the following function, whose own apply() calls would
        // false-positive the commit-not-apply pins).
        val end = lines.drop(start + 1).indexOfFirst {
            it.startsWith("    fun ") || it.startsWith("    var ") || it.startsWith("    val ")
        }.let { if (it < 0) lines.size else start + 1 + it }
        return lines.subList(start, end).joinToString("\n")
    }

    private fun repoRoot(): java.io.File {
        val cwd = java.io.File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (java.io.File(dir, "gradle/libs.versions.toml").isFile &&
                java.io.File(dir, "app").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}
