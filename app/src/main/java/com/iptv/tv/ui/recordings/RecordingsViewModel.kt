package com.iptv.tv.ui.recordings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.data.db.RecordingEntity
import com.iptv.tv.data.db.ScheduledRecordingEntity
import com.iptv.tv.domain.model.ContentType
import com.iptv.tv.player.PlaybackController
import com.iptv.tv.player.PlaybackOpenedFrom
import com.iptv.tv.player.PlaybackSession
import com.iptv.tv.service.RecordingManager
import com.iptv.tv.data.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import com.iptv.tv.ui.launchSafely
import java.io.File
import javax.inject.Inject

@HiltViewModel
class RecordingsViewModel @Inject constructor(
    private val recordingManager: RecordingManager,
    private val playbackController: PlaybackController,
    private val recordingDao: com.iptv.tv.data.db.RecordingDao,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    val recordings: StateFlow<List<RecordingEntity>> = recordingManager.observeRecordings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val scheduled: StateFlow<List<ScheduledRecordingEntity>> = recordingManager.observeScheduled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun play(item: RecordingEntity) {
        playbackController.play(
            PlaybackSession(
                type = ContentType.MOVIE,
                streamId = item.channelStreamId,
                title = item.title,
                url = File(item.filePath).toURI().toString(),
                contentKey = "rec_${item.id}",
                startPositionMs = item.positionMs,
                openedFrom = PlaybackOpenedFrom.LIBRARY,
            ),
        )
    }

    fun delete(item: RecordingEntity) {
        launchSafely("RecordingsViewModel.delete") {
            File(item.filePath).delete()
            recordingDao.delete(item.id)
            profileRepository.removeFromAllContinueWatching("rec_${item.id}")
        }
    }

    fun rename(item: RecordingEntity, title: String) {
        launchSafely("RecordingsViewModel.rename") { recordingDao.rename(item.id, title) }
    }

    fun protect(item: RecordingEntity) {
        launchSafely("RecordingsViewModel.protect") { recordingDao.setProtected(item.id, !item.protectedFromAutoDelete) }
    }

    fun cancel(item: ScheduledRecordingEntity) {
        launchSafely("RecordingsViewModel.cancel") { recordingManager.cancelScheduled(item.id) }
    }
}
