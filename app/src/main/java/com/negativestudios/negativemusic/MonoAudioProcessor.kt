package com.negativestudios.negativemusic

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Optional stereo-to-mono mix for decoded 16-bit PCM music.
 * The mixed signal is copied to both output channels, so audio stays centred on headphones
 * and speakers while preserving the player's normal stereo output format.
 */
@OptIn(UnstableApi::class)
class MonoAudioProcessor(initiallyEnabled: Boolean) : BaseAudioProcessor() {
    @Volatile
    private var monoEnabled: Boolean = initiallyEnabled

    fun setMonoEnabled(enabled: Boolean) {
        monoEnabled = enabled
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (
            inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
            inputAudioFormat.channelCount == 2
        ) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        val outputBuffer = replaceOutputBuffer(bytes)
        outputBuffer.order(inputBuffer.order())

        if (!monoEnabled) {
            outputBuffer.put(inputBuffer)
        } else {
            while (inputBuffer.remaining() >= 4) {
                val left = inputBuffer.short.toInt()
                val right = inputBuffer.short.toInt()
                val mixed = ((left + right) / 2).toShort()
                outputBuffer.putShort(mixed)
                outputBuffer.putShort(mixed)
            }
        }
        outputBuffer.flip()
    }
}
