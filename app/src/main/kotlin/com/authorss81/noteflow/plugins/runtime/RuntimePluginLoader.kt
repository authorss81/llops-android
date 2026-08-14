package com.authorss81.noteflow.plugins.runtime

import com.authorss81.noteflow.plugins.NoteflowPlugin
import java.io.File
import java.util.Properties
import java.util.jar.JarFile

/**
 * Creates the plugin [ClassLoader] that materializes a VERIFIED artifact's DEX.
 *
 * Production ([com.authorss81.noteflow.services.AppClassLoaderFactory]) uses
 * `dalvik.system.DexClassLoader` with the app classloader as parent. Tests
 * inject a `URLClassLoader` over a signed test jar so the whole load path
 * (find class → validate interface → instantiate → wire context) is exercised
 * in pure JVM without a device.
 */
fun interface ClassLoaderFactory {
    fun create(artifactPath: String, parent: ClassLoader): ClassLoader
}

/**
 * A plugin that wants its capability facade at load time implements this and
 * receives its [PluginContext] via [setContext] right after instantiation
 * (Phase 23 runtime loader).
 *
 * The plugin code NEVER constructs or reaches for a `Context`, DB, keystore,
 * `EncryptionService` or decrypted-content handle — it only ever holds the
 * [PluginContext] the runtime gives it. Bundled plugins do not need this
 * interface (they are wired directly by the app).
 *
 * PHASE 29: [PluginContextAware] moved into `plugin-sdk` (same package, so the
 * simple name resolves here without an import) — downloadable plugin artifacts
 * compile against the same type the base app resolves.
 */

/** The `META-INF/plugin-entry.properties` descriptor of a downloadable plugin. */
data class PluginEntryDescriptor(
    val pluginId: String,
    val pluginClass: String
)

/**
 * Materializes a VERIFIED downloadable plugin artifact into a runnable
 * [NoteflowPlugin] (Phase 23 — fills the Phase-22 `PluginRuntime.load` seam).
 *
 * The artifact must already have passed [ArtifactSignatureVerifier] — the
 * caller ([SignatureVerifiedPluginRuntime]) re-verifies on EVERY load. This
 * loader only:
 *
 * 1. reads the artifact's `META-INF/plugin-entry.properties` descriptor;
 * 2. creates a plugin [ClassLoader] via [ClassLoaderFactory];
 * 3. loads the declared plugin class and instantiates it (no-arg constructor);
 * 4. checks it really is a [NoteflowPlugin] and that its manifest id matches
 *    the catalog [PluginEntry.id] (identity must not drift);
 * 5. hands it a capability-aware [PluginContext] via [PluginContextAware].
 *
 * The compile-time registry API is untouched: downloadable plugins join the
 * SAME registry as installable entries via
 * [com.authorss81.noteflow.plugins.PluginRegistry.installPlugin] — existing
 * plugins are unaffected. Never throws; failures are typed [RuntimeOutcome].
 *
 * @param classLoaderFactory where the plugin [ClassLoader] comes from.
 * @param contextFactory how the capability facade is built for [entry].
 * @param parentClassLoader the parent for the plugin loader (the app's
 *   classloader in production, so the plugin resolves the framework interfaces
 *   it was compiled against).
 */
class RuntimePluginLoader(
    private val classLoaderFactory: ClassLoaderFactory,
    private val contextFactory: PluginContextFactory = PluginContextFactory.DEFAULT,
    private val parentClassLoader: ClassLoader = RuntimePluginLoader::class.java.classLoader
) {

    /**
     * Load [entry]'s plugin from the VERIFIED artifact at [artifactPath].
     * Returns [RuntimeOutcome.Success] with the materialized plugin + its
     * capability facade, or a typed failure. Never throws.
     */
    fun load(entry: PluginEntry, artifactPath: String): RuntimeOutcome<LoadedPlugin> {
        if (entry.source != PluginEntrySource.REMOTE) {
            return RuntimeOutcome.Failed(
                "plugin '${entry.id}' is bundled — bundled plugins are compiled in and are not loaded at runtime."
            )
        }
        val descriptor = readDescriptor(artifactPath)
            ?: return RuntimeOutcome.Failed(
                "artifact for '${entry.id}' is missing the '${DESCRIPTOR_PATH}' plugin descriptor."
            )
        if (descriptor.pluginId != entry.id) {
            return RuntimeOutcome.Failed(
                "artifact declares plugin id '${descriptor.pluginId}' but the catalog entry is '${entry.id}'. Refusing to load."
            )
        }
        val classLoader = classLoaderFactory.create(artifactPath, parentClassLoader)
        val clazz = try {
            classLoader.loadClass(descriptor.pluginClass)
        } catch (e: Throwable) {
            return RuntimeOutcome.Failed(
                "could not load plugin class '${descriptor.pluginClass}' (${e::class.java.simpleName})."
            )
        }
        val instance = try {
            clazz.getDeclaredConstructor().newInstance()
        } catch (e: Throwable) {
            return RuntimeOutcome.Failed(
                "could not instantiate plugin '${descriptor.pluginClass}' (${e::class.java.simpleName})."
            )
        }
        if (instance !is NoteflowPlugin) {
            return RuntimeOutcome.Failed(
                "plugin class '${descriptor.pluginClass}' does not implement NoteflowPlugin. Refusing to load."
            )
        }
        if (instance.manifest.id != entry.id) {
            return RuntimeOutcome.Failed(
                "plugin manifest id '${instance.manifest.id}' does not match catalog id '${entry.id}'. Refusing to load."
            )
        }
        val context = contextFactory.contextFor(entry)
        (instance as? PluginContextAware)?.setContext(context)
        return RuntimeOutcome.Success(LoadedPlugin(entry, instance, context))
    }

    private fun readDescriptor(artifactPath: String): PluginEntryDescriptor? = try {
        JarFile(File(artifactPath)).use { jar ->
            val entry = jar.getJarEntry(DESCRIPTOR_PATH) ?: return null
            val props = Properties()
            jar.getInputStream(entry).use { props.load(it) }
            val pluginId = props.getProperty("plugin.id")?.trim().orEmpty()
            val pluginClass = props.getProperty("plugin.class")?.trim().orEmpty()
            if (pluginId.isBlank() || pluginClass.isBlank()) return null
            PluginEntryDescriptor(pluginId, pluginClass)
        }
    } catch (_: Throwable) {
        null
    }

    companion object {
        /** Where the artifact declares its plugin id + entry class. */
        const val DESCRIPTOR_PATH = "META-INF/plugin-entry.properties"
    }
}
