package com.authorss81.noteflow.services

/**
 * Phase 36: pure-JVM motion/haptics policy. Kept free of android/Compose imports so
 * the tuning decisions are testable on the JVM.
 *
 * Spring tuning lives here (data), and `theme/Motion.kt` builds the actual Compose
 * `AnimationSpec`s from these values so the UI never hardcodes magic numbers.
 */
object MotionPolicy {

    enum class SpringKind {
        /** Bottom-sheet expansion: soft, low stiffness, gentle bounce. */
        SHEET,
        /** Swipe-to-dismiss / delete fling: no overshoot, snappy settle. */
        DISMISS,
        /** Canvas panning overshoot: damped (ratio > 1) so it never oscillates. */
        CANVAS_PAN,
        /** Shared-element card → editor reveal: boucny but bounded growth. */
        REVEAL
    }

    data class SpringTuning(val dampingRatio: Float, val stiffness: Float) {
        init {
            require(dampingRatio > 0f && dampingRatio.isFinite()) { "dampingRatio must be > 0" }
            require(stiffness > 0f && stiffness.isFinite()) { "stiffness must be > 0" }
        }
    }

    /** Map a gesture kind to a tuned (dampingRatio, stiffness) pair. */
    fun springFor(kind: SpringKind): SpringTuning = when (kind) {
        SpringKind.SHEET -> SpringTuning(dampingRatio = 0.9f, stiffness = 300f)
        SpringKind.DISMISS -> SpringTuning(dampingRatio = 1.0f, stiffness = 500f)
        SpringKind.CANVAS_PAN -> SpringTuning(dampingRatio = 1.4f, stiffness = 200f)
        SpringKind.REVEAL -> SpringTuning(dampingRatio = 0.8f, stiffness = 260f)
    }

    /**
     * The single gate every haptic must pass. Haptics are off when the user disabled
     * the app haptics setting OR system reduce-motion / remove-animations is on.
     */
    fun hapticsAllowed(hapticsEnabled: Boolean, reduceMotion: Boolean): Boolean =
        hapticsEnabled && !reduceMotion

    /**
     * True when the slider's quantized position moved to a different notch.
     * Sliders with `steps` expose N notches; [granularity] is the size of one notch
     * in value-space (e.g. 1pt for the width slider). A tick fires only on a
     * notch boundary crossing, not on every pixel of drag.
     */
    fun sliderNotchTriggered(previous: Float, current: Float, granularity: Float): Boolean {
        require(granularity > 0f) { "granularity must be > 0" }
        if (previous == current) return false
        val prevNotch = kotlin.math.floor(previous / granularity).toLong()
        val currNotch = kotlin.math.floor(current / granularity).toLong()
        return prevNotch != currNotch
    }

    /**
     * Shared-element reveal start scale: morph from the tapped card's width share of
     * the full-screen container, clamped to [minScale] so tiny cards don't make the
     * editor sub-pixel at ignition. Returns 1.0 (no-reveal) for degenerate inputs.
     */
    fun revealStartScale(cardWidth: Float, containerWidth: Float, minScale: Float = 0.55f): Float {
        if (cardWidth <= 0f || containerWidth <= 0f || cardWidth >= containerWidth) return 1f
        return (cardWidth / containerWidth).coerceIn(minScale, 1f)
    }
}