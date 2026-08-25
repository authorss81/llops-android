package com.authorss81.noteflow.services

import com.authorss81.noteflow.plugins.PluginCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 212: [SettingsPluginInvocationJournalStore] + the bounded
 * [PluginInvocationJournal] append/cap logic it persists. The journal lives in
 * its OWN key family (NOT under `plugins.<id>.*`) so a plugin can neither read
 * nor forge it, and the store's Delete ([SettingsManager.wipePluginState])
 * wipes it.
 */
class SettingsPluginInvocationJournalStoreTest {

    private val id = "com.example.tracked.plugin"

    private fun entry(at: Long) = PluginInvocationJournal.Entry(
        atMillis = at,
        capabilityKey = PluginCapability.TextTransform.key,
        ok = true,
        detail = null
    )

    @Test
    fun `fresh plugins have no journal`() {
        val store = SettingsPluginInvocationJournalStore(settingsOver(FakePrefs()))

        assertNull(store.read(id))
    }

    @Test
    fun `written wires round-trip`() {
        val store = SettingsPluginInvocationJournalStore(settingsOver(FakePrefs()))
        val wire = PluginInvocationJournal.record(null, entry(at = 1000L))

        store.write(id, wire)

        val parsed = PluginInvocationJournal.parse(store.read(id))
        assertEquals(1, parsed.size)
        assertEquals(1000L, parsed.single().atMillis)
    }

    @Test
    fun `writing null removes the journal`() {
        val prefs = FakePrefs()
        val store = SettingsPluginInvocationJournalStore(settingsOver(prefs))
        store.write(id, PluginInvocationJournal.record(null, entry(1L)))

        store.write(id, null)

        assertNull(store.read(id))
        assertTrue(prefs.map.keys.none { it.startsWith("plugin_invocation_journal_") })
    }

    @Test
    fun `the journal is capped at MAX_JOURNAL_ENTRIES, oldest dropped first`() {
        val prefs = FakePrefs()
        val store = SettingsPluginInvocationJournalStore(settingsOver(prefs))
        var wire: String? = null
        for (i in 1..25) {
            wire = PluginInvocationJournal.record(wire, entry(at = i.toLong()))
            store.write(id, wire)
        }

        val persisted = PluginInvocationJournal.parse(store.read(id))
        assertEquals(PluginInvocationJournal.MAX_JOURNAL_ENTRIES, persisted.size)
        assertEquals("oldest entries must be evicted", 6L, persisted.first().atMillis)
        assertEquals(25L, persisted.last().atMillis)
    }

    @Test
    fun `journal survives disable but not Delete`() {
        val prefs = FakePrefs()
        val settings = settingsOver(prefs)
        val store = SettingsPluginInvocationJournalStore(settings)
        store.write(id, PluginInvocationJournal.record(null, entry(42L)))

        // Disable keeps data:
        settings.setPluginEnabled(id, false)
        assertEquals(1, PluginInvocationJournal.parse(store.read(id)).size)

        // Delete wipes everything, journal included:
        settings.wipePluginState(id)
        assertNull(store.read(id))
    }

    @Test
    fun `journals are per-plugin and never collide with plugin settings`() {
        val prefs = FakePrefs()
        val settings = settingsOver(prefs)
        val store = SettingsPluginInvocationJournalStore(settings)
        store.write(id, PluginInvocationJournal.record(null, entry(7L)))
        settings.setPluginSetting(id, "mode", "upper")

        assertTrue(
            "the journal key family must stay outside plugins.<id>.*",
            prefs.map.keys.none { it.startsWith("plugins.$id.") && it.contains("journal") }
        )
        assertEquals("upper", settings.getPluginSetting(id, "mode"))
        assertEquals(1, PluginInvocationJournal.parse(store.read(id)).size)
    }
}
