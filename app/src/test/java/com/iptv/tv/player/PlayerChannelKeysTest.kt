package com.iptv.tv.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerChannelKeysTest {
    @Test
    fun channelKeysMapToZapDelta() {
        assertEquals(1, PlayerChannelKeys.deltaFor(KEYCODE_CHANNEL_UP))
        assertEquals(-1, PlayerChannelKeys.deltaFor(KEYCODE_CHANNEL_DOWN))
        assertNull(PlayerChannelKeys.deltaFor(KEYCODE_DPAD_CENTER))
    }

    @Test
    fun mediaSkipKeysMapToTenSeconds() {
        assertEquals(-10_000L, PlayerChannelKeys.seekDeltaFor(KEYCODE_MEDIA_REWIND))
        assertEquals(10_000L, PlayerChannelKeys.seekDeltaFor(KEYCODE_MEDIA_FAST_FORWARD))
        assertNull(PlayerChannelKeys.seekDeltaFor(KEYCODE_DPAD_CENTER))
        assertNull(PlayerChannelKeys.seekDeltaFor(KEYCODE_ENTER))
    }

    @Test
    fun playPauseKeysRecognizedAndOkIsNotATransportKey() {
        assertTrue(PlayerChannelKeys.isPlayPauseKey(KEYCODE_MEDIA_PLAY_PAUSE))
        assertTrue(PlayerChannelKeys.isPlayPauseKey(KEYCODE_MEDIA_PLAY))
        assertTrue(PlayerChannelKeys.isPlayPauseKey(KEYCODE_MEDIA_PAUSE))
        assertFalse(PlayerChannelKeys.isPlayPauseKey(KEYCODE_DPAD_CENTER))
        assertFalse(PlayerChannelKeys.isPlayPauseKey(KEYCODE_ENTER))
    }

    companion object {
        private const val KEYCODE_DPAD_CENTER = 23
        private const val KEYCODE_ENTER = 66
        private const val KEYCODE_MEDIA_PLAY_PAUSE = 85
        private const val KEYCODE_MEDIA_PLAY = 126
        private const val KEYCODE_MEDIA_PAUSE = 127
        private const val KEYCODE_MEDIA_REWIND = 89
        private const val KEYCODE_MEDIA_FAST_FORWARD = 90
        private const val KEYCODE_CHANNEL_UP = 166
        private const val KEYCODE_CHANNEL_DOWN = 167
    }
}
