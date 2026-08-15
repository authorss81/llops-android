package com.authorss81.noteflow

import com.authorss81.noteflow.theme.TypeScale
import com.authorss81.noteflow.theme.TypeScaleRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 34: the type scale is the contract every text role hangs off. It must
 * cover every Material 3 style, every style must map to one intent role, and
 * every line-height must sit on the 4 dp baseline grid so rows align.
 */
class TypeScaleTest {

    @Test
    fun `scale covers every material 3 role style`() {
        assertTrue(TypeScale.isComplete())
    }

    @Test
    fun `every style resolves to exactly one intent role`() {
        for (spec in TypeScale.scales) {
            assertEquals(spec.role, TypeScale.roleFor(spec.styleName))
            assertNotNull(TypeScale.specFor(spec.styleName))
        }
    }

    @Test
    fun `all line-heights sit on the 4 dp baseline grid`() {
        for (spec in TypeScale.scales) {
            assertTrue("${spec.styleName} line-height off grid", spec.isOnBaselineGrid)
            assertTrue("${spec.styleName} fontSize<=0", spec.fontSizeSp > 0f)
            assertTrue("${spec.styleName} lineHeight>size", spec.lineHeightSp >= spec.fontSizeSp)
        }
    }

    @Test
    fun `styles descend in size within each role`() {
        for (role in TypeScaleRole.entries) {
            val names = TypeScale.styleNamesFor(role)
            assertTrue(names.isNotEmpty())
            val sizes = names.mapNotNull { TypeScale.specFor(it)?.fontSizeSp }
            for (i in 1 until sizes.size) {
                assertTrue("$role not descending at $i", sizes[i] < sizes[i - 1])
            }
        }
    }

    @Test
    fun `role members cover each role's material styles`() {
        assertEquals(TypeScale.styleNamesFor(TypeScaleRole.DISPLAY), listOf("displayLarge", "displayMedium", "displaySmall"))
        assertEquals(TypeScale.styleNamesFor(TypeScaleRole.HEADLINE), listOf("headlineLarge", "headlineMedium", "headlineSmall"))
        assertEquals(TypeScale.styleNamesFor(TypeScaleRole.TITLE), listOf("titleLarge", "titleMedium", "titleSmall"))
        assertEquals(TypeScale.styleNamesFor(TypeScaleRole.BODY), listOf("bodyLarge", "bodyMedium", "bodySmall"))
        assertEquals(TypeScale.styleNamesFor(TypeScaleRole.LABEL), listOf("labelLarge", "labelMedium", "labelSmall"))
    }

    @Test
    fun `unknown style names are absent`() {
        assertEquals(null, TypeScale.roleFor("notARealStyle"))
        assertEquals(null, TypeScale.specFor("notARealStyle"))
    }

    @Test
    fun `labels are the smallest and display the largest`() {
        val label = TypeScale.specFor("labelSmall")!!
        val display = TypeScale.specFor("displayLarge")!!
        assertTrue(label.fontSizeSp < display.fontSizeSp)
        assertTrue(label.lineHeightSp < display.lineHeightSp)
    }
}