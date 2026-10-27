package com.samyak.playlist

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PlaylistDownloadTest {
    private fun serve(headers: String, body: String, block: (() -> Unit)? = null, action: (String) -> Unit) {
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            val worker = thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { /* consume test request */ }
                        val output = socket.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nConnection: close\r\n$headers\r\n\r\n$body".toByteArray(Charsets.UTF_8))
                        output.flush()
                        block?.invoke()
                    }
                } catch (_: IOException) { /* client cancellation is expected */ }
            }
            try { action("http://127.0.0.1:${server.localPort}/playlist?token=private") }
            finally { worker.join(6000) }
        }
    }

    private fun withDirectory(action: (File) -> Unit) {
        val directory = Files.createTempDirectory("playlist-test").toFile()
        try { action(directory) } finally { directory.deleteRecursively() }
    }

    @Test fun downloadsCompleteUtf8PlaylistToDiskAndCleansSpool() = withDirectory { directory ->
        val body = "#EXTM3U\r\n#EXTINF:-1,東京\r\nhttps://host/live/1\r\n"
        serve("Content-Type: audio/x-mpegurl\r\nContent-Length: ${body.toByteArray().size}", body) { url ->
            runBlocking {
                val result = PlaylistDownload.useFile(directory, url) { file, _ ->
                    assertEquals(body.toByteArray().size.toLong(), file.length())
                    var count = 0
                    M3uParser.parse(file.inputStream()) { count++ }
                    count
                }
                assertEquals(1, result)
            }
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun incompleteDownloadNeverReachesImporter() = withDirectory { directory ->
        serve("Content-Length: 1000", "#EXTM3U\n") { url ->
            assertThrows(IOException::class.java) {
                runBlocking { PlaylistDownload.useFile(directory, url) { _, _ -> fail("Truncated content was imported") } }
            }
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun enforcesActualByteLimitWithoutContentLength() = withDirectory { directory ->
        serve("Content-Type: text/plain", "é".repeat(50)) { url ->
            assertThrows(PlaylistLimitException::class.java) {
                runBlocking { PlaylistDownload.useFile(directory, url, PlaylistLimits(80, 64)) { _, _ -> fail("Oversized response imported") } }
            }
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun exactDownloadLimitSucceeds() = withDirectory { directory ->
        serve("Content-Length: 4", "1234") { url ->
            runBlocking { PlaylistDownload.useFile(directory, url, PlaylistLimits(4, 4)) { file, _ -> assertEquals(4L, file.length()) } }
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun cancellationClosesBlockedNetworkReadAndDeletesPartialFile() = withDirectory { directory ->
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        serve("Content-Length: 1000", "#EXTM3U\n", {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        }) { url ->
            try {
                runBlocking {
                    val job = launch(Dispatchers.IO) {
                        PlaylistDownload.useFile(directory, url) { _, _ -> fail("Cancelled download imported") }
                    }
                    assertTrue(started.await(5, TimeUnit.SECONDS))
                    withTimeout(2000) { job.cancelAndJoin() }
                    assertTrue(job.isCancelled)
                }
            } finally { release.countDown() }
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }
}
