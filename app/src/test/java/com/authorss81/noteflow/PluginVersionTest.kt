package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.SemanticVersion
import com.authorss81.noteflow.plugins.runtime.BumpKind
import com.authorss81.noteflow.plugins.runtime.PluginVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 22: [PluginVersion] — the semver type of the unified catalog seam.
 * Strict parse, ordering, bumping and framework interop. Pure JVM.
 */
class PluginVersionTest {

    @Test
    fun `parse accepts exactly three non-negative components`() {
        assertEquals(PluginVersion(1, 2, 3), PluginVersion.parse("1.2.3"))
        assertEquals(PluginVersion(0, 0, 0), PluginVersion.parse("0.0.0"))
        assertEquals(PluginVersion(10, 200, 3000), PluginVersion.parse(" 10.200.3000 "))
        assertEquals(PluginVersion(0, 0, 0), PluginVersion.parse("0.0.0\n"))
    }

    @Test
    fun `parse rejects malformed or negative versions`() {
        assertNull(PluginVersion.parse("1.2"))
        assertNull(PluginVersion.parse("1"))
        assertNull(PluginVersion.parse("1.2.3.4"))
        assertNull(PluginVersion.parse("1.2.x"))
        assertNull(PluginVersion.parse("1.-2.3"))
        assertNull(PluginVersion.parse(""))
        assertNull(PluginVersion.parse("v1.2.3"))
    }

    @Test
    fun `ordering follows major then minor then patch`() {
        assertTrue(PluginVersion(1, 2, 3) < PluginVersion(1, 2, 4))
        assertTrue(PluginVersion(1, 2, 3) < PluginVersion(1, 3, 0))
        assertTrue(PluginVersion(1, 9, 9) < PluginVersion(2, 0, 0))
        assertEquals(PluginVersion(2, 0, 1), PluginVersion(2, 0, 1))
        assertFalse(PluginVersion(2, 0, 1) < PluginVersion(2, 0, 1))
    }

    @Test
    fun `isNewerThan only when strictly newer`() {
        assertTrue(PluginVersion(2, 0, 0).isNewerThan(PluginVersion(1, 9, 9)))
        assertTrue(PluginVersion(1, 1, 0).isNewerThan(PluginVersion(1, 0, 9)))
        assertTrue(PluginVersion(1, 0, 1).isNewerThan(PluginVersion(1, 0, 0)))
        assertFalse(PluginVersion(1, 0, 0).isNewerThan(PluginVersion(1, 0, 0)))
        assertFalse(PluginVersion(1, 0, 0).isNewerThan(PluginVersion(2, 0, 0)))
    }

    @Test
    fun `bump derives the next version and resets lower components`() {
        assertEquals(PluginVersion(1, 2, 4), PluginVersion(1, 2, 3).bump(BumpKind.PATCH))
        assertEquals(PluginVersion(1, 3, 0), PluginVersion(1, 2, 9).bump(BumpKind.MINOR))
        assertEquals(PluginVersion(2, 0, 0), PluginVersion(1, 9, 9).bump(BumpKind.MAJOR))
        assertEquals(PluginVersion(0, 0, 1), PluginVersion(0, 0, 0).bump(BumpKind.PATCH))
    }

    @Test
    fun `interops with the framework SemanticVersion without loss`() {
        val original = PluginVersion(4, 5, 6)
        val semver = original.toSemanticVersion()
        assertEquals(SemanticVersion(4, 5, 6), semver)
        assertEquals(original, PluginVersion.from(semver))
        assertEquals("4.5.6", original.toString())
    }
}
