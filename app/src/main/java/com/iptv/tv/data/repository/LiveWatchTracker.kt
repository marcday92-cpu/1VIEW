package com.iptv.tv.data.repository

import com.iptv.tv.data.preferences.AppPreferences
import com.iptv.tv.domain.model.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ranks Live channels by total time watched. A session only starts counting
 * after two minutes, then time is added while you stay on the channel.
 * Stored in Room view_history, so catalog cache clears do not wipe it.
 */
@Singleton
class LiveWatchTracker @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val preferences: AppPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var session: Session? = null
    private var ticker: Job? = null

    fun onLiveStarted(channel: Channel) {
        ticker?.cancel()
        ticker = scope.launch {
            mutex.withLock {
                persistLocked()
                val now = System.currentTimeMillis()
                session = Session(channel, startedAt = now, creditedUntil = now)
            }
            runCatching { preferences.setLastChannelStreamId(channel.streamId) }
            delay(MIN_SESSION_MS)
            if (!isActive) return@launch
            mutex.withLock { persistLocked() }
            while (isActive) {
                delay(FLUSH_MS)
                mutex.withLock { persistLocked() }
            }
        }
    }

    fun onLiveStopped() {
        ticker?.cancel()
        ticker = scope.launch {
            mutex.withLock {
                persistLocked()
                session = null
            }
        }
    }

    private suspend fun persistLocked() {
        val current = session ?: return
        val now = System.currentTimeMillis()
        if (now - current.startedAt < MIN_SESSION_MS) return
        val delta = now - current.creditedUntil
        if (delta < 1_000L) return
        profileRepository.addLiveWatchTime(
            current.channel.streamId,
            current.channel.name,
            delta,
            current.channel.logoUrl,
        )
        current.creditedUntil = now
    }

    private class Session(
        val channel: Channel,
        val startedAt: Long,
        var creditedUntil: Long,
    )

    companion object {
        private const val MIN_SESSION_MS = 120_000L
        private const val FLUSH_MS = 30_000L
    }
}
