package com.authorss81.noteflow.services

import com.authorss81.noteflow.plugins.runtime.ClassLoaderFactory
import com.authorss81.noteflow.plugins.runtime.PluginFrameworkClassLoader

/**
 * Production [ClassLoaderFactory] (Phase 23): materializes a VERIFIED plugin
 * artifact's DEX via `dalvik.system.DexClassLoader`.
 *
 * **B1-AUTH-01 fix (phase-46):** the plugin loader no longer gets the app
 * classloader as DIRECT parent. [PluginFrameworkClassLoader] sits between them,
 * so plugin bytecode resolves the plugin-SDK/`plugins.*` framework surface
 * (interfaces it was compiled against) but any OTHER
 * `com.authorss81.noteflow.*` package (`services`, `data`, `ui`, `theme`,
 * `utils`, …) throws `ClassNotFoundException` — `VaultKeyHolder`,
 * `SecurityService`, `NoteflowDatabase`, `SettingsManager` and `NoteRepository`
 * are unreachable in-process even from a compromised signature-verified
 * artifact. Platform/JDK/kotlin/third-party classes still resolve (through the
 * scoped loader → the app classloader → boot).
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
            PluginFrameworkClassLoader(parent)
        )
}
