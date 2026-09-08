package com.iptv.tv.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.data.repository.IptvRepository
import com.iptv.tv.data.repository.ProfileRepository
import com.iptv.tv.domain.model.Category
import com.iptv.tv.domain.model.FeedType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import com.iptv.tv.ui.launchSafely
import javax.inject.Inject

data class EditableCategory(
    val category: Category,
    val hidden: Boolean,
    val pinned: Boolean,
    val sortOrder: Int,
    val displayName: String,
    val mergedIntoId: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val iptvRepository: IptvRepository,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val feed = MutableStateFlow(FeedType.LIVE)
    val selectedFeed: StateFlow<FeedType> = feed.asStateFlow()

    val items: StateFlow<List<EditableCategory>> = profileRepository.observeActiveProfileId()
        .flatMapLatest { raw ->
            val profileId = raw ?: profileRepository.getActiveProfileId()
            feed.flatMapLatest { feedType ->
                val catsFlow = when (feedType) {
                    FeedType.LIVE -> iptvRepository.observeLiveCategories()
                    FeedType.VOD -> iptvRepository.observeVodCategories()
                    FeedType.SERIES -> iptvRepository.observeSeriesCategories()
                }
                combine(catsFlow, profileRepository.observeCategoryOverrides(profileId, feedType)) { cats, overrides ->
                    val map = overrides.associateBy { it.providerCategoryId }
                    cats.map { cat ->
                        val ov = map[cat.id]
                        val manualOrder = ov?.sortOrder?.takeIf { it >= 0 }
                        EditableCategory(
                            category = cat,
                            hidden = ov?.hidden == true,
                            pinned = ov?.pinned == true,
                            sortOrder = manualOrder ?: cat.sortOrder,
                            displayName = ov?.displayName ?: cat.name,
                            mergedIntoId = ov?.mergedIntoId,
                        )
                    }.sortedWith(
                        compareByDescending<EditableCategory> { it.pinned }
                            .thenBy { it.hidden }
                            .thenBy { it.sortOrder },
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectFeed(type: FeedType) {
        feed.value = type
    }

    fun combineInto(source: EditableCategory, targetId: String) {
        launchSafely("CategoriesViewModel.combine") {
            profileRepository.combineCategory(
                profileRepository.getActiveProfileId(),
                feed.value,
                source.category.id,
                targetId,
            )
        }
    }

    fun uncombine(item: EditableCategory) {
        launchSafely("CategoriesViewModel.uncombine") {
            profileRepository.uncombineCategory(
                profileRepository.getActiveProfileId(),
                feed.value,
                item.category.id,
            )
        }
    }
    fun hide(item: EditableCategory, hidden: Boolean) = update(item, hidden = hidden)
    fun pin(item: EditableCategory, pinned: Boolean) = update(item, pinned = pinned)
    fun rename(item: EditableCategory, name: String) = update(item, displayName = name)

    fun move(item: EditableCategory, delta: Int) {
        launchSafely("CategoriesViewModel.move") {
            val list = items.value.toMutableList()
            if (list.isEmpty()) return@launchSafely
            val i = list.indexOfFirst { it.category.id == item.category.id }
            val j = (i + delta).coerceIn(0, list.lastIndex)
            if (i < 0 || i == j) return@launchSafely
            val a = list[i]
            list[i] = list[j]
            list[j] = a
            val profileId = profileRepository.getActiveProfileId()
            val feedType = feed.value
            list.forEachIndexed { index, cat ->
                profileRepository.setCategoryOverride(
                    profileId, feedType, cat.category.id,
                    displayName = cat.displayName,
                    sortOrder = index,
                    hidden = cat.hidden,
                    pinned = cat.pinned,
                )
            }
        }
    }

    private fun update(
        item: EditableCategory,
        hidden: Boolean? = null,
        pinned: Boolean? = null,
        displayName: String? = null,
    ) {
        launchSafely("CategoriesViewModel.update") {
            profileRepository.setCategoryOverride(
                profileRepository.getActiveProfileId(),
                feed.value,
                item.category.id,
                displayName = displayName ?: item.displayName,
                hidden = hidden ?: item.hidden,
                pinned = pinned ?: item.pinned,
            )
        }
    }
}
