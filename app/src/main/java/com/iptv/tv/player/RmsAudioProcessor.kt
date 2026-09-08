package com.iptv.tv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Pass-through processor that exposes a 0..1 RMS of the PCM actually being
 * played. Used to line sidecar subtitles up with dialogue without the mic.
 */
@OptIn(UnstableApi::class)
class RmsAudioProcessor : BaseAudioProcessor() {
    @Volatile
    var level: Float = 0f
        private set

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        level = measure(inputBuffer.duplicate())
        val output = replaceOutputBuffer(remaining)
        output.put(inputBuffer)
        output.flip()
    }

    private fun measure(buffer: ByteBuffer): Float {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> {
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                val samples = buffer.asShortBuffer()
                var acc = 0.0
                var n = 0
                while (samples.hasRemaining()) {
                    val v = samples.get().toDouble()
                    acc += v * v
                    n++
                }
                if (n == 0) 0f else (sqrt(acc / n) / 32768.0).toFloat()
            }
            C.ENCODING_PCM_FLOAT -> {
                val samples = buffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                var acc = 0.0
                var n = 0
                while (samples.hasRemaining()) {
                    val v = samples.get().toDouble()
                    acc += v * v
                    n++
                }
                if (n == 0) 0f else sqrt(acc / n).toFloat()
            }
            else -> level
        }
    }
}
