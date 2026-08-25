package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.data.repository.DecryptedPageCache
import com.authorss81.noteflow.data.repository.SearchCorpusReuse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-207 (crypto/DB efficiency): the search-corpus rebuild must REUSE every
 * still-valid row by ciphertext hash and re-decrypt ONLY genuinely rewritten or
 * new rows. Together with the repository's lazy dirty-flag invalidation, this
 * means a single note edit followed by a palette/search query costs ONE row's
 * AES-GCM instead of the whole capped window's.
 *
 * Modeled here exactly as NoteRepository.loadSearchCorpus drives it:
 * `corpusReuse.assemble(rawCiphertextWindow) { decryptPageOrNullForCorpus(it) }`.
 */
class SearchCorpusReuseTest {

    private fun rawPage(
        id: String,
        titleCipher: String,
        bodyCipher: String?,
        updatedAt: Long = 1L
    ): NotePageEntity = NotePageEntity(
        id = id,
        sectionId = "s",
        title = titleCipher,
        extractedText = bodyCipher,
        updatedAt = updatedAt,
        sourceFileType = "text"
    )

    private class DecryptCounter {
        var calls = 0
        val seen = mutableListOf<String>()
        var failIds: Set<String> = emptySet()

        fun decrypt(page: NotePageEntity): NotePageEntity? {
            calls++
            seen += page.id
            if (page.id in failIds) return null // undecryptable → dropped from corpus
            return page.copy(
                title = "PLAIN(${page.title})",
                extractedText = page.extractedText?.let { "PLAIN($it)" }
            )
        }
    }

    @Test
    fun `first build decrypts every row`() {
        val reuse = SearchCorpusReuse(DecryptedPageCache())
        val counter = DecryptCounter()
        val window = listOf(
            rawPage("p1", "c1", "b1"),
            rawPage("p2", "c2", "b2"),
            rawPage("p3", "c3", "b3")
        )
        val result = reuse.assemble(window) { counter.decrypt(it) }
        assertEquals(3, counter.calls)
        assertEquals(3, result.size)
        assertEquals("PLAIN(c1)", result[0].title)
        assertEquals("PLAIN(b3)", result[2].extractedText)
    }

    @Test
    fun `a rebuild after one row changed re-decrypts only that row`() {
        val reuse = SearchCorpusReuse(DecryptedPageCache())
        val first = DecryptCounter()
        val original = listOf(
            rawPage("p1", "c1", "b1", updatedAt = 10L),
            rawPage("p2", "c2", "b2", updatedAt = 20L),
            rawPage("p3", "c3", "b3", updatedAt = 30L)
        )
        reuse.assemble(original) { first.decrypt(it) }
        assertEquals(3, first.calls)

        // The keystroke save rewrote p2 only — its ciphertext differs now.
        val afterEdit = listOf(
            rawPage("p1", "c1", "b1", updatedAt = 10L),
            rawPage("p2", "c2-EDITED", "b2-EDITED", updatedAt = 99L),
            rawPage("p3", "c3", "b3", updatedAt = 30L)
        )
        val second = DecryptCounter()
        val rebuilt = reuse.assemble(afterEdit) { second.decrypt(it) }
        assertEquals(
            "only the edited row pays an AES-GCM pass",
            1,
            second.calls
        )
        assertEquals(listOf("p2"), second.seen)
        assertEquals(3, rebuilt.size)
        // Unchanged rows come back with the SAME plaintext as the first build.
        assertEquals("PLAIN(c1)", rebuilt[0].title)
        assertEquals("PLAIN(c3)", rebuilt[2].title)
        // The edited row carries its fresh plaintext.
        assertEquals("PLAIN(c2-EDITED)", rebuilt[1].title)
        assertEquals("PLAIN(b2-EDITED)", rebuilt[1].extractedText)
    }

    @Test
    fun `fresh metadata rides on the RAW row even for reused entries`() {
        val reuse = SearchCorpusReuse(DecryptedPageCache())
        val counter = DecryptCounter()
        reuse.assemble(listOf(rawPage("p1", "c1", "b1", updatedAt = 1L))) { counter.decrypt(it) }
        val bumped = rawPage("p1", "c1", "b1", updatedAt = 500L)
        val rebuilt = reuse.assemble(listOf(bumped)) { counter.decrypt(it) }
        assertEquals("no new decrypt for unchanged ciphertext", 1, counter.calls)
        assertTrue(rebuilt[0] === bumped || rebuilt[0].updatedAt == 500L)
        assertEquals("PLAIN(c1)", rebuilt[0].title)
    }

    @Test
    fun `undecryptable rows stay dropped and are retried next rebuild not cached forever`() {
        val reuse = SearchCorpusReuse(DecryptedPageCache())
        val failing = DecryptCounter().apply { failIds = setOf("bad") }
        val window = listOf(rawPage("ok", "c1", "b1"), rawPage("bad", "cX", "bX"))
        val first = reuse.assemble(window) { failing.decrypt(it) }
        assertEquals("the undecryptable row is dropped from the searchable corpus", listOf("ok"), first.map { it.id })

        // Still undecryptable this epoch (e.g. still locked / same wrong key):
        // it must be RETRIED (never memoized as a permanent hole) and re-dropped.
        val retry = DecryptCounter().apply { failIds = setOf("bad") }
        val second = reuse.assemble(window) { retry.decrypt(it) }
        assertTrue(
            "a dropped row is never memoized — it is retried (e.g. after unlock/re-key)",
            "bad" in retry.seen
        )
        assertEquals("the healthy row is still reused", 1, retry.calls)
        assertEquals(listOf("bad"), retry.seen)
        assertEquals(listOf("ok"), second.map { it.id })

        // And once decryption SUCCEEDS again (unlock / re-key), the row recovers.
        val recovered = DecryptCounter()
        val third = reuse.assemble(window) { recovered.decrypt(it) }
        assertEquals(listOf("ok", "bad"), third.map { it.id })
        assertEquals("only the previously-failed row decrypts", listOf("bad"), recovered.seen)
    }

    @Test
    fun `order of the raw window is preserved minus drops`() {
        val reuse = SearchCorpusReuse(DecryptedPageCache())
        val counter = DecryptCounter()
        val window = listOf(
            rawPage("newest", "c1", "b1"),
            rawPage("middle", "c2", "b2"),
            rawPage("oldest", "c3", "b3")
        )
        reuse.assemble(window) { counter.decrypt(it) }
        val reshuffled = window.reversed()
        val rebuilt = reuse.assemble(reshuffled) { counter.decrypt(it) }
        assertEquals(listOf("oldest", "middle", "newest"), rebuilt.map { it.id })
    }

    @Test
    fun `empty window assembles to empty without any decrypt`() {
        val reuse = SearchCorpusReuse(DecryptedPageCache())
        val counter = DecryptCounter()
        val result = reuse.assemble(emptyList()) { counter.decrypt(it) }
        assertTrue(result.isEmpty())
        assertEquals(0, counter.calls)
    }

    @Test
    fun `a renamed page whose body cipher is unchanged still misses on the title hash`() {
        val reuse = SearchCorpusReuse(DecryptedPageCache())
        val first = DecryptCounter()
        reuse.assemble(listOf(rawPage("p1", "old-cipher", "same-body"))) { first.decrypt(it) }
        val second = DecryptCounter()
        val rebuilt = reuse.assemble(listOf(rawPage("p1", "new-cipher", "same-body"))) { second.decrypt(it) }
        assertEquals("title rewrite must invalidate", 1, second.calls)
        assertEquals("PLAIN(new-cipher)", rebuilt[0].title)
    }
}
