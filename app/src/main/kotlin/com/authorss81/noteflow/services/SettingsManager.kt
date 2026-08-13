package com.authorss81.noteflow.services

import android.content.Context
import android.content.SharedPreferences
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

    var deviceTierOverride: String?
        get() = prefs.getString("device_tier_override", null)
        set(value) = prefs.edit().putString("device_tier_override", value).apply()

    var renderingEngineOverride: String?
        get() = prefs.getString("rendering_engine_override", null)
        set(value) = prefs.edit().putString("rendering_engine_override", value).apply()

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

    fun clearSecuritySettings() {
        prefs.edit()
            .remove("master_password_salt")
            .remove("master_password_wrapped_dek")
            .remove("biometric_auth_enabled")
            .apply()
    }
}
