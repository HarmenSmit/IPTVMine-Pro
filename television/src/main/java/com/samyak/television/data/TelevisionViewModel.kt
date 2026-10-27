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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TelevisionViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "TelevisionViewModel"
    private val providerRepository = ProviderRepository(application)
    private val storeDelegate = lazy { PlaylistStore(application, "iptv-tv-channels.db", Channel::class.java, { it.name }, { it.category }) }
    private val store by storeDelegate
    private var fetchJob: Job? = null
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
                suspend fun publish() {
                    val snapshot = store.snapshot(providers.map { it.url })
                    withContext(Dispatchers.Main) {
                        if (_channels.value !== snapshot) {
                            _channels.value = snapshot
                            updateFilter()
                            updateSearch()
                        }
                    }
                    val categories = snapshot.categoryCounts().project { it.first }.withAll("All")
                    withContext(Dispatchers.Main) {
                        _categories.value = categories
                    }
                }
                publish()
                var failed = 0
                var updated = 0
                for (provider in providers) {
                    ensureActive()
                    if (!manual && !store.needsRefresh(provider.url, 30 * 60 * 1000L)) continue
                    try {
                        store.recordRefreshAttempt(provider.url)
                        ChannelsLoader.importChannels(provider.url, provider.title, store, getApplication<Application>().cacheDir)
                        updated++
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        ensureActive()
                        failed++
                        Log.w(TAG, "Playlist import failed (${failure.javaClass.simpleName}); previous data retained")
                    }
                }
                if (updated > 0) publish()
                withContext(Dispatchers.Main) {
                    _errorMessage.value = when {
                        failed > 0 -> "$failed playlist import(s) failed. Previous data retained. Check your connection and playlist URL."
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
        fetchJob?.cancel(); filterJob?.cancel(); searchJob?.cancel()
        filterCancellationSignal?.cancel()
        searchCancellationSignal?.cancel()
        val fetching = fetchJob
        val filtering = filterJob
        val searching = searchJob
        CoroutineScope(Dispatchers.IO).launch {
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
