package com.authorss81.noteflow.services

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.authorss81.noteflow.plugins.PluginSettingKey
import com.authorss81.noteflow.services.graph.GraphNeighborhoodFocusPolicy
import com.authorss81.noteflow.services.localsend.LocalSendPairingCodes
import com.authorss81.noteflow.theme.AppThemeMode
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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

    // Phase 125 — enhanced interactive tutorial persistence. tutorialResumeIndex
    // survives "Skip" (exit early) so the next open resumes where the user left
    // off; it is reset to 0 when the tutorial is completed.
    var tutorialResumeIndex: Int
        get() = prefs.getInt("tutorial_resume_index", 0)
        set(value) = prefs.edit().putInt("tutorial_resume_index", value.coerceAtLeast(0)).apply()

    // Phase 156: first-run triage intro shown/dismissed for passwordless vaults.
    // One-time — once set it is never auto-shown again (⋮ → "Show help again"
    // re-opens it on demand regardless of this flag).
    var onboardingCompleted: Boolean
        get() = prefs.getBoolean("onboarding_completed", false)
        set(value) = prefs.edit().putBoolean("onboarding_completed", value).apply()

    // Phase 156: epoch-millis of the last SUCCESSFUL vault backup (0 = never).
    // Written at the single exportBackup chokepoint so every producer (Home
    // menu, WebDAV, LocalSend) records it; drives the home "days since backup"
    // chip + the ⋮-menu "no backup yet" nudge. SharedPreferences, no DB schema.
    var lastBackupTimestamp: Long
        get() = prefs.getLong("last_backup_timestamp", 0L)
        set(value) = prefs.edit().putLong("last_backup_timestamp", value).apply()

    // B2-CRYPTO-09 (phase-107): one-time per-record field AAD migration flag.
    // Set after NoteRepository.migrateFieldRecordAad has bound every pre-phase-107
    // ciphertext to its table|recordId|fieldName context, so the (O-rows) pass is
    // not re-run on every unlock.
    var fieldAadMigrated: Boolean
        get() = prefs.getBoolean("field_aad_migrated", false)
        set(value) = prefs.edit().putBoolean("field_aad_migrated", value).apply()

    // B1-DB-4 (phase-44): one-time migration of legacy plaintext note-body
    // files. Set after NoteRepository.migrateLegacyPlaintextNoteBodies has
    // moved every pre-fix .md/.txt body into the encrypted extractedText column
    // and deleted the plaintext source files, so the O(rows) pass is not re-run
    // on every unlock. Stays unset while any file remains.
    var noteBodyPlaintextMigrated: Boolean
        get() = prefs.getBoolean("note_body_plaintext_migrated", false)
        set(value) = prefs.edit().putBoolean("note_body_plaintext_migrated", value).apply()

    // B1-DB-3 (phase-54): one-time migration of legacy PLAINTEXT voice notes.
    // Set after NoteRepository.migrateLegacyPlaintextVoiceNotes has encrypted
    // every referenced pre-fix `.m4a` into `.enc` blobs and deleted the
    // plaintext, so the O(rows) pass is not re-run on every unlock. Stays unset
    // while any referenced plaintext remains.
    var voiceNotesEncryptedMigrated: Boolean
        get() = prefs.getBoolean("voice_notes_encrypted_migrated", false)
        set(value) = prefs.edit().putBoolean("voice_notes_encrypted_migrated", value).apply()

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

    // B1-CRYPTO-03 (phase-62): salt + wrapped DEK + format now live as ONE
    // versioned blob in a single pref key, committed atomically by
    // commitMasterPasswordCredential (one commit(), all-or-nothing), so a
    // process kill can never leave "new salt + old/missing wrappedDEK" and brick
    // every future unlock. The pre-phase-62 two-key pair
    // (master_password_salt / master_password_wrapped_dek) is still READ as a
    // fallback until the next set/change migrates it to the blob in the same
    // atomic commit — never written any more.
    val masterPasswordCredentialOrLegacy: MasterPasswordCredential?
        get() = MasterPasswordCredential.parse(prefs.getString("master_password_credential", null))
            ?: MasterPasswordCredential.fromLegacy(
                prefs.getString("master_password_salt", null),
                prefs.getString("master_password_wrapped_dek", null)
            )

    /**
     * B1-CRYPTO-03 (phase-62): persists salt + wrapped DEK + format as ONE
     * versioned blob in a SINGLE synchronous commit(). All-or-nothing — either
     * the whole new credential is on disk or the previous state remains intact;
     * there is no window in which a new salt can coexist with an old/missing
     * wrapper. The legacy two-key pair is removed in the SAME commit so the two
     * independent-write failure mode can never be re-created. Returns the
     * disk-acknowledged result so the caller can abort BEFORE flipping any
     * in-memory state when the write failed.
     */
    fun commitMasterPasswordCredential(salt: ByteArray, wrappedDek: String): Boolean {
        val blob = MasterPasswordCredential.serialize(salt, wrappedDek)
        return prefs.edit()
            .putString("master_password_credential", blob)
            .remove("master_password_salt")
            .remove("master_password_wrapped_dek")
            .commit()
    }

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

    // Phase 213: per-stroke soft drop shadows ("paper elevation"). Default ON —
    // the shadow underlay is decided per stroke by BrushShadowPolicy and LOW_END
    // devices skip it entirely (cosmetic overlay, PaperGrainPolicy precedent),
    // so the default only ever ADDS depth on capable hardware. Prefs only, no
    // DB schema impact.
    var paperElevationEnabled: Boolean
        get() = prefs.getBoolean("paper_elevation_enabled", true)
        set(value) = prefs.edit().putBoolean("paper_elevation_enabled", value).apply()

    // Phase 227: paper edge style (RECT / ROUNDED / DECKLED). Sanitized through
    // PaperEdgePolicy so a corrupt stored key can never produce an edge outside
    // the supported set; the default ("rounded") preserves the legacy card.
    var paperEdgeKey: String
        get() = PaperEdgePolicy.sanitizeKey(prefs.getString("paper_edge", null))
        set(value) = prefs.edit()
            .putString("paper_edge", PaperEdgePolicy.sanitizeKey(value))
            .apply()

    // Phase 227: paper texture ("tooth") strength dial, 0..100, default 50 (the
    // exact pre-227 grain). Clamped on both read and write so a stale or corrupt
    // stored value can never leave the dial range.
    var paperTextureStrength: Int
        get() = PaperTextureStrengthPolicy.clamp(
            prefs.getInt("paper_texture_strength", PaperTextureStrengthPolicy.DEFAULT)
        )
        set(value) = prefs.edit()
            .putInt("paper_texture_strength", PaperTextureStrengthPolicy.clamp(value))
            .apply()

    // 36.0: master haptics toggle. All gesture-milestone haptics (shape snap, color
    // detents, slider notches) are additionally gated by reduce-motion via
    // MotionPolicy.hapticsAllowed.
    var hapticsEnabled: Boolean
        get() = prefs.getBoolean("haptics_enabled", true)
        set(value) = prefs.edit().putBoolean("haptics_enabled", value).apply()

    // Phase 208: S-Pen palm rejection persists across editor opens (previously a
    // plain `remember` that reset to true on every visit). Default true = the
    // long-standing behavior. Prefs only, no DB schema impact.
    var palmRejectionEnabled: Boolean
        get() = prefs.getBoolean("palm_rejection_enabled", true)
        set(value) = prefs.edit().putBoolean("palm_rejection_enabled", value).apply()

    // Phase 208: page-list sort mode for the Pages tab (all view modes).
    // Client-side ordering of already-collected lists via PageSortPolicy — the
    // DAO query is untouched (no schema change). Fail-closed decode on both
    // read and write; unknown keys fall back to UPDATED_DESC (the legacy order).
    var pageSortModeKey: String
        get() = PageSortPolicy.sanitizePersistenceKey(prefs.getString("page_sort_mode", null))
        set(value) = prefs.edit()
            .putString("page_sort_mode", PageSortPolicy.sanitizePersistenceKey(value))
            .apply()

    // Phase 210: Knowledge-graph neighborhood focus depth (1–3 hops, default 1).
    // Persisted so a returning user keeps their preferred exploration radius.
    // Sanitized through the policy on read AND write so a hand-edited pref can
    // never push the BFS frontier out of range. Prefs only, no DB schema impact.
    var graphFocusHopCount: Int
        get() = GraphNeighborhoodFocusPolicy.sanitizeHops(
            prefs.getInt(
                "graph_focus_hop_count",
                GraphNeighborhoodFocusPolicy.DEFAULT_HOPS
            )
        )
        set(value) = prefs.edit()
            .putInt(
                "graph_focus_hop_count",
                GraphNeighborhoodFocusPolicy.sanitizeHops(value)
            )
            .apply()

    // 26.6: automatic shape snapping/straightening of freehand strokes.
    // Phase-126 made ALL plugins/assistive features strictly opt-in (off by default).
    var shapeAutoSnapEnabled: Boolean
        get() = prefs.getBoolean("shape_auto_snap_enabled", false)
        set(value) = prefs.edit().putBoolean("shape_auto_snap_enabled", value).apply()

    // Phase 223: ruler / straight-line snap. When ON any freehand drag collapses
    // to an exact start→end LINE (with a live straight guide). Off by default.
    var rulerEnabled: Boolean
        get() = prefs.getBoolean("ruler_enabled", false)
        set(value) = prefs.edit().putBoolean("ruler_enabled", value).apply()

    // Phase 223: two-finger canvas twist. When OFF the two-finger gesture keeps
    // the classic pinch-zoom + pan behaviour exactly (rotation never applies).
    // On by default once the feature ships; users can disable it in settings.
    var canvasTwistEnabled: Boolean
        get() = prefs.getBoolean("canvas_twist_enabled", true)
        set(value) = prefs.edit().putBoolean("canvas_twist_enabled", value).apply()

    // Phase 07 painting features (see PressureCurveHelper / SymmetryMode / StrokeStabilizer):
    // stroke stabilizer defaults OFF so classic rendering is unchanged.
    var strokeStabilizerEnabled: Boolean
        get() = prefs.getBoolean("stroke_stabilizer_enabled", false)
        set(value) = prefs.edit().putBoolean("stroke_stabilizer_enabled", value).apply()

    // Phase 197: stabilizer strength trim, 0–100 (%). 100 = each brush uses its
    // own designed smoothing (the pre-197 default window 8 when no preset is
    // active); 0 = raw input. Sanitized through the policy on read AND write so
    // a hand-edited pref can never put the EWMA window out of range. Prefs only,
    // no DB schema impact; applied to the NEXT stroke (no mid-stroke retune).
    var strokeStabilizerStrengthPercent: Int
        get() = StrokeSmoothingPolicy.sanitizeSliderPercent(
            prefs.getInt("stroke_stabilizer_strength_percent", StrokeSmoothingPolicy.DEFAULT_SLIDER_PERCENT)
        )
        set(value) = prefs.edit()
            .putInt("stroke_stabilizer_strength_percent", StrokeSmoothingPolicy.sanitizeSliderPercent(value))
            .apply()

    // Phase 214: stabilizer lag-compensation dial ("tension"), 0–35 (%).
    // 15 = the pre-214 legacy prediction constant 0.15; 0 = no compensation.
    // Sanitized on read AND write; applied at the NEXT stroke start via the
    // existing retune path (never mid-stroke). Prefs only, no schema impact.
    var strokeStabilizerPredictionPercent: Int
        get() = StrokeSmoothingPolicy.sanitizePredictionPercent(
            prefs.getInt("stroke_stabilizer_prediction_percent", StrokeSmoothingPolicy.DEFAULT_PREDICTION_PERCENT)
        )
        set(value) = prefs.edit()
            .putInt("stroke_stabilizer_prediction_percent", StrokeSmoothingPolicy.sanitizePredictionPercent(value))
            .apply()

    // Phase 214: smoothing model selection ("ewma" classic | "one_euro"
    // adaptive cutoff). Unknown/hand-edited values fail safe to EWMA on read;
    // applies to the NEXT stroke (model swap happens at stroke start).
    var strokeStabilizerModelKey: String
        get() = StrokeSmoothingPolicy.sanitizeModelKey(
            prefs.getString("stroke_stabilizer_model_key", StrokeSmoothingPolicy.MODEL_EWMA)
        )
        set(value) = prefs.edit()
            .putString("stroke_stabilizer_model_key", StrokeSmoothingPolicy.sanitizeModelKey(value))
            .apply()

    // Phase 220: pro brush controls — blender strength (SMUDGE) and texture
    // scatter, 0–100%. Persisted per-session; applied at the NEXT stroke.
    // Sanitized on read AND write so a hand-edited pref can never leave range.
    var blenderStrengthPercent: Int
        get() = prefs.getInt("blender_strength_percent", 85).coerceIn(0, 100)
        set(value) = prefs.edit().putInt("blender_strength_percent", value.coerceIn(0, 100)).apply()

    var scatterAmountPercent: Int
        get() = prefs.getInt("scatter_amount_percent", 0).coerceIn(0, 100)
        set(value) = prefs.edit().putInt("scatter_amount_percent", value.coerceIn(0, 100)).apply()

    // Pressure-response curve (LINEAR = identity, so default behaviour is unchanged).
    var pressureCurveKey: String
        get() = prefs.getString("pressure_curve_key", "linear") ?: "linear"
        set(value) = prefs.edit().putString("pressure_curve_key", value).apply()

    // Mirror mode (OFF = unchanged classic rendering).
    var symmetryModeKey: String
        get() = prefs.getString("symmetry_mode_key", "off") ?: "off"
        set(value) = prefs.edit().putString("symmetry_mode_key", value).apply()

    // Phase 222: tilt-shading gate (stylus angle → width/alpha modulation).
    var tiltShadingEnabled: Boolean
        get() = prefs.getBoolean("tilt_shading_enabled", false)
        set(value) = prefs.edit().putBoolean("tilt_shading_enabled", value).apply()

    // Phase 222: per-layer alpha-lock (paint only where ink already exists).
    // Settings-key pattern: "layer_<layerId>_alphaLock" — no DB migration.
    fun isLayerAlphaLockEnabled(layerId: String): Boolean =
        prefs.getBoolean("layer_${layerId}_alphaLock", false)

    fun setLayerAlphaLockEnabled(layerId: String, enabled: Boolean) {
        prefs.edit().putBoolean("layer_${layerId}_alphaLock", enabled).apply()
    }

    // Phase 222: per-layer clipping mask (layer clips to layer below).
    fun isLayerClippingMaskEnabled(layerId: String): Boolean =
        prefs.getBoolean("layer_${layerId}_clippingMask", false)

    fun setLayerClippingMaskEnabled(layerId: String, enabled: Boolean) {
        prefs.edit().putBoolean("layer_${layerId}_clippingMask", enabled).apply()
    }

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

    // Phase 223: per-page canvas rotation (two-finger twist). Keyed by page id
    // in SharedPreferences (like paper-texture-packs) — NO DB schema change. The
    // value is sanitized through CanvasRotationPolicy when read so a corrupt
    // stored angle can never spin the page to a random value.
    fun canvasRotationDegreesForPage(pageId: String): Float {
        val stored = prefs.getFloat("canvas_rotation_$pageId", 0f)
        return com.authorss81.noteflow.services.CanvasRotationPolicy.sanitize(stored)
    }

    fun setPageCanvasRotationDegrees(pageId: String, degrees: Float) {
        prefs.edit().putFloat(
            "canvas_rotation_$pageId",
            com.authorss81.noteflow.services.CanvasRotationPolicy.sanitize(degrees)
        ).apply()
    }

    // Phase 219: per-template-type visual overrides (line spacing, grid opacity,
    // dot radius, accent color). Stored as a JSON string keyed by template type
    // ("lined", "grid", "dots"). No DB schema impact — prefs only.
    var templatePrefsJson: String
        get() = prefs.getString("template_prefs_json", "{}") ?: "{}"
        set(value) = prefs.edit().putString("template_prefs_json", value).apply()

    /** Read a single template-type override or the default. */
    fun templatePref(templateType: String, key: String, default: String): String {
        return try {
            val obj = org.json.JSONObject(templatePrefsJson)
            if (obj.has(templateType)) {
                val inner = obj.getJSONObject(templateType)
                if (inner.has(key)) inner.getString(key) else default
            } else default
        } catch (_: Exception) { default }
    }

    /** Write a single template-type override. */
    fun setTemplatePref(templateType: String, key: String, value: String) {
        val obj = try { org.json.JSONObject(templatePrefsJson) } catch (_: Exception) { org.json.JSONObject() }
        val inner = if (obj.has(templateType)) obj.getJSONObject(templateType) else org.json.JSONObject()
        inner.put(key, value)
        obj.put(templateType, inner)
        templatePrefsJson = obj.toString()
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

    // Phase 155: two-finger undo/redo gesture shortcuts on the canvas. OFF by
    // default so classic single-finger drawing and pinch-zoom stay untouched;
    // once enabled the canvas classifier never consumes, so pinch-zoom still works.
    var twoFingerUndoRedoEnabled: Boolean
        get() = prefs.getBoolean("two_finger_undo_redo_enabled", false)
        set(value) = prefs.edit().putBoolean("two_finger_undo_redo_enabled", value).apply()

    /** One-time-only non-alarming hint shown when two-finger gestures are first enabled. */
    var twoFingerHintShown: Boolean
        get() = prefs.getBoolean("two_finger_hint_shown", false)
        set(value) = prefs.edit().putBoolean("two_finger_hint_shown", value).apply()

    // Phase 155: long-press quick-color ring on the canvas. OFF by default.
    var quickColorRingEnabled: Boolean
        get() = prefs.getBoolean("quick_color_ring_enabled", false)
        set(value) = prefs.edit().putBoolean("quick_color_ring_enabled", value).apply()

    /** One-time-only non-alarming hint shown when the quick-color ring is first enabled. */
    var quickColorRingHintShown: Boolean
        get() = prefs.getBoolean("quick_color_ring_hint_shown", false)
        set(value) = prefs.edit().putBoolean("quick_color_ring_hint_shown", value).apply()

    // Phase 155: user-imported `.inkbrush` brush presets persisted as a JSON
    // array (shared prefs only — NO DB schema impact). Re-importing the same
    // file dedupes via BrushPresetFileCodec.derivedId.
    var importedBrushPresetsJson: String
        get() = prefs.getString("imported_brush_presets_json", "[]") ?: "[]"
        set(value) = prefs.edit().putString("imported_brush_presets_json", value).apply()

    // Phase 19: dual erasers — "STROKE" (classic whole-stroke eraser) is the
    // default so existing behaviour is unchanged; "PARTIAL" trims each touched
    // stroke into surviving segments. SharedPreferences only, no DB schema change.
    var eraserModeKey: String
        get() = prefs.getString("eraser_mode_key", "STROKE") ?: "STROKE"
        set(value) = prefs.edit().putString("eraser_mode_key", value).apply()

    // Phase 209: recent-search history — the last [RecentSearchPolicy.CAP]
    // non-blank EXECUTED vault-search queries, persisted as a `search_recent_<n>`
    // ring (n = 0 is most recent). SharedPreferences only — NEVER the DB schema.
    // Phase 209 REVIEW-FIX (finding 2): search strings are user-typed text
    // derived from decrypted note content, and prefs are plaintext XML — so every
    // ring value is now ENCRYPTED at rest under a non-extractable AndroidKeyStore
    // AES-GCM key (same discipline as WebDavCredentialStore; honors the phase-158
    // rule that prefs never hold sensitive content in the clear). Fail-CLOSED:
    // when the keystore or encryption is unavailable nothing is written and
    // undecryptable entries read back as null — a plaintext fallback is never
    // taken. The ring insert/dedupe/cap math lives in RecentSearchPolicy (pure
    // JVM); these accessors are the glue and sanitize on read-back.
    fun getRecentSearches(): List<String> =
        RecentSearchPolicy.sanitize(
            (0 until RecentSearchPolicy.CAP).map { decryptRingValue(prefs.getString("search_recent_$it", null)) }
        )

    fun setRecentSearches(queries: List<String>) {
        val clean = RecentSearchPolicy.sanitize(queries)
        prefs.edit().apply {
            for (i in 0 until RecentSearchPolicy.CAP) {
                val enc = if (i < clean.size) encryptRingValue(clean[i]) else null
                if (enc != null) putString("search_recent_$i", enc) else remove("search_recent_$i")
            }
        }.apply()
    }

    /** Mint-or-load the dedicated, non-extractable AES-256-GCM keystore key. */
    private fun recentSearchKeyOrNull(): SecretKey? = try {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(RECENT_SEARCHES_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (keyStore.containsAlias(RECENT_SEARCHES_KEY_ALIAS)) {
            runCatching { keyStore.deleteEntry(RECENT_SEARCHES_KEY_ALIAS) }
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                RECENT_SEARCHES_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        generator.generateKey()
    } catch (t: Throwable) {
        null
    }

    /** AES-GCM encrypt to `iv(12) || ciphertext`, base64 — or null (fail-closed). */
    private fun encryptRingValue(plain: String): String? {
        return try {
            val key = recentSearchKeyOrNull() ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(RECENT_SEARCH_IV_BYTES + cipherText.size)
            System.arraycopy(cipher.iv, 0, combined, 0, RECENT_SEARCH_IV_BYTES)
            System.arraycopy(cipherText, 0, combined, RECENT_SEARCH_IV_BYTES, cipherText.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Decrypt a stored ring value, or null when absent/undecryptable. A null on
     * decrypt also silently retires any pre-review-fix PLAINTEXT entry left by
     * the original phase-209 build (it can never validate against GCM).
     */
    private fun decryptRingValue(stored: String?): String? {
        return try {
            if (stored.isNullOrBlank()) return null
            val key = recentSearchKeyOrNull() ?: return null
            val combined = Base64.decode(stored, Base64.NO_WRAP)
            if (combined.size <= RECENT_SEARCH_IV_BYTES) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(128, combined, 0, RECENT_SEARCH_IV_BYTES)
            )
            String(
                cipher.doFinal(combined, RECENT_SEARCH_IV_BYTES, combined.size - RECENT_SEARCH_IV_BYTES),
                Charsets.UTF_8
            )
        } catch (t: Throwable) {
            null
        }
    }

    // Phase 172: PERSISTED recently-used brush colors + favorites (ARGB ints).
    // The wire format (comma-joined decimal ARGB) and the caps/dedupe live in
    // ColorRecentsPolicy; these accessors are the SharedPreferences glue. Stored
    // as prefs only — never the DB schema — so a cold-restart session keeps the
    // last used colors + favorites instead of the old volatile in-memory list.
    var recentColors: List<Int>
        get() = ColorRecentsPolicy.decodeColors(prefs.getString("recent_colors", null))
        set(value) = prefs.edit()
            .putString("recent_colors", ColorRecentsPolicy.encodeColors(ColorRecentsPolicy.sanitizeRecent(value)))
            .apply()

    var favoriteColors: List<Int>
        get() = ColorRecentsPolicy.decodeColors(prefs.getString("favorite_colors", null))
        set(value) = prefs.edit()
            .putString("favorite_colors", ColorRecentsPolicy.encodeColors(ColorRecentsPolicy.sanitizeFavorites(value)))
            .apply()

    // Phase 122: the editor's current brush color MODE (Rainbow / Gradient /
    // Shimmer / Solid). This is the user's brush choice, persisted across
    // sessions so reopening a note keeps the rainbow brush selected — NOT a
    // per-note/DB concern (per-stroke mode still round-trips through the stroke
    // payload per phase-27). Rounded through ColorModePersistencePolicy so the
    // pref key + fail-closed decode live in one testable decision table.
    var brushColorModeKey: String
        get() = prefs.getString(
            ColorModePersistencePolicy.PREF_KEY_COLOR_MODE,
            ColorModePersistencePolicy.DEFAULT_MODE.persistenceKey
        ) ?: ColorModePersistencePolicy.DEFAULT_MODE.persistenceKey
        set(value) = prefs.edit()
            .putString(ColorModePersistencePolicy.PREF_KEY_COLOR_MODE, value)
            .apply()

    // Phase 122 (review fix): the brush BASE colour and the GRADIENT end colour
    // (ARGB ints) accompany the persisted MODE so a reopened GRADIENT/SHIMMER/Rainbow
    // session restores its real colours instead of falling back to the default navy.
    // SharedPreferences, never the DB schema. Default matches the editor's long-standing
    // default base colour 0xFF1B365D.
    var brushColorArgb: Int
        get() = prefs.getInt("brush_color_key", 0xFF1B365D.toInt())
        set(value) = prefs.edit().putInt("brush_color_key", value).apply()

    var brushGradientToArgb: Int
        get() = prefs.getInt("brush_gradient_to_key", 0xFF1B365D.toInt())
        set(value) = prefs.edit().putInt("brush_gradient_to_key", value).apply()

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

    // Phase 28: GLASS-theme frosted blur. OFF by default on LOW_END devices is
    // handled by GlassBlurGate (tier-aware); this is the user's master switch.
    var glassBlurEnabled: Boolean
        get() = prefs.getBoolean("glass_blur_enabled", true)
        set(value) = prefs.edit().putBoolean("glass_blur_enabled", value).apply()

    // Phase 34: Markdown long-form reading uses the editorial serif (Lora) only
    // when this toggle is on; UI chrome stays geometric sans by default.
    var serifReadingEnabled: Boolean
        get() = prefs.getBoolean("serif_reading_enabled", false)
        set(value) = prefs.edit().putBoolean("serif_reading_enabled", value).apply()

    // Phase 218: optional line-number gutter in fenced/indented code blocks.
    // OFF by default so existing code blocks render identically; the gutter is
    // a cosmetic read-only overlay that does not alter the AnnotatedString
    // content (copy/selection still returns the raw source).
    var markdownCodeGutterEnabled: Boolean
        get() = prefs.getBoolean("markdown_code_gutter_enabled", false)
        set(value) = prefs.edit().putBoolean("markdown_code_gutter_enabled", value).apply()

    // Phase 158 (22.5a): NON-SECRET "a shared clip is pending capture" marker.
    // A boolean + a wall-clock stamp ONLY — never the clip content (that would
    // be plaintext at rest, which every encryption finding forbids). Used to
    // know "the user had a capture in flight" across a process kill; the clip
    // itself is never persisted and either applies (bytes move) or is dropped.
    var capturedSharePending: Boolean
        get() = prefs.getBoolean(PendingSharePolicy.CAPTURED_MARKER_KEY, false)
        set(value) = prefs.edit().putBoolean(PendingSharePolicy.CAPTURED_MARKER_KEY, value).apply()

    var capturedSharePendingAtMs: Long
        get() = prefs.getLong(PendingSharePolicy.CAPTURED_MARKER_AT_MS_KEY, 0L)
        set(value) = prefs.edit().putLong(PendingSharePolicy.CAPTURED_MARKER_AT_MS_KEY, value).apply()

    // 22.1 + B1-PLAT-4 (phase-60): auto-lock after this many seconds of inactivity
    // while foregrounded (0 = off). Ships ENABLED (5 min) by default so a
    // foregrounded, unattended vault cannot stay readable indefinitely on a
    // no-keyguard device.
    var autoLockTimeoutSeconds: Int
        get() = prefs.getInt(
            "auto_lock_timeout_seconds",
            AutoLockPolicy.DEFAULT_AUTO_LOCK_TIMEOUT_SECONDS
        )
        set(value) = prefs.edit().putInt("auto_lock_timeout_seconds", value).apply()

    val hasMasterPassword: Boolean
        // B1-CRYPTO-03 (phase-62): a stored-but-unparseable credential blob
        // (unknown/future format version) must still count as "a master password
        // EXISTS" — the accessor fails closed on it, so the vault is treated as
        // protected and NEVER opened passwordless (which would otherwise cascade
        // into the phase-09 corruption-quarantine path on a downgrade). Legacy
        // half-pairs (salt without wrapper) resolve to null here exactly as
        // pre-fix, so devices already bricked by the old bug are unchanged.
        get() = masterPasswordCredentialOrLegacy != null || prefs.contains("master_password_credential")

    var lowEndWarningShown: Boolean
        get() = prefs.getBoolean("low_end_warning_shown", false)
        set(value) = prefs.edit().putBoolean("low_end_warning_shown", value).apply()

    var useSidebarLayout: Boolean
        get() = prefs.getBoolean("use_sidebar_layout", false)
        set(value) = prefs.edit().putBoolean("use_sidebar_layout", value).apply()

    var showStrokePreviewsInPicker: Boolean
        get() = prefs.getBoolean("show_stroke_previews_in_picker", false)
        set(value) = prefs.edit().putBoolean("show_stroke_previews_in_picker", value).apply()

    // Phase 129 restore: the spatial minimap HUD is OFF by default (the pre-35
    // contract — a plain per-session toggle in the canvas settings sheet). The
    // phase-35 persisted default-true regression is reverted. LOW_END devices
    // still auto-disable it once (with a one-time message) and the user can
    // re-enable it here / from the canvas settings sheet.
    var minimapHudEnabled: Boolean
        get() = prefs.getBoolean("minimap_hud_enabled", MinimapGeometryPolicy.VISIBLE_BY_DEFAULT)
        set(value) = prefs.edit().putBoolean("minimap_hud_enabled", value).apply()

    // Phase 129: making the minimap draggable is a per-user opt-in (OFF by
    // default). The drag offset itself is session-scoped.
    var minimapDraggable: Boolean
        get() = prefs.getBoolean("minimap_draggable", FloatingWidgetDragPolicy.MINIMAP_DRAGGABLE_DEFAULT)
        set(value) = prefs.edit().putBoolean("minimap_draggable", value).apply()

    // Phase 129: the restored horizontal ink bar is draggable only when opted
    // in (OFF by default). Snap-to-edge on release and cross-session dock
    // persistence are separate opt-in extras from phase-35, both default OFF —
    // the default bar posture is the restored pre-35 bottom-centre capsule.
    var inkBarDraggable: Boolean
        get() = prefs.getBoolean("ink_bar_draggable", FloatingWidgetDragPolicy.INK_BAR_DRAGGABLE_DEFAULT)
        set(value) = prefs.edit().putBoolean("ink_bar_draggable", value).apply()

    var inkBarSnapToEdgeEnabled: Boolean
        get() = prefs.getBoolean("ink_bar_snap_to_edge_enabled", FloatingWidgetDragPolicy.INK_BAR_SNAP_TO_EDGE_DEFAULT)
        set(value) = prefs.edit().putBoolean("ink_bar_snap_to_edge_enabled", value).apply()

    var inkBarDockPersistEnabled: Boolean
        get() = prefs.getBoolean("ink_bar_dock_persist_enabled", FloatingWidgetDragPolicy.INK_BAR_DOCK_PERSIST_DEFAULT)
        set(value) = prefs.edit().putBoolean("ink_bar_dock_persist_enabled", value).apply()

    /** Last dragged ink-bar offset (only meaningful while [inkBarDockPersistEnabled]). */
    var inkBarDragOffsetX: Float
        get() = prefs.getFloat("ink_bar_drag_offset_x", -1f)
        set(value) = prefs.edit().putFloat("ink_bar_drag_offset_x", value).apply()

    var inkBarDragOffsetY: Float
        get() = prefs.getFloat("ink_bar_drag_offset_y", -1f)
        set(value) = prefs.edit().putFloat("ink_bar_drag_offset_y", value).apply()

    // Phase 35: remembers whether the low-end auto-disable message has been shown.
    var lowEndMinimapWarningShown: Boolean
        get() = prefs.getBoolean("low_end_minimap_warning_shown", false)
        set(value) = prefs.edit().putBoolean("low_end_minimap_warning_shown", value).apply()

    // Phase 213: remembers whether the paper-elevation low-end auto-off message
    // has been shown (same one-time honest-degradation pattern as the two above).
    var lowEndPaperElevationWarningShown: Boolean
        get() = prefs.getBoolean("low_end_paper_elevation_warning_shown", false)
        set(value) = prefs.edit().putBoolean("low_end_paper_elevation_warning_shown", value).apply()

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
                key == "plugin_update_previous_$pluginId" ||
                key == "plugin_invocation_journal_$pluginId" ||
                key.startsWith(prefix)
        }
        prefs.edit().apply { keys.forEach { remove(it) } }.apply()
    }

    // Phase 173: bounded per-plugin invocation journal (Settings → Plugins →
    // "Recent activity"). Lives in its OWN key family (NOT under plugins.<id>.*)
    // so a plugin can never read or forge its own journal through the plugin
    // settings API, and is wiped together with the plugin by the store's Delete
    // (wipePluginState above — delete = gone + journal gone; disable keeps it).
    fun getPluginInvocationJournal(pluginId: String): String? =
        prefs.getString("plugin_invocation_journal_$pluginId", null)

    fun setPluginInvocationJournal(pluginId: String, journal: String?) {
        prefs.edit().apply {
            if (journal == null) remove("plugin_invocation_journal_$pluginId")
            else putString("plugin_invocation_journal_$pluginId", journal)
        }.apply()
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

    // Phase 24: the update flow's rollback root — the previously-active
    // (pre-update) version of a downloadable plugin, persisted BEFORE any update
    // byte moves so a failed update (or mid-update process death) always has a
    // version to restore. Wiped by store Delete (see wipePluginState below).
    fun getPluginUpdatePreviousJson(pluginId: String): String? =
        prefs.getString("plugin_update_previous_$pluginId", null)

    fun setPluginUpdatePreviousJson(pluginId: String, json: String?) {
        prefs.edit().apply {
            if (json == null) remove("plugin_update_previous_$pluginId")
            else putString("plugin_update_previous_$pluginId", json)
        }.apply()
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

    // Phase 41 / B1-NET-02: TOFU-paired LocalSend receivers. The value is a
    // JSON blob from LocalSendPairedDeviceCodec keyed by the NORMALIZED TLS
    // certificate fingerprint (lowercase, no colons); the alias is display-only
    // — the fingerprint is the identity. SharedPreferences, no DB schema change.
    fun getLocalSendPairedDeviceJson(fingerprint: String): String? =
        prefs.getString(
            "localsend_paired_" + LocalSendPairingCodes.normalizeFingerprint(fingerprint),
            null
        )

    fun setLocalSendPairedDeviceJson(fingerprint: String, json: String?) {
        val key = "localsend_paired_" + LocalSendPairingCodes.normalizeFingerprint(fingerprint)
        prefs.edit().apply {
            if (json == null) remove(key) else putString(key, json)
        }.apply()
    }

    /** Every stored pairing's normalized-fingerprint key (for enumeration). */
    fun allLocalSendPairedFingerprints(): List<String> {
        val out = mutableListOf<String>()
        prefs.all.keys.forEach { key ->
            if (key.startsWith("localsend_paired_")) out.add(key.removePrefix("localsend_paired_"))
        }
        return out
    }

    fun clearSecuritySettings() {
        prefs.edit()
            .remove("master_password_credential")
            .remove("master_password_salt")
            .remove("master_password_wrapped_dek")
            .remove("biometric_auth_enabled")
            .apply()
    }

    companion object {
        /** Dedicated alias — never shared with the WebDAV credential keys. */
        private const val RECENT_SEARCHES_KEY_ALIAS = "noteflow_recent_searches_key"

        /** GCM standard 12-byte IV prefix of every stored ring blob. */
        private const val RECENT_SEARCH_IV_BYTES = 12
    }
}
