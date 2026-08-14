package com.authorss81.noteflow.services

import com.authorss81.noteflow.plugins.runtime.ClassLoaderFactory

/**
 * Production [ClassLoaderFactory] (Phase 23): materializes a VERIFIED plugin
 * artifact's DEX via `dalvik.system.DexClassLoader`.
 *
 * The parent is the app's own classloader, so the plugin resolves the
 * framework interfaces (`NoteflowPlugin`, `PluginContext`, serving interfaces)
 * it was compiled against — the plugin module must never import base-app
 * packages beyond those framework types (see `docs/plugin-architecture.md`).
 *
 * `optimizedDirectory` is the app-private DEX cache directory the platform uses
 * for compiled DEX output (ART ignores it on modern devices but it must be a
 * valid path).
 */
class AppClassLoaderFactory(
    private val optimizedDirectory: String
) : ClassLoaderFactory {

    override fun create(artifactPath: String, parent: ClassLoader): ClassLoader =
        dalvik.system.DexClassLoader(
            artifactPath,
            optimizedDirectory,
            null,
            parent
        )
}
