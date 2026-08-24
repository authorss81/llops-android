# Phase 199 (PERF 2.3): consumer ProGuard rules shipped with :plugin-sdk.
#
# REVIEW FIX (phase-199 review finding 3): R8 consumer rules are merged into
# the CONSUMING BUILD's GLOBAL config — they match every class whose name fits
# the pattern in the whole app, NOT only classes inside this module's jar.
# The original `-keep class com.authorss81.noteflow.plugins.** { *; }`
# therefore also pinned the HOST APP's own plugins.* subpackages (runtime host
# internals, weather/dictionary/websearch cores), silently undoing the scoped
# member-name-only keeps in app/proguard-rules.pro and costing size +
# obfuscation inside the very phase that added shrinkResources.
#
# Scope now matches EXACTLY what :plugin-sdk ships (source-pinned by
# Phase199ReleaseShrinkTest):
#  - root package `com.authorss81.noteflow.plugins.*` (SINGLE star = direct
#    members and their nested `$` types only — subpackages do NOT match):
#    FrameworkPlugin, OcrAndTranslationPlugin, PluginCapability (+ its nested
#    sealed data objects like PluginCapability$TextTransform), PluginLogPolicy,
#    PluginManifest, PluginSettings;
#  - `plugins.runtime.PluginContext` / `PluginEntry` / `PluginVersion` named
#    explicitly, because the HOST APP also owns `plugins.runtime.*` classes
#    (PluginEntryCodec, RuntimePluginLoader, HostedPluginManifest/…Parser) that
#    a runtime wildcard would wrongly keep again;
#  - any future SDK SUBPACKAGE must be listed here explicitly (a new wildcard
#    is exactly how this file leaked last time).
#
# Why keep at all: the base app's classloader is the PARENT of every
# downloadable plugin artifact's DexClassLoader (plugins/runtime/
# PluginFrameworkClassLoader.kt + RuntimePluginLoader.kt). A plugin artifact
# is compiled against THESE type names and resolves them through the parent —
# if R8 renamed or removed the framework surface inside the host APK, every
# downloadable plugin would die with NoClassDefFoundError at first link,
# regardless of its own signature verification. Class IDENTITY (exact binary
# names + member signatures) is part of the SDK contract and is pinned here,
# where it can never drift from the code it protects.

-keep class com.authorss81.noteflow.plugins.* { *; }
-keep class com.authorss81.noteflow.plugins.runtime.PluginContext { *; }
-keep class com.authorss81.noteflow.plugins.runtime.PluginEntry { *; }
-keep class com.authorss81.noteflow.plugins.runtime.PluginVersion { *; }

# The SDK surface is also reflectively instantiated by plugin artifacts via
# the documented entry-point class name (FrameworkPlugin implementations),
# which R8 cannot see — keep constructors reachable by name (same exact
# scope as above).
-keepclasseswithmembernames class com.authorss81.noteflow.plugins.* {
    public <init>(...);
}
