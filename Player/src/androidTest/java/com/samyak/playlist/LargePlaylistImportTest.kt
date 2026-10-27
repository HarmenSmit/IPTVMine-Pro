package com.samyak.playlist

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class LargePlaylistImportTest {
    @Test fun downloadsParsesAndStores250MiBOnAndroid(): Unit = runBlocking {
        withContext(Dispatchers.IO) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val database = "large-playlist-${UUID.randomUUID()}.db"
            val record = ("#EXTINF:-1 tvg-id=\"epg\" group-title=\"Nieuws\",Één 東京\nhttps://host/live/1?token=" + "x".repeat(4000) + "\n").toByteArray(Charsets.UTF_8)
            val repeats = (250L * 1024 * 1024 + record.size - 1) / record.size
            val bytes = record.size * repeats
            val runtime = Runtime.getRuntime()
            var sampledPeak = 0L
            val start = System.nanoTime()
            val server = ServerSocket(0)
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) {}
                    socket.getOutputStream().buffered(16 * 1024).use { output ->
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: audio/x-mpegurl\r\nContent-Length: $bytes\r\nConnection: close\r\n\r\n".toByteArray())
                        repeat(repeats.toInt()) { output.write(record) }
                    }
                }
            }
            try {
                PlaylistStore(context, database, M3uRecord::class.java, { it.name }, { it.category }).use { store ->
                    PlaylistDownload.useFile(context.cacheDir, "http://127.0.0.1:${server.localPort}/playlist") { file, checkCancelled ->
                        assertEquals(bytes, file.length())
                        store.beginImport("source").use { stage ->
                            M3uParser.parse(file.inputStream(), checkCancelled = checkCancelled) {
                                stage.add(it)
                                if (stage.count % 1000 == 0) sampledPeak = maxOf(sampledPeak, runtime.totalMemory() - runtime.freeMemory())
                            }
                            stage.publish(checkCancelled)
                        }
                    }
                    val snapshot = store.snapshot(listOf("source"))
                    assertEquals(repeats.toInt(), snapshot.size)
                    assertEquals("Één 東京", snapshot[snapshot.lastIndex].name)
                    assertEquals("epg", snapshot[0].tvgId)
                    assertEquals(listOf("Nieuws" to repeats.toInt()), snapshot.categoryCounts())
                    Log.i("PlaylistImportTest", "ANDROID_MEASUREMENT bytes=$bytes channels=$repeats heapLimit=${runtime.maxMemory()} sampledPeak=$sampledPeak elapsedMs=${(System.nanoTime() - start) / 1_000_000}")
                }
            } finally {
                server.close()
                worker.join(5000)
                context.deleteDatabase(database)
            }
        }
    }
}
