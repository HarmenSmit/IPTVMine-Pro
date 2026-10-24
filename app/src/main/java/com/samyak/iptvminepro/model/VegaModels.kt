package com.samyak.iptvminepro.model

data class VegaProvider(
    val display_name: String,
    val value: String,
    val version: String,
    val icon: String = "",
    val type: String = "global",
    val disabled: Boolean = false,
    var isInstalled: Boolean = true
)

data class VegaCatalog(
    val title: String,
    val filter: String
)

data class VegaPost(
    val title: String,
    val link: String,
    val image: String
)

data class VegaMeta(
    val title: String,
    val synopsis: String,
    val image: String,
    val imdbId: String,
    val type: String,
    val linkList: List<VegaLink> = emptyList()
)

data class VegaLink(
    val title: String,
    val episodesLink: String? = null,
    val directLinks: List<VegaDirectLink>? = null,
    val image: String? = null,
    val duration: String? = null,
    val date: String? = null,
    val episodeNumber: String? = null,
    val seasonNumber: String? = null
)

data class VegaDirectLink(
    val title: String,
    val link: String,
    val type: String,
    val image: String? = null,
    val duration: String? = null,
    val date: String? = null,
    val episodeNumber: String? = null,
    val seasonNumber: String? = null
)

data class VegaStream(
    val server: String,
    val link: String,
    val type: String,
    val quality: String,
    val headers: Map<String, String>? = null
)

/**
 * Prioritizes fast direct mirrors (CF Worker, FastCloud, Cloudflare, Gofile, HubCloud)
 * with robust fallback order.
 */
fun prioritizeStreams(streams: List<VegaStream>): List<VegaStream> {
    if (streams.isEmpty()) return streams

    return streams.distinctBy { it.link }.sortedWith(
        compareBy<VegaStream> { stream ->
            val s = stream.server.lowercase()
            val l = stream.link.lowercase()
            when {
                s.contains("cf worker") || s.contains("worker") -> 0
                s.contains("fastcloud") || s.contains("fast cloud") -> 1
                s.contains("instant") || s.contains("google cdn") -> 2
                s.contains("hubcloud") -> 3
                s.contains("cf storage") || s.contains("cloudflare") -> 4
                s.contains("gofile") -> 5
                s.contains("pixeldrain") || l.contains("pixeldrain") || l.contains("pdrain") -> 6
                else -> 7
            }
        }.thenByDescending { it.quality }
    )
}

/**
 * Checks whether a URL is a genuine direct media stream URL (video/audio container or manifest),
 * rather than an HTML webpage, landing page, or arbitrary link.
 */
fun isPlayableMediaUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val clean = url.substringBefore('?').substringBefore('#').lowercase()
    val mediaExts = listOf(
        ".m3u8", ".mp4", ".mkv", ".mpd", ".ts", ".webm",
        ".avi", ".mov", ".flv", ".wmv", ".3gp", ".m4v"
    )
    return mediaExts.any { clean.endsWith(it) } ||
           clean.contains(".m3u8") ||
           url.contains("/hls/", ignoreCase = true) ||
           url.contains(".m3u8", ignoreCase = true) ||
           clean.contains(".mpd") ||
           url.contains("/dash/", ignoreCase = true) ||
           url.contains(".mpd", ignoreCase = true) ||
           (url.contains("pixeldrain", ignoreCase = true) && url.contains("api/file", ignoreCase = true)) ||
           url.contains("googleusercontent.com", ignoreCase = true) ||
           url.contains(".r2.dev", ignoreCase = true) ||
           url.contains(".workers.dev", ignoreCase = true) ||
           url.contains("fastdl.", ignoreCase = true) ||
           url.contains("hubcdn.", ignoreCase = true) ||
           url.startsWith("rtmp://", ignoreCase = true) ||
           url.startsWith("rtmps://", ignoreCase = true)
}



