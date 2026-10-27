package com.samyak.playlist

import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream

class LargePlaylistTest {
    @Test fun parses250MiBWith64MiBHeapWithoutRetainingRecords() {
        val record = ("#EXTINF:-1 tvg-id=\"international\" tvg-logo=\"https://host/logo.png\" group-title=\"Nieuws\",Één 東京\nhttps://host/live/channel?token=" + "x".repeat(400) + "\n").toByteArray(Charsets.UTF_8)
        val repetitions = (250L * 1024 * 1024 + record.size - 1) / record.size
        val totalBytes = repetitions * record.size
        var remaining = totalBytes
        var offset = 0
        val input = object : InputStream() {
            override fun read(): Int {
                if (remaining == 0L) return -1
                remaining--
                val result = record[offset++].toInt() and 255
                if (offset == record.size) offset = 0
                return result
            }
            override fun read(bytes: ByteArray, start: Int, length: Int): Int {
                if (remaining == 0L) return -1
                val count = minOf(length.toLong(), remaining, (record.size - offset).toLong()).toInt()
                record.copyInto(bytes, start, offset, offset + count)
                remaining -= count; offset += count
                if (offset == record.size) offset = 0
                return count
            }
        }
        val runtime = Runtime.getRuntime()
        var sampledPeak = 0L
        var channels = 0L
        val start = System.nanoTime()
        M3uParser.parse(input) {
            channels++
            if (channels % 10000 == 0L) sampledPeak = maxOf(sampledPeak, runtime.totalMemory() - runtime.freeMemory())
        }
        assertEquals(repetitions, channels)
        assertEquals(0L, remaining)
        assertTrue("This test must run with at most 64 MiB heap", runtime.maxMemory() <= 64L * 1024 * 1024)
        println("STREAMING_MEASUREMENT bytes=$totalBytes channels=$channels heapLimit=${runtime.maxMemory()} sampledPeak=$sampledPeak elapsedMs=${(System.nanoTime() - start) / 1_000_000}")
    }
}
