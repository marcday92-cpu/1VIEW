package com.iptv.tv.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.iptv.tv.domain.model.Category
import com.iptv.tv.domain.model.Episode
import com.iptv.tv.domain.model.SeriesDetail
import com.iptv.tv.domain.model.VodSourceFilter
import com.iptv.tv.ui.components.FreeBadge
import com.iptv.tv.ui.components.SectionSearchChip
import com.iptv.tv.ui.components.SectionSearchSheet
import com.iptv.tv.ui.components.TvModal
import com.iptv.tv.ui.components.tvHoldOptions
import com.iptv.tv.ui.theme.LocalAccent
import com.iptv.tv.ui.theme.TextMuted
import com.iptv.tv.ui.theme.TextPrimary
import com.iptv.tv.ui.theme.TextSecondary

@Composable
fun MoviesScreen(viewModel: MoviesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<com.iptv.tv.domain.model.VodItem?>(null) }
    CatalogLayout(
        categories = state.categories,
        selectedId = state.selectedCategory,
        onSelect = viewModel::selectCategory,
        sourceFilter = state.sourceFilter,
        onFilter = viewModel::setSourceFilter,
        emptyMessage = state.emptyMessage,
        canRetry = state.canRetry,
        onRetry = viewModel::retryFree,
        searchQuery = search.query,
        onSearch = viewModel::openSearch,
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.items, key = { it.streamId }) { item ->
                PosterCard(
                    item.posterUrl,
                    item.name,
                    item.year,
                    onClick = { viewModel.play(item) },
                    onOptions = { menuFor = item },
                    free = item.isFree,
                )
            }
        }
    }
    if (search.open) {
        SectionSearchSheet(
            title = "Search Movies",
            placeholder = "Movie title",
            query = search.query,
            onQueryChange = viewModel::setSearchQuery,
            onDismiss = viewModel::closeSearch,
            status = when {
                search.query.isBlank() -> null
                search.searching -> "Searching…"
                search.results.isEmpty() -> "No movies match “${search.query}”."
                else -> "${search.results.size} result${if (search.results.size == 1) "" else "s"} · OK to play"
            },
        ) { firstFocus ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(search.results, key = { it.streamId }) { item ->
                    val first = item === search.results.firstOrNull()
                    PosterCard(
                        item.posterUrl,
                        item.name,
                        item.year,
                        onClick = { viewModel.playFromSearch(item) },
                        onOptions = { menuFor = item },
                        free = item.isFree,
                        modifier = if (first) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
        }
    }
    menuFor?.let { item ->
        TvModal(onDismiss = { menuFor = null }) {
            Column(
                Modifier.width(400.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1C1C22)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(item.name, style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                Button(onClick = { viewModel.play(item); menuFor = null }) { Text("Play") }
                Button(onClick = { viewModel.toggleFavourite(item); menuFor = null }) { Text("Favourite") }
                Button(onClick = { viewModel.hide(item); menuFor = null }) { Text("Hide") }
                Button(onClick = { viewModel.markWatched(item); menuFor = null }) { Text("Mark watched") }
                Button(onClick = { menuFor = null }) { Text("Close") }
            }
        }
    }
}

@Composable
fun SeriesScreen(viewModel: SeriesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    CatalogLayout(
        categories = state.categories,
        selectedId = state.selectedCategory,
        onSelect = viewModel::selectCategory,
        sourceFilter = state.sourceFilter,
        onFilter = viewModel::setSourceFilter,
        emptyMessage = state.emptyMessage,
        canRetry = state.canRetry,
        onRetry = viewModel::retryFree,
        searchQuery = search.query,
        onSearch = viewModel::openSearch,
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.items, key = { it.seriesId }) { item ->
                PosterCard(
                    item.posterUrl,
                    item.name,
                    subtitle = null,
                    onClick = { viewModel.openSeries(item) },
                    free = item.isFree,
                )
            }
        }
    }
    if (search.open) {
        SectionSearchSheet(
            title = "Search Series",
            placeholder = "Series title",
            query = search.query,
            onQueryChange = viewModel::setSearchQuery,
            onDismiss = viewModel::closeSearch,
            status = when {
                search.query.isBlank() -> null
                search.searching -> "Searching…"
                search.results.isEmpty() -> "No series match “${search.query}”."
                else -> "${search.results.size} result${if (search.results.size == 1) "" else "s"} · OK to open"
            },
        ) { firstFocus ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(search.results, key = { it.seriesId }) { item ->
                    val first = item === search.results.firstOrNull()
                    PosterCard(
                        item.posterUrl,
                        item.name,
                        subtitle = null,
                        onClick = { viewModel.openFromSearch(item) },
                        free = item.isFree,
                        modifier = if (first) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
        }
    }
    state.detail?.let { detail ->
        SeriesDetailModal(
            detail = detail,
            onPlay = { ep -> viewModel.play(ep, detail.name, detail.seriesId) },
            onDismiss = viewModel::dismissDetail,
        )
    }
}

@Composable
internal fun SeriesDetailModal(
    detail: SeriesDetail,
    onPlay: (Episode) -> Unit,
    onDismiss: () -> Unit,
) {
    val seasons = remember(detail.seriesId, detail.seasons) { detail.seasons.keys.sorted() }
    var season by remember(detail.seriesId) { mutableIntStateOf(seasons.firstOrNull() ?: 1) }
    val episodes = remember(detail.seriesId, season) {
        detail.seasons[season].orEmpty().distinctBy { it.streamId }.sortedBy { it.episodeNum }
    }
    val firstEpisode = remember(detail.seriesId, season) { FocusRequester() }
    LaunchedEffect(detail.seriesId, season, episodes.firstOrNull()?.streamId) {
        kotlinx.coroutines.delay(140)
        runCatching { firstEpisode.requestFocus() }
    }

    TvModal(onDismiss = onDismiss, alignment = Alignment.Center) {
        Column(
            Modifier
                .width(720.dp)
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1C1C22))
                .padding(28.dp),
        ) {
            Text(detail.name, style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
            if (!detail.plot.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(detail.plot, maxLines = 3, color = TextSecondary)
            }
            Spacer(Modifier.height(16.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons, key = { it }) { number ->
                    Button(onClick = { season = number }) {
                        Text(if (season == number) "● Season $number" else "Season $number")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(episodes, key = { _, ep -> ep.streamId }) { index, ep ->
                    Surface(
                        onClick = { onPlay(ep) },
                        modifier = if (index == 0) Modifier.focusRequester(firstEpisode) else Modifier,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.06f),
                            contentColor = TextPrimary,
                            focusedContainerColor = LocalAccent.current,
                            focusedContentColor = Color.White,
                            pressedContainerColor = LocalAccent.current,
                            pressedContentColor = Color.White,
                        ),
                    ) {
                        Text(
                            "E${ep.episodeNum}  ${ep.title}",
                            modifier = Modifier.padding(14.dp).fillMaxWidth(),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onDismiss) { Text("Close") }
            Text("Press BACK to close", color = TextMuted, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun CatalogLayout(
    categories: List<Category>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    sourceFilter: VodSourceFilter,
    onFilter: (VodSourceFilter) -> Unit,
    emptyMessage: String?,
    canRetry: Boolean = false,
    onRetry: () -> Unit = {},
    searchQuery: String = "",
    onSearch: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VodSourceFilterRow(selected = sourceFilter, onSelect = onFilter)
            Spacer(Modifier.width(8.dp))
            SectionSearchChip(query = searchQuery, onClick = onSearch)
        }
        if (sourceFilter == VodSourceFilter.FREE) {
            Text(
                "Live movie/series channels plus public-domain films from Archive.org.",
                color = TextMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.weight(1f)) {
            LazyColumn(Modifier.width(220.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(categories, key = { it.id }) { cat ->
                    val selected = cat.id == selectedId
                    Surface(
                        onClick = { onSelect(cat.id) },
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (selected) Color.White.copy(alpha = 0.12f) else Color.Transparent,
                            contentColor = TextPrimary,
                            focusedContainerColor = LocalAccent.current,
                            focusedContentColor = Color.White,
                            pressedContainerColor = LocalAccent.current,
                            pressedContentColor = Color.White,
                        ),
                    ) {
                        Row(
                            Modifier.padding(12.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(cat.name, modifier = Modifier.weight(1f), maxLines = 1)
                            if (cat.isFree) FreeBadge(selected)
                        }
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                if (categories.isEmpty() && !emptyMessage.isNullOrBlank()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(emptyMessage, color = TextMuted)
                        if (canRetry) {
                            Button(onClick = onRetry) { Text("Retry") }
                        }
                    }
                } else {
                    content()
                }
            }
        }
    }
}

@Composable
private fun VodSourceFilterRow(
    selected: VodSourceFilter,
    onSelect: (VodSourceFilter) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VodSourceFilter.entries.forEach { filter ->
            val on = filter == selected
            Surface(
                onClick = { onSelect(filter) },
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (on) LocalAccent.current.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f),
                    contentColor = if (on) TextPrimary else TextSecondary,
                    focusedContainerColor = LocalAccent.current,
                    focusedContentColor = Color.White,
                    pressedContainerColor = LocalAccent.current,
                    pressedContentColor = Color.White,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
            ) {
                Text(
                    filter.label,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun PosterCard(
    url: String?,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    onOptions: () -> Unit = {},
    free: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.tvHoldOptions(onOptions),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF17191F),
            contentColor = TextPrimary,
            focusedContainerColor = LocalAccent.current,
            focusedContentColor = Color.White,
            pressedContainerColor = LocalAccent.current,
            pressedContentColor = Color.White,
        ),
    ) {
        Column {
            Box {
                AsyncImage(
                    model = url,
                    contentDescription = title,
                    modifier = Modifier.height(180.dp).fillMaxWidth().background(Color.Black),
                    contentScale = ContentScale.Crop,
                )
                if (free) {
                    FreeBadge(
                        selected = false,
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                    )
                }
            }
            Text(title, modifier = Modifier.padding(8.dp), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, modifier = Modifier.padding(horizontal = 8.dp), color = TextSecondary)
            }
        }
    }
}
