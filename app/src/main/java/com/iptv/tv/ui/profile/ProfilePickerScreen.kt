package com.iptv.tv.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.iptv.tv.domain.model.Profile
import com.iptv.tv.ui.components.TvModal
import com.iptv.tv.ui.components.TvTextField
import com.iptv.tv.ui.components.tvHoldOptions
import com.iptv.tv.ui.theme.Charcoal
import com.iptv.tv.ui.theme.LocalAccent
import com.iptv.tv.ui.theme.TextMuted

@Composable
fun ProfilePickerScreen(
    profiles: List<Profile>,
    onSelect: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Long, String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Profile?>(null) }
    var renameText by remember { mutableStateOf("") }
    var skipSelect by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Charcoal)
            .padding(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("Who's watching?", style = MaterialTheme.typography.displaySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                profiles.forEach { profile ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            onClick = {
                                if (skipSelect || renaming != null) {
                                    skipSelect = false
                                } else {
                                    onSelect(profile.id)
                                }
                            },
                            modifier = Modifier.tvHoldOptions {
                                skipSelect = true
                                renaming = profile
                                renameText = profile.name
                            },
                            shape = ClickableSurfaceDefaults.shape(CircleShape),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(120.dp)
                                    .clip(CircleShape)
                                    .background(Color(profile.avatarColor)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    profile.initials,
                                    style = MaterialTheme.typography.displaySmall,
                                )
                            }
                        }
                        Text(profile.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                Surface(
                    onClick = { creating = true },
                    shape = ClickableSurfaceDefaults.shape(CircleShape),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("+", style = MaterialTheme.typography.displaySmall)
                    }
                }
            }
            Text("Hold OK or press Menu on a profile to rename it.", color = TextMuted)
        }
    }

    if (creating) {
        TvModal(onDismiss = { creating = false }) {
            Column(
                Modifier.width(500.dp).background(Color(0xFF1C1C22), RoundedCornerShape(16.dp))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Add profile", style = MaterialTheme.typography.headlineSmall)
                TvTextField(createName, { createName = it }, "Profile name")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (createName.isNotBlank()) {
                                onCreate(createName.trim())
                                creating = false
                                createName = ""
                            }
                        },
                        colors = ButtonDefaults.colors(focusedContainerColor = LocalAccent.current),
                    ) { Text("Add") }
                    Button(onClick = { creating = false }) { Text("Cancel") }
                }
            }
        }
    }

    renaming?.let { profile ->
        TvModal(onDismiss = { renaming = null }) {
            Column(
                Modifier.width(500.dp).background(Color(0xFF1C1C22), RoundedCornerShape(16.dp))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Rename profile", style = MaterialTheme.typography.headlineSmall)
                TvTextField(renameText, { renameText = it }, "Profile name")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (renameText.isNotBlank()) {
                                onRename(profile.id, renameText.trim())
                                renaming = null
                            }
                        },
                        colors = ButtonDefaults.colors(focusedContainerColor = LocalAccent.current),
                    ) { Text("Save") }
                    Button(onClick = { renaming = null }) { Text("Cancel") }
                }
            }
        }
    }
}
