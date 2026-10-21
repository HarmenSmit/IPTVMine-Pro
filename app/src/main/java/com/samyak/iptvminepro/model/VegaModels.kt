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
 * Prioritizes unlimited, fast direct mirrors (CF Worker, FastCloud, Cloudflare, Gofile)
 * ahead of hosters with strict daily download/bandwidth limits (such as PixelDrain's 10GB/10-file daily quota).
 */
fun prioritizeStreams(streams: List<VegaStream>): List<VegaStream> {
    if (streams.size <= 1) return streams
    return streams.sortedWith(
        compareBy<VegaStream> { stream ->
            val s = stream.server.lowercase()
            val l = stream.link.lowercase()
            when {
                // Unlimited direct fast CDN mirrors (zero daily limit)
                s.contains("cf worker") || s.contains("worker") -> 0
                s.contains("fastcloud") || s.contains("fast cloud") -> 1
                s.contains("cf storage") || s.contains("cloudflare") -> 2
                s.contains("gofile") -> 3
                // Other streaming hosts without strict daily IP quota
                !s.contains("pixeldrain") && !l.contains("pixeldrain") && !s.contains("pdrain") && !l.contains("pdrain") -> 4
                // Rate-limited hosters (PixelDrain has 10GB / 10-download daily limit per IP)
                else -> 10
            }
        }.thenByDescending { it.quality }
    )
}

