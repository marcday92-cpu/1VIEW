package com.iptv.tv.data.repository

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Layout math for generated 16:9 Home channel cards. Full-bleed photos cover
 * the card; logos (transparent or extreme aspect) are contained with an inset
 * on a filled background so every tile is the same size.
 */
object ChannelCardLayout {
    const val WIDTH = 480
    const val HEIGHT = 270
    const val CARD_ASPECT = WIDTH.toFloat() / HEIGHT
    const val CONTAIN_INSET = 0.07f
    const val COVER_ASPECT_SLACK = 0.38f
    const val FULL_BLEED_OPAQUE = 0.88f

    data class Rect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    fun shouldCover(srcWidth: Int, srcHeight: Int, opaqueRatio: Float): Boolean {
        if (srcWidth <= 0 || srcHeight <= 0) return false
        if (opaqueRatio < FULL_BLEED_OPAQUE) return false
        val aspect = srcWidth.toFloat() / srcHeight
        return abs(aspect - CARD_ASPECT) <= COVER_ASPECT_SLACK
    }

    fun destRect(
        srcWidth: Int,
        srcHeight: Int,
        opaqueRatio: Float,
        cardWidth: Int = WIDTH,
        cardHeight: Int = HEIGHT,
    ): Rect {
        if (srcWidth <= 0 || srcHeight <= 0) {
            return Rect(0, 0, cardWidth, cardHeight)
        }
        return if (shouldCover(srcWidth, srcHeight, opaqueRatio)) {
            cover(srcWidth, srcHeight, cardWidth, cardHeight)
        } else {
            contain(srcWidth, srcHeight, cardWidth, cardHeight, CONTAIN_INSET)
        }
    }

    private fun cover(srcW: Int, srcH: Int, cardW: Int, cardH: Int): Rect {
        val scale = max(cardW.toFloat() / srcW, cardH.toFloat() / srcH)
        val w = (srcW * scale).roundToInt().coerceAtLeast(1)
        val h = (srcH * scale).roundToInt().coerceAtLeast(1)
        val left = (cardW - w) / 2
        val top = (cardH - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    private fun contain(srcW: Int, srcH: Int, cardW: Int, cardH: Int, inset: Float): Rect {
        val boxW = cardW * (1f - 2f * inset)
        val boxH = cardH * (1f - 2f * inset)
        val scale = min(boxW / srcW, boxH / srcH)
        val w = (srcW * scale).roundToInt().coerceAtLeast(1)
        val h = (srcH * scale).roundToInt().coerceAtLeast(1)
        val left = (cardW - w) / 2
        val top = (cardH - h) / 2
        return Rect(left, top, left + w, top + h)
    }
}
