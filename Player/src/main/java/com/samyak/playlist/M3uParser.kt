package com.samyak.playlist

import java.io.InputStream

data class M3uRecord(
    val name: String,
    val logoUrl: String,
    val streamUrl: String,
    val category: String,
    val tvgId: String = "",
    val tvgName: String = "",
    val metadata: String = ""
)

data class M3uSummary(val isM3u: Boolean, val isHls: Boolean, val hasJson: Boolean)

object M3uParser {
    // Scan attributes once. A regex with a greedy key can rescan a huge malformed token
    // from every character and take quadratic time even when the line length is bounded.
    private inline fun attributes(text: String, emit: (String, String) -> Unit) {
        var index = 0
        while (index < text.length) {
            if (!text[index].isLetterOrDigit() && text[index] != '_' && text[index] != '-') { index++; continue }
            val start = index
            while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '_' || text[index] == '-')) index++
            val end = index
            while (index < text.length && text[index].isWhitespace()) index++
            if (index >= text.length || text[index] != '=') continue
            index++
            while (index < text.length && text[index].isWhitespace()) index++
            if (index >= text.length) break
            val quote = text[index].takeIf { it == '"' || it == '\'' }
            if (quote != null) index++
            val valueStart = index
            while (index < text.length && if (quote != null) text[index] != quote else !text[index].isWhitespace() && text[index] != ',') index++
            emit(text.substring(start, end).lowercase(), text.substring(valueStart, index).trim())
            if (quote != null && index < text.length) index++
        }
    }
    private val bandwidth = Regex("BANDWIDTH=(\\d+)")
    private val resolution = Regex("RESOLUTION=([^,\\s]+)")

    /** Invalid URLs/orphan metadata are skipped; safety-limit and I/O failures abort the import. */
    fun parse(
        input: InputStream,
        limits: PlaylistLimits = PlaylistLimits(),
        requireName: Boolean = true,
        validUrl: (String) -> Boolean = { it.startsWith("http://") || it.startsWith("https://") },
        checkCancelled: () -> Unit = {},
        emit: (M3uRecord) -> Unit
    ): M3uSummary {
        var name: String? = null
        var logo = ""
        var group = "Uncategorized"
        var tvgId = ""
        var tvgName = ""
        val metadata = StringBuilder(256)
        var variant: String? = null
        var m3u = false
        var hls = false
        var json = false
        BoundedPlaylistReader(input, limits, checkCancelled).use { reader ->
            while (true) {
                val line = reader.readLine()?.trim()?.removePrefix("\uFEFF") ?: break
                when {
                    line.startsWith("#EXTM3U") -> m3u = true
                    line.startsWith("#EXTINF:") -> {
                        m3u = true
                        // Find the name separator outside quoted attribute values.
                        var quote: Char? = null
                        val comma = line.indexOfFirst { c ->
                            if (c == quote) quote = null else if (quote == null && (c == '"' || c == '\'')) quote = c
                            c == ',' && quote == null
                        }
                        name = if (comma >= 0) line.substring(comma + 1).trim().ifEmpty { null } else null
                        logo = ""; group = "Uncategorized"; tvgId = ""; tvgName = ""
                        val attributes = if (comma >= 0) line.substring(0, comma) else line
                        attributes(attributes) { key, value ->
                            when (key) {
                                "tvg-logo" -> logo = value
                                "group-title" -> group = value.ifEmpty { "Uncategorized" }
                                "tvg-group" -> if (group == "Uncategorized") group = value.ifEmpty { "Uncategorized" }
                                "tvg-id" -> tvgId = value
                                "tvg-name" -> tvgName = value
                            }
                        }
                        metadata.setLength(0)
                        metadata.append(line)
                        variant = null
                    }
                    line.startsWith("#EXT-X-") -> {
                        if (line.startsWith("#EXT-X-STREAM-INF:") || line.startsWith("#EXT-X-TARGETDURATION:") ||
                            line.startsWith("#EXT-X-MEDIA-SEQUENCE:") || line.startsWith("#EXT-X-PLAYLIST-TYPE:")) hls = true
                        if (line.startsWith("#EXT-X-STREAM-INF:")) variant = line
                    }
                    line.startsWith("#") -> {
                        if (metadata.isNotEmpty()) {
                            if (metadata.length + line.length + 1 > limits.maxLineBytes) {
                                throw PlaylistLimitException("Channel metadata exceeds ${limits.maxLineBytes} characters")
                            }
                            metadata.append('\n').append(line)
                        }
                    }
                    line.isNotEmpty() -> {
                        if (line.contains('{') || line.contains('[')) json = true
                        if (validUrl(line)) {
                            val info = variant
                            if (info != null) {
                                val rate = bandwidth.find(info)?.groupValues?.get(1)?.toLongOrNull()?.let { "${it / 1000}kbps" } ?: "Unknown"
                                val res = resolution.find(info)?.groupValues?.get(1) ?: "Unknown"
                                emit(M3uRecord("Stream $res ($rate)", "", line, "HLS Streams", metadata = info))
                            } else if (!requireName || !name.isNullOrEmpty()) {
                                emit(M3uRecord(name ?: "Unknown Channel", logo, line, group, tvgId, tvgName, metadata.toString()))
                            }
                        }
                        name = null; logo = ""; group = "Uncategorized"; tvgId = ""; tvgName = ""; metadata.setLength(0); variant = null
                    }
                }
            }
        }
        return M3uSummary(m3u, hls, json)
    }
}
