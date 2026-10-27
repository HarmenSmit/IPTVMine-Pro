package com.samyak.playlist

import java.io.Closeable
import java.io.IOException
import java.io.InputStream

data class PlaylistLimits(
    val maxBytes: Long = 400L * 1024 * 1024,
    val maxLineBytes: Int = 256 * 1024
) {
    init { require(maxBytes > 0 && maxLineBytes > 0) }
}

class PlaylistLimitException(message: String) : IOException(message)

/** Fixed buffers: a malicious line cannot make readLine allocate an unbounded String. */
class BoundedPlaylistReader(
    private val input: InputStream,
    private val limits: PlaylistLimits = PlaylistLimits(),
    private val checkCancelled: () -> Unit = {}
) : Closeable {
    private val buffer = ByteArray(16 * 1024)
    private val line = ByteArray(limits.maxLineBytes)
    private var position = 0
    private var available = 0
    private var eof = false
    private var skipLf = false
    var bytesRead = 0L
        private set

    private fun nextByte(): Int {
        if (eof) return -1
        while (position == available) {
            checkCancelled()
            // Read at most one byte past the limit, solely to distinguish exact-limit EOF.
            available = input.read(buffer, 0, minOf(buffer.size.toLong(), limits.maxBytes - bytesRead + 1).toInt())
            position = 0
            if (available < 0) { eof = true; return -1 }
            bytesRead += available
            if (bytesRead > limits.maxBytes) throw PlaylistLimitException("Playlist exceeds ${limits.maxBytes} bytes")
        }
        return buffer[position++].toInt() and 255
    }

    fun readLine(): String? {
        var size = 0
        while (true) {
            val byte = nextByte()
            if (skipLf) {
                skipLf = false
                if (byte == 10) continue
            }
            if (byte == -1) return if (size == 0) null else String(line, 0, size, Charsets.UTF_8)
            if (byte == 10 || byte == 13) {
                skipLf = byte == 13
                return String(line, 0, size, Charsets.UTF_8)
            }
            if (size == line.size) throw PlaylistLimitException("Playlist line exceeds ${limits.maxLineBytes} bytes")
            line[size++] = byte.toByte()
        }
    }

    override fun close() = input.close()
}
