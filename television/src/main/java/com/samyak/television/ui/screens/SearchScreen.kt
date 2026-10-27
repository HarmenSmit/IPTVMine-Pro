package com.samyak.television.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.samyak.television.TvChannelCard
import com.samyak.television.data.TelevisionViewModel
import com.samyak.television.model.Channel
import com.samyak.television.model.Provider
import com.samyak.television.model.VegaPost
import com.samyak.television.model.VegaProvider

@Composable
fun SearchScreen(
    viewModel: TelevisionViewModel,
    onChannelClick: (Channel) -> Unit,
    onMovieClick: (VegaPost, VegaProvider, Provider) -> Unit
) {
    var searchMovies by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(end = 100.dp, bottom = 16.dp)
        ) {
            SearchChoice("TV channels", !searchMovies) { searchMovies = false }
            SearchChoice("Movies & Shows", searchMovies) { searchMovies = true }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (searchMovies) {
                SearchMoviesScreen(onMovieClick = onMovieClick)
            } else {
                ChannelSearchScreen(viewModel, onChannelClick)
            }
        }
    }
}

@Composable
private fun ChannelSearchScreen(viewModel: TelevisionViewModel, onChannelClick: (Channel) -> Unit) {
    val query by viewModel.searchQuery.collectAsState()
    val selectedCategory by viewModel.searchCategory.collectAsState()
    val channels by viewModel.searchResults.collectAsState()
    val allChannels by viewModel.channels.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isManualRefreshing by viewModel.isManualRefreshing.collectAsState()
    val searchError by viewModel.searchError.collectAsState()
    val loadError by viewModel.errorMessage.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()

    LaunchedEffect(Unit) { searchFocus.requestFocus() }
    LaunchedEffect(channels) { gridState.scrollToItem(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(end = 100.dp, bottom = 12.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.searchChannels(it) },
                placeholder = { Text("Search TV channels...", color = Color.Gray) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Color(0xFF26A69A),
                    focusedBorderColor = Color(0xFF26A69A),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    focusManager.moveFocus(FocusDirection.Down)
                }),
                modifier = Modifier.weight(1f).focusRequester(searchFocus)
            )
            if (query.isNotEmpty()) {
                SearchChoice("Clear", false) { viewModel.searchChannels("") }
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            items(count = categories.size) { index ->
                val category = categories[index]
                SearchChoice(category, selectedCategory == category) {
                    viewModel.searchChannels(query, category)
                }
            }
        }

        Text(
            text = if (isSearching) "Searching..." else "${channels.size} TV channels",
            color = Color.LightGray,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                searchError != null -> Text(
                    text = searchError.orEmpty(),
                    color = Color.Red,
                    modifier = Modifier.align(Alignment.Center)
                )
                channels.isEmpty() && (isSearching || (isLoading && allChannels.isEmpty())) ->
                    CircularProgressIndicator(
                        color = Color(0xFF26A69A),
                        modifier = Modifier.align(Alignment.Center)
                    )
                channels.isEmpty() -> Text(
                    text = if (allChannels.isEmpty()) loadError ?: "Add an M3U playlist in Playlists to find TV channels."
                        else "No TV channels match your search.",
                    color = Color.LightGray,
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 130.dp),
                    state = gridState,
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Ordinal keys avoid reading every database record to build a key map.
                    // Duplicate playlist entries also remain independently selectable.
                    items(count = channels.size) { index ->
                        val channel = channels[index]
                        TvChannelCard(channel) { onChannelClick(channel) }
                    }
                }
            }
            if (isManualRefreshing && channels.isNotEmpty()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFF26A69A))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color(0xFF26A69A).copy(alpha = 0.35f) else Color.White.copy(alpha = 0.08f),
            focusedContainerColor = Color(0xFF26A69A),
            pressedContainerColor = Color(0xFF208b80)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp))
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}
