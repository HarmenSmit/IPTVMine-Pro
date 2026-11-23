package com.samyak.television.data

import android.app.Application
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.samyak.television.model.Channel
import com.samyak.playlist.*
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.Channel as SignalChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TelevisionViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "TelevisionViewModel"
    private val providerRepository = ProviderRepository(application)
    private val storeDelegate = lazy {
        PlaylistStore(application, "iptv-tv-channels.db", Channel::class.java, { it.name }, { it.category }, deferMaintenance = true)
    }
    private val store by storeDelegate
    private var fetchJob: Job? = null
    private var maintenanceJob: Job? = null
    private val maintenanceRequests = SignalChannel<Unit>(SignalChannel.CONFLATED)
    private var filterJob: Job? = null
    private var filterCancellationSignal: CancellationSignal? = null
    private var searchJob: Job? = null
    private var searchCancellationSignal: CancellationSignal? = null
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    private val _searchCategory = MutableStateFlow("All")
    val searchCategory: StateFlow<String> = _searchCategory.asStateFlow()
    private val _searchResults = MutableStateFlow<List<Channel>>(emptyList())
    val searchResults: StateFlow<List<Channel>> = _searchResults.asStateFlow()
    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()
    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()
    private val _filteredChannels = MutableStateFlow<List<Channel>>(emptyList())
    val filteredChannels: StateFlow<List<Channel>> = _filteredChannels.asStateFlow()

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _categories = MutableStateFlow<List<String>>(listOf("All"))
    val categories: StateFlow<List<String>> = _categories.asStateFlow()

    private val _selectedCategory = MutableStateFlow("All")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _isManualRefreshing = MutableStateFlow(false)
    val isManualRefreshing: StateFlow<Boolean> = _isManualRefreshing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        refreshChannels(manual = false)
    }

    fun onChannelScreenReady() {
        if (maintenanceJob != null) return
        maintenanceJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    store.maintain()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.w(TAG, "Playlist maintenance deferred (${failure.javaClass.simpleName})")
                }
                // Retry retired versions once old UI readers have gone away.
                withTimeoutOrNull(60_000) { maintenanceRequests.receive() }
            }
        }
    }

    fun selectCategory(category: String) {
        _selectedCategory.value = category
        updateFilter()
    }

    fun searchChannels(query: String, category: String = _searchCategory.value) {
        _searchQuery.value = query
        _searchCategory.value = category
        updateSearch()
    }

    private fun updateSearch() {
        searchJob?.cancel()
        searchCancellationSignal?.cancel()
        val snapshot = _channels.value as? ChannelList<Channel> ?: return
        val query = _searchQuery.value
        val category = _searchCategory.value
        val signal = CancellationSignal()
        searchCancellationSignal = signal
        _isSearching.value = true
        _searchError.value = null
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Match the phone: debounce typing and cancel the underlying SQLite query.
                if (query.isNotEmpty()) delay(250)
                ensureActive()
                val result = snapshot.filtered(query, category, signal)
                withContext(Dispatchers.Main) {
                    if (_channels.value === snapshot && searchCancellationSignal === signal) {
                        _searchResults.value = result
                    }
                }
            } catch (cancelled: OperationCanceledException) {
                if (!signal.isCanceled) throw cancelled
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Channel search failed (${failure.javaClass.simpleName})")
                withContext(Dispatchers.Main) {
                    if (searchCancellationSignal === signal) {
                        _searchError.value = "Unable to search channels. Please try again."
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (searchCancellationSignal === signal) _isSearching.value = false
                }
            }
        }
    }

    private fun updateFilter() {
        filterJob?.cancel()
        filterCancellationSignal?.cancel()
        val snapshot = _channels.value as? ChannelList<Channel> ?: return
        val category = _selectedCategory.value
        val signal = CancellationSignal()
        filterCancellationSignal = signal
        filterJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = snapshot.filtered(category = category, cancellationSignal = signal)
                withContext(Dispatchers.Main) {
                    if (_channels.value === snapshot && filterCancellationSignal === signal) {
                        _filteredChannels.value = result
                    }
                }
            } catch (cancelled: OperationCanceledException) {
                if (!signal.isCanceled) throw cancelled
            }
        }
    }

    fun refreshChannels(manual: Boolean = true) {
        val previous = fetchJob
        previous?.cancel()
        _isLoading.value = true
        _isManualRefreshing.value = manual
        _errorMessage.value = null
        fetchJob = viewModelScope.launch(Dispatchers.IO) {
            previous?.join()
            val ownJob = currentCoroutineContext()[Job]
            try {
                val providers = providerRepository.getProviders().filter {
                    it.isActive && it.safeType == com.samyak.television.model.ProviderType.IPTV
                }
                val sources = providers.map { it.url }
                if (_channels.value.isEmpty()) {
                    val firstPage = store.firstPageIfUncached(sources)
                    if (firstPage.isNotEmpty()) withContext(Dispatchers.Main) {
                        if (_selectedCategory.value == "All") _filteredChannels.value = firstPage
                    }
                }
                suspend fun publish() {
                    val snapshot = store.snapshot(sources)
                    withContext(Dispatchers.Main) {
                        if (_channels.value !== snapshot) {
                            _channels.value = snapshot
                            updateFilter()
                            updateSearch()
                        }
                    }
                    val categories = snapshot.categoryCounts()
                        .map { it.first }
                        .filterNot { it == "All" }
                        .sortedWith(
                            compareByDescending<String> { it.startsWith("nl", ignoreCase = true) }
                                .thenBy(String.CASE_INSENSITIVE_ORDER) { it }
                        )
                        .withAll("All")
                    withContext(Dispatchers.Main) {
                        _categories.value = categories
                    }
                }
                publish()
                val importErrors = mutableListOf<String>()
                for (provider in providers) {
                    ensureActive()
                    if (!manual && !store.needsRefresh(provider.url, 30 * 60 * 1000L)) continue
                    try {
                        store.recordRefreshAttempt(provider.url)
                        if (ChannelsLoader.importChannels(provider.url, provider.title, store, getApplication<Application>().cacheDir)) {
                            publish()
                            maintenanceRequests.trySend(Unit)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        ensureActive()
                        val reason = when (failure) {
                            is PlaylistHttpException -> when (failure.statusCode) {
                                401 -> "HTTP 401 — controleer je inloggegevens."
                                403 -> "HTTP 403 — de provider weigert toegang."
                                404 -> "HTTP 404 — de playlist is niet gevonden op dit adres."
                                else -> "De provider antwoordde met HTTP ${failure.statusCode}."
                            }
                            is java.net.MalformedURLException -> "Het playlistadres is ongeldig."
                            is java.net.UnknownHostException -> "De tv kan de hostnaam van de provider niet vinden."
                            is java.net.SocketTimeoutException -> "De verbinding met de provider duurde te lang."
                            is java.net.ConnectException -> failure.message //"De tv kan geen verbinding maken met de provider."
                            is javax.net.ssl.SSLException -> "De beveiligde verbinding met de provider is mislukt."
                            is android.database.sqlite.SQLiteFullException -> "De opslag voor de zenderdatabase is vol."
                            is android.database.sqlite.SQLiteException -> "Databasefout (${failure.javaClass.simpleName})."
                            is PlaylistLimitException -> failure.message ?: "De playlist overschrijdt een importlimiet."
                            else -> when (failure.message) {
                                "Playlist contains no valid channels" -> "De playlist bevat geen geldige zenders."
                                "Server returned media instead of a playlist" -> "De URL verwijst naar media in plaats van een playlist."
                                "Partial playlist response", "Incomplete playlist response" -> "De playlist is slechts gedeeltelijk ontvangen."
                                "Unsupported playlist scheme" -> "Het playlistadres moet met http:// of https:// beginnen."
                                else -> "Import mislukt (${failure.javaClass.simpleName})."
                            }
                        }
                        val name = provider.title.ifBlank { "Naamloze provider" }
                        importErrors.add("$name — $reason")
                        Log.w(TAG, "Playlist import failed (${failure.javaClass.simpleName}); previous data retained")
                    }
                }
                withContext(Dispatchers.Main) {
                    _errorMessage.value = when {
                        importErrors.isNotEmpty() -> importErrors.joinToString("\n")
                        _channels.value.isEmpty() -> "No channels loaded. Check your Internet connection and playlist URL."
                        else -> null
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Unable to load playlist storage (${failure.javaClass.simpleName})")
                withContext(Dispatchers.Main) { _errorMessage.value = "Unable to read or update playlist storage. Previous data retained." }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (fetchJob === ownJob) {
                        _isLoading.value = false
                        _isManualRefreshing.value = false
                    }
                }
            }
        }
    }

    override fun onCleared() {
        val maintaining = maintenanceJob
        maintaining?.cancel()
        fetchJob?.cancel(); filterJob?.cancel(); searchJob?.cancel()
        filterCancellationSignal?.cancel()
        searchCancellationSignal?.cancel()
        val fetching = fetchJob
        val filtering = filterJob
        val searching = searchJob
        CoroutineScope(Dispatchers.IO).launch {
            maintaining?.join()
            fetching?.join(); filtering?.join(); searching?.join()
            try {
                if (storeDelegate.isInitialized()) store.close()
            } catch (failure: Exception) {
                Log.w(TAG, "Playlist storage cleanup failed (${failure.javaClass.simpleName})")
            }
        }
        super.onCleared()
    }
}
