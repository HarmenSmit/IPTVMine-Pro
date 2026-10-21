package com.samyak.television.ui.screens

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.samyak.television.data.ExtensionRepository
import com.samyak.television.data.MovieCacheStore
import com.samyak.television.data.ProviderRepository
import com.samyak.television.data.VegaProviderRunner
import com.samyak.television.model.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchMoviesScreen(
    onMovieClick: (VegaPost, VegaProvider, Provider) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = remember { VegaProviderRunner(context) }
    val providerRepo = remember { ProviderRepository(context) }
    val cacheStore = remember { MovieCacheStore.getInstance(context) }

    DisposableEffect(Unit) {
        onDispose {
            runner.destroy()
        }
    }

    val activeProviders = remember { providerRepo.getProviders().filter { it.isActive && it.safeType == ProviderType.VEGA } }
    var selectedProvider by remember { mutableStateOf<Provider?>(activeProviders.firstOrNull()) }
    var scrapers by remember { mutableStateOf<List<VegaProvider>>(emptyList()) }
    var selectedScraper by remember { mutableStateOf<VegaProvider?>(null) }
    var isScrapersLoading by remember { mutableStateOf(false) }

    var movies by remember { mutableStateOf<List<VegaPost>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(1) }
    var isLoading by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    val extensionRepo = remember { ExtensionRepository.getInstance(context) }
    val installedExtensionsState by extensionRepo.installedExtensionsFlow.collectAsState()

    // 1. Fetch scrapers/extensions with cache store memory
    LaunchedEffect(selectedProvider, installedExtensionsState) {
        val provider = selectedProvider
        if (provider != null) {
            val cachedScrapers = cacheStore.getScrapers(provider.url)?.filter { it.value in installedExtensionsState }
            if (!cachedScrapers.isNullOrEmpty() && scrapers.isEmpty()) {
                scrapers = cachedScrapers
                if (selectedScraper == null) {
                    selectedScraper = cachedScrapers.firstOrNull()
                }
            } else if (scrapers.isEmpty()) {
                isScrapersLoading = true
            }
            try {
                val manifest = runner.fetchManifest(provider.url)
                val installed = manifest.filter { it.value in installedExtensionsState }
                scrapers = installed
                cacheStore.putScrapers(provider.url, manifest)
                if (selectedScraper == null || !installed.any { it.value == selectedScraper?.value }) {
                    selectedScraper = installed.firstOrNull()
                }
            } catch (e: Exception) {
                Log.e("TVSearchMoviesScreen", "Error loading scrapers", e)
                if (scrapers.isEmpty()) {
                    scrapers = emptyList()
                    selectedScraper = null
                }
            } finally {
                isScrapersLoading = false
            }
        }
    }

    // 2. Perform search with debounce, memory cache, and cancellation
    val performSearch: (Boolean, Boolean) -> Unit = { isNextPage, isImmediate ->
        val provider = selectedProvider
        val scraper = selectedScraper
        if (provider != null && scraper != null) {
            searchJob?.cancel()
            val targetPage = if (isNextPage) page else 1
            val trimmedQuery = searchQuery.trim()

            // Immediate cache retrieval for empty query page 1
            if (!isNextPage && trimmedQuery.isBlank()) {
                val cached = cacheStore.getMovies(provider.url, scraper.value, "")
                if (!cached.isNullOrEmpty() && movies.isEmpty()) {
                    movies = cached
                    page = 2
                    hasMore = true
                }
            }

            searchJob = scope.launch {
                if (!isImmediate && !isNextPage && trimmedQuery.isNotBlank()) {
                    delay(350) // Debounce keystrokes
                }
                isLoading = true
                if (!isNextPage) {
                    page = 1
                    hasMore = true
                }
                try {
                    val newMovies = if (trimmedQuery.isNotBlank()) {
                        runner.getSearchPosts(provider.url, scraper.value, trimmedQuery, targetPage)
                    } else {
                        runner.getPosts(provider.url, scraper.value, filter = "", page = targetPage)
                    }

                    if (!isNextPage && trimmedQuery.isBlank() && newMovies.isNotEmpty()) {
                        cacheStore.putMovies(provider.url, scraper.value, "", newMovies)
                    }

                    if (newMovies.isEmpty()) {
                        hasMore = false
                        if (!isNextPage) {
                            movies = emptyList()
                        }
                    } else {
                        movies = if (isNextPage) movies + newMovies else newMovies
                        page = targetPage + 1
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("TVSearchMoviesScreen", "Error searching movies", e)
                    if (!isNextPage) {
                        Toast.makeText(context, "Error fetching content: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    isLoading = false
                }
            }
        }
    }

    // Search trigger on query or scraper change
    LaunchedEffect(searchQuery, selectedScraper) {
        if (selectedScraper != null) {
            performSearch(false, false)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        // 1. Pill-Shaped Search Input Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search by title, genre, or actor...", color = Color.Gray) },
            singleLine = true,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(20.dp)
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF26A69A),
                unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                focusedContainerColor = Color(0xFF0C111F).copy(alpha = 0.85f),
                unfocusedContainerColor = Color(0xFF0C111F).copy(alpha = 0.5f)
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { performSearch(false, true) }),
            shape = RoundedCornerShape(30.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 100.dp, bottom = 12.dp)
        )

        // 2. Scraper selector chips if multiple extensions are installed
        if (scrapers.size > 1) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                items(scrapers) { scraper ->
                    val isSelected = selectedScraper?.value == scraper.value
                    Surface(
                        onClick = {
                            selectedScraper = scraper
                            movies = emptyList()
                            performSearch(false, true)
                        },
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (isSelected) Color(0xFF26A69A) else Color.White.copy(alpha = 0.08f),
                            focusedContainerColor = Color(0xFF26A69A),
                            pressedContainerColor = Color(0xFF208b80)
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
                        modifier = Modifier.wrapContentWidth()
                    ) {
                        Text(
                            text = scraper.display_name,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        // 3. Horizontal Scrollable Popular Categories List
        Text(
            text = "Popular Categories",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        val genres = listOf("Action", "Comedy", "Horror", "Drama", "Sci-Fi", "Thriller", "Romance", "Adventure")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            items(genres) { genre ->
                Surface(
                    onClick = {
                        searchQuery = genre
                        performSearch(false, true)
                    },
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.05f),
                        focusedContainerColor = Color(0xFF26A69A),
                        pressedContainerColor = Color(0xFF208b80)
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
                    modifier = Modifier.wrapContentWidth()
                ) {
                    Text(
                        text = genre,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp)
                    )
                }
            }
        }

        // 4. Grid Title Row (with Extension Source name on the right)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (searchQuery.isBlank()) "Trending Searches" else "Results for \"$searchQuery\"",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            if (selectedScraper != null) {
                Text(
                    text = "Source: ${selectedScraper?.display_name}",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 5. Full Width Grid of Results
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val gridState = rememberLazyGridState()

            // Infinite Scroll Trigger
            val shouldLoadMore = remember {
                derivedStateOf {
                    val lastVisibleItem = gridState.layoutInfo.visibleItemsInfo.lastOrNull()
                        ?: return@derivedStateOf false
                    lastVisibleItem.index >= gridState.layoutInfo.totalItemsCount - 5
                }
            }

            LaunchedEffect(shouldLoadMore.value) {
                if (shouldLoadMore.value && hasMore && !isLoading && movies.isNotEmpty()) {
                    performSearch(true, true)
                }
            }

            if (isLoading && movies.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF26A69A))
                }
            } else if (movies.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val message = when {
                        activeProviders.isEmpty() -> "No Vega Providers added. Please configure provider in Playlists."
                        installedExtensionsState.isEmpty() -> "No Extensions installed. Please install scrapers in Extensions."
                        searchQuery.isBlank() -> "Type in search bar or select a category above"
                        else -> "No results found for \"$searchQuery\""
                    }
                    Text(
                        text = message,
                        color = Color.Gray,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(movies) { _, movie ->
                        val provider = selectedProvider
                        val scraper = selectedScraper
                        TvMovieCard(
                            movie = movie,
                            onClick = {
                                if (provider != null && scraper != null) {
                                    onMovieClick(movie, scraper, provider)
                                }
                            }
                        )
                    }

                    if (isLoading && movies.isNotEmpty()) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(5) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = Color(0xFF26A69A),
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (isLoading && movies.isNotEmpty()) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                    color = Color(0xFF26A69A)
                )
            }
        }
    }
}
