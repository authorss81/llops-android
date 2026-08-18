package com.authorss81.noteflow.services

/**
 * Phase-130: pure-JVM decision table for the FLAG_SECURE window protection.
 *
 * AGENTS.md hard rule: "FLAG_SECURE is applied in non-debug builds". Debug /
 * emulator streaming environments (cloud Android emulators that mirror the
 * display buffer) must render the UI instead of a pitch-black surface, while
 * release builds keep screenshot / recording / recents-thumbnail protection.
 *
 * `MainActivity` gates the `window.addFlags(FLAG_SECURE)` call on
 * [shouldApplySecureFlag] fed with `BuildConfig.DEBUG`:
 *
 *   if (SecureWindowPolicy.shouldApplySecureFlag(BuildConfig.DEBUG)) {
 *       window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
 *   }
 *
 * Debug (`BuildConfig.DEBUG == true`) → `false` (flag NOT applied, so an
 * emulator/streaming mirror can render), release (`== false`) → `true`
 * (flag IS applied).
 */
object SecureWindowPolicy {

    /**
     * Whether the FLAG_SECURE window flag must be applied for a build.
     *
     * [debug] is the module's `BuildConfig.DEBUG`. The flag is applied only in
     * non-debug builds.
     */
    fun shouldApplySecureFlag(debug: Boolean): Boolean = !debug
}
