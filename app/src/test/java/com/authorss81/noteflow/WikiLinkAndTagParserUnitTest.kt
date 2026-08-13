package com.authorss81.noteflow

import com.authorss81.noteflow.utils.WikiLinkParser
import org.junit.Assert.*
import org.junit.Test

class WikiLinkAndTagParserUnitTest {

    @Test
    fun testUnicodeAndEmojiTagExtraction() {
        val sampleText = "Exploring the #müseum and checking out #🎯 and #art today!"
        val tags = WikiLinkParser.extractTags(sampleText)

        assertTrue("Should extract unicode tag müseum", tags.contains("müseum"))
        assertTrue("Should extract emoji tag 🎯", tags.contains("🎯"))
        assertTrue("Should extract standard tag art", tags.contains("art"))
    }

    @Test
    fun testWikiLinkParsing() {
        val content = "Refer to [[Project Roadmap]] and [[Design Doc|Specifications]] for details."
        val links = WikiLinkParser.extractWikiLinks(content)

        assertEquals(2, links.size)

        assertEquals("Project Roadmap", links[0].targetTitle)
        assertNull(links[0].alias)

        assertEquals("Design Doc", links[1].targetTitle)
        assertEquals("Specifications", links[1].alias)
    }

    @Test
    fun testBacklinkWordBoundaryMatching() {
        val sourceContent = "We are hosting a party tonight with great music."
        val pagesToMatch = listOf("art", "party")

        val matches = WikiLinkParser.findLinkedPageIdsForContent(sourceContent, pagesToMatch)

        // "art" should NOT match inside "party" due to word boundaries
        assertFalse("Should not match 'art' inside 'party'", matches.contains("art"))
        assertTrue("Should match exact word 'party'", matches.contains("party"))
    }
}
