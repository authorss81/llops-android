package com.authorss81.testplugins

import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManifest
import com.authorss81.noteflow.plugins.PluginSettings
import com.authorss81.noteflow.plugins.SemanticVersion
import com.authorss81.noteflow.plugins.TextTransformPlugin
import com.authorss81.noteflow.plugins.runtime.PluginContext
import com.authorss81.noteflow.plugins.runtime.PluginContextAware

/**
 * The BENIGN downloadable plugin for the B1-AUTH-01 isolation tests
 * (PluginBytecodeIsolationTest).
 *
 * Unlike the hostile fixtures, this class deliberately lives OUTSIDE the app's
 * private `com.authorss81.noteflow.*` namespace — exactly like a real
 * third-party plugin — so the sandbox ([PluginFrameworkClassLoader]) resolves
 * it and its own companion through the parent. Production plugin classes are
 * packed (with their support classes) into the plugin DEX, resolved by the
 * plugin loader; the test resolves the compiled class from the test classpath,
 * mirroring DownloadablePluginRuntimeTest, but routed THROUGH the scoped
 * parent so the sandbox does not break legitimate plugins.
 *
 * The class body only references the `plugins.*` framework surface plus
 * `android.content.Context` (signature) — nobody else owns a
 * `com.authorss81.noteflow.*` class here.
 */
internal class WhitelistedPlugin : NoteflowPlugin, TextTransformPlugin, PluginContextAware {

    var injectedContext: PluginContext? = null

    override val manifest = PluginManifest(
        id = "com.authorss81.noteflow.plugins.test.downloadable",
        name = "Whitelisted Test Plugin",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = 26,
        description = "A benign plugin that must load and run under the sandbox.",
        capabilities = setOf(PluginCapability.TextTransform),
        permissions = emptySet()
    )

    override fun availability(context: android.content.Context?): PluginAvailability = PluginAvailability.Ok

    override fun onEnable(context: android.content.Context?, settings: PluginSettings) {}

    override fun transformText(text: String): String = "white:${text.uppercase()}"

    override fun setContext(context: PluginContext) {
        injectedContext = context
    }
}