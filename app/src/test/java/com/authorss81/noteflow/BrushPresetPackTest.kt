package com.authorss81.noteflow

import com.authorss81.noteflow.services.BrushPresetPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushPresetPackTest {

    @Test
    fun `every preset uses valid parameter ranges`() {
        val problems = BrushPresetPack.validateAllPresets()
        assertEquals("no BrushStudioParams may leave [0,1]", emptyList<Pair<String, String>>(), problems)
    }

    @Test
    fun `every preset uses a sane stroke size`() {
        val problems = BrushPresetPack.validatePresetSizes()
        assertEquals("every preset size must sit in [0.5,120]", emptyList<Pair<String, String>>(), problems)
    }

    @Test
    fun `preset ids are unique and resolvable`() {
        val all = BrushPresetPack.all()
        val ids = all.map { it.id }
        assertEquals("ids must be unique", ids.size, ids.toSet().size)
        all.forEach { preset ->
            assertTrue("byId must resolve ${preset.id}", BrushPresetPack.isValidId(preset.id))
            assertEquals("byId round-trip", preset.name, BrushPresetPack.byId(preset.id)!!.name)
        }
    }

    @Test
    fun `presets map to real tools only`() {
        BrushPresetPack.all().forEach { preset ->
            assertTrue(
                "preset ${preset.name} must map to a real drawing tool, not ${preset.tool}",
                com.authorss81.noteflow.data.model.StrokeTool.entries.contains(preset.tool)
            )
        }
    }

    @Test
    fun `erased preset clears to unknown id`() {
        assertTrue("null id is not a valid preset", !BrushPresetPack.isValidId(null))
        assertTrue("unknown id is not a valid preset", !BrushPresetPack.isValidId("no_such_preset"))
    }
}