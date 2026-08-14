package com.authorss81.noteflow.services

import android.content.Context
import android.content.SharedPreferences
import com.authorss81.noteflow.plugins.PluginSettingKey
import com.authorss81.noteflow.theme.AppThemeMode

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("noteflow_prefs", Context.MODE_PRIVATE)

    var themeMode: AppThemeMode
        get() {
            val name = prefs.getString("theme_mode", AppThemeMode.LIGHT.name)
            return try {
                AppThemeMode.valueOf(name ?: AppThemeMode.LIGHT.name)
            } catch (e: Exception) {
                AppThemeMode.LIGHT
            }
        }
        set(value) {
            prefs.edit().putString("theme_mode", value.name).apply()
        }

    var isFirstRun: Boolean
        get() = prefs.getBoolean("is_first_run", true)
        set(value) = prefs.edit().putBoolean("is_first_run", value).apply()

    var tutorialCompleted: Boolean
        get() = prefs.getBoolean("tutorial_completed", false)
        set(value) = prefs.edit().putBoolean("tutorial_completed", value).apply()

    var activeNotebookId: String?
        get() = prefs.getString("active_notebook_id", null)
        set(value) = prefs.edit().putString("active_notebook_id", value).apply()

    var activeSectionId: String?
        get() = prefs.getString("active_section_id", null)
        set(value) = prefs.edit().putString("active_section_id", value).apply()

    var activePageId: String?
        get() = prefs.getString("active_page_id", null)
        set(value) = prefs.edit().putString("active_page_id", value).apply()

    var lastNotebookId: String?
        get() = prefs.getString("last_notebook_id", null)
        set(value) = prefs.edit().putString("last_notebook_id", value).apply()

    var masterPasswordSalt: String?
        get() = prefs.getString("master_password_salt", null)
        set(value) = prefs.edit().putString("master_password_salt", value).apply()

    var masterPasswordWrappedDek: String?
        get() = prefs.getString("master_password_wrapped_dek", null)
        set(value) = prefs.edit().putString("master_password_wrapped_dek", value).apply()

    var failedUnlockAttempts: Int
        get() = prefs.getInt("failed_unlock_attempts", 0)
        set(value) = prefs.edit().putInt("failed_unlock_attempts", value).apply()

    var lockoutUntilEpochMs: Long
        get() = prefs.getLong("lockout_until_epoch_ms", 0L)
        set(value) = prefs.edit().putLong("lockout_until_epoch_ms", value).apply()

    var biometricAuthEnabled: Boolean
        get() = prefs.getBoolean("biometric_auth_enabled", false)
        set(value) = prefs.edit().putBoolean("biometric_auth_enabled", value).apply()

    var gpuWetBrushesEnabled: Boolean
        get() = prefs.getBoolean("gpu_wet_brushes_enabled", true)
        set(value) = prefs.edit().putBoolean("gpu_wet_brushes_enabled", value).apply()

    // 26.6: automatic shape snapping/straightening of freehand strokes.
    var shapeAutoSnapEnabled: Boolean
        get() = prefs.getBoolean("shape_auto_snap_enabled", true)
        set(value) = prefs.edit().putBoolean("shape_auto_snap_enabled", value).apply()

    // Phase 07 painting features (see PressureCurveHelper / SymmetryMode / StrokeStabilizer):
    // stroke stabilizer defaults OFF so classic rendering is unchanged.
    var strokeStabilizerEnabled: Boolean
        get() = prefs.getBoolean("stroke_stabilizer_enabled", false)
        set(value) = prefs.edit().putBoolean("stroke_stabilizer_enabled", value).apply()

    // Pressure-response curve (LINEAR = identity, so default behaviour is unchanged).
    var pressureCurveKey: String
        get() = prefs.getString("pressure_curve_key", "linear") ?: "linear"
        set(value) = prefs.edit().putString("pressure_curve_key", value).apply()

    // Mirror mode (OFF = unchanged classic rendering).
    var symmetryModeKey: String
        get() = prefs.getString("symmetry_mode_key", "off") ?: "off"
        set(value) = prefs.edit().putString("symmetry_mode_key", value).apply()

    // Phase 07: custom paper-texture packs. Stored in a preference keyed by
    // page id (NOT the DB schema) so tiled paper backgrounds persist per page.
    fun paperTexturePathForPage(pageId: String): String? =
        prefs.getString("paper_texture_$pageId", null)

    fun setPaperTexturePathForPage(pageId: String, path: String?) {
        prefs.edit().apply {
            if (path == null) {
                remove("paper_texture_$pageId")
            } else {
                putString("paper_texture_$pageId", path)
            }
        }.apply()
    }

    /** All paper-texture file paths currently referenced by any page, so orphan
     *  files no longer referenced by any pref key can be deleted (page removed,
     *  texture cleared, etc.). */
    fun allPaperTexturePaths(): List<String> {
        val out = mutableListOf<String>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("paper_texture_") && value is String) out.add(value)
        }
        return out
    }

    // Phase 13: the last selected ready-made brush preset (BrushPresetPack).
    // Persisted in SharedPreferences — NO DB schema impact.
    var activeBrushPresetId: String?
        get() = prefs.getString("active_brush_preset_id", null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove("active_brush_preset_id") else putString("active_brush_preset_id", value)
            }.apply()
        }

    // Phase 19: dual erasers — "STROKE" (classic whole-stroke eraser) is the
    // default so existing behaviour is unchanged; "PARTIAL" trims each touched
    // stroke into surviving segments. SharedPreferences only, no DB schema change.
    var eraserModeKey: String
        get() = prefs.getString("eraser_mode_key", "STROKE") ?: "STROKE"
        set(value) = prefs.edit().putString("eraser_mode_key", value).apply()

    // Phase 19: render-time vibrancy/saturation boost. OFF by default so stored
    // colors and existing notes render unchanged; stored colorInt is never mutated.
    var vibrancyEnabled: Boolean
        get() = prefs.getBoolean("vibrancy_enabled", false)
        set(value) = prefs.edit().putBoolean("vibrancy_enabled", value).apply()

    // Perceptual saturation boost applied at render time only (0..1, default 0.4).
    var vibrancyBoostLevel: Float
        get() = prefs.getFloat("vibrancy_boost_level", 0.4f).coerceIn(0f, 1f)
        set(value) = prefs.edit().putFloat("vibrancy_boost_level", value.coerceIn(0f, 1f)).apply()

    // Phase 18: brush-physics render settings (SharedPreferences only, no schema change).
    // Velocity width modulation defaults OFF so existing brushes keep their classic look.
    var velocityModulationEnabled: Boolean
        get() = prefs.getBoolean("brush_velocity_modulation_enabled", false)
        set(value) = prefs.edit().putBoolean("brush_velocity_modulation_enabled", value).apply()

    var velocityModulationIntensity: Float
        get() = prefs.getFloat("brush_velocity_modulation_intensity", 1.0f)
        set(value) = prefs.edit().putFloat("brush_velocity_modulation_intensity", value).apply()

    // Calligraphic & chisel nib angles — defaults match the classic fixed angles (45/30).
    var calligraphicNibAngleDeg: Float
        get() = prefs.getFloat("brush_calligraphic_nib_angle_deg", 45f)
        set(value) = prefs.edit().putFloat("brush_calligraphic_nib_angle_deg", value).apply()

    var chiselNibAngleDeg: Float
        get() = prefs.getFloat("brush_chisel_nib_angle_deg", 30f)
        set(value) = prefs.edit().putFloat("brush_chisel_nib_angle_deg", value).apply()

    var deviceTierOverride: String?
        get() = prefs.getString("device_tier_override", null)
        set(value) = prefs.edit().putString("device_tier_override", value).apply()

    // 22.1: auto-lock after this many seconds of inactivity while foregrounded (0 = off).
    var autoLockTimeoutSeconds: Int
        get() = prefs.getInt("auto_lock_timeout_seconds", 0)
        set(value) = prefs.edit().putInt("auto_lock_timeout_seconds", value).apply()

    val hasMasterPassword: Boolean
        get() = masterPasswordSalt != null && masterPasswordWrappedDek != null

    var lowEndWarningShown: Boolean
        get() = prefs.getBoolean("low_end_warning_shown", false)
        set(value) = prefs.edit().putBoolean("low_end_warning_shown", value).apply()

    var useSidebarLayout: Boolean
        get() = prefs.getBoolean("use_sidebar_layout", false)
        set(value) = prefs.edit().putBoolean("use_sidebar_layout", value).apply()

    var showStrokePreviewsInPicker: Boolean
        get() = prefs.getBoolean("show_stroke_previews_in_picker", false)
        set(value) = prefs.edit().putBoolean("show_stroke_previews_in_picker", value).apply()

    var databaseIntegrityWarningDismissed: Boolean
        get() = prefs.getBoolean("database_integrity_warning_dismissed", false)
        set(value) = prefs.edit().putBoolean("database_integrity_warning_dismissed", value).apply()

    var databaseIntegrityCheckEnabled: Boolean
        get() = prefs.getBoolean("database_integrity_check_enabled", true)
        set(value) = prefs.edit().putBoolean("database_integrity_check_enabled", value).apply()

    // Phase 16: SilentToggle — user-wide quiet mode. When ON, read-aloud refuses
    // to speak (a loud, explanatory refusal; never silent degradation).
    var silentModeEnabled: Boolean
        get() = prefs.getBoolean("silent_mode_enabled", false)
        set(value) = prefs.edit().putBoolean("silent_mode_enabled", value).apply()

    // Phase 10: per-plugin opt-in persistence (Settings → Plugins). Plugins are
    // DISABLED by default — the user opts in per plugin.
    fun isPluginEnabled(pluginId: String): Boolean =
        prefs.getBoolean("plugin_enabled_$pluginId", false)

    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        prefs.edit().putBoolean("plugin_enabled_$pluginId", enabled).apply()
        if (enabled) markPluginEverEnabled(pluginId)
    }

    // Phase 11: distinguishes REGISTERED (never enabled, off) from DISABLED
    // (user turned it off) in the derived plugin lifecycle states.
    fun hasPluginEverBeenEnabled(pluginId: String): Boolean =
        prefs.getBoolean("plugin_ever_enabled_$pluginId", false)

    fun markPluginEverEnabled(pluginId: String) {
        prefs.edit().putBoolean("plugin_ever_enabled_$pluginId", true).apply()
    }

    fun clearPluginEverEnabled(pluginId: String) {
        prefs.edit().remove("plugin_ever_enabled_$pluginId").apply()
    }

    // Phase 21: plugin store install state. A plugin that is NOT installed is
    // "not downloaded" — its definition is bundled, but it is excluded from the
    // active registry until the user installs it. Default (no key) = installed,
    // so existing installs keep every bundled plugin (no migration needed).
    fun isPluginUninstalled(pluginId: String): Boolean =
        prefs.getBoolean("plugin_uninstalled_$pluginId", false)

    fun setPluginUninstalled(pluginId: String, uninstalled: Boolean) {
        prefs.edit().putBoolean("plugin_uninstalled_$pluginId", uninstalled).apply()
    }

    // Phase 23: explicit consent to download a REMOTE (downloadable) plugin.
    // The FIRST download requires the user to say yes in the store; the consent
    // is persisted so a re-download does not re-prompt, and Delete wipes it
    // (a re-download after Delete starts from consent-required again).
    fun isPluginDownloadConsented(pluginId: String): Boolean =
        prefs.getBoolean("plugin_download_consent_$pluginId", false)

    fun setPluginDownloadConsented(pluginId: String, consented: Boolean) {
        prefs.edit().putBoolean("plugin_download_consent_$pluginId", consented).apply()
    }

    // Phase 21: COMPLETE removal of a plugin's persisted state. Removes the
    // opt-in flag, the ever-enabled flag, the uninstalled flag, the persisted
    // catalog entry blob and every namespaced `plugins.<id>.*` setting. Used by
    // the store's Delete action (delete = gone + settings wiped; disable = off
    // but re-enableable).
    fun wipePluginState(pluginId: String) {
        val prefix = "plugins.$pluginId."
        val keys = prefs.all.keys.filter { key ->
            key == "plugin_enabled_$pluginId" ||
                key == "plugin_ever_enabled_$pluginId" ||
                key == "plugin_uninstalled_$pluginId" ||
                key == "plugin_entry_$pluginId" ||
                key == "plugin_download_consent_$pluginId" ||
                key.startsWith(prefix)
        }
        prefs.edit().apply { keys.forEach { remove(it) } }.apply()
    }

    // Phase 22: persisted unified catalog-entry blobs (downloadable/remote plugin
    // definitions). A plugin's catalog entry survives process restarts with its
    // downloadUrl / sha256 / pinnedCertHash / updateChannel intact; Delete
    // removes it via wipePluginState above. Bundled entries are never persisted
    // here (they are derived from the compile-time registry).
    fun getPluginEntryJson(pluginId: String): String? =
        prefs.getString("plugin_entry_$pluginId", null)

    fun setPluginEntryJson(pluginId: String, json: String?) {
        prefs.edit().apply {
            if (json == null) remove("plugin_entry_$pluginId") else putString("plugin_entry_$pluginId", json)
        }.apply()
    }

    /** The ids of every persisted plugin-entry blob (for enumeration). */
    fun allPluginEntryIds(): Set<String> {
        val out = mutableSetOf<String>()
        prefs.all.keys.forEach { key ->
            if (key.startsWith("plugin_entry_")) out.add(key.removePrefix("plugin_entry_"))
        }
        return out
    }

    // Phase 11: per-plugin namespaced settings. Every key lives under
    // plugins.<id>.<key> (see PluginSettingKey) so two plugins never collide.
    fun getPluginSetting(pluginId: String, key: String): String? =
        prefs.getString(PluginSettingKey.key(pluginId, key), null)

    fun setPluginSetting(pluginId: String, key: String, value: String?) {
        val full = PluginSettingKey.key(pluginId, key)
        prefs.edit().apply {
            if (value == null) remove(full) else putString(full, value)
        }.apply()
    }

    fun getPluginIntSetting(pluginId: String, key: String, default: Int): Int =
        prefs.getInt(PluginSettingKey.key(pluginId, key), default)

    fun setPluginIntSetting(pluginId: String, key: String, value: Int) {
        prefs.edit().putInt(PluginSettingKey.key(pluginId, key), value).apply()
    }

    fun getPluginBooleanSetting(pluginId: String, key: String, default: Boolean): Boolean =
        prefs.getBoolean(PluginSettingKey.key(pluginId, key), default)

    fun setPluginBooleanSetting(pluginId: String, key: String, value: Boolean) {
        prefs.edit().putBoolean(PluginSettingKey.key(pluginId, key), value).apply()
    }

    fun hasPluginSetting(pluginId: String, key: String): Boolean =
        prefs.contains(PluginSettingKey.key(pluginId, key))

    fun clearSecuritySettings() {
        prefs.edit()
            .remove("master_password_salt")
            .remove("master_password_wrapped_dek")
            .remove("biometric_auth_enabled")
            .apply()
    }
}
