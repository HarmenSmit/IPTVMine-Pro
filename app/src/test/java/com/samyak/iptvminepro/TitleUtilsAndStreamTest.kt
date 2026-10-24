package com.samyak.iptvminepro

import com.samyak.iptvminepro.model.isPlayableMediaUrl
import com.samyak.iptvminepro.utils.TitleUtils
import org.junit.Assert.*
import org.junit.Test

class TitleUtilsAndStreamTest {

    @Test
    fun testTitleCleaningAndSearchExtraction() {
        val raw1 = "Pathaan [Hindi] (2023) 1080p WEB-DL"
        val clean1 = TitleUtils.getSearchableTitle(raw1)
        assertEquals("Pathaan", clean1)

        val raw2 = "Loki Season 2 [Multi Audio]"
        val clean2 = TitleUtils.getSearchableTitle(raw2)
        assertEquals("Loki", clean2)

        val raw3 = "Stranger Things S04 Episode 01 [Dual Audio]"
        val clean3 = TitleUtils.getSearchableTitle(raw3)
        assertEquals("Stranger Things", clean3)

        val raw4 = "Deadpool & Wolverine (2024)"
        val clean4 = TitleUtils.getSearchableTitle(raw4)
        assertEquals("Deadpool & Wolverine", clean4)

        val raw5 = "Money Heist: Part 5 [Hindi-English]"
        val clean5 = TitleUtils.getSearchableTitle(raw5)
        assertEquals("Money Heist", clean5)
    }

    @Test
    fun testIsPlayableMediaUrl() {
        assertTrue(isPlayableMediaUrl("https://example.com/stream.m3u8"))
        assertTrue(isPlayableMediaUrl("https://example.com/video.mp4?token=123"))
        assertTrue(isPlayableMediaUrl("https://example.com/video.mkv#t=10"))
        assertTrue(isPlayableMediaUrl("https://example.com/hls/live/manifest.m3u8"))
        assertTrue(isPlayableMediaUrl("https://pixeldrain.com/api/file/xyz123"))
        assertTrue(isPlayableMediaUrl("https://pixeldrain.dev/api/file/xyz123"))
        assertTrue(isPlayableMediaUrl("https://worker-stream.workers.dev/file"))
        assertTrue(isPlayableMediaUrl("https://pub-cdn.r2.dev/movie.mp4"))
        assertTrue(isPlayableMediaUrl("https://fastdl.beer/dl/video123"))
        assertTrue(isPlayableMediaUrl("https://hubcdn.club/stream/xyz"))
        
        assertFalse(isPlayableMediaUrl("https://officialmoviebox.com/movies/pathaan-detail-r0p639744"))
        assertFalse(isPlayableMediaUrl("https://vegamovies.lease/download/index.html"))
        assertFalse(isPlayableMediaUrl(""))
        assertFalse(isPlayableMediaUrl(null))
    }

    @Test
    fun testNuxtRegexExtractionPattern() {
        val sampleHtml = """
            <!DOCTYPE html>
            <html>
            <head><title>MovieBox</title></head>
            <body>
            <script id="__NUXT_DATA__" type="application/json">
            [{"hasResource":true,"id":"r0p639744","title":"Pathaan"},1]
            </script>
            <div>Page Content</div>
            </body>
            </html>
        """.trimIndent()

        val regex = Regex("""<script[^>]*id="__NUXT_DATA__"[^>]*>([\s\S]*?)<\/script>""", RegexOption.IGNORE_CASE)
        val match = regex.find(sampleHtml)
        assertNotNull(match)
        val extractedJson = match!!.groupValues[1].trim()
        assertTrue(extractedJson.contains("\"hasResource\":true"))
        assertTrue(extractedJson.contains("\"id\":\"r0p639744\""))
    }
}
