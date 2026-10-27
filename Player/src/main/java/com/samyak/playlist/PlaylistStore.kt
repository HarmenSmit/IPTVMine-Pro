package com.samyak.playlist

import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
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
            insert.bindString(2, nameOf(value).lowercase())
            insert.bindString(3, categoryOf(value))
            insert.bindString(4, payload)
            insert.executeInsert()
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
                db.execSQL("INSERT OR REPLACE INTO active(source,generation) VALUES(?,?)", arrayOf(source, generation))
                db.setTransactionSuccessful()
                published = true
            } finally { db.endTransaction() }
        }

        override fun close() {
            if (transactionOpen) { db.endTransaction(); transactionOpen = false }
            insert.close()
            if (!published) db.delete("channels", "generation=?", arrayOf(generation))
        }
    }

    fun beginImport(source: String): Import = Import(source)

    fun count(source: String): Int = DatabaseUtils.longForQuery(db,
        "SELECT count(*) FROM channels WHERE generation=(SELECT generation FROM active WHERE source=?)", arrayOf(source)).toInt()

    fun snapshot(sources: List<String>): ChannelList<T> {
        val generations = sources.mapNotNull { source ->
            db.rawQuery("SELECT generation FROM active WHERE source=?", arrayOf(source)).use { if (it.moveToFirst()) it.getString(0) else null }
        }
        val selection = if (generations.isEmpty()) "0" else "generation IN (${generations.joinToString(",") { "?" }})"
        val order = if (generations.isEmpty()) "id" else "CASE generation " + generations.mapIndexed { index, generation ->
            "WHEN '$generation' THEN $index"
        }.joinToString(" ") + " END,id"
        return ChannelList(db, selection, generations.toTypedArray(), gson, type, order)
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
    private val order: String
) : AbstractList<T>() {
    private val table = "snapshot_" + UUID.randomUUID().toString().replace("-", "")
    override val size: Int
    init {
        // The ordinal index lives on disk. Random UI access never scans an ever-growing OFFSET.
        db.execSQL("CREATE TABLE $table (position INTEGER PRIMARY KEY, channelId INTEGER NOT NULL)")
        try {
            db.execSQL("INSERT INTO $table(channelId) SELECT id FROM channels WHERE $selection ORDER BY $order", args)
            size = DatabaseUtils.longForQuery(db, "SELECT count(*) FROM $table", null).toInt()
            PlaylistDatabases.register(db, table, this)
        } catch (failure: Exception) {
            db.execSQL("DROP TABLE IF EXISTS $table")
            throw failure
        }
    }
    private val cache = object : LinkedHashMap<Int, T>(16, .75f, true) {}
    private var cacheChars = 0

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

    fun filtered(query: String? = null, category: String? = null): ChannelList<T> {
        if (query.isNullOrEmpty() && (category.isNullOrEmpty() || category.equals("all", true))) return this
        var where = selection
        val parameters = args.toMutableList()
        if (!query.isNullOrEmpty()) { where += " AND instr(name,?)>0"; parameters.add(query.lowercase()) }
        if (!category.isNullOrEmpty() && !category.equals("all", true)) { where += " AND category=?"; parameters.add(category) }
        return ChannelList(db, where, parameters.toTypedArray(), gson, type, order)
    }

    fun categoryCounts(): List<Pair<String, Int>> = SqlList(db,
        "SELECT category,count(*) FROM channels WHERE $selection GROUP BY category ORDER BY category", args
    ) { it.getString(0) to it.getInt(1) }

    // StateFlow/Compose must not compare every record in two large snapshots.
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

private class SqlList<T>(
    private val db: SQLiteDatabase, private val sql: String, private val args: Array<String>, private val decode: (Cursor) -> T
) : AbstractList<T>() {
    private val table = "snapshot_" + UUID.randomUUID().toString().replace("-", "")
    override val size: Int
    init {
        db.execSQL("CREATE TABLE $table AS $sql", args)
        size = DatabaseUtils.longForQuery(db, "SELECT count(*) FROM $table", null).toInt()
        PlaylistDatabases.register(db, table, this)
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
            if (it.moveToFirst() && it.getInt(0) > 0) db.execSQL("DELETE FROM channels WHERE generation NOT IN (SELECT generation FROM active)")
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
            lease.database.execSQL("DELETE FROM channels WHERE generation NOT IN (SELECT generation FROM active)")
        } finally {
            lease.database.close()
            leases.remove(lease.path)
        }
    }
}
