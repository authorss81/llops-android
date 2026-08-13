package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginEnableResult
import com.authorss81.noteflow.plugins.PluginLifecycleState
import com.authorss81.noteflow.plugins.PluginManager
import com.authorss81.noteflow.plugins.PluginManifest
import com.authorss81.noteflow.plugins.PluginManifestValidator
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.PluginResult
import com.authorss81.noteflow.plugins.ManifestValidation
import com.authorss81.noteflow.plugins.SemanticVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 11: manifest validation must reject invalid plugins with a clear reason
 * — never a crash — and keep them out of routing and enabling.
 */
class PluginManifestValidationTest {

    private val apiLevel = 26

    @Test
    fun validManifestPasses() {
        val manifest = PluginManifest(
            id = "t.valid",
            name = "Valid",
            version = SemanticVersion(1, 2, 3),
            minSupportedApi = apiLevel,
            description = "works",
            capabilities = setOf(PluginCapability.TextTransform)
        )
        assertTrue(PluginManifestValidator.validate(manifest, apiLevel) is ManifestValidation.Valid)
    }

    @Test
    fun blankIdRejectedWithReason() {
        val manifest = PluginManifest(
            id = "  ",
            name = "No Id",
            version = SemanticVersion(1, 0, 0),
            minSupportedApi = apiLevel,
            description = "d",
            capabilities = setOf(PluginCapability.TextTransform)
        )
        val result = PluginManifestValidator.validate(manifest, apiLevel)
        assertTrue(result is ManifestValidation.Invalid)
        assertTrue((result as ManifestValidation.Invalid).errors.any { it.contains("id") })
    }

    @Test
    fun blankNameRejectedWithReason() {
        val manifest = PluginManifest(
            id = "t.noname",
            name = " ",
            version = SemanticVersion(1, 0, 0),
            minSupportedApi = apiLevel,
            description = "d",
            capabilities = setOf(PluginCapability.TextTransform)
        )
        val result = PluginManifestValidator.validate(manifest, apiLevel)
        assertTrue(result is ManifestValidation.Invalid)
        assertTrue((result as ManifestValidation.Invalid).errors.any { it.contains("name") })
    }

    @Test
    fun invalidVersionRejected() {
        val manifest = PluginManifest(
            id = "t.badver",
            name = "Bad Version",
            version = SemanticVersion(-1, 0, 0),
            minSupportedApi = apiLevel,
            description = "d",
            capabilities = setOf(PluginCapability.TextTransform)
        )
        val result = PluginManifestValidator.validate(manifest, apiLevel)
        assertTrue(result is ManifestValidation.Invalid)
        assertTrue((result as ManifestValidation.Invalid).errors.any { it.contains("version") })
    }

    @Test
    fun incompatibleApiRejectedWithReason() {
        val manifest = PluginManifest(
            id = "t.toonew",
            name = "Too New",
            version = SemanticVersion(1, 0, 0),
            minSupportedApi = 34,
            description = "d",
            capabilities = setOf(PluginCapability.TextTransform)
        )
        val result = PluginManifestValidator.validate(manifest, apiLevel)
        assertTrue(result is ManifestValidation.Invalid)
        assertTrue((result as ManifestValidation.Invalid).errors.any { it.contains("API") })
    }

    @Test
    fun noCapabilitiesRejected() {
        val manifest = PluginManifest(
            id = "t.nocap",
            name = "No Capabilities",
            version = SemanticVersion(1, 0, 0),
            minSupportedApi = apiLevel,
            description = "d",
            capabilities = emptySet()
        )
        val result = PluginManifestValidator.validate(manifest, apiLevel)
        assertTrue(result is ManifestValidation.Invalid)
        assertTrue((result as ManifestValidation.Invalid).errors.any { it.contains("capability") })
    }

    @Test
    fun selfDependencyRejected() {
        val manifest = PluginManifest(
            id = "t.self",
            name = "Self Dep",
            version = SemanticVersion(1, 0, 0),
            minSupportedApi = apiLevel,
            description = "d",
            capabilities = setOf(PluginCapability.TextTransform),
            dependencies = setOf("t.self")
        )
        val result = PluginManifestValidator.validate(manifest, apiLevel)
        assertTrue(result is ManifestValidation.Invalid)
        assertTrue((result as ManifestValidation.Invalid).errors.any { it.contains("itself") })
    }

    @Test
    fun registryRejectsInvalidPluginWithoutCrashing() {
        val bad = TestPlugin(
            id = "t.bad",
            name = " ", // blank name -> invalid manifest
            capabilities = setOf(PluginCapability.TextTransform)
        )
        val registry = PluginRegistry(InMemoryEnableStore(), plugins = listOf(bad), currentApiLevel = apiLevel)

        // Constructed fine; the plugin is REJECTED with the validation reason.
        assertTrue(registry.isRejected("t.bad"))
        val info = registry.stateOf("t.bad")
        assertEquals(PluginLifecycleState.REJECTED, info?.state)
        assertTrue(info?.reason?.contains("name") == true)

        // It cannot be enabled...
        val result = registry.setEnabled("t.bad", true)
        assertTrue(result is PluginEnableResult.Refused)
        assertTrue(!registry.isEnabled("t.bad"))

        // ...and cannot be routed (no valid declarer).
        val manager = PluginManager(registry)
        val routed = manager.withPlugin(PluginCapability.TextTransform, null) { it.id }
        assertTrue(routed is PluginResult.Failure)
        assertTrue((routed as PluginResult.Failure).message.contains("No plugin is installed"))
    }

    @Test
    fun duplicateIdRejectsLaterRegistration() {
        val first = TestPlugin("t.dup", name = "First")
        val second = TestPlugin("t.dup", name = "Second")
        val registry = PluginRegistry(InMemoryEnableStore(), plugins = listOf(first, second), currentApiLevel = apiLevel)

        assertTrue(registry.isRejected("t.dup"))
        val errors = registry.validationErrorsOf("t.dup") ?: emptyList()
        assertTrue(errors.any { it.contains("duplicate") })
        val info = registry.stateOf("t.dup")
        assertEquals(PluginLifecycleState.REJECTED, info?.state)
        assertTrue(info?.reason?.contains("duplicate") == true)
    }

    @Test
    fun semanticVersionParseIsStrict() {
        assertEquals(SemanticVersion(1, 2, 3), SemanticVersion.parse("1.2.3"))
        assertTrue(SemanticVersion.parse("1.2") == null)
        assertTrue(SemanticVersion.parse("1.2.3.4") == null)
        assertTrue(SemanticVersion.parse("a.b.c") == null)
        assertTrue(SemanticVersion.parse("1.-2.3") == null)
    }

    @Test
    fun semanticVersionOrdering() {
        assertTrue(SemanticVersion(1, 0, 0) < SemanticVersion(2, 0, 0))
        assertTrue(SemanticVersion(1, 9, 9) < SemanticVersion(1, 10, 0))
        assertTrue(SemanticVersion(2, 0, 0) > SemanticVersion(1, 99, 99))
        assertEquals(SemanticVersion(1, 0, 0), SemanticVersion(1, 0, 0))
    }
}