package com.authorss81.noteflow

import android.content.Context
import com.authorss81.noteflow.plugins.CaseChangePlugin
import com.authorss81.noteflow.plugins.InMemoryPluginSettingsStore
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginLogger
import com.authorss81.noteflow.plugins.PluginManifest
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.PluginSettings
import com.authorss81.noteflow.plugins.SemanticVersion
import com.authorss81.noteflow.plugins.TextTransformPlugin
import com.authorss81.noteflow.plugins.store.InMemoryPluginInstallStore
import com.authorss81.noteflow.plugins.store.PluginStoreCatalog
import com.authorss81.noteflow.plugins.store.PluginStoreController
import com.authorss81.noteflow.services.FakePrefs
import com.authorss81.noteflow.services.PluginArtifactStorage
import com.authorss81.noteflow.services.PluginInstallDefaults
import com.authorss81.noteflow.services.PluginPayloadPathPolicy
import com.authorss81.noteflow.services.SettingsPluginInstallStore
import com.authorss81.noteflow.services.settingsOver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase 270 (plugin install-default + delete atomicity + payload path):
 * optional default not-installed, delete-failure still uninstalls, and the
 * `../` payload traversal guard — all pure JVM, no network, no Context.
 */
class Phase270PluginsTest {

    private val caseChangeId = "com.authorss81.noteflow.plugins.casechange"
    private val builtInId = "com.authorss81.noteflow.plugins.rot13"

    // ---------- 1. optional default not-installed (JVM) ----------

    @Test
    fun `optional plugin defaults to NOT installed on fresh prefs`() {
        val store = SettingsPluginInstallStore(settingsOver(FakePrefs()))

        assertFalse(
            "fresh install must list the optional plugin as Not downloaded",
            store.isInstalled(caseChangeId)
        )
        assertTrue(
            "built-ins keep the backward-compatible installed default",
            store.isInstalled(builtInId)
        )
    }

    @Test
    fun `explicit install key wins over the optional default`() {
        val prefs = FakePrefs()
        val store = SettingsPluginInstallStore(settingsOver(prefs))

        store.setInstalled(caseChangeId, true)
        assertTrue(store.isInstalled(caseChangeId))

        store.setInstalled(caseChangeId, false)
        assertFalse(store.isInstalled(caseChangeId))
    }

    @Test
    fun `install-default decision table is key-exists aware`() {
        assertFalse(PluginInstallDefaults.defaultInstalled(caseChangeId))
        assertTrue(PluginInstallDefaults.defaultInstalled(builtInId))
        assertFalse(PluginInstallDefaults.resolveInstalled(caseChangeId, keyExists = false, storedUninstalled = false))
        assertTrue(PluginInstallDefaults.resolveInstalled(builtInId, keyExists = false, storedUninstalled = false))
        assertTrue(PluginInstallDefaults.resolveInstalled(caseChangeId, keyExists = true, storedUninstalled = false))
        assertFalse(PluginInstallDefaults.resolveInstalled(caseChangeId, keyExists = true, storedUninstalled = true))
    }

    // ---------- 2. delete failure still uninstalls (JVM) ----------

    @Test
    fun `delete with throwing asset wipe still uninstalls`() {
        val plugin = ThrowingAssetsPlugin()
        val registry = PluginRegistry(
            enableStore = InMemoryEnableStore(),
            settingsStore = InMemoryPluginSettingsStore(),
            installStore = InMemoryPluginInstallStore(),
            plugins = listOf(plugin),
            currentApiLevel = 26
        )
        val logger = RecordingLogger()
        val controller = PluginStoreController(registry, PluginStoreCatalog(registry), logger)
        registry.installPlugin(plugin, null)

        val outcome = controller.delete(plugin.id, null)

        assertTrue("delete must succeed even when the asset wipe throws", outcome is PluginStoreController.DeleteOutcome.Deleted)
        assertFalse(registry.isInstalled(plugin.id))
        assertFalse(registry.allPlugins.any { it.id == plugin.id })
        assertTrue(
            "the failed wipe must be logged with the fixed code (never the exception text)",
            logger.errors.any { it.contains("code=DELETE_ASSETS_FAILED") }
        )
    }

    @Test
    fun `delete guards the asset wipe and still runs the registry uninstall (source pin)`() {
        val src = readStoreController()
        val deleteBody = src.substringAfter("fun delete(pluginId: String")
        assertTrue("delete must catch a throwing asset wipe", deleteBody.contains("catch ("))
        assertTrue("delete must log the fixed code, not the exception", deleteBody.contains("code=DELETE_ASSETS_FAILED"))
        assertTrue("delete must still uninstall after a failed wipe", deleteBody.contains("uninstallPlugin(pluginId"))
    }

    // ---------- 3. `../` payload rejected (JVM canonical test) ----------

    @Test
    fun `legit payload targets resolve inside the root`() {
        val root = java.io.File(System.getProperty("java.io.tmpdir"), "phase270-root-${System.nanoTime()}")
        try {
            val lib = PluginPayloadPathPolicy.resolveTarget(root, "lib/arm64-v8a/libocr.so")
            assertNotNull(lib)
            assertTrue(lib!!.canonicalPath.startsWith(root.canonicalPath + java.io.File.separator))

            val asset = PluginPayloadPathPolicy.resolveTarget(root, "assets/mlkit-google-ocr-models/model.bin")
            assertNotNull(asset)
            assertTrue(asset!!.canonicalPath.startsWith(root.canonicalPath + java.io.File.separator))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `dot-dot payload targets are rejected`() {
        val root = java.io.File(System.getProperty("java.io.tmpdir"), "phase270-root-${System.nanoTime()}")
        try {
            assertNull(
                "reserved-prefix ../ escape must be skipped, never written",
                PluginPayloadPathPolicy.resolveTarget(root, "assets/mlkit-google-ocr-models/../../../evil.so")
            )
            assertNull(
                "in-root .. relocation (marker-forge shape) must also be skipped",
                PluginPayloadPathPolicy.resolveTarget(root, "assets/mlkit-google-ocr-models/../../evil.so")
            )
            assertNull(
                PluginPayloadPathPolicy.resolveTarget(root, "assets/../../evil.so")
            )
            assertNull(
                PluginPayloadPathPolicy.resolveTarget(root, "lib/arm64-v8a/../../../../evil.so")
            )
            assertNull(
                PluginPayloadPathPolicy.resolveTarget(root, "..")
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `extraction consults the canonical guard (source pin)`() {
        val src = readArtifactStorage()
        assertTrue(
            "extractPayload must route every entry through the canonical guard",
            src.contains("PluginPayloadPathPolicy.resolveTarget(root, targetName)")
        )
        assertFalse(
            "the unguarded File(root, targetName) write path must be gone",
            src.contains("val out = File(root, targetName)")
        )
    }

    // ---------- helpers ----------

    /** A plugin whose asset wipe always throws (half-deleted-assets fault injection). */
    private class ThrowingAssetsPlugin : NoteflowPlugin, TextTransformPlugin {
        override val manifest = PluginManifest(
            id = "t.throwing-assets",
            name = "Throwing Assets",
            version = SemanticVersion(1, 0, 0),
            minSupportedApi = 26,
            description = "Test plugin whose asset wipe throws.",
            capabilities = setOf(PluginCapability.TextTransform)
        )

        override fun availability(context: Context?): PluginAvailability = PluginAvailability.Ok
        override fun onEnable(context: Context?, settings: PluginSettings) {}
        override fun transformText(text: String): String = text
        override fun deleteDownloadedAssets(context: Context?) {
            throw IllegalStateException("simulated half-deleted assets")
        }
    }

    private class RecordingLogger : PluginLogger {
        val errors = mutableListOf<String>()
        override fun lifecycle(event: String, pluginId: String, pluginName: String) {}
        override fun error(pluginId: String, pluginName: String, detail: String) {
            errors.add(detail)
        }
    }

    private fun readStoreController(): String {
        val file = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/plugins/store/PluginStoreController.kt")
        assertTrue("PluginStoreController.kt must exist", file.isFile)
        return file.readText()
    }

    private fun readArtifactStorage(): String {
        val file = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/PluginArtifactStorage.kt")
        assertTrue("PluginArtifactStorage.kt must exist", file.isFile)
        return file.readText()
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
