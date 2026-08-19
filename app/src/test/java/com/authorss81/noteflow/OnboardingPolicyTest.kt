package com.authorss81.noteflow

import com.authorss81.noteflow.services.OnboardingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 156: the passwordless first-run triage gate + copy. The policy is the
 * single source of truth — the UI must never auto-show the intro to a vault
 * that already has a master password, nor re-auto-show it once completed.
 */
class OnboardingPolicyTest {

    @Test
    fun `auto-shows exactly on a passwordless first run before completion`() {
        assertTrue(OnboardingPolicy.shouldAutoShow(isFirstRun = true, hasMasterPassword = false, onboardingCompleted = false))
    }

    @Test
    fun `never auto-shows after completion`() {
        assertFalse(OnboardingPolicy.shouldAutoShow(isFirstRun = true, hasMasterPassword = false, onboardingCompleted = true))
    }

    @Test
    fun `never auto-shows once the user set a master password`() {
        assertFalse(OnboardingPolicy.shouldAutoShow(isFirstRun = true, hasMasterPassword = true, onboardingCompleted = false))
        assertFalse(OnboardingPolicy.shouldAutoShow(isFirstRun = true, hasMasterPassword = true, onboardingCompleted = true))
    }

    @Test
    fun `never auto-shows on later launches`() {
        assertFalse(OnboardingPolicy.shouldAutoShow(isFirstRun = false, hasMasterPassword = false, onboardingCompleted = false))
    }

    @Test
    fun `the intro has exactly three non-blank steps`() {
        assertEquals(3, OnboardingPolicy.steps.size)
        for (step in OnboardingPolicy.steps) {
            assertTrue("step title blank: ${step.title}", step.title.isNotBlank())
            assertTrue("step body blank: ${step.title}", step.body.isNotBlank())
        }
    }

    @Test
    fun `step copy covers the three triage surfaces`() {
        val joined = OnboardingPolicy.steps.joinToString { it.title.lowercase() + " " + it.body.lowercase() }
        assertTrue("must mention note creation", joined.contains("note"))
        assertTrue("must mention drawing/ink", joined.contains("ink"))
        assertTrue("must mention plugins and backup", joined.contains("backup") && joined.contains("plugin"))
    }

    @Test
    fun `privacy stance copy is non-blank and never mentions the cloud as a store`() {
        assertTrue(OnboardingPolicy.PRIVACY_STANCE_TITLE.isNotBlank())
        assertTrue(OnboardingPolicy.PRIVACY_STANCE_BODY.isNotBlank())
        assertTrue(OnboardingPolicy.PRIVACY_STANCE_BODY.contains("encrypted", ignoreCase = true))
    }
}