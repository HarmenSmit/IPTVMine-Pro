package com.samyak.playlist

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import com.google.gson.Gson
import java.security.MessageDigest

/** Immutable ordinal lists and category totals survive process restarts with their generations. */
internal object PlaylistCatalogueCache {
    data class View(val table: String, val size: Int)

    fun initialize(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS playlist_views (cache_key TEXT PRIMARY KEY, table_name TEXT NOT NULL, row_count INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS playlist_view_generations (cache_key TEXT NOT NULL, generation TEXT NOT NULL, PRIMARY KEY(cache_key,generation))")
            db.execSQL("CREATE TABLE IF NOT EXISTS playlist_generation_counts (generation TEXT PRIMARY KEY, channel_count INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS playlist_refresh (source TEXT PRIMARY KEY, last_success_ms INTEGER NOT NULL DEFAULT 0, last_attempt_ms INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE IF NOT EXISTS playlist_http_state (source TEXT PRIMARY KEY, generation TEXT NOT NULL, sha256 TEXT NOT NULL, etag TEXT, last_modified TEXT)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun key(kind: String, generations: List<String>) =
        MessageDigest.getInstance("SHA-256").digest(Gson().toJson(listOf(kind) + generations).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun find(db: SQLiteDatabase, key: String): View? = db.rawQuery(
        "SELECT v.table_name,v.row_count FROM playlist_views v JOIN sqlite_master m ON m.name=v.table_name AND m.type='table' WHERE v.cache_key=?", arrayOf(key)
    ).use { cursor -> if (cursor.moveToFirst()) View(cursor.getString(0), cursor.getInt(1)) else null }

    fun hasChannelView(db: SQLiteDatabase, generations: List<String>) =
        find(db, key("channels-v1", generations)) != null

    fun getOrBuild(db: SQLiteDatabase, kind: String, generations: List<String>, build: (String) -> Unit): View {
        val key = key(kind, generations)
        find(db, key)?.let { return it }
        db.beginTransaction()
        try {
            find(db, key)?.let { db.setTransactionSuccessful(); return it }
            val table = "catalogue_$key"
            build(table)
            val size = DatabaseUtils.longForQuery(db, "SELECT count(*) FROM $table", null).toInt()
            db.execSQL("INSERT OR REPLACE INTO playlist_views VALUES(?,?,?)", arrayOf<Any>(key, table, size))
            generations.forEach { db.execSQL("INSERT OR IGNORE INTO playlist_view_generations VALUES(?,?)", arrayOf(key, it)) }
            db.setTransactionSuccessful()
            return View(table, size)
        } finally { db.endTransaction() }
    }

    fun count(db: SQLiteDatabase, generation: String): Int {
        db.rawQuery("SELECT channel_count FROM playlist_generation_counts WHERE generation=?", arrayOf(generation)).use {
            if (it.moveToFirst()) return it.getInt(0)
        }
        val count = DatabaseUtils.longForQuery(db, "SELECT count(*) FROM channels WHERE generation=?", arrayOf(generation)).toInt()
        db.execSQL("INSERT OR IGNORE INTO playlist_generation_counts VALUES(?,?)", arrayOf<Any>(generation, count))
        return count
    }

    fun pruneObsolete(db: SQLiteDatabase, canRemove: (String) -> Boolean = { true }) {
        val exists = db.rawQuery("SELECT 1 FROM sqlite_master WHERE name='playlist_views'", null).use { it.moveToFirst() }
        if (!exists) return
        val obsolete = linkedMapOf<String, String>()
        db.rawQuery("SELECT v.cache_key,v.table_name,g.generation FROM playlist_views v JOIN playlist_view_generations g ON g.cache_key=v.cache_key WHERE g.generation NOT IN (SELECT generation FROM active)", null).use {
            while (it.moveToNext()) {
                if (canRemove(it.getString(2))) obsolete[it.getString(0)] = it.getString(1)
            }
        }
        obsolete.forEach { (key, table) ->
            db.beginTransactionNonExclusive()
            try {
                check(table.matches(Regex("catalogue_[a-f0-9]{64}")))
                db.execSQL("DROP TABLE IF EXISTS $table")
                db.delete("playlist_views", "cache_key=?", arrayOf(key))
                db.delete("playlist_view_generations", "cache_key=?", arrayOf(key))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        db.execSQL("DELETE FROM playlist_generation_counts WHERE generation NOT IN (SELECT generation FROM active)")
    }
}
