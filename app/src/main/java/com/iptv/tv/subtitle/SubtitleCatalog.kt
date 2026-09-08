package com.iptv.tv.subtitle

import com.iptv.tv.domain.model.SubtitleCandidate
import com.iptv.tv.domain.model.SubtitleQuery
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.io.File
import javax.inject.Inject

class SubtitleCatalog @Inject constructor(
    private val providers: Set<@JvmSuppressWildcards SubtitleProvider>,
) {
    suspend fun search(query: SubtitleQuery): List<SubtitleCandidate> {
        val errors = mutableListOf<String>()
        val results = providers
            .sortedBy { provider -> if (provider.name == SubDlProvider.NAME) 0 else 1 }
            .flatMap { provider ->
                runCatching { provider.search(query) }
                    .onFailure { error ->
                        val message = error.message?.takeIf { it.isNotBlank() }
                        if (!message.isNullOrBlank()) errors += message
                    }
                    .getOrDefault(emptyList())
            }
            .sortedByDescending { it.matchScore }
        if (results.isEmpty() && errors.isNotEmpty()) {
            throw SubtitleException(errors.first())
        }
        return results
    }

    /**
     * @param episode the (season, episode) being played, so a season pack yields the right file.
     */
    suspend fun download(candidate: SubtitleCandidate, destDir: File, episode: Pair<Int, Int>? = null): File {
        val provider = providers.firstOrNull { it.name == candidate.provider }
            ?: providers.firstOrNull { it.name == SubDlProvider.NAME }
            ?: throw SubtitleException("No subtitle provider is available.")
        return provider.download(candidate, destDir, episode)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SubtitleModule {
    @Binds
    @IntoSet
    abstract fun bindSubDl(impl: SubDlProvider): SubtitleProvider
}
