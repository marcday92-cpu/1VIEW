package com.iptv.tv.ui.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.iptv.tv.ui.components.tvDpadLeftTo
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.iptv.tv.domain.model.FeedType
import com.iptv.tv.ui.theme.TextMuted
import com.iptv.tv.ui.theme.TextPrimary
import com.iptv.tv.ui.theme.TextSecondary
import com.iptv.tv.ui.components.TvModal
import com.iptv.tv.ui.components.TvTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box

@Composable
fun CategoriesScreen(
    viewModel: CategoriesViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
    firstFocus: FocusRequester? = null,
    exitLeft: FocusRequester? = null,
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val feed by viewModel.selectedFeed.collectAsStateWithLifecycle()
    var renameItem by remember { mutableStateOf<EditableCategory?>(null) }
    var renameText by remember { mutableStateOf("") }
    var combineItem by remember { mutableStateOf<EditableCategory?>(null) }
    Column(modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Rename, hide, show or reorder locally. New provider categories automatically appear in source order. Adult lists stay at the bottom.",
            color = TextSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                FeedType.LIVE to "Live",
                FeedType.VOD to "Movies",
                FeedType.SERIES to "Series",
            ).forEachIndexed { index, (type, label) ->
                Button(
                    onClick = { viewModel.selectFeed(type) },
                    modifier = if (index == 0 && firstFocus != null) {
                        Modifier
                            .focusRequester(firstFocus)
                            .then(if (exitLeft != null) Modifier.tvDpadLeftTo(exitLeft) else Modifier)
                    } else {
                        Modifier
                    },
                ) {
                    Text(if (feed == type) "● $label" else label)
                }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f, fill = true),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.category.id }) { item ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp)),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            item.displayName + if (item.pinned) "  · Pinned" else "",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                        )
                        if (item.hidden) Text("Hidden", color = TextMuted)
                        if (!item.mergedIntoId.isNullOrBlank()) {
                            val target = items.find { it.category.id == item.mergedIntoId }?.displayName
                                ?: item.mergedIntoId
                            Text("Combined into $target", color = TextMuted)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.hide(item, !item.hidden) }) {
                                Text(if (item.hidden) "Show" else "Hide")
                            }
                            Button(onClick = { viewModel.pin(item, !item.pinned) }) {
                                Text(if (item.pinned) "Unpin" else "Pin")
                            }
                            Button(onClick = {
                                renameItem = item
                                renameText = item.displayName
                            }) { Text("Rename") }
                            if (item.mergedIntoId.isNullOrBlank()) {
                                Button(onClick = { combineItem = item }) { Text("Combine") }
                            } else {
                                Button(onClick = { viewModel.uncombine(item) }) { Text("Uncombine") }
                            }
                            Button(onClick = { viewModel.move(item, -1) }) { Text("Up") }
                            Button(onClick = { viewModel.move(item, 1) }) { Text("Down") }
                        }
                    }
                }
            }
        }
    }

    renameItem?.let { item ->
        TvModal(onDismiss = { renameItem = null }) {
            Column(
                Modifier.width(500.dp).background(Color(0xFF1C1C22), RoundedCornerShape(16.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Rename category", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                TvTextField(renameText, { renameText = it }, "Category name")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val name = renameText.trim()
                        if (name.isNotBlank()) viewModel.rename(item, name)
                        renameItem = null
                    }) { Text("Save") }
                    Button(onClick = { renameItem = null }) { Text("Cancel") }
                }
            }
        }
    }

    combineItem?.let { item ->
        TvModal(onDismiss = { combineItem = null }) {
            Column(
                Modifier.width(500.dp).background(Color(0xFF1C1C22), RoundedCornerShape(16.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Combine ${item.displayName} into", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                Text("Channels from this list will appear under the group you pick.", color = TextMuted)
                items.filter { it.category.id != item.category.id && it.mergedIntoId.isNullOrBlank() }.forEach { target ->
                    Button(onClick = {
                        viewModel.combineInto(item, target.category.id)
                        combineItem = null
                    }) { Text(target.displayName) }
                }
                Button(onClick = { combineItem = null }) { Text("Cancel") }
            }
        }
    }
}
