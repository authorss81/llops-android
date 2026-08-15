package com.authorss81.noteflow.plugins.runtime

import java.lang.ClassLoader

/**
 * The scoped, interface-only parent classloader for downloadable plugin bytecode
 * (B1-AUTH-01 fix, phase-46 — see `docs/security-report.md`).
 *
 * Before this class, plugin DEX was loaded with the app classloader as DIRECT
 * parent ([com.authorss81.noteflow.services.AppClassLoaderFactory]), so a
 * signature-verified artifact could resolve ANY base-app class — including
 * `com.authorss81.noteflow.services.VaultKeyHolder`, `SecurityService`,
 * `NoteflowDatabase`, `SettingsManager`, the repository and the DB — and reach
 * the DEK/vault in-process, completely bypassing the capability facade
 * (`PluginContext`). The whitelist was a documentation convention, not a
 * boundary.
 *
 * `PluginFrameworkClassLoader` sits BETWEEN the plugin loader (its parent) and
 * the app classloader (our parent) and enforces the ONLY sanctioned visibility:
 *
 * - everything under `com.authorss81.noteflow.plugins.*` — the plugin-SDK
 *   framework surface plus the serving interfaces the artifacts are compiled
 *   against — resolves through the app classloader, unchanged;
 * - every OTHER `com.authorss81.noteflow.*` package (`services`, `data`, `ui`,
 *   `theme`, `utils`, the activity root classes, any future package) is refused
 *   with [ClassNotFoundException] — fail-closed for packages added later;
 * - everything outside the app namespace (`java.*`, `javax.*`, `android.*`,
 *   `kotlin.*`, `kotlinx.*`, third-party coordinates) delegates to the app
 *   classloader, so platform/JDK/stdio/coroutines types stay resolvable.
 *
 * Reflection reach-through is closed by the same check: `Class.forName(...)`
 * from plugin code resolves through the plugin's own loader chain, lands here,
 * and is refused identically. So even bytecode that never statically imports a
 * forbidden class cannot fabricate a handle to the vault.
 *
 * NOTE (phase-46 review): the whole `plugins.*` namespace is treated as the
 * shared framework surface and is resolvable by artifacts. Host internals under
 * `plugins.*` MUST never expose a vault handle (a field/parameter/supertype of
 * `VaultKeyHolder`, `SecurityService`, `NoteflowDatabase`, `SettingsManager`
 * or `NoteRepository`) or an artifact could reach it through THIS loader.
 * That invariant is pinned by `PluginBytecodeIsolationTest`
 * (`no vault-handle types are referenced by code in the resolvable plugin
 * surface`) — keep it green when touching plugin-host classes.
 *
 * Pure JVM (extends [java.lang.ClassLoader]) — used directly by production
 * ([com.authorss81.noteflow.services.AppClassLoaderFactory]) and by the
 * pure-JVM runtime tests, so the sandbox semantics are provable without a
 * device.
 */
class PluginFrameworkClassLoader(
    parent: ClassLoader
) : ClassLoader(parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*>? {
        if (isAppPrivateForbidden(name)) {
            throw ClassNotFoundException(
                "class '$name' is behind the plugin sandbox: the app's private " +
                    "packages (services, data, ui, theme, utils, …) are never re-exported to plugins. " +
                    "Downloadable plugins may only resolve the plugins.* framework surface."
            )
        }
        return super.loadClass(name, resolve)
    }

    companion object {
        /** The framework namespace a downloadable plugin may resolve (plugin-SDK
         *  contracts + serving interfaces, which live under `plugins.*`). */
        const val FRAMEWORK_PACKAGE = "com.authorss81.noteflow.plugins."

        /** The app's own root namespace — everything under it is sandboxed. */
        const val APP_PACKAGE = "com.authorss81.noteflow."

        /**
         * True when [name] is a base-app class the plugin must never resolve:
         * any `com.authorss81.noteflow.*` class OUTSIDE the sanctioned
         * [FRAMEWORK_PACKAGE] surface. Fail-closed — an unknown future package
         * is refused as loudly as the known secret-bearing ones today.
         */
        fun isAppPrivateForbidden(name: String): Boolean =
            name.startsWith(APP_PACKAGE) && !name.startsWith(FRAMEWORK_PACKAGE)
    }
}