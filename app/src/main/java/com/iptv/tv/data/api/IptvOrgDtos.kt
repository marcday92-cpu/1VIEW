package com.iptv.tv.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class IptvOrgChannelDto(
    val id: String? = null,
    val name: String? = null,
    val country: String? = null,
    val categories: List<String> = emptyList(),
    @SerialName("alt_names") val altNames: List<String> = emptyList(),
    @SerialName("is_nsfw") val isNsfw: Boolean = false,
    val closed: String? = null,
    @SerialName("replaced_by") val replacedBy: String? = null,
)

@Serializable
data class IptvOrgStreamDto(
    val channel: String? = null,
    val url: String? = null,
    val quality: String? = null,
    val label: String? = null,
    val referrer: String? = null,
    @SerialName("http_referrer") val httpReferrer: String? = null,
    @SerialName("user_agent") val userAgent: String? = null,
) {
    val headerReferrer: String? get() = httpReferrer?.takeIf { it.isNotBlank() } ?: referrer
}

@Serializable
data class IptvOrgLogoDto(
    val channel: String? = null,
    val url: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("in_use") val inUse: Boolean = true,
    val tags: List<String> = emptyList(),
)

@Serializable
data class IptvOrgCountryDto(
    val name: String? = null,
    val code: String? = null,
)

@Serializable
data class IptvOrgBlocklistDto(
    val channel: String? = null,
    val reason: String? = null,
)
