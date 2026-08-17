package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.StrokeColorMode

/**
 * Phase 122 decision table for persisting the active brush color mode across
 * editor sessions (Rainbow / Gradient / Shimmer / Solid). The mode is NOT a
 * note-data concern — it is the user's current brush choice, so it lives in
 * SharedPreferences via [SettingsManager], never in the DB schema. Per-stroke
 * mode still round-trips through each stroke's own serialized payload
 * (phase-27); this table is only about the editor's "current mode" default.
 *
 * Fail closed: an unknown/blank stored value resolves to [DEFAULT_MODE]
 * (SOLID), so a corrupted preference can never enable a phantom mode and a
 * pre-phase-122 install simply starts solid as before.
 */
object ColorModePersistencePolicy {

    const val PREF_KEY_COLOR_MODE = "brush_color_mode_key"

    val DEFAULT_MODE: StrokeColorMode = StrokeColorMode.SOLID

    fun prefValue(mode: StrokeColorMode): String = mode.persistenceKey

    fun modeFromPref(value: String?): StrokeColorMode =
        StrokeColorMode.fromKey(value)
}