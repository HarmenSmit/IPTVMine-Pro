package com.samyak.iptvminepro.provider

import android.util.Log
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.samyak.iptvminepro.model.Channel
import com.samyak.iptvminepro.utils.ContentFilter
import kotlinx.coroutines.*
import java.io.IOException
import java.net.URL
import com.samyak.playlist.*
import org.json.JSONObject
import org.json.JSONArray
import com.samyak.iptvminepro.notification.NotificationHelper
import com.samyak.iptvminepro.model.Provider

class ChannelsProvider(application: Application) : AndroidViewModel(application) {

    private val _providers = MutableLiveData<List<Provider>>(emptyList())
    val providers: LiveData<List<Provider>> get() = _providers

    private val repository = ProviderRepository(application)
    private val playlistStoreDelegate = lazy { PlaylistStore(application, "iptv-channels.db", Channel::class.java, { it.name }, { it.category }) }
    private val playlistStore by playlistStoreDelegate
    private val _categoryCounts = MutableLiveData<List<Pair<String, Int>>>(emptyList())
    val categoryCounts: LiveData<List<Pair<String, Int>>> get() = _categoryCounts
    private var filterJob: Job? = null

    private val _channels = MutableLiveData<List<Channel>>(emptyList())
    val channels: LiveData<List<Channel>> get() = _channels

    private val _filteredChannels = MutableLiveData<List<Channel>>(emptyList())
    val filteredChannels: LiveData<List<Channel>> get() = _filteredChannels

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> get() = _error

    private val _categories = MutableLiveData<List<String>>(emptyList())
    val categories: LiveData<List<String>> get() = _categories

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> get() = _isLoading

    private val _providerChannelCounts = MutableLiveData<Map<String, Int>>(emptyMap())
    val providerChannelCounts: LiveData<Map<String, Int>> get() = _providerChannelCounts

    private val sourceUrls = mutableListOf<String>()

    private var lastMatchCount = 0
    private val notificationHelper = NotificationHelper(application)
    private var notificationCount = 0

    // Fetch cooldown: prevent repeated network requests
    private var lastFetchTimestamp = 0L
    private var lastFetchedUrlsHash = 0

    /**
     * Per-URL failure backoff. Prevents repeated connection attempts (and wasted mobile data)
     * against sources that are consistently failing. Uses exponential backoff so a broken
     * provider is retried at most once per backoff window instead of on every screen entry.
     */
    private data class FailureState(val failureCount: Int, val nextRetryAt: Long)
    private val sourceFailureStates = java.util.concurrent.ConcurrentHashMap<String, FailureState>()

    private fun isInBackoff(url: String, now: Long): Boolean {
        val state = sourceFailureStates[url] ?: return false
        return now < state.nextRetryAt
    }

    private fun backoffRemainingSeconds(url: String, now: Long): Long {
        val state = sourceFailureStates[url] ?: return 0L
        return ((state.nextRetryAt - now) / 1000L).coerceAtLeast(0L)
    }

    private fun recordSourceSuccess(url: String) {
        sourceFailureStates.remove(url)
    }

    private fun recordSourceFailure(url: String) {
        val newCount = (sourceFailureStates[url]?.failureCount ?: 0) + 1
        // Exponential backoff: BASE * 2^(n-1), capped at MAX_BACKOFF_MS
        val delay = (BASE_BACKOFF_MS shl minOf(newCount - 1, 6)).coerceAtMost(MAX_BACKOFF_MS)
        sourceFailureStates[url] = FailureState(newCount, System.currentTimeMillis() + delay)
        Log.w(TAG, "Source failed ($newCount consecutive), retry in ${delay / 1000}s")
    }

    fun showNewMatchNotification(channel: Channel) {
        notificationCount++
        notificationHelper.showMatchNotification(
            title = "New Match Available!",
            message = "${channel.team1} vs ${channel.team2} is now live!",
            matchName = channel.name,
            bannerUrl = channel.logoUrl,
            badgeCount = notificationCount
        )
    }

    fun clearNotifications() {
        notificationCount = 0
        notificationHelper.clearNotifications()
    }

    init {
        refreshProviders()
    }

    fun refreshProviders() {
        val list = repository.getProviders()
        _providers.postValue(list)
        synchronized(sourceUrls) {
            sourceUrls.clear()
            sourceUrls.addAll(list.filter { it.isActive && it.safeType == com.samyak.iptvminepro.model.ProviderType.IPTV }.map { it.url })
        }
    }

    private var fetchJob: Job? = null

    companion object {

        private const val TAG = "ChannelsProvider"
        private const val DEFAULT_LOGO_URL = ""
        private const val FETCH_COOLDOWN_MS = 5 * 60 * 1000L // 5 minutes
        // Per-source exponential backoff bounds (used to stop hammering failing URLs)
        private const val BASE_BACKOFF_MS = 60 * 1000L // 1 minute
        private const val MAX_BACKOFF_MS = 60 * 60 * 1000L // 1 hour cap
        private val VIDEO_EXTENSIONS = setOf(
            ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm",
            ".mpg", ".mpeg", ".3gp", ".ogv", ".rm", ".rmvb"
        )
        // Generic JSON field-name candidates used to parse arbitrary IPTV JSON playlists.
        private val JSON_URL_KEYS = listOf(
            "url", "link", "stream", "stream_url", "streamurl", "streamUrl",
            "source_url", "adfree_url", "dai_url", "file", "playback_url",
            "manifest", "hls", "m3u8", "playbackUrl", "streamLink"
        )
        private val JSON_NAME_KEYS = listOf(
            "name", "title", "channel", "channel_name", "channelName",
            "tvg-name", "tvg_name", "tvgName", "display_name"
        )
        private val JSON_LOGO_KEYS = listOf(
            "logo", "logo_url", "logoUrl", "tvg-logo", "tvg_logo", "tvgLogo",
            "icon", "image", "img", "thumbnail", "poster", "src"
        )
        private val JSON_CATEGORY_KEYS = listOf(
            "group", "group-title", "group_title", "groupTitle", "category",
            "genre", "event_category", "categoryName", "type"
        )
        // Container/wrapper keys that should NOT be used as a category name.
        private val JSON_CONTAINER_KEYS = setOf(
            "channels", "streams", "data", "items", "list", "results", "result",
            "playlist", "playlists", "stations", "entries", "categories", "content"
        )
    }

    fun getSourceUrls(): List<String> = synchronized(sourceUrls) {
        sourceUrls.toList()
    }

    fun addSourceUrl(url: String) {
        synchronized(sourceUrls) {
            if (url.isNotBlank() && !sourceUrls.contains(url)) {
                sourceUrls.add(url)
            }
        }
    }

    fun removeSourceUrl(url: String) {
        synchronized(sourceUrls) {
            sourceUrls.remove(url)
        }
    }

    fun setSourceUrls(urls: List<String>) {
        synchronized(sourceUrls) {
            sourceUrls.clear()
            sourceUrls.addAll(urls.filter { it.isNotBlank() })
        }
    }

    /**
     * Load channels only if needed — skips fetching if data is cached and cooldown hasn't expired.
     * Use this for automatic/screen-entry fetches. Use [fetchM3UFile] for explicit user-triggered refreshes.
     */
    fun loadIfNeeded() {
        val now = System.currentTimeMillis()
        val currentUrlsHash = synchronized(sourceUrls) { sourceUrls.hashCode() }
        val withinCooldown = (now - lastFetchTimestamp) < FETCH_COOLDOWN_MS
        val urlsUnchanged = currentUrlsHash == lastFetchedUrlsHash
        val fetchAttempted = lastFetchTimestamp != 0L

        // Skip if we already attempted a fetch recently for the same set of URLs — regardless of
        // whether it succeeded. This is critical: if every source is failing, _channels stays empty,
        // and re-fetching on every screen entry would hammer the failing URLs and burn mobile data.
        if (fetchAttempted && withinCooldown && urlsUnchanged) {
            Log.d(TAG, "loadIfNeeded: skipping fetch — recent attempt, cooldown active, URLs unchanged")
            return
        }
        fetchM3UFile()
    }

    fun fetchM3UFile(forceRefresh: Boolean = false) {
        refreshProviders()
        if (forceRefresh) sourceFailureStates.clear()
        startFetch(getSourceUrls(), respectBackoff = !forceRefresh)
    }

    fun fetchFromSingleSource(sourceUrl: String) {
        sourceFailureStates.remove(sourceUrl)
        startFetch(listOf(sourceUrl), respectBackoff = false)
    }

    private fun startFetch(urls: List<String>, respectBackoff: Boolean) {
        val previous = fetchJob
        previous?.cancel()
        filterJob?.cancel()
        lastFetchTimestamp = System.currentTimeMillis()
        lastFetchedUrlsHash = urls.hashCode()
        _isLoading.value = true
        fetchJob = viewModelScope.launch(Dispatchers.IO) {
            // SQLite imports are serialized; cancellation finishes rollback before the next import.
            previous?.join()
            val ownJob = currentCoroutineContext()[Job]
            val failures = StringBuilder()
            var successes = 0
            try {
                // Cached generations remain available during download and after a failed update.
                publishSnapshot(urls)
                for ((index, source) in urls.withIndex()) {
                    ensureActive()
                    if (respectBackoff && isInBackoff(source, System.currentTimeMillis())) {
                        failures.append("Source ${index + 1}: Temporarily skipped (retry in ${backoffRemainingSeconds(source, System.currentTimeMillis())}s).\n")
                        continue
                    }
                    try {
                        importSource(source)
                        recordSourceSuccess(source)
                        successes++
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        ensureActive()
                        recordSourceFailure(source)
                        // Exception messages from HTTP clients may contain credential-bearing URLs.
                        val reason = when (failure) {
                            is PlaylistHttpException -> "HTTP ${failure.statusCode}"
                            is PlaylistLimitException -> failure.message ?: "Playlist safety limit exceeded"
                            is java.net.UnknownHostException -> "No internet connection or DNS error"
                            is java.net.SocketTimeoutException -> "Connection timeout"
                            is IOException -> "Incomplete or invalid playlist / network I/O error"
                            else -> "Playlist import failed"
                        }
                        failures.append("Source ${index + 1}: $reason\n")
                        Log.w(TAG, "Source ${index + 1} import failed (${failure.javaClass.simpleName}); previous playlist retained")
                    }
                }
                publishSnapshot(urls)
                withContext(Dispatchers.Main) {
                    _error.value = when {
                        urls.isEmpty() -> "No source URLs configured"
                        failures.isNotEmpty() -> "Loaded $successes/${urls.size} sources. Previous data retained for failed sources.\n$failures"
                        else -> null
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Unable to load playlist storage (${failure.javaClass.simpleName})")
                withContext(Dispatchers.Main) { _error.value = "Unable to read or update playlist storage. Previous data retained." }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (fetchJob === ownJob) _isLoading.value = false
                }
            }
        }

    }

    private suspend fun importSource(source: String) {
        val url = URL(source)
        if (url.protocol != "http" && url.protocol != "https") throw IOException("Unsupported playlist scheme")
        val provider = repository.getProviders().find { it.url == source }
        if (isDirectVideoUrl(source)) {
            playlistStore.beginImport(source).use { staging ->
                staging.add(Channel(provider?.title ?: source.substringAfterLast('/').substringBeforeLast('.'), "", source, category = "Direct Videos"))
                currentCoroutineContext().ensureActive()
                staging.publish()
            }
        } else {
            PlaylistDownload.useFile(getApplication<Application>().cacheDir, source) { file, checkCancelled ->
                playlistStore.beginImport(source).use { staging ->
                    val prefix = file.inputStream().use { input ->
                        val bytes = ByteArray(8192)
                        val count = input.read(bytes)
                        if (count <= 0) "" else String(bytes, 0, count, Charsets.UTF_8).trim().removePrefix("\uFEFF")
                    }
                    fun importJson() {
                        val content = PlaylistDownload.readSmallJson(file, checkCancelled)
                        val start = content.indexOfFirst { it == '{' || it == '[' }
                        if (start < 0) throw IOException("Invalid JSON playlist")
                        parseJSONFile(content.substring(start)).forEach { checkCancelled(); staging.add(it) }
                    }
                    val jsonPrefix = !prefix.startsWith("#") && (prefix.contains('{') || prefix.contains('['))
                    if (jsonPrefix) {
                        importJson()
                    } else {
                        val summary = M3uParser.parse(file.inputStream(), validUrl = ::isValidStreamUrl,
                            checkCancelled = checkCancelled) { record ->
                            if (!ContentFilter.shouldBlockContent(record.name, record.category)) {
                                staging.add(Channel(record.name, record.logoUrl, record.streamUrl, category = record.category,
                                    tvgId = record.tvgId, tvgName = record.tvgName, playlistMetadata = record.metadata))
                            }
                        }
                        if (summary.hasJson && !summary.isM3u && !summary.isHls) {
                            staging.clear()
                            importJson()
                        } else if (summary.isHls) {
                            staging.clear()
                            val title = provider?.title.orEmpty().ifBlank { source.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Stream" } }
                            staging.add(Channel(title, "", source, category = "Direct Videos"))
                        }
                    }
                    staging.publish(checkCancelled)
                }
            }
        }
        val count = playlistStore.count(source)
        provider?.let { repository.updateProvider(it.copy(channelCount = count)) }
    }

    private suspend fun publishSnapshot(urls: List<String>) {
        val snapshot = playlistStore.snapshot(urls)
        val counts = snapshot.categoryCounts()
        val categories = counts.project { it.first }.withAll("All")
        val sourceCounts = urls.associateWith { playlistStore.count(it) }
        withContext(Dispatchers.Main) {
            _channels.value = snapshot
            _filteredChannels.value = snapshot
            _categories.value = if (snapshot.isEmpty()) emptyList() else categories
            _categoryCounts.value = counts
            _providerChannelCounts.value = sourceCounts
            refreshProviders()
        }
    }

    private fun parseJSONFile(jsonText: String): List<Channel> {
        val channelsList = ArrayList<Channel>()
        try {
            val trimmed = jsonText.trim()
            val root: Any = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)

            // Detect the legacy sports "matches" format so its team/notification behavior is preserved.
            val isMatchesFormat = when (root) {
                is JSONObject -> root.has("matches")
                is JSONArray -> root.length() > 0 && root.optJSONObject(0)?.let { looksLikeMatch(it) } == true
                else -> false
            }

            if (isMatchesFormat) {
                val matchesArray = if (root is JSONArray) root else (root as JSONObject).getJSONArray("matches")
                parseMatchesArray(matchesArray, channelsList)

                // Enhanced proportional notification logic (matches/events only)
                val totalMatches = channelsList.size
                val newMatchesThreshold = (totalMatches * 0.2).toInt() // 20% threshold
                if (lastMatchCount > 0) {
                    val newMatchesCount = totalMatches - lastMatchCount
                    if (newMatchesCount > 0 && newMatchesCount >= newMatchesThreshold) {
                        channelsList.takeLast(newMatchesCount).forEach { showNewMatchNotification(it) }
                    }
                }
                lastMatchCount = totalMatches
            } else {
                // Generic recursive parser — handles arbitrary IPTV JSON shapes:
                // nested category maps, flat arrays, channels/streams/data wrappers, etc.
                extractChannelsFromJson(root, null, channelsList)
                Log.d(TAG, "Generic JSON parse produced ${channelsList.size} channels")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Invalid JSON playlist (${e.javaClass.simpleName})")
            throw IOException("Invalid JSON playlist")
        }
        return channelsList
    }

    /** Heuristic to detect the sports "match" object shape. */
    private fun looksLikeMatch(obj: JSONObject): Boolean {
        return obj.has("team_1") || obj.has("team_2") || obj.has("adfree_url") ||
                obj.has("dai_url") || obj.has("event_name") || obj.has("match_name") ||
                obj.has("event_category")
    }

    /**
     * Recursively walk arbitrary JSON and extract channels. An object is treated as a channel
     * when it contains a valid http(s) URL under any known URL key; otherwise we recurse into
     * its arrays/objects. When recursing through a category map (e.g. {"General": [...]}), the
     * map key is inherited as the category unless the channel object specifies its own group.
     */
    private fun extractChannelsFromJson(element: Any?, inheritedCategory: String?, out: ArrayList<Channel>) {
        when (element) {
            is JSONObject -> {
                val streamUrl = findFirstValidUrl(element)
                if (streamUrl != null) {
                    val name = findFirstString(element, JSON_NAME_KEYS) ?: "Unknown Channel"
                    val logo = findFirstString(element, JSON_LOGO_KEYS) ?: DEFAULT_LOGO_URL
                    val category = findFirstString(element, JSON_CATEGORY_KEYS)
                        ?: inheritedCategory ?: "Uncategorized"
                    if (!ContentFilter.shouldBlockContent(name, category)) {
                        out.add(Channel(name = name, logoUrl = logo, streamUrl = streamUrl, category = category))
                    }
                } else {
                    val keys = element.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = element.get(key)
                        val isContainer = value is JSONArray || value is JSONObject
                        val childCategory = if (isContainer && !JSON_CONTAINER_KEYS.contains(key.lowercase())) {
                            key
                        } else {
                            inheritedCategory
                        }
                        extractChannelsFromJson(value, childCategory, out)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until element.length()) {
                    extractChannelsFromJson(element.opt(i), inheritedCategory, out)
                }
            }
        }
    }

    /** Return the first valid http(s) URL found under any known URL key, or null. */
    private fun findFirstValidUrl(obj: JSONObject): String? {
        for (key in JSON_URL_KEYS) {
            if (obj.has(key) && !obj.isNull(key)) {
                val value = obj.optString(key, "").trim()
                if (isValidUrl(value)) return value
            }
        }
        return null
    }

    /** Return the first non-empty string value found under the given candidate keys, or null. */
    private fun findFirstString(obj: JSONObject, keys: List<String>): String? {
        for (key in keys) {
            if (obj.has(key) && !obj.isNull(key)) {
                val value = obj.optString(key, "").trim()
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }

    private fun parseMatchesArray(matchesArray: JSONArray, channelsList: ArrayList<Channel>) {
        for (i in 0 until matchesArray.length()) {
            val match = matchesArray.getJSONObject(i)
            
            val team1 = match.optString("team_1", "")
            val team2 = match.optString("team_2", "")
            val matchName = match.optString("match_name", "")
            val eventName = match.optString("event_name", "")
            val status = match.optString("status", "UNKNOWN")
            val startTime = match.optString("startTime", "")
            
            val name = when {
                matchName.isNotEmpty() -> matchName
                eventName.isNotEmpty() -> eventName
                team1.isNotEmpty() && team2.isNotEmpty() -> "$team1 vs $team2"
                match.has("title") && !match.isNull("title") -> match.getString("title")
                else -> "Live Event"
            }
            
            val logoUrl = if (match.has("src") && !match.isNull("src")) {
                match.getString("src")
            } else {
                DEFAULT_LOGO_URL
            }
            
            val streamUrl = when {
                match.has("adfree_url") && !match.isNull("adfree_url") -> match.getString("adfree_url")
                match.has("dai_url") && !match.isNull("dai_url") -> match.getString("dai_url")
                else -> ""
            }
            
            val category = if (match.has("event_category") && !match.isNull("event_category")) {
                match.getString("event_category")
            } else {
                "Live Events"
            }
            
            val isValidStream = streamUrl.isEmpty() || isValidUrl(streamUrl)
            if (name.isNotEmpty() && isValidStream) {
                if (!ContentFilter.shouldBlockContent(name, category)) {
                    channelsList.add(
                        Channel(
                            name = name,
                            logoUrl = logoUrl,
                            streamUrl = streamUrl,
                            category = category,
                            team1 = team1,
                            team2 = team2,
                            status = status,
                            startTime = startTime
                        )
                    )

                }
            }
        }
    }

    private fun isValidUrl(url: String?): Boolean {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"))
    }

    private fun isValidStreamUrl(url: String): Boolean {
        if (!isValidUrl(url)) return false

        return url.contains(".m3u8") || url.contains(".mp4") || url.contains(".avi") || 
               url.contains(".mkv") || url.contains(".ts") || url.contains(".mpd") ||
               url.contains("stream") || url.contains("live") ||
               (url.startsWith("http") && url.split(":").size >= 3)
    }

    /**
     * Check if a URL points directly to a video file (not a playlist).
     * Direct video providers become a virtual channel without downloading the video.
     */
    private fun isDirectVideoUrl(url: String): Boolean {
        val lowerUrl = url.lowercase().split("?").first().split("#").first()
        return VIDEO_EXTENSIONS.any { lowerUrl.endsWith(it) }
    }

    fun filterChannels(query: String) = filterChannelsByQueryAndCategory(query, null)

    fun filterChannelsByCategory(category: String?) = filterChannelsByQueryAndCategory(null, category)

    fun filterChannelsByQueryAndCategory(query: String?, category: String?) {
        filterJob?.cancel()
        val snapshot = _channels.value as? ChannelList<Channel> ?: return
        filterJob = viewModelScope.launch(Dispatchers.IO) {
            val result = snapshot.filtered(query, category)
            withContext(Dispatchers.Main) { _filteredChannels.value = result }
        }
    }

    override fun onCleared() {
        fetchJob?.cancel()
        filterJob?.cancel()
        val fetching = fetchJob
        val filtering = filterJob
        CoroutineScope(Dispatchers.IO).launch {
            fetching?.join()
            filtering?.join()
            try {
                if (playlistStoreDelegate.isInitialized()) playlistStore.close()
            } catch (failure: Exception) {
                Log.w(TAG, "Playlist storage cleanup failed (${failure.javaClass.simpleName})")
            }
        }
        super.onCleared()
    }
}
