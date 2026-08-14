package com.authorss81.noteflow

import com.authorss81.noteflow.data.repository.LruBoundedMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 100 (B2-DOS-10): the in-memory stroke-content-hash diff cache in
 * `NoteRepository.lastSavedStrokeHash` must be bounded. A long editing session on
 * a vault with tens of thousands of strokes previously grew the map for the whole
 * session (entries keyed by global stroke UUID, switched pages never GC'd).
 *
 * These tests prove the LRU cap: the map never exceeds its bound no matter how
 * many distinct stroke ids are seen, and evictions only ever displace the
 * least-recently-accessed entry — a cold stroke being re-saved on its next write.
 */
class LruBoundedMapTest {

    @Test
    fun `size never exceeds the configured cap across many distinct keys`() {
        val cap = 100
        val map = LruBoundedMap<String, Int>(cap)
        for (i in 0 until 10_000) {
            map["stroke-uuid-$i"] = i
        }
        assertEquals(cap, map.size)
    }

    @Test
    fun `least recently accessed entries are the first to be evicted`() {
        val map = LruBoundedMap<String, Int>(3)
        map["a"] = 1
        map["b"] = 2
        map["c"] = 3
        // Touch "a" so it is the most recently used; "b" is now the least recent.
        map["a"]
        map["d"] = 4
        assertEquals(3, map.size)
        assertNull(map["b"])
        assertEquals(1, map["a"])
        assertEquals(3, map["c"])
        assertEquals(4, map["d"])
    }

    @Test
    fun `overwriting an existing key does not count as a new entry`() {
        val map = LruBoundedMap<String, Int>(2)
        map["a"] = 1
        map["b"] = 2
        map["a"] = 99
        map["c"] = 3
        assertEquals(2, map.size)
        assertNull(map["b"])
        assertEquals(99, map["a"])
        assertEquals(3, map["c"])
    }

    @Test
    fun `evicted key previously matched by the diff check will be re-saved once re-added`() {
        // Mirrors saveStrokesForPage's changed-detection: a missing entry (false
        // null) must be treated as "changed" so the stroke is rewritten, never
        // dropped silently.
        val map = LruBoundedMap<String, Int>(2)
        map["stroke-1"] = 42
        map["stroke-2"] = 43
        map["stroke-3"] = 44 // evicts least-recently-used "stroke-1"
        assertTrue(map["stroke-1"] == null)
        assertEquals(2, map.size)
        // Re-saving "stroke-1" with a changed hash writes and re-seats the entry.
        map["stroke-1"] = 52
        assertEquals(52, map["stroke-1"])
        assertEquals(2, map.size)
    }

    @Test
    fun `remove deletes an entry and frees a slot`() {
        val map = LruBoundedMap<String, Int>(2)
        map["a"] = 1
        map["b"] = 2
        map.remove("a")
        assertNull(map["a"])
        assertEquals(1, map.size)
    }
}