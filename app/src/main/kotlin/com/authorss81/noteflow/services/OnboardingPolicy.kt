package com.authorss81.noteflow.services

/**
 * Phase 156: pure, JVM-testable first-run triage policy.
 *
 * The passwordless first-run intro is deliberately NOT a feature tutorial — it
 * is a short, one-time, dismissible triage for a brand-new vault (phase-125's
 * enhanced interactive tutorial remains a separate, ⋮-menu reachable
 * experience). All gate + copy decisions live here so the UI can never drift
 * from the tested contract.
 */
object OnboardingPolicy {

    /**
     * Whether the intro should AUTO-SHOW on this launch. It shows once, only
     * for a passwordless (no-master-password yet) vault, and only while the
     * onboarding flag is still unset. A vault that later gains a master
     * password never re-auto-shows it (the ⋮ menu's "Show help again" entry
     * re-opens it on demand regardless of password state).
     */
    fun shouldAutoShow(
        isFirstRun: Boolean,
        hasMasterPassword: Boolean,
        onboardingCompleted: Boolean
    ): Boolean = isFirstRun && !hasMasterPassword && !onboardingCompleted

    /** One intro step's copy. */
    data class OnboardingStep(
        val title: String,
        val body: String
    )

    /**
     * The three non-blocking triage steps. The privacy stance is surfaced as a
     * separate banner card above these (see FirstRunOnboardingSheet) — this
     * list is the copy for the 3-step flow only.
     */
    val steps: List<OnboardingStep> = listOf(
        OnboardingStep(
            title = "Create notes fast",
            body = "Tap + to start typing a note, or import a PDF, image or web page from the home toolbar."
        ),
        OnboardingStep(
            title = "Draw on the ink canvas",
            body = "Every note has an infinite ink canvas. Scribble with a brush, snap shapes to straight lines, and let it grow with you."
        ),
        OnboardingStep(
            title = "Plugins & backup",
            body = "Extend the app from the Plugin Store and protect your vault with an encrypted backup — to a file or your own server."
        )
    )

    /** The privacy stance banner always shown above the step cards. */
    const val PRIVACY_STANCE_TITLE = "Your vault stays on this device"
    const val PRIVACY_STANCE_BODY =
        "Everything is encrypted before it is stored. There is no cloud account, no sync to a vendor server — your notes live only where you put them."
}
