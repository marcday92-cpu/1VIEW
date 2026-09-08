package com.iptv.tv.subtitle

import com.iptv.tv.domain.model.ContentType
import com.iptv.tv.domain.model.SubtitleCandidate
import com.iptv.tv.domain.model.SubtitleQuery
import java.io.File

interface SubtitleProvider {
    val name: String
    suspend fun search(query: SubtitleQuery): List<SubtitleCandidate>
    suspend fun download(candidate: SubtitleCandidate, destDir: File, episode: Pair<Int, Int>? = null): File
}
