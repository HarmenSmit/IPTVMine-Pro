package com.samyak.playlist

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PlaylistStoreTest {
    data class Record(val name: String, val category: String, val url: String)
    private fun withStore(action: (PlaylistStore<Record>) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = "playlist-test-${UUID.randomUUID()}.db"
        val store = PlaylistStore(context, database, Record::class.java, { it.name }, { it.category })
        try { action(store) } finally { store.close(); context.deleteDatabase(database) }
    }
    private fun record(index: Int) = Record("Één 東京 $index", if (index % 2 == 0) "Even" else "Odd", "https://host/live/$index")

    @Test fun batchesAndDiskFiltersPreserveEveryRecordAndCategory() = withStore { store ->
        store.beginImport("source").use { stage ->
            repeat(1003) { stage.add(record(it)) }
            assertEquals(1003, stage.count)
            assertEquals(0, store.count("source"))
            stage.publish()
        }
        val snapshot = store.snapshot(listOf("source"))
        assertEquals(1003, snapshot.size)
        for (index in listOf(1002, 0, 250, 999, 1)) assertEquals(record(index), snapshot[index])
        val filtered = snapshot.filtered(query = "ÉÉN 東京", category = "Even")
        assertEquals(502, filtered.size)
        assertEquals(record(1002), filtered[501])
        assertEquals(listOf("Even" to 502, "Odd" to 501), snapshot.categoryCounts())
    }

    @Test fun failedImportAfterCommittedBatchesRetainsPreviousPlaylist() = withStore { store ->
        store.beginImport("source").use { it.add(record(1)); it.publish() }
        assertThrows(java.io.IOException::class.java) {
            store.beginImport("source").use { stage ->
                repeat(751) { stage.add(record(it)) }
                throw java.io.IOException("Disconnected")
            }
        }
        assertEquals(listOf(record(1)), store.snapshot(listOf("source")))
    }

    @Test fun cancellationBeforePublishRollsBackAndKeepsPreviousSnapshot() = withStore { store ->
        store.beginImport("source").use { it.add(record(1)); it.publish() }
        assertThrows(CancellationException::class.java) {
            store.beginImport("source").use { stage ->
                repeat(600) { stage.add(record(it)) }
                stage.publish { throw CancellationException("cancelled") }
            }
        }
        assertEquals(1, store.count("source"))
        assertEquals(record(1), store.snapshot(listOf("source"))[0])
    }

    @Test fun successfulReplacementKeepsOldUiSnapshotStable() = withStore { store ->
        store.beginImport("source").use { it.add(record(1)); it.publish() }
        val old = store.snapshot(listOf("source"))
        store.beginImport("source").use { it.add(record(2)); it.publish() }
        assertEquals(record(1), old[0])
        assertEquals(record(2), store.snapshot(listOf("source"))[0])
    }

    @Test fun sourcesKeepConfiguredOrderAndFailuresRemainIsolated() = withStore { store ->
        store.beginImport("second").use { it.add(record(2)); it.publish() }
        store.beginImport("first").use { it.add(record(1)); it.publish() }
        assertEquals(listOf(record(1), record(2)), store.snapshot(listOf("first", "second")))
        store.beginImport("second").use { it.add(record(3)) }
        assertEquals(listOf(record(1), record(2)), store.snapshot(listOf("first", "second")))
    }

    @Test fun emptyAndOversizedImportsCannotReplaceWorkingData() = withStore { store ->
        store.beginImport("source").use { it.add(record(1)); it.publish() }
        assertThrows(java.io.IOException::class.java) { store.beginImport("source").use { it.publish() } }
        assertThrows(PlaylistLimitException::class.java) {
            store.beginImport("source").use { it.add(Record("x".repeat(1024 * 1024 + 1), "Bad", "https://host/live/2")) }
        }
        assertEquals(1, store.count("source"))
    }

    @Test fun parserSafetyFailureAfterSeveralBatchesKeepsPreviousData() = withStore { store ->
        store.beginImport("source").use { it.add(record(1)); it.publish() }
        val input = ("#EXTINF:-1,New\nhttps://host/live/2\n".repeat(600) + "x".repeat(257 * 1024)).byteInputStream()
        assertThrows(PlaylistLimitException::class.java) {
            store.beginImport("source").use { stage ->
                M3uParser.parse(input) { stage.add(Record(it.name, it.category, it.streamUrl)) }
                stage.publish()
            }
        }
        assertEquals(record(1), store.snapshot(listOf("source"))[0])
        assertEquals(1, store.count("source"))
    }

    @Test fun interruptedProcessStagingIsRemovedOnNextOpen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = "playlist-recovery-${UUID.randomUUID()}.db"
        try {
            PlaylistStore(context, database, Record::class.java, { it.name }, { it.category }).use { store ->
                store.beginImport("source").use { it.add(record(1)); it.publish() }
            }
            context.openOrCreateDatabase(database, 0, null).use { db ->
                db.execSQL("INSERT INTO channels(generation,name,category,payload) VALUES('crashed','bad','bad','{}')")
                db.execSQL("CREATE TABLE snapshot_abcdef (position INTEGER)")
            }
            PlaylistStore(context, database, Record::class.java, { it.name }, { it.category }).use { store ->
                assertEquals(record(1), store.snapshot(listOf("source"))[0])
            }
            context.openOrCreateDatabase(database, 0, null).use { db ->
                db.rawQuery("SELECT count(*) FROM channels WHERE generation='crashed'", null).use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            }
        } finally { context.deleteDatabase(database) }
    }
}
