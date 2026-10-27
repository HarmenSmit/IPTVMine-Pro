package com.samyak.playlist

import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import com.google.gson.Gson
import java.io.Closeable
import java.util.UUID
import java.lang.ref.WeakReference

/** Disk snapshots preserve the List API without retaining the channel catalogue in the heap. */
class PlaylistStore<T>(
    context: Context,
    databaseName: String,
    private val type: Class<T>,
    private val nameOf: (T) -> String,
    private val categoryOf: (T) -> String
) : Closeable {
    private val gson = Gson()
    private val lease = PlaylistDatabases.acquire(context, databaseName)
    private val db = lease.database

    init {
        db.execSQL("PRAGMA cache_size=-2048")
        db.execSQL("PRAGMA temp_store=FILE")
        db.execSQL("CREATE TABLE IF NOT EXISTS channels (id INTEGER PRIMARY KEY, generation TEXT NOT NULL, name TEXT NOT NULL, category TEXT NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS channels_generation ON channels(generation, id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS channels_category ON channels(generation, category, id)")
        db.execSQL("CREATE TABLE IF NOT EXISTS active (source TEXT PRIMARY KEY, generation TEXT NOT NULL)")
        PlaylistCatalogueCache.initialize(db)
        ChannelSearchIndex.initialize(db)
    }

    inner class Import internal constructor(private val source: String) : Closeable {
        private val generation = UUID.randomUUID().toString()
        private var pending = 0
        private var pendingBytes = 0
        private var transactionOpen = false
        private var published = false
        var count = 0
            private set
        private val insert = db.compileStatement("INSERT INTO channels(generation,name,category,payload) VALUES(?,?,?,?)")
        private val insertSearch = db.compileStatement("INSERT INTO ${ChannelSearchIndex.TABLE}(docid,grams) VALUES(?,?)")

        private fun commitBatch() {
            if (transactionOpen) {
                try { db.setTransactionSuccessful() } finally { db.endTransaction(); transactionOpen = false }
            }
            pending = 0; pendingBytes = 0
        }

        fun add(value: T) {
            val payload = gson.toJson(value)
            // CursorWindow and the decoder must also be protected from oversized records.
            if (payload.toByteArray(Charsets.UTF_8).size > 1024 * 1024) throw PlaylistLimitException("Channel record exceeds 1 MiB")
            if (!transactionOpen) { db.beginTransaction(); transactionOpen = true }
            insert.bindString(1, generation)
            val name = nameOf(value).lowercase()
            insert.bindString(2, name)
            insert.bindString(3, categoryOf(value))
            insert.bindString(4, payload)
            val id = insert.executeInsert()
            insertSearch.bindLong(1, id)
            insertSearch.bindString(2, ChannelSearchTokens.document(name))
            insertSearch.executeInsert()
            count++; pending++; pendingBytes += payload.length * 2
            // No Kotlin batch list: SQLite batches at most 250 records or 1 MiB of text.
            if (pending >= 250 || pendingBytes >= 1024 * 1024) commitBatch()
        }

        fun clear() {
            commitBatch()
            db.delete("channels", "generation=?", arrayOf(generation))
            count = 0
        }

        fun publish(checkCancelled: () -> Unit = {}) {
            commitBatch()
            if (count == 0) throw java.io.IOException("Playlist contains no valid channels")
            db.beginTransaction()
            try {
                checkCancelled()
                db.execSQL("INSERT OR REPLACE INTO playlist_generation_counts VALUES(?,?)", arrayOf<Any>(generation, count))
                db.execSQL("INSERT OR REPLACE INTO active(source,generation) VALUES(?,?)", arrayOf(source, generation))
                val now = System.currentTimeMillis()
                db.execSQL("INSERT OR REPLACE INTO playlist_refresh(source,last_success_ms,last_attempt_ms) VALUES(?,?,?)", arrayOf<Any>(source, now, now))
                db.setTransactionSuccessful()
                published = true
            } finally { db.endTransaction() }
        }

        override fun close() {
            if (transactionOpen) { db.endTransaction(); transactionOpen = false }
            insert.close()
            insertSearch.close()
            if (!published) db.delete("channels", "generation=?", arrayOf(generation))
        }
    }

    fun beginImport(source: String): Import = Import(source)

    fun count(source: String): Int {
        val generation = db.rawQuery("SELECT generation FROM active WHERE source=?", arrayOf(source)).use { if (it.moveToFirst()) it.getString(0) else null }
        return generation?.let { PlaylistCatalogueCache.count(db, it) } ?: 0
    }

    fun recordRefreshAttempt(source: String, now: Long = System.currentTimeMillis()) {
        db.execSQL("INSERT OR IGNORE INTO playlist_refresh(source) VALUES(?)", arrayOf(source))
        db.execSQL("UPDATE playlist_refresh SET last_attempt_ms=? WHERE source=?", arrayOf<Any>(now, source))
    }

    fun needsRefresh(source: String, maxAgeMs: Long, now: Long = System.currentTimeMillis()): Boolean {
        db.rawQuery("SELECT r.last_success_ms,r.last_attempt_ms,a.generation FROM playlist_refresh r LEFT JOIN active a ON a.source=r.source WHERE r.source=?", arrayOf(source)).use { cursor ->
            if (!cursor.moveToFirst()) return true
            val success = cursor.getLong(0)
            val attempt = cursor.getLong(1)
            if (!cursor.isNull(2) && success > 0 && now >= success && now - success < maxAgeMs) return false
            // Persist a short retry delay too, so restarting cannot hammer a failing provider.
            return attempt == 0L || now < attempt || now - attempt >= 60_000L
        }
    }

    private var cachedGenerations: List<String>? = null
    private var cachedSnapshot: ChannelList<T>? = null

    @Synchronized fun snapshot(sources: List<String>): ChannelList<T> {
        val generations = sources.mapNotNull { source ->
            db.rawQuery("SELECT generation FROM active WHERE source=?", arrayOf(source)).use { if (it.moveToFirst()) it.getString(0) else null }
        }.distinct()
        if (generations == cachedGenerations) cachedSnapshot?.let { return it }
        val selection = if (generations.isEmpty()) "0" else "generation IN (${generations.joinToString(",") { "?" }})"
        val order = if (generations.isEmpty()) "id" else "CASE generation " + generations.mapIndexed { index, generation ->
            "WHEN '$generation' THEN $index"
        }.joinToString(" ") + " END,id"
        val view = PlaylistCatalogueCache.getOrBuild(db, "channels-v1", generations) { table ->
            db.execSQL("CREATE TABLE $table (position INTEGER PRIMARY KEY,channelId INTEGER NOT NULL)")
            // Concatenating the generations avoids sorting the whole catalogue with CASE.
            generations.forEach { generation ->
                db.execSQL("INSERT INTO $table(channelId) SELECT id FROM channels WHERE generation=? ORDER BY id", arrayOf(generation))
            }
        }
        return ChannelList(db, selection, generations.toTypedArray(), gson, type, order, persistentView = view, rootGenerations = generations).also {
            cachedGenerations = generations
            cachedSnapshot = it
        }
    }

    /** Delete obsolete generations only when this owner and its UI snapshots have gone away. */
    override fun close() {
        PlaylistDatabases.release(lease)
    }
}

class ChannelList<T> internal constructor(
    private val db: SQLiteDatabase,
    private val selection: String,
    private val args: Array<String>,
    private val gson: Gson,
    private val type: Class<T>,
    private val order: String,
    cancellationSignal: CancellationSignal? = null,
    persistentView: PlaylistCatalogueCache.View? = null,
    private val rootGenerations: List<String>? = null
) : AbstractList<T>() {
    private val table = persistentView?.table ?: ("snapshot_" + UUID.randomUUID().toString().replace("-", ""))
    override val size: Int
    init {
        if (persistentView != null) {
            size = persistentView.size
        } else {
            // The ordinal index lives on disk. Random UI access never scans an ever-growing OFFSET.
            db.execSQL("CREATE TABLE $table (position INTEGER PRIMARY KEY, channelId INTEGER NOT NULL)")
            try {
                size = if (cancellationSignal == null) {
                    db.execSQL("INSERT INTO $table(channelId) SELECT id FROM channels WHERE $selection ORDER BY $order", args)
                    DatabaseUtils.longForQuery(db, "SELECT count(*) FROM $table", null).toInt()
                } else {
                    // execSQL(INSERT ... SELECT) cannot be cancelled. Read with a CancellationSignal
                    // and build the ordinal snapshot in a transaction, checking cancellation per row.
                    cancellationSignal.throwIfCanceled()
                    db.beginTransactionNonExclusive()
                    try {
                        var count = 0
                        db.compileStatement("INSERT INTO $table(channelId) VALUES(?)").use { insert ->
                            db.rawQuery("SELECT id FROM channels WHERE $selection ORDER BY $order", args, cancellationSignal).use { cursor ->
                                while (cursor.moveToNext()) {
                                    cancellationSignal.throwIfCanceled()
                                    insert.bindLong(1, cursor.getLong(0))
                                    insert.executeInsert()
                                    count++
                                }
                            }
                        }
                        cancellationSignal.throwIfCanceled()
                        db.setTransactionSuccessful()
                        count
                    } finally {
                        db.endTransaction()
                    }
                }
                PlaylistDatabases.register(db, table, this)
            } catch (failure: Exception) {
                db.execSQL("DROP TABLE IF EXISTS $table")
                throw failure
            }
        }
    }
    private val cache = object : LinkedHashMap<Int, T>(16, .75f, true) {}
    private var cacheChars = 0
    private data class FilterKey(val query: String, val category: String?)
    private val filteredCache = object : LinkedHashMap<FilterKey, ChannelList<T>>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FilterKey, ChannelList<T>>?) = size > 8
    }

    @Synchronized override fun get(index: Int): T {
        if (index !in 0 until size) throw IndexOutOfBoundsException("$index / $size")
        cache[index]?.let { return it }
        var answer: T? = null
        db.rawQuery("SELECT c.payload FROM $table s JOIN channels c ON c.id=s.channelId WHERE s.position>=? ORDER BY s.position LIMIT 16", arrayOf((index + 1).toString())).use { cursor ->
            var offset = index
            while (cursor.moveToNext()) {
                val text = cursor.getString(0)
                val value = gson.fromJson(text, type)
                if (offset == index) answer = value
                // Cache is bounded by both count and serialized size, even for hostile metadata.
                if (cacheChars + text.length > 128 * 1024 || cache.size >= 32) {
                    cache.clear(); cacheChars = 0
                    if (offset != index) break
                }
                if (text.length <= 128 * 1024) { cache[offset] = value; cacheChars += text.length }
                offset++
            }
        }
        return checkNotNull(answer)
    }

    fun filtered(query: String? = null, category: String? = null, cancellationSignal: CancellationSignal? = null): ChannelList<T> {
        cancellationSignal?.throwIfCanceled()
        val key = FilterKey(query.orEmpty().lowercase(), category?.takeUnless { it.isEmpty() || it.equals("all", true) })
        if (key.query.isEmpty() && key.category == null) return this
        synchronized(filteredCache) { filteredCache[key] }?.let { return it }
        var where = selection
        val parameters = args.toMutableList()
        if (key.query.isNotEmpty()) {
            ChannelSearchTokens.query(key.query)?.let { tokens ->
                // Start with indexed candidates, rather than scanning every name in a generation.
                // Very long names use a bounded overflow bucket; the literal check handles them.
                where += " AND id IN (SELECT docid FROM ${ChannelSearchIndex.TABLE} WHERE grams MATCH ? UNION SELECT docid FROM ${ChannelSearchIndex.TABLE} WHERE grams MATCH '${ChannelSearchTokens.OVERFLOW}')"
                parameters.add(tokens)
            }
            where += " AND instr(name,?)>0"
            parameters.add(key.query)
        }
        if (key.category != null) { where += " AND category=?"; parameters.add(key.category) }
        val result = ChannelList(db, where, parameters.toTypedArray(), gson, type, order, cancellationSignal)
        cancellationSignal?.throwIfCanceled()
        synchronized(filteredCache) { filteredCache[key] = result }
        return result
    }

    private var cachedCategoryCounts: List<Pair<String, Int>>? = null
    @Synchronized fun categoryCounts(): List<Pair<String, Int>> {
        cachedCategoryCounts?.let { return it }
        val sql = "SELECT category,count(*) FROM channels WHERE $selection GROUP BY category ORDER BY category"
        val view = rootGenerations?.let { generations ->
            PlaylistCatalogueCache.getOrBuild(db, "categories-v1", generations) { table -> db.execSQL("CREATE TABLE $table AS $sql", args) }
        }
        return SqlList(db, sql, args, view) { it.getString(0) to it.getInt(1) }.also { cachedCategoryCounts = it }
    }

    // StateFlow/Compose must not compare every record in two large snapshots.
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

private class SqlList<T>(
    private val db: SQLiteDatabase, private val sql: String, private val args: Array<String>, persistentView: PlaylistCatalogueCache.View? = null, private val decode: (Cursor) -> T
) : AbstractList<T>() {
    private val table = persistentView?.table ?: ("snapshot_" + UUID.randomUUID().toString().replace("-", ""))
    override val size: Int
    init {
        if (persistentView != null) {
            size = persistentView.size
        } else {
            db.execSQL("CREATE TABLE $table AS $sql", args)
            size = DatabaseUtils.longForQuery(db, "SELECT count(*) FROM $table", null).toInt()
            PlaylistDatabases.register(db, table, this)
        }
    }
    override fun get(index: Int): T {
        if (index !in 0 until size) throw IndexOutOfBoundsException()
        return db.rawQuery("SELECT * FROM $table WHERE rowid=?", arrayOf((index + 1).toString())).use { check(it.moveToFirst()); decode(it) }
    }
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/** Lazy projections also prevent category lists and home-screen rows from copying all records. */
fun <A, B> List<A>.project(transform: (A) -> B): List<B> {
    val source = this
    return object : AbstractList<B>() {
        override val size get() = source.size
        override fun get(index: Int) = transform(source[index])
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }
}

fun <T> List<T>.withAll(first: T): List<T> {
    val source = this
    return object : AbstractList<T>() {
        override val size get() = source.size + 1
        override fun get(index: Int): T {
            if (index !in 0 until size) throw IndexOutOfBoundsException()
            return if (index == 0) first else source[index - 1]
        }
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }
}

/** One connection pool per database; activity recreation cannot close another owner's snapshots. */
private object PlaylistDatabases {
    class Lease(val path: String, val database: SQLiteDatabase, var owners: Int = 1) {
        val snapshots = mutableMapOf<String, WeakReference<Any>>()
    }
    private val leases = mutableMapOf<String, Lease>()

    @Synchronized fun acquire(context: Context, name: String): Lease {
        val path = context.getDatabasePath(name).absolutePath
        leases[path]?.let { it.owners++; return it }
        val db = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        // UI readers can keep reading the previous generation while an import batch writes.
        db.enableWriteAheadLogging()
        // Recover interrupted processes. No snapshots/importers are alive on the first lease.
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'snapshot_%'", null).use { cursor ->
            val tables = mutableListOf<String>()
            while (cursor.moveToNext()) tables.add(cursor.getString(0))
            for (table in tables) if (Regex("snapshot_[a-f0-9]+").matches(table)) db.execSQL("DROP TABLE $table")
        }
        db.rawQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='active'", null).use {
            if (it.moveToFirst() && it.getInt(0) > 0) {
                PlaylistCatalogueCache.pruneObsolete(db)
                pruneChannels(db)
            }
        }
        return Lease(path, db).also { leases[path] = it }
    }

    @Synchronized fun register(db: SQLiteDatabase, table: String, snapshot: Any) {
        val lease = leases.values.first { it.database === db }
        val iterator = lease.snapshots.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.get() == null) { db.execSQL("DROP TABLE IF EXISTS ${entry.key}"); iterator.remove() }
        }
        lease.snapshots[table] = WeakReference(snapshot)
    }

    @Synchronized fun release(lease: Lease) {
        if (--lease.owners > 0) return
        try {
            for (table in lease.snapshots.keys) lease.database.execSQL("DROP TABLE IF EXISTS $table")
            PlaylistCatalogueCache.pruneObsolete(lease.database)
            pruneChannels(lease.database)
        } finally {
            lease.database.close()
            leases.remove(lease.path)
        }
    }

    private fun pruneChannels(db: SQLiteDatabase) {
        val active = mutableSetOf<String>()
        db.rawQuery("SELECT generation FROM active", null).use { while (it.moveToNext()) active.add(it.getString(0)) }
        var previous: String? = null
        while (true) {
            // Seek to the next generation through the existing index. A NOT IN delete would
            // scan every channel even on a normal startup with nothing to remove.
            val generation = (if (previous == null) {
                db.rawQuery("SELECT generation FROM channels ORDER BY generation LIMIT 1", null)
            } else {
                db.rawQuery("SELECT generation FROM channels WHERE generation>? ORDER BY generation LIMIT 1", arrayOf(previous))
            }).use { if (it.moveToFirst()) it.getString(0) else null } ?: break
            if (generation !in active) db.delete("channels", "generation=?", arrayOf(generation))
            previous = generation
        }
    }
}
