package com.samyak.playlist

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException

class M3uParserTest {
    private fun parse(text: String, limits: PlaylistLimits = PlaylistLimits()): List<M3uRecord> {
        val result = mutableListOf<M3uRecord>()
        M3uParser.parse(text.byteInputStream(), limits, emit = result::add)
        return result
    }

    @Test fun preservesAttributesUnicodeAndExtensionsAcrossLineEndings() {
        for (newline in listOf("\n", "\r\n", "\r")) {
            val text = listOf("\uFEFF#EXTM3U", "#EXTINF:-1 tvg-id=\"epg.1\" tvg-name=\"東京\" tvg-logo=\"https://logo/1.png\" group-title=\"Nieuws\",Één, 東京",
                "#EXTVLCOPT:http-user-agent=Custom", "https://stream/live/1", "").joinToString(newline)
            val channel = parse(text).single()
            assertEquals("Één, 東京", channel.name)
            assertEquals("epg.1", channel.tvgId)
            assertEquals("東京", channel.tvgName)
            assertEquals("https://logo/1.png", channel.logoUrl)
            assertEquals("Nieuws", channel.category)
            assertTrue(channel.metadata.contains("#EXTVLCOPT:http-user-agent=Custom"))
        }
    }

    @Test fun standardRecordsMatchLegacyFields() {
        val channels = parse("#EXTM3U\n#EXTINF:-1 tvg-logo=\"https://logo/a\" group-title=\"Sport\",Alpha\nhttps://host/live/a\n#EXTINF:-1,Beta\nhttps://host/live/b")
        assertEquals(listOf("Alpha", "Beta"), channels.map { it.name })
        assertEquals(listOf("Sport", "Uncategorized"), channels.map { it.category })
        assertEquals(listOf("https://host/live/a", "https://host/live/b"), channels.map { it.streamUrl })
    }

    @Test fun orphanInvalidAndIncompleteRecordsCannotReuseMetadata() {
        val channels = parse("#EXTINF:-1,Missing\n#EXTINF:-1,Valid\nhttps://host/live/valid\n#EXTINF:-1,Invalid\nnot a URL\nhttps://host/live/orphan\n#EXTINF:malformed\nhttps://host/live/empty\n#EXTINF:-1,Unfinished")
        assertEquals(listOf("Valid"), channels.map { it.name })
    }

    @Test fun acceptsSingleQuotedAndUnquotedAttributesAndTvgGroup() {
        val channel = parse("#EXTINF:-1 tvg-id=id tvg-name='Name' tvg-group='Fallback',Test\nhttps://host/live/1").single()
        assertEquals("id", channel.tvgId)
        assertEquals("Name", channel.tvgName)
        assertEquals("Fallback", channel.category)
    }

    @Test fun groupTitleTakesPrecedenceOverTvgGroup() {
        val channel = parse("#EXTINF:-1 tvg-group=\"Fallback\" group-title=\"Main\",Test\nhttps://host/live/1").single()
        assertEquals("Main", channel.category)
    }

    @Test fun detectsHlsEvenAfterOtherRecords() {
        var count = 0
        val summary = M3uParser.parse("#EXTM3U\n#EXTINF:5,\nsegment.ts\n#EXT-X-TARGETDURATION:5".byteInputStream()) { count++ }
        assertTrue(summary.isHls)
        assertEquals(0, count)
    }

    @Test fun hlsMasterVariantsKeepBandwidthAndResolution() {
        val channel = parse("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,CODECS=abc\nhttps://host/master.m3u8").single()
        assertEquals("Stream 1280x720 (1000kbps)", channel.name)
        assertEquals("HLS Streams", channel.category)
    }

    @Test fun tvModeKeepsUnnamedHttpStreams() {
        val channels = mutableListOf<M3uRecord>()
        M3uParser.parse("https://host/channel".byteInputStream(), requireName = false, emit = channels::add)
        assertEquals("Unknown Channel", channels.single().name)
    }

    @Test fun byteLimitIncludesMultibyteUtf8AndCrLf() {
        val bytes = "é\r\n東京\n".toByteArray(Charsets.UTF_8)
        BoundedPlaylistReader(bytes.inputStream(), PlaylistLimits(bytes.size.toLong(), 8)).use {
            assertEquals("é", it.readLine()); assertEquals("東京", it.readLine()); assertNull(it.readLine())
            assertEquals(bytes.size.toLong(), it.bytesRead)
        }
        assertThrows(PlaylistLimitException::class.java) {
            BoundedPlaylistReader(bytes.inputStream(), PlaylistLimits(bytes.size - 1L, 8)).use { while (it.readLine() != null) {} }
        }
    }

    @Test fun permitsExactLineLimitAndRejectsNextByteBeforeStringAllocation() {
        BoundedPlaylistReader("1234\n".byteInputStream(), PlaylistLimits(10, 4)).use { assertEquals("1234", it.readLine()) }
        assertThrows(PlaylistLimitException::class.java) {
            BoundedPlaylistReader("12345".byteInputStream(), PlaylistLimits(10, 4)).use { it.readLine() }
        }
    }

    @Test fun hostileInfiniteLineAbortsAndClosesInput() {
        var closed = false
        var bytes = 0
        val input = object : InputStream() {
            override fun read() = 'x'.code.also { bytes++ }
            override fun close() { closed = true }
        }
        assertThrows(PlaylistLimitException::class.java) { M3uParser.parse(input, PlaylistLimits(1024 * 1024, 256)) {} }
        assertTrue(closed)
        assertTrue(bytes <= 16 * 1024)
    }

    @Test fun repeatedMetadataCannotAccumulateWithoutBound() {
        assertThrows(PlaylistLimitException::class.java) {
            parse("#EXTINF:-1,Test\n" + "#OPTION:x\n".repeat(100), PlaylistLimits(10000, 64))
        }
    }

    @Test(timeout = 2000) fun malformedAttributeAtMaximumLineLengthDoesNotRescanQuadratically() {
        val line = "#EXTINF:-1 " + "a".repeat(250 * 1024)
        assertTrue(parse("$line\nhttps://host/live/1").isEmpty())
    }

    @Test fun ioFailurePropagatesAndClosesStream() {
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("Disconnected")
            override fun close() { closed = true }
        }
        assertThrows(IOException::class.java) { M3uParser.parse(input) {} }
        assertTrue(closed)
    }

    @Test fun cancellationPropagatesAndClosesStream() {
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int = 0
            override fun close() { closed = true }
        }
        assertThrows(CancellationException::class.java) {
            M3uParser.parse(input, checkCancelled = { throw CancellationException("cancelled") }) {}
        }
        assertTrue(closed)
    }
}
