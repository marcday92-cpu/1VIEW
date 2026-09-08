package com.iptv.tv.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.catch
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.iptv.tv.domain.model.LiveSourceFilter
import com.iptv.tv.domain.model.VodSourceFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// A power cut during a write must not crash-loop the app: a corrupt file is replaced by empty
// preferences (every key has a sensible default) and read errors surface as defaults.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "iptv_prefs",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { emptyPreferences() },
)

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.dataStore

    /** Preference reads never throw: an unreadable file behaves like first launch. */
    private val dataStore = object {
        val data: Flow<Preferences> = store.data.catch { error ->
            if (error is java.io.IOException) emit(emptyPreferences()) else throw error
        }

        suspend fun edit(transform: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
            store.edit(transform)
        }
    }

    val activeProfileId: Flow<Long?> = dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_PROFILE]?.takeIf { it > 0 }
    }

    val lastChannelStreamId: Flow<Int?> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_CHANNEL]?.takeIf { it > 0 }
    }

    val launchToLastChannel: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_LAUNCH_LAST_CHANNEL] ?: false
    }

    /** Open Home after the Fire Stick boots. Does not start a channel. Default on. */
    val openOnStartup: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_OPEN_ON_STARTUP] ?: true
    }

    val accentColor: Flow<Long> = dataStore.data.map { prefs ->
        prefs[KEY_ACCENT_COLOR] ?: 0xFFC9A227
    }

    val epgDaysToKeep: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_EPG_DAYS] ?: 3
    }

    val showProfilePicker: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SHOW_PROFILE_PICKER] ?: false
    }

    val recordingStoragePath: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_RECORDING_PATH]
    }

    val subtitleAutoSyncMode: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_SUBTITLE_AUTO_SYNC].let { stored ->
            when (stored) {
                "off" -> "off"
                else -> "on"
            }
        }
    }

    /** Caption text size in sp. Deliberately large: this is read from a sofa. */
    val subtitleTextSize: Flow<Float> = dataStore.data.map { prefs ->
        prefs[KEY_SUBTITLE_TEXT_SIZE] ?: 24f
    }

    /** How far above the bottom edge captions sit, as a fraction of view height. */
    val subtitleBottomPadding: Flow<Float> = dataStore.data.map { prefs ->
        prefs[KEY_SUBTITLE_BOTTOM_PADDING] ?: 0.08f
    }

    val subtitleBackgroundBox: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SUBTITLE_BACKGROUND] ?: false
    }

    val subtitleLanguage: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_SUBTITLE_LANGUAGE] ?: "en"
    }

    suspend fun setSubtitleTextSize(sizeSp: Float) {
        dataStore.edit { it[KEY_SUBTITLE_TEXT_SIZE] = sizeSp.coerceIn(14f, 44f) }
    }

    suspend fun setSubtitleBottomPadding(fraction: Float) {
        dataStore.edit { it[KEY_SUBTITLE_BOTTOM_PADDING] = fraction.coerceIn(0f, 0.4f) }
    }

    suspend fun setSubtitleBackgroundBox(enabled: Boolean) {
        dataStore.edit { it[KEY_SUBTITLE_BACKGROUND] = enabled }
    }

    suspend fun setSubtitleLanguage(language: String) {
        dataStore.edit { it[KEY_SUBTITLE_LANGUAGE] = language }
    }

    val multiViewLayout: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_MULTI_LAYOUT] ?: "FOUR"
    }

    /** One entry per tile; 0 means the tile is empty. */
    val multiViewSlots: Flow<List<Int>> = dataStore.data.map { prefs ->
        prefs[KEY_MULTI_SLOTS]
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?: emptyList()
    }

    suspend fun setActiveProfileId(id: Long) {
        dataStore.edit { it[KEY_ACTIVE_PROFILE] = id }
    }

    suspend fun setLastChannelStreamId(streamId: Int) {
        dataStore.edit { it[KEY_LAST_CHANNEL] = streamId }
    }

    suspend fun setLaunchToLastChannel(enabled: Boolean) {
        dataStore.edit { it[KEY_LAUNCH_LAST_CHANNEL] = enabled }
    }

    suspend fun setOpenOnStartup(enabled: Boolean) {
        dataStore.edit { it[KEY_OPEN_ON_STARTUP] = enabled }
    }

    suspend fun setAccentColor(color: Long) {
        dataStore.edit { it[KEY_ACCENT_COLOR] = color }
    }

    suspend fun setEpgDaysToKeep(days: Int) {
        dataStore.edit { it[KEY_EPG_DAYS] = days }
    }

    suspend fun setShowProfilePicker(show: Boolean) {
        dataStore.edit { it[KEY_SHOW_PROFILE_PICKER] = show }
    }

    suspend fun setRecordingStoragePath(path: String) {
        dataStore.edit { it[KEY_RECORDING_PATH] = path }
    }

    suspend fun setSubtitleAutoSyncMode(mode: String) {
        dataStore.edit { it[KEY_SUBTITLE_AUTO_SYNC] = mode }
    }

    suspend fun setMultiViewLayout(layout: String) {
        dataStore.edit { it[KEY_MULTI_LAYOUT] = layout }
    }

    suspend fun setMultiViewSlots(streamIds: List<Int>) {
        dataStore.edit { it[KEY_MULTI_SLOTS] = streamIds.joinToString(",") }
    }

    /** Encoded as `slotIndex|url|title`. Empty means no Web tile. */
    val multiViewWebSlot: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_MULTI_WEB] ?: ""
    }

    suspend fun setMultiViewWebSlot(encoded: String) {
        dataStore.edit { it[KEY_MULTI_WEB] = encoded }
    }

    val iptvOrgFetchedAt: Flow<Long> = dataStore.data.map { prefs ->
        prefs[KEY_IPTV_ORG_FETCHED_AT] ?: 0L
    }

    suspend fun setIptvOrgFetchedAt(epochMs: Long) {
        dataStore.edit { it[KEY_IPTV_ORG_FETCHED_AT] = epochMs }
    }

    val liveSourceFilter: Flow<LiveSourceFilter> = dataStore.data.map { prefs ->
        LiveSourceFilter.fromStored(prefs[KEY_LIVE_SOURCE_FILTER])
    }

    suspend fun setLiveSourceFilter(filter: LiveSourceFilter) {
        dataStore.edit { it[KEY_LIVE_SOURCE_FILTER] = filter.name }
    }

    val startTab: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_START_TAB].takeIf { it == "live" } ?: "home"
    }

    /** Version code whose update banner was put off (Later, or Install already pressed), and when. */
    val updateSnooze: Flow<Pair<Int, Long>> = dataStore.data.map { prefs ->
        (prefs[KEY_UPDATE_SNOOZE_CODE] ?: 0) to (prefs[KEY_UPDATE_SNOOZE_AT] ?: 0L)
    }

    suspend fun setUpdateSnooze(versionCode: Int, at: Long) {
        dataStore.edit {
            it[KEY_UPDATE_SNOOZE_CODE] = versionCode
            it[KEY_UPDATE_SNOOZE_AT] = at
        }
    }

    suspend fun setStartTab(tab: String) {
        dataStore.edit { it[KEY_START_TAB] = if (tab == "live") "live" else "home" }
    }

    val playerPreference: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_PLAYER_PREFERENCE] ?: "AUTO"
    }

    suspend fun setPlayerPreference(mode: String) {
        dataStore.edit { it[KEY_PLAYER_PREFERENCE] = mode }
    }

    val streamEngineOverrides: Flow<Map<Int, String>> = dataStore.data.map { prefs ->
        parseOverrideMap(prefs[KEY_STREAM_ENGINE_OVERRIDES])
    }

    suspend fun setStreamEngineOverride(streamId: Int, engine: String?) {
        dataStore.edit { prefs ->
            val map = parseOverrideMap(prefs[KEY_STREAM_ENGINE_OVERRIDES]).toMutableMap()
            if (engine.isNullOrBlank() || engine == "AUTO") map.remove(streamId) else map[streamId] = engine
            prefs[KEY_STREAM_ENGINE_OVERRIDES] = map.entries.joinToString(",") { "${it.key}=${it.value}" }
        }
    }

    val recordingStartBufferMinutes: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_REC_START_BUFFER] ?: 2
    }

    val recordingEndBufferMinutes: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_REC_END_BUFFER] ?: 5
    }

    suspend fun setRecordingStartBufferMinutes(minutes: Int) {
        dataStore.edit { it[KEY_REC_START_BUFFER] = minutes.coerceIn(0, 15) }
    }

    suspend fun setRecordingEndBufferMinutes(minutes: Int) {
        dataStore.edit { it[KEY_REC_END_BUFFER] = minutes.coerceIn(0, 30) }
    }

    val audioTrackPrefs: Flow<Map<String, String>> = dataStore.data.map { prefs ->
        parseStringMap(prefs[KEY_AUDIO_TRACK_PREFS])
    }

    suspend fun setAudioTrackPref(contentKey: String, trackId: String) {
        dataStore.edit { prefs ->
            val map = parseStringMap(prefs[KEY_AUDIO_TRACK_PREFS]).toMutableMap()
            map[contentKey] = trackId
            if (map.size > 400) {
                map.keys.take(map.size - 400).forEach { map.remove(it) }
            }
            prefs[KEY_AUDIO_TRACK_PREFS] = map.entries.joinToString("\n") { "${it.key}=${it.value}" }
        }
    }

    /**
     * Restart a live programme from the beginning via timeshift. Default off: `tv_archive`
     * only proves the channel has catch-up after the programme ends, not restart-while-live.
     */
    val timeshiftWhileLive: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_TIMESHIFT_WHILE_LIVE] ?: false
    }

    suspend fun setTimeshiftWhileLive(enabled: Boolean) {
        dataStore.edit { it[KEY_TIMESHIFT_WHILE_LIVE] = enabled }
    }

    val autoplayNextEpisode: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_AUTOPLAY_NEXT] ?: true
    }

    suspend fun setAutoplayNextEpisode(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTOPLAY_NEXT] = enabled }
    }

    val parentalPinHash: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_PARENTAL_PIN_HASH]?.takeIf { it.isNotBlank() }
    }

    suspend fun setParentalPinHash(hash: String?) {
        dataStore.edit { prefs ->
            if (hash.isNullOrBlank()) prefs.remove(KEY_PARENTAL_PIN_HASH)
            else prefs[KEY_PARENTAL_PIN_HASH] = hash
        }
    }

    val archiveFetchedAt: Flow<Long> = dataStore.data.map { prefs ->
        prefs[KEY_ARCHIVE_FETCHED_AT] ?: 0L
    }

    suspend fun setArchiveFetchedAt(epochMs: Long) {
        dataStore.edit { it[KEY_ARCHIVE_FETCHED_AT] = epochMs }
    }

    val moviesSourceFilter: Flow<VodSourceFilter> = dataStore.data.map { prefs ->
        VodSourceFilter.fromStored(prefs[KEY_MOVIES_SOURCE_FILTER])
    }

    suspend fun setMoviesSourceFilter(filter: VodSourceFilter) {
        dataStore.edit { it[KEY_MOVIES_SOURCE_FILTER] = filter.name }
    }

    val seriesSourceFilter: Flow<VodSourceFilter> = dataStore.data.map { prefs ->
        VodSourceFilter.fromStored(prefs[KEY_SERIES_SOURCE_FILTER])
    }

    suspend fun setSeriesSourceFilter(filter: VodSourceFilter) {
        dataStore.edit { it[KEY_SERIES_SOURCE_FILTER] = filter.name }
    }

    /** Browser tab: most recent first, at most [MAX_RECENT_SITES]. */
    val recentSites: Flow<List<com.iptv.tv.ui.web.RecentSite>> = dataStore.data.map { prefs ->
        parseRecentSites(prefs[KEY_RECENT_SITES])
    }

    suspend fun rememberRecentSite(url: String, title: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return
        dataStore.edit { prefs ->
            val all = parseRecentSites(prefs[KEY_RECENT_SITES])
            val existing = all.filter { it.url != cleanUrl }
            // Keep the stored title when the page has not produced one yet (early onPageStarted).
            val label = title.trim().ifBlank { all.firstOrNull { it.url == cleanUrl }?.title.orEmpty() }
            val next = listOf(com.iptv.tv.ui.web.RecentSite(cleanUrl, label)) + existing
            prefs[KEY_RECENT_SITES] = next.take(MAX_RECENT_SITES)
                .joinToString("\n") { "${it.url}\t${it.title.replace('\n', ' ').replace('\t', ' ')}" }
        }
    }

    suspend fun forgetRecentSite(url: String) {
        dataStore.edit { prefs ->
            val next = parseRecentSites(prefs[KEY_RECENT_SITES]).filter { it.url != url }
            prefs[KEY_RECENT_SITES] = next.joinToString("\n") { "${it.url}\t${it.title}" }
        }
    }

    /** Sections the user has switched off in Settings → Manage features. Empty = everything on. */
    val disabledFeatures: Flow<Set<com.iptv.tv.domain.model.AppFeature>> = dataStore.data.map { prefs ->
        com.iptv.tv.domain.model.AppFeature.parseDisabled(prefs[KEY_DISABLED_FEATURES])
    }

    suspend fun setFeatureEnabled(feature: com.iptv.tv.domain.model.AppFeature, enabled: Boolean) {
        dataStore.edit { prefs ->
            val current = com.iptv.tv.domain.model.AppFeature.parseDisabled(prefs[KEY_DISABLED_FEATURES])
            val next = if (enabled) current - feature else current + feature
            prefs[KEY_DISABLED_FEATURES] = com.iptv.tv.domain.model.AppFeature.encodeDisabled(next)
        }
    }

    companion object {
        const val MAX_RECENT_SITES = 8

        private fun parseRecentSites(raw: String?): List<com.iptv.tv.ui.web.RecentSite> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.lineSequence().mapNotNull { line ->
                val tab = line.indexOf('\t')
                val url = (if (tab >= 0) line.substring(0, tab) else line).trim()
                if (!url.startsWith("http")) return@mapNotNull null
                com.iptv.tv.ui.web.RecentSite(url, if (tab >= 0) line.substring(tab + 1).trim() else "")
            }.distinctBy { it.url }.take(MAX_RECENT_SITES).toList()
        }

        private fun parseOverrideMap(raw: String?): Map<Int, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            return raw.split(',').mapNotNull { part ->
                val bits = part.split('=', limit = 2)
                if (bits.size != 2) return@mapNotNull null
                val id = bits[0].toIntOrNull() ?: return@mapNotNull null
                id to bits[1]
            }.toMap()
        }

        private fun parseStringMap(raw: String?): Map<String, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            return raw.lineSequence().mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
            }.toMap()
        }

        private val KEY_ACTIVE_PROFILE = longPreferencesKey("active_profile_id")
        private val KEY_LAST_CHANNEL = intPreferencesKey("last_channel_stream_id")
        private val KEY_UPDATE_SNOOZE_CODE = intPreferencesKey("update_snooze_code")
        private val KEY_UPDATE_SNOOZE_AT = longPreferencesKey("update_snooze_at")
        private val KEY_LAUNCH_LAST_CHANNEL = booleanPreferencesKey("launch_last_channel")
        private val KEY_OPEN_ON_STARTUP = booleanPreferencesKey("open_on_startup")
        private val KEY_ACCENT_COLOR = longPreferencesKey("accent_color")
        private val KEY_EPG_DAYS = intPreferencesKey("epg_days_to_keep")
        private val KEY_SHOW_PROFILE_PICKER = booleanPreferencesKey("show_profile_picker")
        private val KEY_RECORDING_PATH = stringPreferencesKey("recording_storage_path")
        private val KEY_SUBTITLE_AUTO_SYNC = stringPreferencesKey("subtitle_auto_sync_mode")
        private val KEY_SUBTITLE_TEXT_SIZE = floatPreferencesKey("subtitle_text_size")
        private val KEY_SUBTITLE_BOTTOM_PADDING = floatPreferencesKey("subtitle_bottom_padding")
        private val KEY_SUBTITLE_BACKGROUND = booleanPreferencesKey("subtitle_background_box")
        private val KEY_SUBTITLE_LANGUAGE = stringPreferencesKey("subtitle_language")
        private val KEY_MULTI_LAYOUT = stringPreferencesKey("multi_view_layout")
        private val KEY_MULTI_SLOTS = stringPreferencesKey("multi_view_slots")
        private val KEY_MULTI_WEB = stringPreferencesKey("multi_view_web_slot")
        private val KEY_IPTV_ORG_FETCHED_AT = longPreferencesKey("iptv_org_fetched_at")
        private val KEY_LIVE_SOURCE_FILTER = stringPreferencesKey("live_source_filter")
        private val KEY_START_TAB = stringPreferencesKey("start_tab")
        private val KEY_PLAYER_PREFERENCE = stringPreferencesKey("player_preference")
        private val KEY_STREAM_ENGINE_OVERRIDES = stringPreferencesKey("stream_engine_overrides")
        private val KEY_REC_START_BUFFER = intPreferencesKey("recording_start_buffer_min")
        private val KEY_REC_END_BUFFER = intPreferencesKey("recording_end_buffer_min")
        private val KEY_AUDIO_TRACK_PREFS = stringPreferencesKey("audio_track_prefs")
        private val KEY_TIMESHIFT_WHILE_LIVE = booleanPreferencesKey("timeshift_while_live")
        private val KEY_AUTOPLAY_NEXT = booleanPreferencesKey("autoplay_next_episode")
        private val KEY_PARENTAL_PIN_HASH = stringPreferencesKey("parental_pin_hash")
        private val KEY_ARCHIVE_FETCHED_AT = longPreferencesKey("archive_org_fetched_at")
        private val KEY_MOVIES_SOURCE_FILTER = stringPreferencesKey("movies_source_filter")
        private val KEY_SERIES_SOURCE_FILTER = stringPreferencesKey("series_source_filter")
        private val KEY_RECENT_SITES = stringPreferencesKey("browser_recent_sites")
        private val KEY_DISABLED_FEATURES = stringPreferencesKey("disabled_features")
    }
}
