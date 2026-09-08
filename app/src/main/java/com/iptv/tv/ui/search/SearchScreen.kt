package com.iptv.tv.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.iptv.tv.ui.catalog.SeriesDetailModal
import com.iptv.tv.ui.components.TvModal
import com.iptv.tv.ui.components.TvTextField
import com.iptv.tv.ui.theme.TextMuted
import com.iptv.tv.ui.theme.TextPrimary
import com.iptv.tv.ui.theme.TextSecondary

@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    pendingVoice: VoiceSearchRequest? = null,
    onVoiceConsumed: () -> Unit = {},
    openTypeBoxNonce: Int = 0,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var typing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    fun openTypeBox(seed: String = state.query) {
        draft = seed
        typing = true
    }

    LaunchedEffect(pendingVoice) {
        val request = pendingVoice ?: return@LaunchedEffect
        if (!request.seedQuery.isNullOrBlank()) {
            viewModel.updateQuery(request.seedQuery)
            draft = request.seedQuery
        }
        onVoiceConsumed()
    }

    // Only a *new* request opens the keyboard; returning to this tab must not re-open it.
    var handledNonce by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(openTypeBoxNonce) {
        if (openTypeBoxNonce > handledNonce) {
            handledNonce = openTypeBoxNonce
            openTypeBox()
        }
    }

    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Search", style = MaterialTheme.typography.displaySmall, color = TextPrimary)
        Button(onClick = { openTypeBox() }) {
            Text(
                if (state.query.isBlank()) "Type a search"
                else state.query,
            )
        }
        when {
            state.status.isNotBlank() -> Text(state.status, color = TextSecondary)
            state.loading -> Text("Searching…", color = TextMuted)
            state.query.isBlank() -> Text(
                "Channels, programmes, movies, series and recordings in one place. Type with the remote keyboard.",
                color = TextMuted,
            )
            else -> Text("${state.results.size} result${if (state.results.size == 1) "" else "s"}", color = TextMuted)
        }
        val ranked = state.results
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ranked, key = { it.id + it.type.name }) { result ->
                Surface(onClick = { viewModel.open(result) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(result.title, color = TextPrimary)
                        Text(
                            listOfNotNull(result.type.label, result.subtitle).joinToString(" · "),
                            color = TextSecondary,
                        )
                    }
                }
            }
        }
    }

    if (typing) {
        TvModal(onDismiss = { typing = false }) {
            val fieldFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(80)
                runCatching { fieldFocus.requestFocus() }
            }
            Column(
                Modifier
                    .width(560.dp)
                    .background(Color(0xFF1C1C22), RoundedCornerShape(16.dp))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Search", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                TvTextField(
                    draft,
                    { draft = it },
                    "Channels, programmes, movies, series, recordings",
                    focusRequester = fieldFocus,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        viewModel.updateQuery(draft.trim())
                        typing = false
                    }) { Text("Search") }
                    Button(onClick = { typing = false }) { Text("Cancel") }
                }
            }
        }
    }

    state.seriesDetail?.let { detail ->
        SeriesDetailModal(
            detail = detail,
            onPlay = { viewModel.playEpisode(it, detail) },
            onDismiss = viewModel::dismissSeries,
        )
    }
}
