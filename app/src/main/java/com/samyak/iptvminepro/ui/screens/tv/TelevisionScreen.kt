package com.samyak.iptvminepro.ui.screens.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samyak.iptvminepro.R
import com.samyak.iptvminepro.provider.ChannelsProvider
import com.samyak.iptvminepro.ui.components.ChannelCard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelevisionScreen(
    viewModel: ChannelsProvider = viewModel(),
    searchQuery: String = "",
    onChannelClick: (com.samyak.iptvminepro.model.Channel) -> Unit,
    onAddProviderClick: (() -> Unit)? = null
) {
    val channels by viewModel.filteredChannels.observeAsState(emptyList())
    val allChannels by viewModel.channels.observeAsState(emptyList())
    val categories by viewModel.categories.observeAsState(emptyList())
    val isLoading by viewModel.isLoading.observeAsState(false)
    val errorMessage by viewModel.error.observeAsState(null)

    var selectedCategory by remember { mutableStateOf("All") }

    LaunchedEffect(Unit) {
        viewModel.loadIfNeeded()
    }

    LaunchedEffect(searchQuery, selectedCategory, allChannels) {
        viewModel.filterChannelsByQueryAndCategory(searchQuery, selectedCategory)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {

        // Categories Row
        if (categories.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                items(categories) { category ->
                    val isSelected = category == selectedCategory
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCategory = category },
                        label = { Text(category) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            selectedBorderColor = Color.Transparent,
                            borderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                        ),
                        shape = RoundedCornerShape(20.dp)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (isLoading && channels.isEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (!errorMessage.isNullOrEmpty() && channels.isEmpty()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(id = R.drawable.undraw_files_missing_ntwe),
                        contentDescription = "Error",
                        modifier = Modifier
                            .size(150.dp)
                            .padding(bottom = 16.dp)
                    )
                    Text(
                        text = errorMessage ?: "Unknown Error occurred",
                        color = MaterialTheme.colorScheme.error,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontSize = 14.sp
                    )
                    if (onAddProviderClick != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onAddProviderClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add Provider")
                        }
                    }
                }
            } else if (channels.isEmpty()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(id = R.drawable.undraw_files_missing_ntwe),
                        contentDescription = "No channels",
                        modifier = Modifier
                            .size(150.dp)
                            .padding(bottom = 16.dp)
                    )
                    Text(
                        text = "No TV channels found",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontSize = 16.sp
                    )
                    if (onAddProviderClick != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onAddProviderClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add Provider")
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(channels, key = { it.name + "_" + it.streamUrl }) { channel ->
                        ChannelCard(
                            channel = channel,
                            onClick = { onChannelClick(channel) }
                        )
                    }
                }
            }

            // Overlay linear progress indicator for background loading
            if (isLoading && channels.isNotEmpty()) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
