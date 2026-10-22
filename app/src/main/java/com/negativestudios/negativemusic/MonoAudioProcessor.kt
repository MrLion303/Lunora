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
    private var pcmEncoding: Int = C.ENCODING_PCM_16BIT

    fun setMonoEnabled(enabled: Boolean) {
        monoEnabled = enabled
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        pcmEncoding = inputAudioFormat.encoding
        return if (
            inputAudioFormat.channelCount == 2 &&
            (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
                inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
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
            when (pcmEncoding) {
                C.ENCODING_PCM_16BIT -> {
                    while (inputBuffer.remaining() >= 4) {
                        val left = inputBuffer.short.toInt()
                        val right = inputBuffer.short.toInt()
                        val mixed = ((left + right) / 2).toShort()
                        outputBuffer.putShort(mixed)
                        outputBuffer.putShort(mixed)
                    }
                }
                C.ENCODING_PCM_FLOAT -> {
                    while (inputBuffer.remaining() >= 8) {
                        val left = inputBuffer.float
                        val right = inputBuffer.float
                        val mixed = ((left + right) / 2f).coerceIn(-1f, 1f)
                        outputBuffer.putFloat(mixed)
                        outputBuffer.putFloat(mixed)
                    }
                }
                else -> outputBuffer.put(inputBuffer)
            }
        }
        outputBuffer.flip()
    }
}
