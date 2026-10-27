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

    fun initialize(db: SQLiteDatabase) {
        // The schema and backfill commit together, so an interrupted upgrade is safe to retry.
        db.beginTransaction()
        try {
            val exists = db.rawQuery("SELECT 1 FROM sqlite_master WHERE name=?", arrayOf(TABLE)).use { it.moveToFirst() }
            if (!exists) {
                db.execSQL("CREATE VIRTUAL TABLE $TABLE USING fts4(grams, tokenize=simple)")
                db.compileStatement("INSERT INTO $TABLE(docid,grams) VALUES(?,?)").use { insert ->
                    var lastId = 0L
                    do {
                        var count = 0
                        db.rawQuery("SELECT id,name FROM channels WHERE id>? ORDER BY id LIMIT 250", arrayOf(lastId.toString())).use { cursor ->
                            while (cursor.moveToNext()) {
                                lastId = cursor.getLong(0)
                                insert.bindLong(1, lastId)
                                insert.bindString(2, ChannelSearchTokens.document(cursor.getString(1)))
                                insert.executeInsert()
                                count++
                            }
                        }
                    } while (count == 250)
                }
            }
            db.execSQL("CREATE TRIGGER IF NOT EXISTS channel_search_delete AFTER DELETE ON channels BEGIN DELETE FROM $TABLE WHERE docid=old.id; END")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
