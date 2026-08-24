# Phase 199 (PERF 2.3): consumer ProGuard rules shipped with :plugin-sdk.
#
# These rules are applied automatically by R8 in every module that CONSUMES
# this one as a dependency (the base app's release minification, and any
# downloadable-plugin module built against this SDK). They are load-bearing
# for the hybrid plugin architecture (docs/plugin-architecture.md):
#
# The base app's classloader is the PARENT of every downloadable plugin's
# DexClassLoader (services/AppClassLoaderFactory.kt). A plugin artifact is
# compiled against THESE type names and resolves them through the parent — so
# if R8 renamed or removed the framework surface inside the host APK, every
# downloadable plugin would die with NoClassDefFoundError at first link,
# regardless of its own signature verification. Class IDENTITY (exact binary
# names + member signatures) is therefore part of the SDK contract and is
# pinned here, where it can never drift from the code it protects.
#
# Scope: ONLY the packages owned by this module (namespace
# com.authorss81.noteflow.plugins). The app-side classes that happen to share
# the `plugins.*` prefix live under sub-packages of the :app module (e.g.
# plugins.runtime host internals) — keeping them too would be harmless for
# correctness but wasteful; the -keep below matches exactly what :plugin-sdk
# ships because consumer rules are evaluated against the library's own jar
# contents only.

-keep class com.authorss81.noteflow.plugins.** { *; }

# The SDK surface is also reflectively instantiated by plugin artifacts via
# the documented entry-point class name (FrameworkPlugin implementations),
# which R8 cannot see — keep constructors reachable by name.
-keepclasseswithmembernames class com.authorss81.noteflow.plugins.** {
    public <init>(...);
}
