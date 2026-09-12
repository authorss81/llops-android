package com.authorss81.noteflow.services

/**
 * Phase 267 (settings commit/clamp/keys/threads): pure-JVM sanitize + budget
 * decision table for every SharedPreferences read/write that previously
 * trusted raw disk bytes.
 *
 * Pre-fix, a hand-edited (ADB) `noteflow_prefs` XML could:
 * - set `auto_lock_timeout_seconds` to -1 (lock disabled) or to a huge value
 *   whose `* 1000L` deadline never fires while foregrounded;
 * - set `tutorial_resume_index` / `failed_unlock_attempts` /
 *   `lockout_until_epoch_ms` to unbounded values (negative resume index,
 *   attempt-counter overflow, a far-future lockout = permanent lockout);
 * - store unknown `pressure_curve_key` / `symmetry_mode_key` /
 *   `eraser_mode_key` / `brush_color_mode_key` / `device_tier_override` keys
 *   (consumers mostly failed safe via `fromSettingKey`, but the raw value was
 *   re-persisted and re-read on every launch);
 * - grow `template_prefs_json` / `imported_brush_presets_json` without bound
 *   (whole-file read + `JSONObject` parse on the read path) or store an
 *   arbitrarily long `paper_texture_<page>` path.
 *
 * Every function here is total (never throws, never returns NaN/Infinite or
 * an out-of-range value) and every bound is a named constant so the clamping
 * is unit-pinned. Android-free so it runs on the plain JVM.
 */
object SettingsPrefsPolicy {

    /** Prefs-schema version. Bumped only when a key's meaning changes. */
    const val CURRENT_PREFS_VERSION: Int = 1

    /** Auto-lock window bounds: 0 = off, max 24 h (phase-267). */
    const val MAX_AUTO_LOCK_TIMEOUT_SECONDS: Int = 86400

    // Phase-267 review fix (finding 7): single implementation — delegates to
    // AutoLockPolicy.sanitize (the PROMPT-mandated owner of the 0..86400
    // window) so the two entry points can never diverge.
    fun sanitizeAutoLockTimeoutSeconds(value: Int): Int =
        AutoLockPolicy.sanitize(value)

    /** Tutorial resume slide: never negative, capped so ADB cannot push it absurd. */
    const val MAX_TUTORIAL_RESUME_INDEX: Int = 10_000

    fun sanitizeTutorialResumeIndex(value: Int): Int =
        value.coerceIn(0, MAX_TUTORIAL_RESUME_INDEX)

    /** Failed-attempt counter: never negative, capped against Int overflow. */
    const val MAX_FAILED_ATTEMPTS_TRACKED: Int = 10_000

    fun sanitizeFailedAttempts(value: Int): Int =
        value.coerceIn(0, MAX_FAILED_ATTEMPTS_TRACKED)

    /**
     * Lockout deadline: never negative and never further than one max backoff
     * window past now, so an ADB-written far-future value cannot permanently
     * lock the vault. [MAX_LOCKOUT_DELAY_MS] mirrors the 15-minute cap in
     * `NoteflowViewModel.computeLockoutDelayMs`. Phase-267 review fix
     * (finding 9): wall-clock skew is accepted — if the device clock moves
     * backward between write and read a legit lockout shortens instead of
     * extending, which is the fail-open-safe direction for availability.
     */
    const val MAX_LOCKOUT_DELAY_MS: Long = 15 * 60 * 1000L

    fun sanitizeLockoutUntilEpochMs(stored: Long, nowMs: Long): Long =
        stored.coerceIn(0L, nowMs + MAX_LOCKOUT_DELAY_MS)

    // -- Enum-key round-trips (unknown/hand-edited keys fail safe to defaults) --

    private val PRESSURE_CURVE_KEYS = setOf("linear", "light", "heavy", "smooth")
    const val DEFAULT_PRESSURE_CURVE_KEY = "linear"

    fun sanitizePressureCurveKey(value: String?): String =
        if (PRESSURE_CURVE_KEYS.contains(value)) value!! else DEFAULT_PRESSURE_CURVE_KEY

    private val SYMMETRY_MODE_KEYS = setOf("off", "vertical", "horizontal", "radial")
    const val DEFAULT_SYMMETRY_MODE_KEY = "off"

    fun sanitizeSymmetryModeKey(value: String?): String =
        if (SYMMETRY_MODE_KEYS.contains(value)) value!! else DEFAULT_SYMMETRY_MODE_KEY

    private val ERASER_MODE_KEYS = setOf("STROKE", "PARTIAL")
    const val DEFAULT_ERASER_MODE_KEY = "STROKE"

    fun sanitizeEraserModeKey(value: String?): String {
        val match = ERASER_MODE_KEYS.firstOrNull { it.equals(value, ignoreCase = true) }
        return match ?: DEFAULT_ERASER_MODE_KEY
    }

    private val BRUSH_COLOR_MODE_KEYS = setOf("SOLID", "RAINBOW", "GRADIENT", "SHIMMER")
    const val DEFAULT_BRUSH_COLOR_MODE_KEY = "SOLID"

    fun sanitizeBrushColorModeKey(value: String?): String =
        if (BRUSH_COLOR_MODE_KEYS.contains(value)) value!! else DEFAULT_BRUSH_COLOR_MODE_KEY

    /** Device-tier override: a valid `DeviceTier` name or null (auto-detect). */
    private val DEVICE_TIER_NAMES = setOf("LOW_END", "MID_RANGE", "FLAGSHIP")

    fun sanitizeDeviceTierOverride(value: String?): String? =
        if (DEVICE_TIER_NAMES.contains(value)) value else null

    // -- Float dials (non-finite ADB values fail safe to the UI defaults) --

    /** Velocity→width strength: BrushStudioDialog slider is 0.1..1.0, default 1.0. */
    const val DEFAULT_VELOCITY_INTENSITY: Float = 1.0f

    fun sanitizeVelocityIntensity(value: Float): Float =
        if (!value.isFinite()) DEFAULT_VELOCITY_INTENSITY else value.coerceIn(0f, 1f)

    /** Nib-angle sliders are -45..90 degrees. */
    const val MIN_NIB_ANGLE_DEG: Float = -45f
    const val MAX_NIB_ANGLE_DEG: Float = 90f
    const val DEFAULT_CALLIGRAPHIC_NIB_ANGLE_DEG: Float = 45f
    const val DEFAULT_CHISEL_NIB_ANGLE_DEG: Float = 30f

    fun sanitizeNibAngleDeg(value: Float, default: Float): Float =
        if (!value.isFinite()) default else value.coerceIn(MIN_NIB_ANGLE_DEG, MAX_NIB_ANGLE_DEG)

    // -- Unbounded-string budgets --

    /** Whole-file template-override JSON: refuse writes past this. */
    const val MAX_TEMPLATE_PREFS_CHARS: Int = 65_536

    /** Imported `.inkbrush` preset array JSON: refuse writes past this. */
    const val MAX_IMPORTED_PRESETS_CHARS: Int = 262_144

    /** Single paper-texture path: refuse writes past this. */
    const val MAX_TEXTURE_PATH_CHARS: Int = 4_096

    fun isTemplatePrefsJsonAcceptable(value: String): Boolean =
        value.length <= MAX_TEMPLATE_PREFS_CHARS

    fun isImportedPresetsJsonAcceptable(value: String): Boolean =
        value.length <= MAX_IMPORTED_PRESETS_CHARS

    fun isTexturePathAcceptable(path: String): Boolean =
        path.isNotEmpty() && path.length <= MAX_TEXTURE_PATH_CHARS
}
