package com.samyak.playlist

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.CancellationSignal
import android.os.OperationCanceledException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

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

    @Test fun catalogueAndCategoryTotalsAreReusedAfterRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = "playlist-startup-${UUID.randomUUID()}.db"
        fun open() = PlaylistStore(context, database, Record::class.java, { it.name }, { it.category })
        try {
            open().use { store ->
                store.beginImport("source").use { stage -> repeat(1003) { stage.add(record(it)) }; stage.publish() }
                val snapshot = store.snapshot(listOf("source"))
                assertSame(snapshot, store.snapshot(listOf("source")))
                assertEquals(listOf("Even" to 502, "Odd" to 501), snapshot.categoryCounts())
                assertSame(snapshot.categoryCounts(), snapshot.categoryCounts())
                assertEquals(1003, store.count("source"))
            }
            context.openOrCreateDatabase(database, 0, null).use { db ->
                db.rawQuery("SELECT count(*) FROM playlist_views", null).use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
                // A cache miss on the next process-style reopen would try to write this metadata.
                db.execSQL("CREATE TRIGGER forbid_cache_rebuild BEFORE INSERT ON playlist_views BEGIN SELECT RAISE(FAIL,'Unexpected catalogue rebuild'); END")
            }
            open().use { store ->
                val snapshot = store.snapshot(listOf("source"))
                assertEquals(1003, snapshot.size)
                assertEquals(record(1002), snapshot[1002])
                assertEquals(listOf("Even" to 502, "Odd" to 501), snapshot.categoryCounts())
                assertEquals(1003, store.count("source"))
            }
        } finally { context.deleteDatabase(database) }
    }

    @Test fun persistentRefreshTimesArePerSourceAndFailedAttemptsDoNotMarkSuccess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = "playlist-refresh-${UUID.randomUUID()}.db"
        fun open() = PlaylistStore(context, database, Record::class.java, { it.name }, { it.category })
        val maxAge = 30 * 60 * 1000L
        try {
            var firstRefresh = 0L
            open().use { store ->
                assertTrue(store.needsRefresh("first", maxAge))
                store.beginImport("first").use { it.add(record(1)); it.publish() }
                store.beginImport("second").use { it.add(record(2)); it.publish() }
                context.openOrCreateDatabase(database, 0, null).use { db ->
                    db.rawQuery("SELECT last_success_ms FROM playlist_refresh WHERE source='first'", null).use { assertTrue(it.moveToFirst()); firstRefresh = it.getLong(0) }
                }
                assertFalse(store.needsRefresh("first", maxAge, firstRefresh + maxAge - 1))
                assertTrue(store.needsRefresh("first", maxAge, firstRefresh + maxAge))
                assertTrue(store.needsRefresh("first", maxAge, firstRefresh - 1))
                store.recordRefreshAttempt("first", firstRefresh + maxAge)
                store.beginImport("first").use { it.add(record(3)) } // Failed/cancelled update.
                assertFalse(store.needsRefresh("first", maxAge, firstRefresh + maxAge + 30_000L))
                assertTrue(store.needsRefresh("first", maxAge, firstRefresh + maxAge + 60_000L))
                assertFalse(store.needsRefresh("second", maxAge))
            }
            open().use { store ->
                assertFalse(store.needsRefresh("first", maxAge, firstRefresh + maxAge + 30_000L))
                assertTrue(store.needsRefresh("first", maxAge, firstRefresh + maxAge + 60_000L))
                assertEquals(listOf(record(1)), store.snapshot(listOf("first")))
                assertFalse(store.needsRefresh("second", maxAge))
            }
        } finally { context.deleteDatabase(database) }
    }

    @Test fun changedGenerationInvalidatesCachedViewsAndOldReadersStayValid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = "playlist-view-replace-${UUID.randomUUID()}.db"
        fun open() = PlaylistStore(context, database, Record::class.java, { it.name }, { it.category })
        try {
            open().use { store ->
                store.beginImport("source").use { it.add(record(1)); it.publish() }
                val previous = store.snapshot(listOf("source"))
                val previousTotals = previous.categoryCounts()
                store.beginImport("source").use { it.add(record(2)); it.publish() }
                val current = store.snapshot(listOf("source"))
                assertNotSame(previous, current)
                assertEquals(listOf(record(1)), previous)
                assertEquals(listOf("Odd" to 1), previousTotals)
                assertEquals(listOf(record(2)), current)
                assertEquals(listOf("Even" to 1), current.categoryCounts())
            }
            context.openOrCreateDatabase(database, 0, null).use { db ->
                db.rawQuery("SELECT count(*) FROM playlist_views", null).use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
            }
            open().use { store -> assertEquals(listOf(record(2)), store.snapshot(listOf("source"))) }
        } finally { context.deleteDatabase(database) }
    }

    @Test fun indexedSearchPreservesLiteralSubstringsCategoriesAndSourceOrder() = withStore { store ->
        val first = listOf(
            Record("NL Sport HD", "Sport", "1"),
            Record("Één 東京😀 100%_ [HD]", "News", "2"),
            Record("a\"b OR c*", "News", "3"),
            Record("x".repeat(300) + "Rare Sport", "Sport", "4"),
            Record("abc bcd cde", "News", "7"),
            Record("abcdef", "News", "8")
        )
        val second = listOf(Record("Other SPORT", "Sport", "5"), Record("Sports", "News", "6"))
        store.beginImport("second").use { stage -> second.forEach(stage::add); stage.publish() }
        store.beginImport("first").use { stage -> first.forEach(stage::add); stage.publish() }
        val all = first + second
        val snapshot = store.snapshot(listOf("first", "second"))
        for (query in listOf("sport", "SPO", "NL Sport HD", "東京😀", "100%_", "%_", " [HD]", "\"b OR", "*", "é", "😀", "abcde", "not found", "Rare Sport", "x".repeat(270), "")) {
            for (category in listOf(null, "All", "Sport", "News")) {
                val expected = all.filter { it.name.lowercase().contains(query.lowercase()) && (category == null || category == "All" || it.category == category) }
                assertEquals("query=$query category=$category", expected, snapshot.filtered(query, category))
            }
        }
    }

    @Test fun cachedResultsAreScopedToSnapshotAndCancelledRequestsAreRejected() = withStore { store ->
        store.beginImport("source").use { it.add(record(1)); it.publish() }
        val old = store.snapshot(listOf("source"))
        val result = old.filtered("ÉÉN")
        assertSame(result, old.filtered("één", "All"))
        val signal = CancellationSignal().apply { cancel() }
        assertThrows(OperationCanceledException::class.java) { old.filtered("één", cancellationSignal = signal) }
        store.beginImport("source").use { it.add(record(2)); it.publish() }
        assertEquals(listOf(record(1)), old.filtered("één"))
        assertEquals(listOf(record(2)), store.snapshot(listOf("source")).filtered("één"))
    }

    @Test fun existingPlaylistIsIndexedWithoutDownloadingAgain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = "playlist-search-upgrade-${UUID.randomUUID()}.db"
        try {
            context.openOrCreateDatabase(database, 0, null).use { db ->
                db.execSQL("CREATE TABLE channels(id INTEGER PRIMARY KEY,generation TEXT NOT NULL,name TEXT NOT NULL,category TEXT NOT NULL,payload TEXT NOT NULL)")
                db.execSQL("CREATE TABLE active(source TEXT PRIMARY KEY,generation TEXT NOT NULL)")
                db.execSQL("INSERT INTO active VALUES('source','old')")
                val payload = com.google.gson.Gson().toJson(Record("Existing Sport", "TV", "1"))
                db.execSQL("INSERT INTO channels VALUES(1,'old','existing sport','TV',?)", arrayOf(payload))
            }
            repeat(2) {
                PlaylistStore(context, database, Record::class.java, { it.name }, { it.category }).use { store ->
                    assertEquals("Existing Sport", store.snapshot(listOf("source")).filtered("sport")[0].name)
                    store.beginImport("failed").use { stage -> repeat(260) { stage.add(record(it)) } }
                }
                context.openOrCreateDatabase(database, 0, null).use { db ->
                    db.rawQuery("SELECT count(*) FROM channel_search_v1", null).use { cursor ->
                        assertTrue(cursor.moveToFirst()); assertEquals(1, cursor.getInt(0))
                    }
                }
            }
        } finally { context.deleteDatabase(database) }
    }

    @Test fun cancellingRunningSqlRemovesTheUnfinishedResultTable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = "playlist-search-cancel-${UUID.randomUUID()}.db"
        try {
            PlaylistStore(context, database, Record::class.java, { it.name }, { it.category }).use { store ->
                store.beginImport("source").use { it.add(record(1)); it.publish() }
                context.openOrCreateDatabase(database, 0, null).use { db ->
                    val signal = CancellationSignal()
                    val started = CountDownLatch(1)
                    val failure = AtomicReference<Throwable>()
                    val worker = thread(isDaemon = true) {
                        started.countDown()
                        try {
                            // Keep SQLite busy long enough to cancel during query execution.
                            ChannelList(db, "id IN (WITH RECURSIVE spin(n) AS (SELECT 0 UNION ALL SELECT n+1 FROM spin WHERE n<100000000) SELECT 1 FROM spin)", emptyArray(), com.google.gson.Gson(), Record::class.java, "id", signal)
                        } catch (caught: Throwable) { failure.set(caught) }
                    }
                    try {
                        assertTrue(started.await(5, TimeUnit.SECONDS))
                        Thread.sleep(100)
                    } finally {
                        signal.cancel()
                        worker.join(5000)
                    }
                    assertFalse("Cancelled SQL is still running", worker.isAlive)
                    assertTrue("Unexpected failure: ${failure.get()}", failure.get() is OperationCanceledException)
                    db.rawQuery("SELECT count(*) FROM sqlite_master WHERE name LIKE 'snapshot_%'", null).use {
                        assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                    }
                }
            }
        } finally { context.deleteDatabase(database) }
    }

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
