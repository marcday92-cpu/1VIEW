package com.iptv.tv.ui.multi

import androidx.lifecycle.ViewModel
import com.iptv.tv.data.preferences.AppPreferences
import com.iptv.tv.data.repository.IptvRepository
import com.iptv.tv.data.repository.ProfileRepository
import com.iptv.tv.domain.model.Channel
import com.iptv.tv.domain.model.FeedType
import com.iptv.tv.player.MultiViewPool
import com.iptv.tv.player.PlaybackController
import com.iptv.tv.ui.web.WebViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import com.iptv.tv.ui.launchSafely
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class MultiLayout(val label: String, val slots: Int) {
    TWO("2 screens", 2),
    THREE("3 screens", 3),
    FOUR("4 screens", 4),
    SIX("6 screens", 6),
}

data class MultiUiState(
    val layout: MultiLayout = MultiLayout.FOUR,
    val slots: List<MultiSlot?> = List(MAX_SLOTS) { null },
    val activeSlot: Int = 0,
    val pickerForSlot: Int? = null,
    val menuForSlot: Int? = null,
    val channels: List<Channel> = emptyList(),
    val pickerRails: List<MultiPickerRail> = emptyList(),
    val pickerRailId: String = "all",
    val favouriteIds: List<Int> = emptyList(),
    val recentIds: List<Int> = emptyList(),
    val filter: String = "",
    val ready: Boolean = false,
    val limitNote: String? = null,
    /** Short-lived warnings per screen (a borrowed line handed back). */
    val slotNotices: Map<Int, String> = emptyMap(),
) {
    /** Only slots inside the current layout count: a tile parked in a hidden slot is not on screen. */
    val webSlotIndex: Int?
        get() = slots.take(layout.slots).indexOfFirst { it is MultiSlot.Web }.takeIf { it >= 0 }

}

data class MultiPickerRail(val id: String, val label: String)

const val MAX_SLOTS = 6
const val WEB_RAIL_ID = "web"

/** Shown on the Multi chrome, empty tiles, and picker. */
const val MULTI_LIMIT_NOTE =
    "Put any channel on any screen, plus one Web page. Every screen keeps playing; sound follows the highlighted one."

const val MULTI_SECOND_WEB =
    "Only one Web tile at a time. Remove the page from screen %d first."

@HiltViewModel
class MultiViewModel @Inject constructor(
    private val iptvRepository: IptvRepository,
    private val profileRepository: ProfileRepository,
    private val preferences: AppPreferences,
    private val playbackController: PlaybackController,
    private val credentialsStore: com.iptv.tv.data.credentials.CredentialsStore,
    private val lines: LineBroker,
    private val recordingManager: com.iptv.tv.service.RecordingManager,
    val pool: MultiViewPool,
    val edition: com.iptv.tv.edition.BrowserEdition,
) : ViewModel() {

    private val _state = MutableStateFlow(MultiUiState())
    val state: StateFlow<MultiUiState> = _state.asStateFlow()

    val tileStatus = pool.status

    /** Slot → label of the borrowed line a screen is streaming on, for the tile caption. */
    val lineLabels: StateFlow<Map<Int, String>> = lines.labels

    /** The tile start-up loop; cancelled when the screen is left so tiles never restart in the background. */
    private var startJob: kotlinx.coroutines.Job? = null

    /** One in-flight recovery per screen (panel check after a cut); cancelled with the screen. */
    private val recoveryJobs = mutableMapOf<Int, kotlinx.coroutines.Job>()

    /** True between onEnter and onLeave; recoveries never start a stream for a grid nobody is looking at. */
    private var onScreen = false

    init {
        // A cut on a borrowed line may mean its owner started watching. Ask the panel before
        // reconnecting, so a screen never plays on top of the line's owner.
        pool.onStreamCut = { slot -> lines.leaseFor(slot)?.let { recoverBorrowed(slot, it); true } ?: false }
        launchSafely("MultiViewModel.restore", onError = { _state.value = _state.value.copy(ready = true) }) {
            runCatching {
                withContext(Dispatchers.IO) {
                    val profileId = profileRepository.getActiveProfileId()
                    val hidden = profileRepository.observeHiddenChannelIds(profileId).first()
                    val all = iptvRepository.observeLiveChannels().first().filter { it.streamId !in hidden }
                    val savedLayout = preferences.multiViewLayout.first()
                    val layout = MultiLayout.values().firstOrNull { it.name == savedLayout } ?: MultiLayout.FOUR
                    val savedIds = preferences.multiViewSlots.first()
                    val slots = MutableList<MultiSlot?>(MAX_SLOTS) { index ->
                        val id = savedIds.getOrNull(index) ?: 0
                        if (id > 0) all.find { it.streamId == id }?.let { MultiSlot.Live(it) } else null
                    }
                    decodeWebSlot(preferences.multiViewWebSlot.first())?.let { (index, web) ->
                        if (index in 0 until MAX_SLOTS) slots[index] = web
                    }
                    val cats = iptvRepository.observeLiveCategories().first()
                    val presented = profileRepository.applyCategoryOverrides(profileId, FeedType.LIVE, cats)
                    val favIds = profileRepository.observeFavouriteChannelIds(profileId).first()
                    val recentIds = profileRepository.observeRecentChannels(profileId).first()
                    val rails = buildList {
                        add(MultiPickerRail(WEB_RAIL_ID, "Web"))
                        add(MultiPickerRail("all", "All channels"))
                        add(MultiPickerRail("favourites", "Favourites"))
                        add(MultiPickerRail("recent", "Recently Watched"))
                        presented.forEach { add(MultiPickerRail(it.id, it.name)) }
                    }
                    Restore(all, layout, slots, rails, favIds, recentIds)
                }
            }.onSuccess { restore ->
                _state.value = _state.value.copy(
                    channels = restore.all,
                    layout = restore.layout,
                    slots = restore.slots,
                    pickerRails = restore.rails,
                    favouriteIds = restore.favIds,
                    recentIds = restore.recentIds,
                    ready = true,
                )
                startAll()
            }.onFailure {
                _state.value = _state.value.copy(ready = true)
            }
        }
    }

    val filteredChannels: List<Channel>
        get() {
            val query = _state.value.filter.trim()
            val all = _state.value.channels
            return if (query.isBlank()) all else all.filter { it.name.contains(query, ignoreCase = true) }
        }

    fun setLayout(layout: MultiLayout) {
        val previous = _state.value.layout
        startJob?.cancel()
        _state.value = _state.value.copy(
            layout = layout,
            activeSlot = _state.value.activeSlot.coerceAtMost(layout.slots - 1),
        )
        for (slot in layout.slots until MAX_SLOTS) pool.release(slot)
        launchSafely("MultiViewModel.setLayout") { preferences.setMultiViewLayout(layout.name) }
        if (layout.slots > previous.slots) startAll() else pool.setActiveSlot(_state.value.activeSlot)
    }

    fun openPicker(slot: Int, rail: String = "all") {
        _state.value = _state.value.copy(
            pickerForSlot = slot,
            menuForSlot = null,
            filter = "",
            pickerRailId = rail,
        )
    }

    fun closePicker() {
        _state.value = _state.value.copy(pickerForSlot = null, filter = "")
    }

    fun setFilter(value: String) {
        _state.value = _state.value.copy(filter = value)
    }

    fun openMenu(slot: Int) {
        if (_state.value.slots.getOrNull(slot) == null) openPicker(slot)
        else _state.value = _state.value.copy(menuForSlot = slot)
    }

    fun closeMenu() {
        _state.value = _state.value.copy(menuForSlot = null)
    }

    fun webBlockedOn(slot: Int): Boolean {
        val existing = _state.value.webSlotIndex
        return existing != null && existing != slot
    }

    fun showWebLimit() {
        val screen = (_state.value.webSlotIndex ?: 0) + 1
        _state.value = _state.value.copy(limitNote = MULTI_SECOND_WEB.format(screen))
    }

    fun clearLimitNote() {
        if (_state.value.limitNote != null) {
            _state.value = _state.value.copy(limitNote = null)
        }
    }

    fun assign(slot: Int, channel: Channel) {
        val slots = _state.value.slots.toMutableList()
        slots[slot] = MultiSlot.Live(channel)
        _state.value = _state.value.copy(slots = slots, pickerForSlot = null, filter = "", limitNote = null)
        persistSlots(slots)
        setActive(slot)
        startSlot(slot, channel)
        launchSafely("MultiViewModel.assign") {
            runCatching {
                profileRepository.recordChannelView(
                    profileRepository.getActiveProfileId(),
                    channel.streamId,
                    channel.name,
                    channel.logoUrl,
                )
            }
        }
    }

    /** @return false when [rawUrl] is not something a browser can open. */
    fun assignWeb(slot: Int, rawUrl: String, title: String): Boolean {
        val url = WebViewModel.normalise(rawUrl)
        if (url == null) {
            _state.value = _state.value.copy(limitNote = "Type a web address such as bbc.co.uk.")
            return false
        }
        if (webBlockedOn(slot)) {
            showWebLimit()
            return false
        }
        val slots = _state.value.slots.toMutableList()
        startJob?.cancel()
        pool.release(slot)
        launchSafely("MultiViewModel.releaseLine") { lines.release(slot) }
        slots[slot] = MultiSlot.Web(url, title.ifBlank { url })
        _state.value = _state.value.copy(slots = slots, pickerForSlot = null, filter = "", limitNote = null)
        persistSlots(slots)
        setActive(slot)
        return true
    }

    fun clear(slot: Int) {
        val slots = _state.value.slots.toMutableList()
        slots[slot] = null
        _state.value = _state.value.copy(slots = slots, menuForSlot = null)
        persistSlots(slots)
        startJob?.cancel()
        recoveryJobs.remove(slot)?.cancel()
        pool.release(slot)
        launchSafely("MultiViewModel.releaseLine") { lines.release(slot) }
        // The loop may have been half-way through the grid; restart the rest safely.
        startAll()
    }

    private fun cancelRecoveries() {
        recoveryJobs.values.forEach { it.cancel() }
        recoveryJobs.clear()
    }

    fun setActive(slot: Int) {
        if (_state.value.activeSlot == slot) return
        _state.value = _state.value.copy(activeSlot = slot)
        pool.setActiveSlot(slot)
    }

    /**
     * Reconnect from the tile menu: a real restart (the player is dropped, the line chosen
     * again), so it also revives a screen stuck buffering with no error and gives a screen that
     * had no idle line another look.
     */
    fun retry(slot: Int) {
        val live = _state.value.slots.getOrNull(slot) as? MultiSlot.Live ?: return
        recoveryJobs.remove(slot)?.cancel()
        pool.release(slot)
        launchSafely("MultiViewModel.retry") {
            lines.release(slot)
            startTile(slot, live.channel, _state.value.layout.slots, audible = slot == _state.value.activeSlot)
            explainAssignment()
        }
    }

    /** Hands the tile's channel to the full-screen player and frees every tile. */
    fun fullscreen(slot: Int) {
        val live = _state.value.slots.getOrNull(slot) as? MultiSlot.Live ?: return
        val playlist = _state.value.slots.mapNotNull { (it as? MultiSlot.Live)?.channel }
        startJob?.cancel()
        launchSafely("MultiViewModel.fullscreen") {
            val url = iptvRepository.livePlaybackUrl(live.channel)
            pool.releaseAll()
            _state.value = _state.value.copy(menuForSlot = null)
            playbackController.playLive(
                live.channel,
                url,
                playlist.ifEmpty { listOf(live.channel) },
                openedFrom = com.iptv.tv.player.PlaybackOpenedFrom.MULTI,
            )
            runCatching { preferences.setLastChannelStreamId(live.channel.streamId) }
        }
    }

    fun onEnter() {
        onScreen = true
        // Multi owns playback: a Live preview or channel still running underneath would hold an
        // extra connection to the provider, and the grid's tiles are what get cut for it.
        if (playbackController.session.value != null) playbackController.close()
        if (_state.value.ready) startAll()
    }

    // No background polling of borrowed lines: the panel treats a burst of logins from one
    // address as abuse and stalls every stream to it. An owner starting to watch shows up as
    // the provider cutting our stream, which hands the line back through onStreamCut.

    /** The owner of [slot]'s borrowed line is watching: give it back and restart elsewhere. */
    /**
     * Our stream on a borrowed line was cut or stalled. If the panel shows someone else on the
     * line, its owner is watching: hand it back and move the screen. Otherwise it was just a
     * dropped connection, so reconnect on the same line without bothering anyone.
     */
    private fun recoverBorrowed(slot: Int, lease: LineBroker.Lease) {
        if (!onScreen) return
        recoveryJobs.remove(slot)?.cancel()
        recoveryJobs[slot] = launchSafely("MultiViewModel.recoverBorrowed") {
            pool.markReconnecting(slot)
            delay(PANEL_SETTLE_MS)
            if (lines.leaseFor(slot) !== lease) return@launchSafely
            val verdict = lines.checkAfterCut(lease)
            if (!onScreen || lines.leaseFor(slot) !== lease) return@launchSafely
            when (verdict) {
                LineBroker.Verdict.FREE -> pool.reconnect(slot)
                LineBroker.Verdict.TAKEN -> handOverLine(slot)
                LineBroker.Verdict.UNKNOWN -> {
                    // Neither reconnect on top of a possible owner nor punish the line: stop and say why.
                    lines.release(slot)
                    pool.release(slot)
                    pool.markUnavailable(slot, NO_LINE_MESSAGE)
                    showSlotNotice(slot, "Could not check ${lease.line.label}'s line with the provider: screen ${slot + 1} stopped. Reconnect from its menu to try again.")
                    explainAssignment()
                }
            }
        }.also { job -> job.invokeOnCompletion { if (recoveryJobs[slot] === job) recoveryJobs.remove(slot) } }
    }

    private suspend fun handOverLine(slot: Int) {
        val owner = lines.leaseFor(slot)?.line?.label ?: "Its line"
        lines.release(slot, ownerIsWatching = true)
        val tile = _state.value.slots.getOrNull(slot) as? MultiSlot.Live ?: return
        pool.release(slot)
        val outcome = when (startTile(slot, tile.channel, _state.value.layout.slots)) {
            is TileStart.Borrowed -> "moved to ${lines.leaseFor(slot)?.line?.label}"
            TileStart.Main -> "now on your line"
            TileStart.NoLine -> "stopped, no other line is idle"
        }
        showSlotNotice(slot, "$owner is being used by its owner: screen ${slot + 1} $outcome.")
        explainAssignment()
    }

    private fun showSlotNotice(slot: Int, text: String) {
        _state.value = _state.value.copy(slotNotices = _state.value.slotNotices + (slot to text))
        launchSafely("MultiViewModel.clearNotice") {
            delay(12_000)
            if (_state.value.slotNotices[slot] == text) {
                _state.value = _state.value.copy(slotNotices = _state.value.slotNotices - slot)
            }
        }
    }

    /**
     * A recording uses the main line too. Running now, or due within the next hour and a half,
     * it will cut the screens that share the main line.
     */
    private suspend fun recordingWarning(): String? {
        recordingManager.active.value?.let { return "A recording (${it.title}) is running on your main line; screens on your line will be interrupted." }
        val now = System.currentTimeMillis()
        val next = runCatching { recordingManager.observeScheduled().first() }.getOrDefault(emptyList())
            .filter { it.startTimeMs - it.startBufferMinutes * 60_000L in now..(now + RECORDING_WARN_MS) }
            .minByOrNull { it.startTimeMs } ?: return null
        val at = com.iptv.tv.util.formatClock(next.startTimeMs)
        return "Recording ${next.title} starts at $at on your main line; screens on your line will be interrupted then."
    }

    fun onLeave() {
        onScreen = false
        startJob?.cancel()
        startJob = null
        cancelRecoveries()
        pool.releaseAll()
        launchSafely("MultiViewModel.releaseLines") { lines.releaseAll() }
    }

    override fun onCleared() {
        onScreen = false
        startJob?.cancel()
        cancelRecoveries()
        pool.releaseAll()
    }

    private fun startAll() {
        val state = _state.value
        pool.setActiveSlot(state.activeSlot)
        startJob?.cancel()
        startJob = launchSafely("MultiViewModel.startAll") {
            // The audible screen goes first and alone. A panel that limits the line's streams
            // keeps the stream it admitted first and culls the ones started on top of it, so
            // the screen the viewer is listening to should be the one the panel keeps.
            val first = state.activeSlot.takeIf { state.slots.getOrNull(it) is MultiSlot.Live }
            val order = (listOfNotNull(first) + (0 until state.layout.slots).filter { it != first })
            for (slot in order) {
                when (val tile = state.slots.getOrNull(slot)) {
                    is MultiSlot.Live -> {
                        // One tile without a stream must not stop the rest of the grid.
                        startTile(slot, tile.channel, state.layout.slots, audible = slot == first)
                        if (slot == first) {
                            withTimeoutOrNull(8_000) {
                                pool.status.first { it[slot]?.let { st -> !st.buffering || st.error != null } == true }
                            }
                            delay(3_000)
                        } else {
                            delay(1_500)
                        }
                    }
                    is MultiSlot.Web -> pool.release(slot)
                    null -> Unit
                }
            }
            explainAssignment()
        }
    }

    /**
     * One note after the grid starts: which screen borrowed which line, why any screen is
     * still sharing the main line, and a recording that will get in the way.
     */
    private var lastAssignmentNote: String? = null
    private suspend fun explainAssignment() {
        val state = _state.value
        val labels = lines.labels.value
        val parts = mutableListOf<String>()
        val iptvSlots = (0 until state.layout.slots).filter { (state.slots.getOrNull(it) as? MultiSlot.Live)?.channel?.isFree == false }
        val onMain = iptvSlots.filter { it !in labels }
        val allowed = credentialsStore.getMaxConnections()
        if (labels.isNotEmpty()) parts += labels.entries.sortedBy { it.key }.joinToString(" · ") { "Screen ${it.key + 1}: ${it.value}" }
        val status = pool.status.value
        val noLine = iptvSlots.filter { status[it]?.error == NO_LINE_MESSAGE }
        val why = lines.describeLines().filterNot { it.endsWith("carrying one of your screens") }
        if (noLine.isNotEmpty()) {
            parts += "No idle line for screen${if (noLine.size > 1) "s" else ""} ${noLine.joinToString(", ") { "${it + 1}" }}" +
                if (why.isNotEmpty()) " (${why.joinToString("; ")}). Reconnect from the screen's menu to try again." else "."
        } else if (onMain.filter { it !in noLine }.size > allowed) {
            parts += "Screens ${onMain.joinToString(", ") { "${it + 1}" }} share your line (allows $allowed), so the provider will cut and reconnect them" +
                if (why.isNotEmpty()) ". Extra lines: ${why.joinToString("; ")}." else "."
        }
        recordingWarning()?.let { parts += it }
        val note = parts.joinToString("  ").ifBlank { null }
        if (note != null && note != lastAssignmentNote) {
            lastAssignmentNote = note
            _state.value = _state.value.copy(limitNote = note)
        }
    }

    /** How a screen was started: on a borrowed line, on the main line, or not at all. */
    private sealed class TileStart {
        data class Borrowed(val label: String) : TileStart()
        object Main : TileStart()
        object NoLine : TileStart()
    }

    /**
     * Starts a screen's stream. Free channels use the main line. An IPTV screen already
     * streaming on a borrowed line keeps it, whatever happens to sound or channel, so changing
     * channel on "via Dean" stays on Dean's line. Otherwise the audible screen takes the main
     * line when it has room, and every other screen borrows an idle extra line. When extra
     * lines are in use but none is idle and the main line is full, the screen says so and
     * stays off rather than sharing the main line and getting it cut every half minute;
     * sharing only happens when no extra lines are set up.
     */
    private suspend fun startTile(slot: Int, channel: Channel, maxTiles: Int, audible: Boolean = false): TileStart {
        val lease = when {
            channel.isFree -> lines.release(slot).let { null }
            // Asking the panel again about a line we are streaming on would count our own
            // stream as somebody watching and drop the lease from under the player.
            lines.leaseFor(slot) != null && pool.isHealthy(slot) -> lines.leaseFor(slot)
            audible && mainLineHasRoom(slot) -> lines.release(slot).let { null }
            else -> lines.acquire(slot)
        }
        if (lease == null && !channel.isFree && lines.sharingActive() && !mainLineHasRoom(slot)) {
            pool.release(slot)
            pool.markUnavailable(slot, NO_LINE_MESSAGE)
            return TileStart.NoLine
        }
        val url = runCatching {
            if (lease != null) iptvRepository.livePlaybackUrl(channel, lease.credentials) else iptvRepository.livePlaybackUrl(channel)
        }.getOrElse {
            pool.markUnavailable(slot, "No stream for this channel")
            return if (lease != null) TileStart.Borrowed(lease.line.label) else TileStart.Main
        }
        pool.play(slot, url, maxTiles, PlaybackController.playbackHeaders(channel, url))
        return if (lease != null) TileStart.Borrowed(lease.line.label) else TileStart.Main
    }

    /**
     * True when another IPTV screen can join the main line without exceeding its allowance.
     * Only screens actually streaming count: at start-up nothing has started yet, so the
     * audible screen always gets the main line first.
     */
    private fun mainLineHasRoom(slot: Int): Boolean {
        val state = _state.value
        val labels = lines.labels.value
        val onMain = (0 until state.layout.slots).count { other ->
            other != slot &&
                (state.slots.getOrNull(other) as? MultiSlot.Live)?.channel?.isFree == false &&
                other !in labels &&
                pool.isStreaming(other)
        }
        return onMain < credentialsStore.getMaxConnections().coerceAtLeast(1)
    }

    private fun startSlot(slot: Int, channel: Channel) {
        launchSafely("MultiViewModel.startSlot") {
            startTile(slot, channel, _state.value.layout.slots, audible = slot == _state.value.activeSlot)
        }
    }

    private fun persistSlots(slots: List<MultiSlot?>) {
        launchSafely("MultiViewModel.persistSlots") {
            runCatching {
                preferences.setMultiViewSlots(slots.map { (it as? MultiSlot.Live)?.channel?.streamId ?: 0 })
                val web = slots.withIndex().firstOrNull { it.value is MultiSlot.Web }
                if (web != null) {
                    val page = web.value as MultiSlot.Web
                    preferences.setMultiViewWebSlot("${web.index}|${page.url}|${page.title}")
                } else {
                    preferences.setMultiViewWebSlot("")
                }
            }
        }
    }

    fun selectPickerRail(id: String) {
        _state.value = _state.value.copy(pickerRailId = id, filter = "")
    }

    /** Picker rows, recomputed only when the picker inputs change (not on every recomposition). */
    val pickerChannels: StateFlow<List<Channel>> = _state
        .map { s -> PickerInputs(s.pickerForSlot != null, s.pickerRailId, s.filter, s.channels, s.favouriteIds, s.recentIds) }
        .distinctUntilChanged()
        .map { inputs -> if (!inputs.open) emptyList() else pickerRows(inputs) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun pickerRows(s: PickerInputs): List<Channel> {
        if (s.railId == WEB_RAIL_ID) return emptyList()
        val query = s.filter.trim()
        val byId = if (s.railId == "favourites" || s.railId == "recent") s.channels.associateBy { it.streamId } else emptyMap()
        val byRail = when (s.railId) {
            "all" -> s.channels
            "favourites" -> s.favouriteIds.mapNotNull { byId[it] }
            "recent" -> s.recentIds.mapNotNull { byId[it] }
            else -> s.channels.filter { it.categoryId == s.railId }
        }.distinctBy { it.streamId }
        return if (query.isBlank()) byRail else byRail.filter { it.name.contains(query, ignoreCase = true) }
    }

    private data class PickerInputs(
        val open: Boolean,
        val railId: String,
        val filter: String,
        val channels: List<Channel>,
        val favouriteIds: List<Int>,
        val recentIds: List<Int>,
    )

    private data class Restore(
        val all: List<Channel>,
        val layout: MultiLayout,
        val slots: List<MultiSlot?>,
        val rails: List<MultiPickerRail>,
        val favIds: List<Int>,
        val recentIds: List<Int>,
    )

    companion object {
        private const val RECORDING_WARN_MS = 90 * 60 * 1000L
        /** Time for the panel to forget a stream it just dropped before we ask who is on the line. */
        private const val PANEL_SETTLE_MS = 1_500L
        private const val NO_LINE_MESSAGE = "No idle line for this screen"

        fun decodeWebSlot(encoded: String): Pair<Int, MultiSlot.Web>? {
            if (encoded.isBlank()) return null
            val parts = encoded.split('|', limit = 3)
            val index = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val url = parts.getOrNull(1)?.let { WebViewModel.normalise(it) } ?: return null
            val title = parts.getOrNull(2).orEmpty().ifBlank { url }
            return index to MultiSlot.Web(url, title)
        }
    }
}
