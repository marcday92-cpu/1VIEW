package com.iptv.tv.ui.recordings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.tv.material3.Text
import com.iptv.tv.util.formatDay
import com.iptv.tv.data.db.RecordingEntity
import com.iptv.tv.ui.components.TvModal
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width

@Composable
fun RecordingsScreen(viewModel: RecordingsViewModel = hiltViewModel()) {
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val scheduled by viewModel.scheduled.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<RecordingEntity?>(null) }
    LazyColumn(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("RECORDINGS", style = MaterialTheme.typography.displaySmall) }
        item { Text("Scheduled", color = Color.White.copy(alpha = 0.5f)) }
        items(scheduled, key = { "scheduled_${it.id}" }) { item ->
                Row(
                    Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp)).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title)
                        Text("${item.channelName}  ${formatDay(item.startTimeMs)}", color = Color.White.copy(alpha = 0.6f))
                    }
                    Button(onClick = { viewModel.cancel(item) }) { Text("Cancel") }
                }
        }
        item { Text("Recent", color = Color.White.copy(alpha = 0.5f)) }
        items(recordings, key = { "recording_${it.id}" }) { item ->
                    Row(
                        Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp)).padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title)
                            Text("${item.channelName} · ${item.status}", color = Color.White.copy(alpha = 0.6f))
                        }
                        Button(onClick = { viewModel.play(item) }) { Text("Play") }
                        Button(onClick = { viewModel.protect(item) }) {
                            Text(if (item.protectedFromAutoDelete) "Protected" else "Protect")
                        }
                        Button(onClick = { pendingDelete = item }) { Text("Delete") }
                    }
        }
    }

    pendingDelete?.let { item ->
        val deleteFocus = remember(item.id) { FocusRequester() }
        LaunchedEffect(item.id) {
            runCatching { deleteFocus.requestFocus() }
        }
        TvModal(onDismiss = { pendingDelete = null }) {
            Column(
                Modifier.width(460.dp).background(Color(0xFF1C1C22), RoundedCornerShape(16.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Delete recording?", style = MaterialTheme.typography.headlineSmall)
                Text(item.title)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.delete(item); pendingDelete = null },
                        modifier = Modifier.focusRequester(deleteFocus),
                    ) { Text("Delete") }
                    Button(onClick = { pendingDelete = null }) { Text("Cancel") }
                }
            }
        }
    }
}
