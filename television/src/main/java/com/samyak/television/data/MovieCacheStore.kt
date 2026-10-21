package com.samyak.television.data

import android.content.Context
import android.content.SharedPreferences
import android.util.LruCache
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.samyak.television.model.VegaCatalog
import com.samyak.television.model.VegaPost
import com.samyak.television.model.VegaProvider
import java.security.MessageDigest

/**
 * Two-tier cache store (L1 Memory Cache in RAM + L2 Persistent DataStore in SharedPreferences)
 * for Android TV scrapers, categories, and movie/series posts.
 */
class MovieCacheStore private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences("tv_movie_cache_store", Context.MODE_PRIVATE)
    private val gson = Gson()

    // ── L1 Memory Cache (RAM - Fast LRU) ────────────────────────────────────────
    private val memoryPosts = LruCache<String, List<VegaPost>>(200)
    private val memoryCatalogs = LruCache<String, List<VegaCatalog>>(50)
    private val memoryScrapers = LruCache<String, List<VegaProvider>>(20)

    companion object {
        @Volatile
        private var instance: MovieCacheStore? = null

        fun getInstance(context: Context): MovieCacheStore {
            return instance ?: synchronized(this) {
                instance ?: MovieCacheStore(context).also { instance = it }
            }
        }

        // BUG-006 fix: MD5-hash the key to prevent SharedPreferences key collisions
        // from URLs containing | separators or exceeding key length limits
        private fun md5(input: String): String {
            return try {
                val digest = MessageDigest.getInstance("MD5")
                digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                input.hashCode().toString(16)
            }
        }
    }

    private fun postKey(providerUrl: String, scraperValue: String, filterOrQuery: String): String {
        // Use null-byte separator (impossible in URLs) + MD5 hash to prevent collisions
        val raw = "$providerUrl\u0000$scraperValue\u0000${filterOrQuery.trim().lowercase()}"
        return md5(raw)
    }

    private fun catalogKey(providerUrl: String, scraperValue: String): String {
        val raw = "$providerUrl\u0000$scraperValue"
        return md5(raw)
    }

    fun getMovies(providerUrl: String, scraperValue: String, filterOrQuery: String): List<VegaPost>? {
        val key = postKey(providerUrl, scraperValue, filterOrQuery)
        memoryPosts.get(key)?.let { return it }

        val json = prefs.getString("posts_$key", null) ?: return null
        return try {
            val type = object : TypeToken<List<VegaPost>>() {}.type
            val list: List<VegaPost> = gson.fromJson(json, type)
            if (!list.isNullOrEmpty()) {
                memoryPosts.put(key, list)
            }
            list
        } catch (_: Exception) {
            null
        }
    }

    fun putMovies(providerUrl: String, scraperValue: String, filterOrQuery: String, movies: List<VegaPost>) {
        if (movies.isEmpty()) return
        val key = postKey(providerUrl, scraperValue, filterOrQuery)
        // BUG-014 fix: truncate BEFORE putting into LRU so memory and disk are consistent
        val toSave = if (movies.size > 80) movies.take(80) else movies
        memoryPosts.put(key, toSave)

        try {
            val json = gson.toJson(toSave)
            prefs.edit().putString("posts_$key", json).apply()
        } catch (_: Exception) {}
    }

    fun getCategories(providerUrl: String, scraperValue: String): List<VegaCatalog>? {
        val key = catalogKey(providerUrl, scraperValue)
        memoryCatalogs.get(key)?.let { return it }

        val json = prefs.getString("cats_$key", null) ?: return null
        return try {
            val type = object : TypeToken<List<VegaCatalog>>() {}.type
            val list: List<VegaCatalog> = gson.fromJson(json, type)
            if (!list.isNullOrEmpty()) {
                memoryCatalogs.put(key, list)
            }
            list
        } catch (_: Exception) {
            null
        }
    }

    fun putCategories(providerUrl: String, scraperValue: String, categories: List<VegaCatalog>) {
        if (categories.isEmpty()) return
        val key = catalogKey(providerUrl, scraperValue)
        memoryCatalogs.put(key, categories)
        try {
            val json = gson.toJson(categories)
            prefs.edit().putString("cats_$key", json).apply()
        } catch (_: Exception) {}
    }

    fun getScrapers(providerUrl: String): List<VegaProvider>? {
        val key = providerUrl.trim()
        memoryScrapers.get(key)?.let { return it }

        val json = prefs.getString("scrapers_$key", null) ?: return null
        return try {
            val type = object : TypeToken<List<VegaProvider>>() {}.type
            val list: List<VegaProvider> = gson.fromJson(json, type)
            if (!list.isNullOrEmpty()) {
                memoryScrapers.put(key, list)
            }
            list
        } catch (_: Exception) {
            null
        }
    }

    fun putScrapers(providerUrl: String, scrapers: List<VegaProvider>) {
        if (scrapers.isEmpty()) return
        val key = providerUrl.trim()
        memoryScrapers.put(key, scrapers)
        try {
            val json = gson.toJson(scrapers)
            prefs.edit().putString("scrapers_$key", json).apply()
        } catch (_: Exception) {}
    }

    fun clear() {
        memoryPosts.evictAll()
        memoryCatalogs.evictAll()
        memoryScrapers.evictAll()
        prefs.edit().clear().apply()
    }
}
