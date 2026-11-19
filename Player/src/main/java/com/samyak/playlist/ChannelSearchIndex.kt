package com.samyak.playlist

import android.database.sqlite.SQLiteDatabase

/** Encoded trigrams let Android's built-in FTS4 index arbitrary substrings, including punctuation. */
internal object ChannelSearchTokens {
    private const val MAX_NAME_LENGTH = 256
    const val OVERFLOW = "overflow"

    fun document(normalizedName: String): String =
        if (normalizedName.length > MAX_NAME_LENGTH) OVERFLOW
        else tokens(normalizedName, MAX_NAME_LENGTH).joinToString(" ")

    // A few trigrams narrow candidates; instr() still checks the entire literal query.
    fun query(normalizedQuery: String): String? =
        tokens(normalizedQuery.take(MAX_NAME_LENGTH), 8).takeIf { it.isNotEmpty() }?.joinToString(" ")

    private fun tokens(text: String, limit: Int): Set<String> {
        val result = linkedSetOf<String>()
        for (start in 0 until text.length - 2) {
            // Fixed-width UTF-16 encoding has no FTS operators or tokenizer separators.
            result.add(buildString(13) {
                append('g')
                for (offset in 0..2) append(text[start + offset].code.toString(16).padStart(4, '0'))
            })
            if (result.size == limit) break
        }
        return result
    }
}

internal object ChannelSearchIndex {
    const val TABLE = "channel_search_v1"

    fun initialize(db: SQLiteDatabase, deferBackfill: Boolean) {
        db.beginTransaction()
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS playlist_search_backfill(last_id INTEGER NOT NULL)")
            val exists = db.rawQuery("SELECT 1 FROM sqlite_master WHERE name=?", arrayOf(TABLE)).use { it.moveToFirst() }
            if (!exists) {
                db.execSQL("CREATE VIRTUAL TABLE $TABLE USING fts4(grams, tokenize=simple)")
                db.execSQL("INSERT INTO playlist_search_backfill SELECT 0 WHERE EXISTS (SELECT 1 FROM channels)")
            }
            db.execSQL("CREATE TRIGGER IF NOT EXISTS channel_search_delete AFTER DELETE ON channels BEGIN DELETE FROM $TABLE WHERE docid=old.id; END")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (!deferBackfill) while (backfillBatch(db)) { /* finish migration for eager callers */ }
    }

    fun ready(db: SQLiteDatabase): Boolean =
        db.rawQuery("SELECT 1 FROM playlist_search_backfill", null).use { !it.moveToFirst() }

    fun backfillBatch(db: SQLiteDatabase): Boolean {
        if (ready(db)) return false
        db.beginTransactionNonExclusive()
        try {
            var last = db.rawQuery("SELECT last_id FROM playlist_search_backfill", null).use {
                if (it.moveToFirst()) it.getLong(0) else return false
            }
            var count = 0
            db.compileStatement("INSERT OR REPLACE INTO $TABLE(docid,grams) VALUES(?,?)").use { insert ->
                db.rawQuery("SELECT id,name FROM channels WHERE id>? ORDER BY id LIMIT 250", arrayOf(last.toString())).use { rows ->
                    while (rows.moveToNext()) {
                        last = rows.getLong(0)
                        insert.bindLong(1, last)
                        insert.bindString(2, ChannelSearchTokens.document(rows.getString(1)))
                        insert.executeInsert()
                        count++
                    }
                }
            }
            if (count < 250) db.delete("playlist_search_backfill", null, null)
            else db.execSQL("UPDATE playlist_search_backfill SET last_id=?", arrayOf(last))
            db.setTransactionSuccessful()
            return count == 250
        } finally { db.endTransaction() }
    }
}
