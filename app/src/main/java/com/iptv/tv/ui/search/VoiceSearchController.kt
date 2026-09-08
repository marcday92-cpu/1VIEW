package com.iptv.tv.ui.search

import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

data class VoiceSearchRequest(
    val seedQuery: String? = null,
    val openTypeBox: Boolean = true,
)

/** Opens typed Search from Fire remote Search / Assist keys — not a microphone. */
@Singleton
class VoiceSearchController @Inject constructor() {
    // replay = 1: a SEARCH intent can arrive before the UI is composed (cold start); the
    // collector clears the replay cache once it has taken the request.
    private val _requests = MutableSharedFlow<VoiceSearchRequest>(replay = 1, extraBufferCapacity = 1)
    val requests: SharedFlow<VoiceSearchRequest> = _requests.asSharedFlow()

    // resetReplayCache is marked experimental but is the documented way to drop a replayed value.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun consumed() {
        _requests.resetReplayCache()
    }

    fun onVoiceKey(): Boolean = _requests.tryEmit(VoiceSearchRequest(openTypeBox = true))

    fun onSpokenOrTypedQuery(query: String): Boolean =
        _requests.tryEmit(VoiceSearchRequest(seedQuery = query, openTypeBox = false))

    companion object {
        fun isVoiceKey(keyCode: Int): Boolean = when (keyCode) {
            KeyEvent.KEYCODE_SEARCH,
            KeyEvent.KEYCODE_ASSIST,
            KeyEvent.KEYCODE_VOICE_ASSIST,
            -> true
            else -> false
        }
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface VoiceSearchEntryPoint {
    fun voiceSearch(): VoiceSearchController
}
