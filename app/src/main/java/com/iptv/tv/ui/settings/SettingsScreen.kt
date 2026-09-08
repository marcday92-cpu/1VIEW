package com.iptv.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.iptv.tv.BuildConfig
import com.iptv.tv.domain.model.AppFeature
import com.iptv.tv.ui.categories.CategoriesScreen
import com.iptv.tv.ui.components.TvModal
import com.iptv.tv.ui.components.TvTextField
import com.iptv.tv.ui.components.tvDpadLeftTo
import com.iptv.tv.ui.components.tvDpadRightTo
import com.iptv.tv.ui.theme.LocalAccent
import com.iptv.tv.ui.theme.TextMuted
import com.iptv.tv.ui.theme.TextPrimary
import com.iptv.tv.ui.theme.TextSecondary
import com.iptv.tv.util.formatBytes
import kotlinx.coroutines.delay

private enum class SettingsSection(val label: String) {
    GENERAL("General"),
    PLAYBACK("Playback & Subtitles"),
    LIVE("Live TV & Guide"),
    LISTS("Customise lists"),
    FEATURES("Manage features"),
    CONNECTION("Connection & Data"),
    VPN("VPN"),
    PIN("PIN & Adult Content"),
    ABOUT("About"),
}

@Composable
fun SettingsScreen(
    onLogout: () -> Unit,
    onLoggedIn: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var section by remember { mutableStateOf(SettingsSection.GENERAL) }
    val accent by viewModel.accent.collectAsStateWithLifecycle()
    val epgDays by viewModel.epgDays.collectAsStateWithLifecycle()
    val openOnStartup by viewModel.openOnStartup.collectAsStateWithLifecycle()
    val autoSync by viewModel.autoSyncMode.collectAsStateWithLifecycle()
    val adultHidden by viewModel.adultHidden.collectAsStateWithLifecycle()
    val activeProfileName by viewModel.activeProfileName.collectAsStateWithLifecycle()
    val subtitleLanguage by viewModel.subtitleLanguage.collectAsStateWithLifecycle()
    val launchLast by viewModel.launchLast.collectAsStateWithLifecycle()
    val startTab by viewModel.startTab.collectAsStateWithLifecycle()
    val askProfile by viewModel.askProfile.collectAsStateWithLifecycle()
    val playerPreference by viewModel.playerPreference.collectAsStateWithLifecycle()
    val recStartBuffer by viewModel.recStartBuffer.collectAsStateWithLifecycle()
    val recEndBuffer by viewModel.recEndBuffer.collectAsStateWithLifecycle()
    val preferExternal by viewModel.preferExternal.collectAsStateWithLifecycle()
    val audioLanguage by viewModel.audioLanguage.collectAsStateWithLifecycle()
    val autoplayNext by viewModel.autoplayNext.collectAsStateWithLifecycle()
    val timeshiftWhileLive by viewModel.timeshiftWhileLive.collectAsStateWithLifecycle()
    val pinSet by viewModel.pinSet.collectAsStateWithLifecycle()
    val adultUnlocked by viewModel.adultUnlocked.collectAsStateWithLifecycle()
    val pinStatus by viewModel.pinStatus.collectAsStateWithLifecycle()
    val dataStatus by viewModel.dataStatus.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val storageOptions by viewModel.storageOptions.collectAsStateWithLifecycle()
    val disabledFeatures by viewModel.disabledFeatures.collectAsStateWithLifecycle()
    val vpn by viewModel.vpn.collectAsStateWithLifecycle()
    val recordingPath by viewModel.recordingStoragePath.collectAsStateWithLifecycle()
    var groupName by remember { mutableStateOf("") }
    var renamingProfile by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var creatingGroupModal by remember { mutableStateOf(false) }
    var pinEntry by remember { mutableStateOf<PinAction?>(null) }
    var pinDigits by remember { mutableStateOf("") }
    /** For Change PIN: the verified current PIN while the new one is typed. */
    var pinCurrent by remember { mutableStateOf<String?>(null) }
    val categoryFocus = remember { FocusRequester() }
    val paneFocus = remember { FocusRequester() }
    val accentColor = LocalAccent.current
    val liveEnabled = AppFeature.LIVE !in disabledFeatures

    LaunchedEffect(Unit) {
        delay(50)
        runCatching { categoryFocus.requestFocus() }
    }
    LaunchedEffect(connected) {
        if (connected) onLoggedIn()
    }

    /** The first control of every section: D-pad RIGHT from the menu lands here, LEFT goes back. */
    val firstControl = Modifier.focusRequester(paneFocus).tvDpadLeftTo(categoryFocus)

    Row(Modifier.fillMaxSize().padding(32.dp)) {
        LazyColumn(Modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsSection.entries.forEach { item ->
                item {
                    Surface(
                        onClick = { section = item },
                        modifier = Modifier
                            .then(if (section == item) Modifier.focusRequester(categoryFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) section = item }
                            .tvDpadRightTo(paneFocus),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (section == item) Color.White.copy(alpha = 0.12f) else Color.Transparent,
                            contentColor = if (section == item) TextPrimary else TextSecondary,
                            focusedContainerColor = accentColor,
                            focusedContentColor = Color.White,
                        ),
                    ) {
                        Text(item.label, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
        val contentModifier = Modifier.weight(1f).padding(start = 32.dp).let { base ->
            if (section == SettingsSection.LISTS) base else base.verticalScroll(rememberScrollState())
        }
        Column(contentModifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(section.label, style = MaterialTheme.typography.headlineSmall)
            when (section) {
                SettingsSection.GENERAL -> {
                    Text(
                        if (activeProfileName.isBlank()) "Local profiles keep favourites, history and subtitle style separate."
                        else "You are using $activeProfileName. Favourites, hidden channels, custom groups, category order, continue watching and captions stay on this profile.",
                    )
                    Button(
                        onClick = {
                            renameText = activeProfileName
                            renamingProfile = true
                        },
                        modifier = firstControl,
                    ) { Text("Rename this profile") }
                    Text(
                        "Switch or add profiles from the circle at the top left. Hold OK on a profile to rename it.",
                        color = TextMuted,
                    )
                    ToggleRow("Ask who's watching at start", askProfile) { viewModel.setAskProfile(!askProfile) }
                    Text("Off uses the last profile automatically.", color = TextMuted)
                    ToggleRow("Open 1VIEW when the Fire Stick starts", openOnStartup) {
                        viewModel.setOpenOnStartup(!openOnStartup)
                    }
                    if (liveEnabled) {
                        ToggleRow("Resume last channel on start", launchLast) { viewModel.setLaunchLast(!launchLast) }
                        Button(onClick = { viewModel.setStartTab(if (startTab == "live") "home" else "live") }) {
                            Text(if (startTab == "live") "Start on: Live" else "Start on: Home")
                        }
                    }
                    Text("Accent colour", style = MaterialTheme.typography.titleMedium)
                    val colors = listOf(0xFFC9A227, 0xFF3D8BFF, 0xFF8B5CF6, 0xFF2EE59D)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        colors.forEach { c ->
                            Surface(
                                onClick = { viewModel.setAccent(c) },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = if (accent == c) Color.White.copy(alpha = 0.14f) else Color.Transparent,
                                    focusedContainerColor = Color.White.copy(alpha = 0.22f),
                                ),
                            ) {
                                Box(
                                    Modifier
                                        .padding(10.dp)
                                        .width(44.dp)
                                        .background(Color(c), RoundedCornerShape(8.dp))
                                        .padding(vertical = 22.dp),
                                )
                            }
                        }
                    }
                    Text("Takes effect straight away and is saved on this profile.", color = TextMuted)
                }
                SettingsSection.PLAYBACK -> {
                    ToggleRow("Autoplay next episode", autoplayNext, firstControl) {
                        viewModel.setAutoplayNext(!autoplayNext)
                    }
                    Text(
                        "When on, the next episode starts after a 5-second countdown. Cancel or BACK stops it.",
                        color = TextMuted,
                    )
                    Text("Subtitles", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { viewModel.cycleSubtitleLanguage() }) {
                        Text("Caption language: ${SettingsViewModel.subtitleLanguageLabel(subtitleLanguage)}")
                    }
                    ToggleRow("Auto captions and sync", autoSync != "off") {
                        viewModel.setAutoSync(if (autoSync == "off") "on" else "off")
                    }
                    Text(
                        "When on, captions turn on for movies and series. The stream's own track is used if it has one; otherwise a file is downloaded from SubDL and lined up with the soundtrack. Not used on live TV.",
                        color = TextMuted,
                    )
                    ToggleRow("Prefer downloaded captions over embedded", preferExternal) {
                        viewModel.setPreferExternal(!preferExternal)
                    }
                    Button(onClick = { viewModel.cycleAudioLanguage() }) {
                        Text("Preferred audio: ${SettingsViewModel.subtitleLanguageLabel(audioLanguage)}")
                    }
                    Text("Player engine", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { viewModel.cyclePlayerPreference() }) {
                        Text(
                            when (playerPreference) {
                                "MEDIA3" -> "Player: Media3 only"
                                "VLC" -> "Player: VLC only"
                                else -> "Player: Automatic (recommended)"
                            },
                        )
                    }
                    Text(
                        "Automatic uses Media3 for IPTV streams and VLC for free channels and Archive.org films, switching engines by itself when one fails. Change this only if a stream is awkward; the channel menu can also force an engine per stream. Takes effect on the next stream you start.",
                        color = TextMuted,
                    )
                }
                SettingsSection.LIVE -> {
                    if (!liveEnabled) {
                        Text("Live TV is switched off under Manage features.", color = TextMuted)
                    }
                    Text("Guide", style = MaterialTheme.typography.titleMedium)
                    Button(
                        onClick = { viewModel.setEpgDays(if (epgDays >= 7) 3 else epgDays + 1) },
                        modifier = firstControl,
                    ) {
                        Text("Keep guide data for: $epgDays days")
                    }
                    Text("Applies to the next guide refresh. More days means a bigger download.", color = TextMuted)
                    ToggleRow("Restart live programmes from the start", timeshiftWhileLive) {
                        viewModel.setTimeshiftWhileLive(!timeshiftWhileLive)
                    }
                    Text(
                        "Off by default. Catch-up is for programmes that have already finished. Only turn this on if your IPTV provider can timeshift a show that is still on air.",
                        color = TextMuted,
                    )
                    Text("Custom groups", style = MaterialTheme.typography.titleMedium)
                    Text("Groups appear as their own rails on Live. Hold OK on a channel to add it to a group.", color = TextMuted)
                    groups.forEach { group ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                group.name + if (group.pinned) "  · Pinned" else "",
                                color = TextMuted,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                            Button(onClick = { viewModel.pinGroup(group.id, !group.pinned) }) {
                                Text(if (group.pinned) "Unpin" else "Pin")
                            }
                            Button(onClick = { viewModel.deleteGroup(group.id) }) { Text("Delete") }
                        }
                    }
                    Button(onClick = {
                        groupName = ""
                        creatingGroupModal = true
                    }) { Text("Create custom group") }
                    if (connected && AppFeature.RECORDINGS !in disabledFeatures) {
                        Text("Recording", style = MaterialTheme.typography.titleMedium)
                        Text("Where recordings are saved:", color = TextMuted)
                        storageOptions.forEachIndexed { index, opt ->
                            val selected = opt.path == (recordingPath ?: storageOptions.firstOrNull()?.path)
                            Surface(
                                onClick = { viewModel.setStoragePath(opt.path) },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = if (selected) accentColor.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.06f),
                                    contentColor = TextPrimary,
                                    focusedContainerColor = accentColor,
                                    focusedContentColor = Color.White,
                                ),
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(if (selected) "✓  ${opt.label}" else opt.label)
                                    Text("${formatBytes(opt.freeBytes)} free", color = TextMuted)
                                }
                            }
                        }
                        Button(onClick = { viewModel.cycleRecStart() }) {
                            Text("Start recording early: $recStartBuffer min")
                        }
                        Button(onClick = { viewModel.cycleRecEnd() }) {
                            Text("End recording late: $recEndBuffer min")
                        }
                    }
                }
                SettingsSection.LISTS -> {
                    Text("Hide, pin, rename and combine categories for Live, Movies and Series. Changes apply straight away.", color = TextMuted)
                    CategoriesScreen(
                        modifier = Modifier.weight(1f),
                        firstFocus = paneFocus,
                        exitLeft = categoryFocus,
                    )
                }
                SettingsSection.FEATURES -> {
                    Text(
                        "Switch off the parts of 1VIEW you don't use. Home and Settings always stay. Your favourites, history and logins are kept even while a section is off.",
                        color = TextMuted,
                    )
                    AppFeature.entries.forEachIndexed { index, feature ->
                        val explicitlyOff = feature in disabledFeatures
                        val blockedByLive = feature.needsLive && !liveEnabled
                        val on = !explicitlyOff && !blockedByLive
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            ToggleRow(
                                label = feature.label,
                                on = on,
                                modifier = if (index == 0) firstControl else Modifier,
                                enabled = !blockedByLive,
                            ) { viewModel.setFeatureEnabled(feature, !on) }
                            Text(
                                if (blockedByLive) "${feature.description} Turn Live TV on to use it." else feature.description,
                                color = TextMuted,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
                SettingsSection.CONNECTION -> {
                    val loginError by viewModel.loginError.collectAsStateWithLifecycle()
                    val loginBusy by viewModel.loginBusy.collectAsStateWithLifecycle()
                    if (connected) {
                        val usage by viewModel.lineUsage.collectAsStateWithLifecycle()
                        DisposableEffect(Unit) {
                            viewModel.watchLineUsage(true)
                            onDispose { viewModel.watchLineUsage(false) }
                        }
                        Text("Connected to ${viewModel.providerLabel()}.")
                        Text(
                            when {
                                usage == null -> "Streams in use: checking… (line allows ${viewModel.maxConnections()})"
                                usage?.active == null -> "Line allows ${usage?.max} stream${if (usage?.max == 1) "" else "s"} at a time."
                                else -> "Streams in use now: ${usage?.active} of ${usage?.max}, as counted by the provider. Refreshes every 10 seconds."
                            },
                            color = TextMuted,
                        )
                        Button(
                            onClick = {
                                viewModel.onLoggedOut()
                                onLogout()
                            },
                            modifier = firstControl,
                        ) { Text("Disconnect IPTV") }
                    } else {
                        var customServer by remember { mutableStateOf(false) }
                        var server by remember { mutableStateOf("") }
                        var username by remember { mutableStateOf("") }
                        var password by remember { mutableStateOf("") }
                        Text("Free Live TV is already on. Sign in to EDTV for your extra Live, Movies and Series.")
                        if (customServer) {
                            TvTextField(server, { server = it }, "Server URL")
                        }
                        TvTextField(
                            username,
                            { username = it },
                            "Username",
                            firstControl,
                        )
                        TvTextField(password, { password = it }, "Password", password = true)
                        loginError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(
                            onClick = {
                                if (customServer) viewModel.connectProvider(server, username, password)
                                else viewModel.connectEdtv(username, password)
                            },
                            enabled = !loginBusy,
                        ) {
                            Text(
                                if (loginBusy) "Connecting…"
                                else if (customServer) "Connect IPTV"
                                else "Connect EDTV",
                            )
                        }
                        Button(onClick = { customServer = !customServer }) {
                            Text(if (customServer) "Use EDTV" else "Add a different IPTV server")
                        }
                    }
                    if (connected) {
                        val extraLines by viewModel.extraLines.collectAsStateWithLifecycle()
                        val extraError by viewModel.extraLineError.collectAsStateWithLifecycle()
                        val extraBusy by viewModel.extraLineBusy.collectAsStateWithLifecycle()
                        val lineSharing by viewModel.lineSharing.collectAsStateWithLifecycle()
                        Text("Lines for Multi", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Other logins on the same server, such as a friend's line. When switched on, Multi puts each extra IPTV screen on a line the provider shows as idle and gives it back when its owner starts watching. Your main line always carries the screen with sound.",
                            color = TextMuted,
                        )
                        ToggleRow(
                            label = "Use these lines in Multi",
                            on = lineSharing,
                            onToggle = { viewModel.setLineSharing(!lineSharing) },
                        )
                        extraLines.forEach { row ->
                            val status = row.status
                            val text = when {
                                !row.checked -> "checking…"
                                status == null -> "not reachable"
                                !status.valid -> "login rejected"
                                !status.status.equals("Active", ignoreCase = true) -> status.status.lowercase()
                                (status.active ?: 0) == 0 -> "idle, ${status.max} stream${if (status.max == 1) "" else "s"} allowed"
                                else -> "in use by its owner (${status.active} of ${status.max})"
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.line.label)
                                    Text(text, color = TextMuted, fontSize = 13.sp)
                                }
                                Button(onClick = { viewModel.removeExtraLine(row.line.id) }) { Text("Remove") }
                            }
                        }
                        var lineLabel by remember { mutableStateOf("") }
                        var lineUser by remember { mutableStateOf("") }
                        var linePass by remember { mutableStateOf("") }
                        TvTextField(lineLabel, { lineLabel = it }, "Name, e.g. Bob's line")
                        TvTextField(lineUser, { lineUser = it }, "Username")
                        TvTextField(linePass, { linePass = it }, "Password", password = true)
                        extraError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(
                            onClick = {
                                viewModel.addExtraLine(lineLabel, lineUser, linePass)
                                if (extraError == null) { lineLabel = ""; lineUser = ""; linePass = "" }
                            },
                            enabled = !extraBusy,
                        ) { Text(if (extraBusy) "Checking…" else "Add line") }
                    }
                    Text("Refresh & cache", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { viewModel.refreshIptv() }) { Text("Refresh IPTV catalogue") }
                    if (connected && liveEnabled) Button(onClick = { viewModel.refreshEpg() }) { Text("Refresh guide (EPG)") }
                    Button(onClick = { viewModel.clearLocalCache() }) { Text("Clear local cache & reload") }
                    if (AppFeature.WEB !in disabledFeatures) {
                        Button(onClick = { viewModel.clearBrowserData() }) { Text("Clear browser data") }
                        Text(
                            "Clearing the IPTV cache does not log you out of websites. Browser data is separate.",
                            color = TextMuted,
                        )
                    }
                    if (dataStatus.isNotBlank()) Text(dataStatus, color = TextSecondary)
                }
                SettingsSection.VPN -> {
                    Text(
                        when {
                            vpn.tunnelActive -> "VPN: connected (Android reports a VPN network)"
                            vpn.surfsharkInstalled -> "VPN: not connected"
                            else -> "VPN: not set up"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (vpn.tunnelActive) accentColor else TextPrimary,
                    )
                    Text(
                        "VPN connections are managed by the Surfshark app, not by 1VIEW. Connect or disconnect there and come back; 1VIEW keeps working either way and reconnects streams when the network changes.",
                        color = TextMuted,
                    )
                    if (vpn.surfsharkInstalled) {
                        Button(onClick = { viewModel.openSurfshark() }, modifier = firstControl) { Text("Open Surfshark") }
                    } else {
                        Button(onClick = { viewModel.getSurfshark() }, modifier = firstControl) { Text("Get Surfshark from the Appstore") }
                    }
                    Text(
                        "The status above is what Android reports about the active network. 1VIEW cannot tell which VPN app or server is in use.",
                        color = TextMuted,
                        fontSize = 13.sp,
                    )
                    if (dataStatus.isNotBlank()) Text(dataStatus, color = TextSecondary)
                }
                SettingsSection.PIN -> {
                    Text(
                        "A 4–8 digit PIN hides adult lists from Live, Movies, Series and Search. Unlock lasts until you lock again, change profile, or restart the app.",
                        color = TextMuted,
                    )
                    if (!pinSet) {
                        Button(
                            onClick = { pinDigits = ""; pinEntry = PinAction.CREATE },
                            modifier = firstControl,
                        ) { Text("Create PIN") }
                    } else {
                        Button(
                            onClick = { pinDigits = ""; pinEntry = PinAction.UNLOCK },
                            modifier = firstControl,
                        ) { Text(if (adultUnlocked) "Unlocked this session" else "Unlock adult lists") }
                        Button(onClick = { viewModel.lockAdult() }) { Text("Lock now") }
                        Button(onClick = { pinDigits = ""; pinCurrent = null; pinEntry = PinAction.CHANGE }) { Text("Change PIN") }
                        Button(onClick = { pinDigits = ""; pinEntry = PinAction.REMOVE }) { Text("Remove PIN") }
                    }
                    if (viewModel.adultToggleAvailable) {
                        ToggleRow("Hide adult lists even when unlocked", adultHidden) {
                            viewModel.setAdultHidden(!adultHidden)
                        }
                    }
                    if (pinStatus.isNotBlank()) Text(pinStatus, color = TextSecondary)
                }
                SettingsSection.ABOUT -> {
                    Text("1VIEW · ${viewModel.edition.displayName}", style = MaterialTheme.typography.titleMedium)
                    Text("Version ${viewModel.versionLabel}", color = TextSecondary)
                    Text(
                        when (BuildConfig.EDITION) {
                            "deetv" -> "This build includes the DeeTV shortcut in the Web tab and Multi view."
                            else -> "This build includes a standard web browser in the Web tab and Multi view."
                        },
                        color = TextMuted,
                    )
                    Text(
                        "Live TV playback uses Media3 (ExoPlayer) and libVLC. Free channels come from iptv-org; free films from Archive.org. Online subtitles are powered by SubDL.",
                        color = TextMuted,
                    )
                    val updateStatus by viewModel.updateStatus.collectAsStateWithLifecycle()
                    val updateAvailable by viewModel.updateAvailable.collectAsStateWithLifecycle()
                    val updateProgress by viewModel.updateProgress.collectAsStateWithLifecycle()
                    if (updateStatus.isNotBlank()) Text(updateStatus, color = TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { viewModel.checkForUpdates() }, modifier = firstControl) {
                            Text("Check for updates")
                        }
                        if (updateAvailable != null) {
                            Button(onClick = { viewModel.installUpdate() }, enabled = updateProgress == null) {
                                Text(if (updateProgress != null) "Downloading $updateProgress%" else "Install ${updateAvailable?.version?.versionName}")
                            }
                        }
                    }
                    Button(onClick = { section = SettingsSection.FEATURES }) {
                        Text("Manage features")
                    }
                }
            }
        }
    }

    if (renamingProfile) {
        TvModal(onDismiss = { renamingProfile = false }) {
            Column(
                Modifier.width(500.dp).background(Color(0xFF1C1C22), RoundedCornerShape(16.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Rename profile", style = MaterialTheme.typography.headlineSmall)
                TvTextField(renameText, { renameText = it }, "Profile name")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (renameText.isNotBlank()) {
                            viewModel.renameActiveProfile(renameText.trim())
                            renamingProfile = false
                        }
                    }) { Text("Save") }
                    Button(onClick = { renamingProfile = false }) { Text("Cancel") }
                }
            }
        }
    }
    if (creatingGroupModal) {
        TvModal(onDismiss = { creatingGroupModal = false }) {
            Column(
                Modifier.width(500.dp).background(Color(0xFF1C1C22), RoundedCornerShape(16.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("New group", style = MaterialTheme.typography.headlineSmall)
                TvTextField(groupName, { groupName = it }, "Group name")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (groupName.isNotBlank()) {
                            viewModel.createGroup(groupName)
                            groupName = ""
                            creatingGroupModal = false
                        }
                    }) { Text("Save") }
                    Button(onClick = {
                        groupName = ""
                        creatingGroupModal = false
                    }) { Text("Cancel") }
                }
            }
        }
    }
    pinEntry?.let { action ->
        TvModal(onDismiss = { pinEntry = null; pinDigits = "" }) {
            Column(
                Modifier.width(420.dp).background(Color(0xFF1C1C22), RoundedCornerShape(16.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    when (action) {
                        PinAction.CREATE -> "New PIN"
                        PinAction.CHANGE -> if (pinCurrent == null) "Enter current PIN" else "New PIN"
                        PinAction.UNLOCK -> "Enter PIN"
                        PinAction.REMOVE -> "Enter current PIN"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text("•".repeat(pinDigits.length).ifBlank { " " }, color = TextPrimary)
                listOf("123", "456", "789", "0⌫").forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { ch ->
                            Button(onClick = {
                                when (ch) {
                                    '⌫' -> if (pinDigits.isNotEmpty()) pinDigits = pinDigits.dropLast(1)
                                    else -> if (pinDigits.length < 8) pinDigits += ch
                                }
                            }) { Text(ch.toString()) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        when (action) {
                            PinAction.CREATE -> viewModel.createPin(pinDigits)
                            PinAction.CHANGE -> {
                                val current = pinCurrent
                                if (current == null) {
                                    // First step: remember the current PIN, then ask for the new one.
                                    pinCurrent = pinDigits
                                    pinDigits = ""
                                    return@Button
                                }
                                viewModel.changePin(current, pinDigits)
                            }
                            PinAction.UNLOCK -> viewModel.unlockAdult(pinDigits)
                            PinAction.REMOVE -> viewModel.removePin(pinDigits)
                        }
                        pinEntry = null
                        pinDigits = ""
                        pinCurrent = null
                    }) { Text("OK") }
                    Button(onClick = { pinEntry = null; pinDigits = ""; pinCurrent = null }) { Text("Cancel") }
                }
            }
        }
    }
}

/** A setting that is either on or off. The state is part of the label so it reads from the sofa. */
@Composable
private fun ToggleRow(
    label: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onToggle: () -> Unit,
) {
    val accent = LocalAccent.current
    Surface(
        onClick = onToggle,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(0.8f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.06f),
            contentColor = TextPrimary,
            focusedContainerColor = accent,
            focusedContentColor = Color.White,
            pressedContainerColor = accent,
            pressedContentColor = Color.White,
            disabledContainerColor = Color.White.copy(alpha = 0.03f),
            disabledContentColor = TextMuted,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, modifier = Modifier.weight(1f))
            Text(
                if (on) "ON" else "OFF",
                color = if (!enabled) TextMuted else if (on) Color.White else TextSecondary,
                modifier = Modifier
                    .background(
                        if (on && enabled) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f),
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
    }
}

private enum class PinAction { CREATE, CHANGE, UNLOCK, REMOVE }
