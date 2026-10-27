package com.samyak.television.data

import android.app.Application
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

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        refreshChannels()
    }

    fun selectCategory(category: String) {
        _selectedCategory.value = category
        updateFilter()
    }

    private fun updateFilter() {
        filterJob?.cancel()
        val snapshot = _channels.value as? ChannelList<Channel> ?: return
        val category = _selectedCategory.value
        filterJob = viewModelScope.launch(Dispatchers.IO) {
            val result = snapshot.filtered(category = category)
            withContext(Dispatchers.Main) { _filteredChannels.value = result }
        }
    }

    fun refreshChannels() {
        val previous = fetchJob
        previous?.cancel()
        _isLoading.value = true
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
                    val categories = snapshot.categoryCounts().project { it.first }.withAll("All")
                    withContext(Dispatchers.Main) {
                        _channels.value = snapshot
                        _categories.value = categories
                        updateFilter()
                    }
                }
                publish()
                var failed = 0
                for (provider in providers) {
                    ensureActive()
                    try {
                        ChannelsLoader.importChannels(provider.url, provider.title, store, getApplication<Application>().cacheDir)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        ensureActive()
                        failed++
                        Log.w(TAG, "Playlist import failed (${failure.javaClass.simpleName}); previous data retained")
                    }
                }
                publish()
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
                withContext(NonCancellable + Dispatchers.Main) { if (fetchJob === ownJob) _isLoading.value = false }
            }
        }
    }

    override fun onCleared() {
        fetchJob?.cancel(); filterJob?.cancel()
        val fetching = fetchJob
        val filtering = filterJob
        CoroutineScope(Dispatchers.IO).launch {
            fetching?.join(); filtering?.join()
            try {
                if (storeDelegate.isInitialized()) store.close()
            } catch (failure: Exception) {
                Log.w(TAG, "Playlist storage cleanup failed (${failure.javaClass.simpleName})")
            }
        }
        super.onCleared()
    }
}
