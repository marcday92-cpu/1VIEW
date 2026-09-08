package com.iptv.tv.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.BuildConfig
import com.iptv.tv.data.credentials.CredentialsStore
import com.iptv.tv.data.network.VpnAccess
import com.iptv.tv.data.network.VpnStatus
import com.iptv.tv.data.parental.ParentalLock
import com.iptv.tv.data.preferences.AppPreferences
import com.iptv.tv.data.repository.EpgRepository
import com.iptv.tv.data.repository.IptvRepository
import com.iptv.tv.data.repository.ProfileRepository
import com.iptv.tv.data.storage.CacheCleaner
import com.iptv.tv.data.storage.RecordingStorage
import com.iptv.tv.data.storage.StorageOption
import com.iptv.tv.domain.model.AppFeature
import com.iptv.tv.domain.model.CategoryLayout
import com.iptv.tv.domain.model.FeedType
import com.iptv.tv.domain.model.IptvProviders
import com.iptv.tv.domain.model.ServerCredentials
import com.iptv.tv.edition.BrowserEdition
import com.iptv.tv.ui.launchSafely
import com.iptv.tv.ui.web.WebViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val credentialsStore: CredentialsStore,
    private val iptvRepository: IptvRepository,
    private val epgRepository: EpgRepository,
    private val profileRepository: ProfileRepository,
    private val recordingStorage: RecordingStorage,
    private val parentalLock: ParentalLock,
    private val vpnAccess: VpnAccess,
    val edition: BrowserEdition,
    private val updateChecker: com.iptv.tv.update.UpdateChecker,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _updateStatus = kotlinx.coroutines.flow.MutableStateFlow(
        updateChecker.available.value?.let { "Update ${it.version.versionName} is available." } ?: "",
    )
    /** What the last manual update check said; the About pane shows it. */
    val updateStatus: StateFlow<String> = _updateStatus
    val updateAvailable: StateFlow<com.iptv.tv.update.UpdateChecker.Result.Available?> = updateChecker.available
    val updateProgress: StateFlow<Int?> = updateChecker.progress

    /** The manual check does report failures, unlike the automatic one at launch. */
    fun checkForUpdates() {
        launchSafely("SettingsViewModel.checkForUpdates") {
            _updateStatus.value = "Checking…"
            _updateStatus.value = when (val result = updateChecker.check()) {
                is com.iptv.tv.update.UpdateChecker.Result.Available -> "Update ${result.version.versionName} is available."
                is com.iptv.tv.update.UpdateChecker.Result.UpToDate -> "You have the latest version."
                is com.iptv.tv.update.UpdateChecker.Result.Failed -> "Could not check for updates: ${result.reason}."
            }
        }
    }

    fun installUpdate() {
        launchSafely("SettingsViewModel.installUpdate") {
            _updateStatus.value = "Downloading the update…"
            if (!updateChecker.install()) {
                _updateStatus.value = updateChecker.installError.value ?: "The update could not be installed."
            } else {
                _updateStatus.value = "Follow the installer on screen."
            }
        }
    }

    private fun <T> Flow<T>.asState(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val launchLast: StateFlow<Boolean> = preferences.launchToLastChannel.asState(false)
    val startTab: StateFlow<String> = preferences.startTab.asState("home")
    val askProfile: StateFlow<Boolean> = preferences.showProfilePicker.asState(false)
    val playerPreference: StateFlow<String> = preferences.playerPreference.asState("AUTO")
    val recStartBuffer: StateFlow<Int> = preferences.recordingStartBufferMinutes.asState(2)
    val recEndBuffer: StateFlow<Int> = preferences.recordingEndBufferMinutes.asState(5)
    val preferExternal: StateFlow<Boolean> = profileRepository.observeActiveProfile()
        .map { it?.preferExternalSubs == true }.asState(false)
    val audioLanguage: StateFlow<String> = profileRepository.observeActiveProfile()
        .map { it?.preferredAudioLanguage ?: "en" }.asState("en")
    val groups = profileRepository.observeActiveProfileId()
        .flatMapLatest { raw ->
            val id = raw ?: profileRepository.getActiveProfileId()
            profileRepository.observeCustomGroups(id)
        }
        .asState(emptyList())
    val openOnStartup: StateFlow<Boolean> = preferences.openOnStartup.asState(true)
    val accent: StateFlow<Long> = combine(
        profileRepository.observeActiveProfile(),
        preferences.accentColor,
    ) { profile, global ->
        profile?.accentColor ?: profile?.avatarColor ?: global
    }.asState(0xFFC9A227)
    val activeProfileName: StateFlow<String> = profileRepository.observeActiveProfile()
        .map { it?.name.orEmpty() }.asState("")
    val subtitleLanguage: StateFlow<String> = profileRepository.observeActiveProfile()
        .map { it?.subtitleLanguage ?: "en" }.asState("en")
    val epgDays: StateFlow<Int> = preferences.epgDaysToKeep.asState(3)
    val autoSyncMode: StateFlow<String> = preferences.subtitleAutoSyncMode.asState("on")
    val autoplayNext: StateFlow<Boolean> = preferences.autoplayNextEpisode.asState(true)
    val timeshiftWhileLive: StateFlow<Boolean> = preferences.timeshiftWhileLive.asState(false)
    val pinSet: StateFlow<Boolean> = preferences.parentalPinHash.map { !it.isNullOrBlank() }.asState(false)
    val adultUnlocked: StateFlow<Boolean> = parentalLock.unlocked.asState(false)
    private val _pinStatus = MutableStateFlow("")
    val pinStatus: StateFlow<String> = _pinStatus.asStateFlow()
    private val _dataStatus = MutableStateFlow("")
    val dataStatus: StateFlow<String> = _dataStatus.asStateFlow()

    private val _connected = MutableStateFlow(credentialsStore.hasCredentials())
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()
    private val _loginBusy = MutableStateFlow(false)
    val loginBusy: StateFlow<Boolean> = _loginBusy.asStateFlow()

    /** Storage options are read with StatFs, so they are computed once off the main thread. */
    val storageOptions: StateFlow<List<StorageOption>> = flow {
        emit(withContext(Dispatchers.IO) { recordingStorage.listOptions() })
    }.asState(emptyList())

    /** Which sections the user has switched off; Home and Settings are never in this list. */
    val disabledFeatures: StateFlow<Set<AppFeature>> = preferences.disabledFeatures.asState(emptySet())

    val vpn: StateFlow<VpnStatus> = vpnAccess.status
        .asState(VpnStatus(surfsharkInstalled = vpnAccess.isSurfsharkInstalled(), tunnelActive = false))

    val versionLabel: String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    val adultHidden: StateFlow<Boolean> = profileRepository.observeActiveProfileId()
        .flatMapLatest { raw ->
            val profileId = raw ?: profileRepository.getActiveProfileId()
            combine(
                profileRepository.observeCategoryOverrides(profileId, FeedType.LIVE),
                profileRepository.observeCategoryOverrides(profileId, FeedType.VOD),
            ) { live, vod ->
                live.any { it.providerCategoryId == CategoryLayout.adultLiveId && it.hidden } ||
                    vod.any { it.providerCategoryId == CategoryLayout.adultVodId && it.hidden }
            }
        }
        .asState(false)

    fun setLaunchLast(v: Boolean) = launchSafely("Settings.setLaunchLast") { preferences.setLaunchToLastChannel(v) }
    fun setStartTab(tab: String) = launchSafely("Settings.setStartTab") { preferences.setStartTab(tab) }
    fun setAskProfile(v: Boolean) = launchSafely("Settings.setAskProfile") { preferences.setShowProfilePicker(v) }
    fun cyclePlayerPreference() = launchSafely("Settings.cyclePlayer") {
        val next = when (preferences.playerPreference.first()) {
            "MEDIA3" -> "VLC"
            "VLC" -> "AUTO"
            else -> "MEDIA3"
        }
        preferences.setPlayerPreference(next)
    }
    fun cycleRecStart() = launchSafely("Settings.recStart") {
        val cur = preferences.recordingStartBufferMinutes.first()
        preferences.setRecordingStartBufferMinutes(if (cur >= 10) 0 else cur + 2)
    }
    fun cycleRecEnd() = launchSafely("Settings.recEnd") {
        val cur = preferences.recordingEndBufferMinutes.first()
        preferences.setRecordingEndBufferMinutes(if (cur >= 15) 0 else cur + 5)
    }
    fun setPreferExternal(v: Boolean) = launchSafely("Settings.preferExternal") {
        profileRepository.setPreferExternalSubs(v)
    }
    fun cycleAudioLanguage() = launchSafely("Settings.cycleAudio") {
        val current = profileRepository.getPreferredAudioLanguage()
        val next = SUBTITLE_LANGUAGES[(SUBTITLE_LANGUAGES.indexOf(current) + 1).mod(SUBTITLE_LANGUAGES.size)]
        profileRepository.setPreferredAudioLanguage(next)
    }
    fun pinGroup(id: Long, pinned: Boolean) = launchSafely("Settings.pinGroup") {
        profileRepository.pinCustomGroup(id, pinned)
    }
    fun deleteGroup(id: Long) = launchSafely("Settings.deleteGroup") {
        profileRepository.deleteCustomGroup(id)
    }
    fun setOpenOnStartup(v: Boolean) = launchSafely("Settings.setOpenOnStartup") { preferences.setOpenOnStartup(v) }
    fun setAccent(v: Long) = launchSafely("Settings.setAccent") { profileRepository.setAccentColor(v) }
    fun setEpgDays(v: Int) = launchSafely("Settings.setEpgDays") { preferences.setEpgDaysToKeep(v) }
    fun setAutoSync(v: String) = launchSafely("Settings.setAutoSync") { preferences.setSubtitleAutoSyncMode(v) }
    fun setAutoplayNext(v: Boolean) = launchSafely("Settings.setAutoplayNext") {
        preferences.setAutoplayNextEpisode(v)
    }
    fun setTimeshiftWhileLive(v: Boolean) = launchSafely("Settings.setTimeshiftWhileLive") {
        preferences.setTimeshiftWhileLive(v)
    }

    fun renameActiveProfile(name: String) = launchSafely("Settings.renameActiveProfile") {
        profileRepository.renameProfile(profileRepository.getActiveProfileId(), name)
    }

    fun cycleSubtitleLanguage() = launchSafely("Settings.cycleSubtitleLanguage") {
        val current = profileRepository.getActiveSubtitleLanguage()
        val next = SUBTITLE_LANGUAGES[(SUBTITLE_LANGUAGES.indexOf(current) + 1).mod(SUBTITLE_LANGUAGES.size)]
        profileRepository.setSubtitleLanguage(next)
    }

    fun setStoragePath(path: String) = launchSafely("Settings.setStoragePath") { preferences.setRecordingStoragePath(path) }

    fun setFeatureEnabled(feature: AppFeature, enabled: Boolean) = launchSafely("Settings.setFeature") {
        preferences.setFeatureEnabled(feature, enabled)
    }

    fun openSurfshark() {
        if (!vpnAccess.openSurfshark()) _dataStatus.value = "Surfshark is not installed on this stick."
    }

    fun getSurfshark() {
        if (!vpnAccess.openStore()) _dataStatus.value = "Could not open the app store on this stick."
    }

    fun setAdultHidden(hidden: Boolean) = launchSafely("Settings.setAdultHidden") {
        val profileId = profileRepository.getActiveProfileId()
        profileRepository.setCategoryOverride(
            profileId,
            FeedType.LIVE,
            CategoryLayout.adultLiveId,
            hidden = hidden,
        )
        profileRepository.setCategoryOverride(
            profileId,
            FeedType.VOD,
            CategoryLayout.adultVodId,
            hidden = hidden,
        )
    }

    fun createGroup(name: String) = launchSafely("Settings.createGroup") {
        profileRepository.createCustomGroup(profileRepository.getActiveProfileId(), name.trim())
    }

    /** The "Hide adult lists" override targets EDTV's category ids, so it only shows for EDTV. */
    val adultToggleAvailable: Boolean
        get() = IptvProviders.isEdtv(credentialsStore.getCredentials()?.serverUrl)

    val recordingStoragePath: StateFlow<String?> = preferences.recordingStoragePath.asState(null)

    fun refreshIptv() = launchSafely("Settings.refreshIptv", onError = {
        _dataStatus.value = it.message ?: "IPTV refresh failed"
    }) {
        _dataStatus.value = "Refreshing IPTV…"
        iptvRepository.refreshAllFeeds()
        _dataStatus.value = "IPTV catalogue refreshed"
    }

    fun refreshEpg() = launchSafely("Settings.refreshEpg", onError = {
        _dataStatus.value = it.message ?: "EPG refresh failed"
    }) {
        _dataStatus.value = "Refreshing EPG…"
        epgRepository.refreshEpg().getOrThrow()
        _dataStatus.value = "EPG refreshed"
    }

    fun clearLocalCache() = launchSafely("Settings.clearCache", onError = {
        _dataStatus.value = it.message ?: "Could not clear cache"
    }) {
        _dataStatus.value = "Clearing local cache…"
        iptvRepository.clearCatalogCache()
        epgRepository.clearCache()
        CacheCleaner.clearDisposableCaches(context)
        iptvRepository.refreshAllFeeds()
        if (credentialsStore.hasCredentials()) epgRepository.refreshEpg()
        _dataStatus.value = "Cache cleared; favourites, history, profiles and browser login kept"
    }

    fun clearBrowserData() {
        WebViewModel.clearBrowserData(context)
        _dataStatus.value = "Browser cookies and site data cleared"
    }

    fun createPin(pin: String) = launchSafely("Settings.createPin", onError = {
        _pinStatus.value = it.message ?: "Could not set PIN"
    }) {
        parentalLock.setPin(pin)
        _pinStatus.value = "PIN saved. Adult lists stay locked until you unlock."
    }

    fun changePin(current: String, next: String) = launchSafely("Settings.changePin", onError = {
        _pinStatus.value = it.message ?: "Could not change PIN"
    }) {
        _pinStatus.value = if (parentalLock.changePin(current, next)) {
            "PIN changed. Adult lists stay locked until you unlock."
        } else {
            "Wrong current PIN."
        }
    }

    fun unlockAdult(pin: String) = launchSafely("Settings.unlockPin") {
        _pinStatus.value = if (parentalLock.verify(pin)) "Unlocked for this session." else "Wrong PIN."
    }

    fun lockAdult() {
        parentalLock.lock()
        _pinStatus.value = "Adult lists locked."
    }

    fun removePin(pin: String) = launchSafely("Settings.removePin") {
        _pinStatus.value = if (parentalLock.clearPin(pin)) "PIN removed." else "Wrong PIN."
    }

    fun connectProvider(serverUrl: String, username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _loginError.value = "Enter username and password"
            return
        }
        if (serverUrl.isBlank()) {
            _loginError.value = "Enter a server URL"
            return
        }
        // Xtream panels are usually typed as host:port; give them a scheme.
        val server = serverUrl.trim().trimEnd('/').let {
            if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "http://$it"
        }
        launchSafely(
            "Settings.connectProvider",
            onError = { error ->
                _loginBusy.value = false
                _loginError.value = error.message ?: "Could not connect"
            },
        ) {
            _loginBusy.value = true
            _loginError.value = null
            val result = iptvRepository.authenticate(
                ServerCredentials(server, username.trim(), password),
            )
            if (result.isFailure) {
                _loginBusy.value = false
                _loginError.value = result.exceptionOrNull()?.message ?: "Could not connect"
                return@launchSafely
            }
            // Leave Connecting as soon as credentials are saved. The catalogue/EPG refresh
            // is started by the app shell (see onLoggedIn) so leaving Settings cannot cancel it.
            _connected.value = true
            _loginBusy.value = false
        }
    }

    fun connectEdtv(username: String, password: String) {
        connectProvider(IptvProviders.EDTV_URL, username, password)
    }

    /** Only flips the local flag; the app shell performs the actual sign-out once. */
    fun onLoggedOut() {
        _connected.value = false
    }

    fun maxConnections(): Int = credentialsStore.getMaxConnections()
    fun providerLabel(): String = IptvProviders.displayName(credentialsStore.getCredentials()?.serverUrl)

    /** What the provider's panel counts as open streams on this line, polled while the pane is shown. */
    private val _lineUsage = kotlinx.coroutines.flow.MutableStateFlow<IptvRepository.ConnectionSnapshot?>(null)
    val lineUsage: kotlinx.coroutines.flow.StateFlow<IptvRepository.ConnectionSnapshot?> = _lineUsage
    private var lineUsageJob: kotlinx.coroutines.Job? = null

    /** Extra lines for Multi with the panel's live view of each, polled with the main line. */
    data class ExtraLineRow(val line: com.iptv.tv.data.credentials.ExtraLine, val status: IptvRepository.ConnectionSnapshot?, val checked: Boolean)
    private val _extraLines = kotlinx.coroutines.flow.MutableStateFlow<List<ExtraLineRow>>(
        credentialsStore.getExtraLines().map { ExtraLineRow(it, null, checked = false) },
    )
    val extraLines: kotlinx.coroutines.flow.StateFlow<List<ExtraLineRow>> = _extraLines
    private val _extraLineError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val extraLineError: kotlinx.coroutines.flow.StateFlow<String?> = _extraLineError
    private val _extraLineBusy = kotlinx.coroutines.flow.MutableStateFlow(false)
    val extraLineBusy: kotlinx.coroutines.flow.StateFlow<Boolean> = _extraLineBusy

    /** Validates the login against the panel before it is saved. */
    fun addExtraLine(label: String, username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _extraLineError.value = "Enter the line's username and password."
            return
        }
        val main = credentialsStore.getCredentials() ?: run {
            _extraLineError.value = "Sign in to the main line first; extra lines use the same server."
            return
        }
        if (username.trim() == main.username) {
            _extraLineError.value = "That is your main line already."
            return
        }
        _extraLineBusy.value = true
        _extraLineError.value = null
        launchSafely("SettingsViewModel.addExtraLine", onError = { _extraLineBusy.value = false }) {
            val status = iptvRepository.lineStatus(com.iptv.tv.domain.model.ServerCredentials(main.serverUrl, username.trim(), password.trim()))
            _extraLineBusy.value = false
            when {
                status == null -> _extraLineError.value = "Could not reach the server to check that line."
                !status.valid -> _extraLineError.value = "The server rejected that username or password."
                !status.status.equals("Active", ignoreCase = true) -> _extraLineError.value = "That line is ${status.status.lowercase()}."
                else -> {
                    val line = credentialsStore.addExtraLine(label, username, password)
                    _extraLines.value = _extraLines.value + ExtraLineRow(line, status, checked = true)
                }
            }
        }
    }

    fun removeExtraLine(id: String) {
        credentialsStore.removeExtraLine(id)
        _extraLines.value = _extraLines.value.filterNot { it.line.id == id }
    }

    private val _lineSharing = kotlinx.coroutines.flow.MutableStateFlow(credentialsStore.isLineSharingEnabled())
    val lineSharing: kotlinx.coroutines.flow.StateFlow<Boolean> = _lineSharing
    fun setLineSharing(enabled: Boolean) {
        credentialsStore.setLineSharingEnabled(enabled)
        _lineSharing.value = enabled
    }

    private suspend fun refreshExtraLines() {
        val rows = _extraLines.value
        if (rows.isEmpty()) return
        val updated = rows.map { row ->
            val creds = credentialsStore.credentialsFor(row.line)
            row.copy(status = creds?.let { iptvRepository.lineStatus(it) }, checked = true)
        }
        _extraLines.value = updated
    }

    fun watchLineUsage(active: Boolean) {
        lineUsageJob?.cancel()
        lineUsageJob = null
        if (!active) return
        lineUsageJob = launchSafely("SettingsViewModel.lineUsage") {
            // Extra lines are checked once per visit; polling several logins from one address
            // looks like abuse to the panel and gets every stream to this address stalled.
            refreshExtraLines()
            while (true) {
                _lineUsage.value = iptvRepository.connectionSnapshot()
                kotlinx.coroutines.delay(10_000)
            }
        }
    }

    companion object {
        private val SUBTITLE_LANGUAGES = listOf("en", "fr", "de", "es", "it", "pt")

        fun subtitleLanguageLabel(code: String): String = when (code) {
            "en" -> "English"
            "fr" -> "French"
            "de" -> "German"
            "es" -> "Spanish"
            "it" -> "Italian"
            "pt" -> "Portuguese"
            else -> code.uppercase()
        }
    }
}

private typealias Flow<T> = kotlinx.coroutines.flow.Flow<T>
