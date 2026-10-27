package com.samyak.playlist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class PlaylistHttpException(val statusCode: Int) : IOException("HTTP $statusCode")

object PlaylistDownload {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** A completed disk spool allows full-file format detection without retaining its text. */
    suspend fun <T> useFile(
        directory: File,
        url: String,
        limits: PlaylistLimits = PlaylistLimits(),
        consume: (File, () -> Unit) -> T
    ): T = withContext(Dispatchers.IO) {
        coroutineScope {
            val scope = this
            val request = Request.Builder().url(url).header("User-Agent", "IPTVmine/1.0 (Android)").build()
            val call = client.newCall(request)
            val file = File.createTempFile("playlist-", ".part", directory)
            // Close the socket immediately on cancellation, including during a blocking read.
            val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.execute().use { response ->
                    scope.ensureActive()
                    if (!response.isSuccessful) throw PlaylistHttpException(response.code)
                    val contentType = response.header("Content-Type").orEmpty().substringBefore(';').lowercase()
                    if (!contentType.contains("mpegurl") &&
                        (contentType.startsWith("video/") || contentType.startsWith("image/") || contentType.startsWith("audio/"))) {
                        throw IOException("Server returned media instead of a playlist")
                    }
                    val body = response.body
                    val length = body.contentLength()
                    if (length > limits.maxBytes) throw PlaylistLimitException("Playlist exceeds ${limits.maxBytes} bytes")
                    var total = 0L
                    body.byteStream().use { input ->
                        file.outputStream().buffered(16 * 1024).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                scope.ensureActive()
                                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), limits.maxBytes - total + 1).toInt())
                                if (read < 0) break
                                total += read
                                if (total > limits.maxBytes) throw PlaylistLimitException("Playlist exceeds ${limits.maxBytes} bytes")
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    if (length >= 0 && total != length) throw IOException("Incomplete playlist response")
                }
                scope.ensureActive()
                consume(file) { scope.ensureActive() }
            } catch (failure: Exception) {
                // Cancelling a blocked socket read surfaces as IOException on some platforms.
                scope.ensureActive()
                throw failure
            } finally {
                cancellation.cancel()
                call.cancel()
                file.delete()
            }
        }
    }

    /** Legacy JSON formats remain supported under a separate explicit, small memory budget. */
    fun readSmallJson(file: File, checkCancelled: () -> Unit): String {
        val maxBytes = 2 * 1024 * 1024
        if (file.length() > maxBytes) throw PlaylistLimitException("Legacy JSON playlist exceeds 2 MiB")
        return file.inputStream().reader(Charsets.UTF_8).use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(8192)
            while (true) {
                checkCancelled()
                val read = reader.read(buffer)
                if (read < 0) break
                if (result.length + read > maxBytes) throw PlaylistLimitException("Legacy JSON playlist exceeds 2 MiB")
                result.append(buffer, 0, read)
            }
            result.toString()
        }
    }
}
