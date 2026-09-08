package com.iptv.tv.ui.multi

import com.iptv.tv.data.credentials.CredentialsStore
import com.iptv.tv.data.credentials.ExtraLine
import com.iptv.tv.data.repository.IptvRepository
import com.iptv.tv.domain.model.ServerCredentials
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands Multi screens a line to stream on. The main line carries the audible screen; every
 * other IPTV screen borrows one of the extra lines (a friend's login on the same server),
 * but only a line the provider's panel currently shows as idle, and only until its owner
 * starts watching. A borrowed line is never used for two screens at once, so it stays inside
 * its own one-stream allowance and the owner is never cut.
 */
@Singleton
class LineBroker @Inject constructor(
    private val credentialsStore: CredentialsStore,
    private val iptvRepository: IptvRepository,
) {
    /** A screen's borrowed line. */
    data class Lease(val slot: Int, val line: ExtraLine, val credentials: ServerCredentials)

    private val mutex = Mutex()
    private val leases = mutableMapOf<Int, Lease>()
    /** Lines whose owner was seen watching; left alone until this clock time (elapsedRealtime). */
    private val busyUntil = mutableMapOf<String, Long>()

    /** What the panel said about each extra line the last time a screen asked for one. */
    private val _lastSeen = MutableStateFlow<Map<String, IptvRepository.ConnectionSnapshot?>>(emptyMap())
    val lastSeen: StateFlow<Map<String, IptvRepository.ConnectionSnapshot?>> = _lastSeen.asStateFlow()

    private val _labels = MutableStateFlow<Map<Int, String>>(emptyMap())
    /** Slot → label of the borrowed line, for the tile caption. */
    val labels: StateFlow<Map<Int, String>> = _labels.asStateFlow()

    fun leaseFor(slot: Int): Lease? = leases[slot]

    /**
     * Borrows the first extra line the panel reports idle. Lines already leased to another
     * screen, lines whose owner was watching recently, and lines that fail to log in are
     * skipped. Returns null when nothing is free; the caller then shares the main line.
     */
    suspend fun acquire(slot: Int, now: Long = android.os.SystemClock.elapsedRealtime()): Lease? {
        if (!credentialsStore.isLineSharingEnabled()) return null
        val candidates = mutex.withLock {
            leases.remove(slot)?.let { publish() }
            val taken = leases.values.map { it.line.id }.toSet()
            credentialsStore.getExtraLines().filter { it.id !in taken && (busyUntil[it.id] ?: 0L) <= now }
        }
        if (candidates.isEmpty()) return null
        val statuses = coroutineScope {
            candidates.map { line ->
                async {
                    val creds = credentialsStore.credentialsFor(line) ?: return@async null
                    line to (creds to iptvRepository.lineStatus(creds))
                }
            }.map { it.await() }
        }
        _lastSeen.value = _lastSeen.value + statuses.filterNotNull().associate { it.first.id to it.second.second }
        return mutex.withLock {
            val taken = leases.values.map { it.line.id }.toSet()
            val pick = statuses.firstOrNull { entry ->
                entry != null && entry.first.id !in taken && entry.second.second?.free == true
            } ?: return@withLock null
            val (line, credsAndStatus) = pick
            Lease(slot, line, credsAndStatus.first).also {
                leases[slot] = it
                publish()
            }
        }
    }

    fun extraLines(): List<ExtraLine> = credentialsStore.getExtraLines()

    /** "Bob's line: in use by its owner", one per extra line, from the last check. */
    fun describeLines(): List<String> = extraLines().map { line ->
        val seen = _lastSeen.value[line.id]
        val leased = leases.values.any { it.line.id == line.id }
        val state = when {
            leased -> "carrying one of your screens"
            seen == null -> "not reachable"
            !seen.valid -> "login rejected"
            !seen.status.equals("Active", ignoreCase = true) -> seen.status.lowercase()
            (seen.active ?: 0) > 0 -> "in use by its owner"
            else -> "idle"
        }
        "${line.label}: $state"
    }

    /** The screen stopped, or its owner wants the line back. */
    suspend fun release(slot: Int, ownerIsWatching: Boolean = false, now: Long = android.os.SystemClock.elapsedRealtime()) {
        mutex.withLock {
            val lease = leases.remove(slot) ?: return
            if (ownerIsWatching) busyUntil[lease.line.id] = now + OWNER_GRACE_MS
            publish()
        }
    }

    suspend fun releaseAll() {
        mutex.withLock {
            leases.clear()
            publish()
        }
    }

    /** The switch is on and at least one extra line is saved, so screens must not fall back to sharing the main line. */
    fun sharingActive(): Boolean = credentialsStore.isLineSharingEnabled() && credentialsStore.getExtraLines().isNotEmpty()

    /** What a cut borrowed line looks like from the panel once our own stream is gone. */
    enum class Verdict {
        /** Nobody on it: reconnect. */
        FREE,
        /** Its owner (or someone) is streaming, or the login no longer works: hand it back. */
        TAKEN,
        /** The panel could not be reached: neither reconnect blind nor blame the owner. */
        UNKNOWN,
    }

    /**
     * After our stream on a borrowed line was cut or stopped: is somebody else streaming on it?
     * The panel is asked up to three times, a few seconds apart, because it can take a moment
     * to forget the connection we just closed; a line busy on two looks in a row is taken.
     */
    suspend fun checkAfterCut(lease: Lease): Verdict {
        var unreachable = 0
        repeat(CHECK_ATTEMPTS) { attempt ->
            val status = iptvRepository.lineStatus(lease.credentials)
            _lastSeen.value = _lastSeen.value + (lease.line.id to status)
            when (verdict(status)) {
                Look.FREE -> return Verdict.FREE
                Look.TAKEN -> return Verdict.TAKEN
                Look.BUSY -> if (attempt > 0) return Verdict.TAKEN
                Look.UNREACHABLE -> unreachable++
            }
            if (attempt < CHECK_ATTEMPTS - 1) kotlinx.coroutines.delay(RECHECK_MS)
        }
        return if (unreachable == CHECK_ATTEMPTS) Verdict.UNKNOWN else Verdict.TAKEN
    }

    /** One panel answer about a borrowed line. */
    enum class Look { FREE, TAKEN, BUSY, UNREACHABLE }

    private fun publish() {
        _labels.value = leases.mapValues { it.value.line.label }
    }

    companion object {
        /** After an owner is seen watching, leave their line alone for this long. */
        const val OWNER_GRACE_MS = 5 * 60 * 1000L

        /** Second look at a line the panel still shows busy right after our stream was cut. */
        const val RECHECK_MS = 3_000L
        const val CHECK_ATTEMPTS = 3

        /**
         * What one panel answer says about a borrowed line whose stream we just lost. BUSY may
         * still be our own connection being forgotten, so it is only conclusive when repeated.
         */
        fun verdict(status: IptvRepository.ConnectionSnapshot?): Look = when {
            status == null -> Look.UNREACHABLE
            !status.valid || !status.status.equals("Active", ignoreCase = true) -> Look.TAKEN
            (status.active ?: 0) == 0 -> Look.FREE
            else -> Look.BUSY
        }

        /**
         * Pure selection rule, kept separate so it can be unit-tested: the first candidate the
         * panel shows idle, that no other screen holds and whose owner was not seen recently.
         */
        fun choose(
            candidates: List<ExtraLine>,
            free: (ExtraLine) -> Boolean,
            taken: Set<String>,
            busyUntil: Map<String, Long>,
            now: Long,
        ): ExtraLine? = candidates.firstOrNull { line ->
            line.id !in taken && (busyUntil[line.id] ?: 0L) <= now && free(line)
        }
    }
}
