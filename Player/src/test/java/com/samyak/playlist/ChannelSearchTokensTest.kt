package com.samyak.playlist

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ChannelSearchTokensTest {
    @Test fun punctuationUnicodeAndFtsOperatorsAreLiteralTokens() {
        for (name in listOf("nl sport", "één 東京😀", "100%_ [hd]", "a\"b OR c*", "a-b.c/d")) {
            val document = ChannelSearchTokens.document(name).split(' ').toSet()
            for (start in name.indices) for (end in start + 1..name.length) {
                val query = ChannelSearchTokens.query(name.substring(start, end)) ?: continue
                assertTrue("Missing substring of $name", document.containsAll(query.split(' ')))
                assertTrue(query.split(' ').all { it.matches(Regex("g[0-9a-f]{12}")) })
            }
        }
    }

    @Test fun indexedCandidatesNeverExcludeMatchingSubstrings() {
        val random = Random(7)
        val alphabet = "abc é東京😀%_"
        repeat(100) {
            val name = buildString { repeat(200) { append(alphabet[random.nextInt(alphabet.length)]) } }
            val document = ChannelSearchTokens.document(name).split(' ').toSet()
            repeat(30) {
                val start = random.nextInt(name.length - 2)
                val query = ChannelSearchTokens.query(name.substring(start, random.nextInt(start + 3, name.length + 1)))!!
                assertTrue(document.containsAll(query.split(' ')))
                assertTrue(query.split(' ').size <= 8)
            }
        }
    }

    @Test fun shortQueriesFallBackAndLongNamesHaveBoundedIndexSize() {
        for (query in listOf("", "a", "東京", "😀")) assertNull(ChannelSearchTokens.query(query))
        assertNotNull(ChannelSearchTokens.query("abc"))
        assertEquals(ChannelSearchTokens.OVERFLOW, ChannelSearchTokens.document("x".repeat(100_000)))
        assertEquals(ChannelSearchTokens.query("xxx"), ChannelSearchTokens.query("x".repeat(100_000)))
        assertTrue(ChannelSearchTokens.document("x".repeat(256)).length < 4000)
    }
}
