package com.negativestudios.negativemusic

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * Lightweight real-time loudness leveling for decoded PCM.
 *
 * It tracks the signal envelope, moves the gain gradually toward a target RMS level,
 * and limits peaks to avoid clipping. This is adaptive normalization rather than
 * metadata-based EBU R128 analysis.
 */
@OptIn(UnstableApi::class)
class LoudnessNormalizationProcessor(initiallyEnabled: Boolean) : BaseAudioProcessor() {
    @Volatile
    private var enabled = initiallyEnabled

    private var encoding = C.ENCODING_PCM_16BIT
    private var channels = 2
    private var gain = 1f

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) gain = 1f
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        encoding = inputAudioFormat.encoding
        channels = inputAudioFormat.channelCount
        return if (
            channels > 0 &&
            (encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT)
        ) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun onFlush() {
        gain = 1f
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val output = replaceOutputBuffer(inputBuffer.remaining())
        output.order(inputBuffer.order())

        if (!enabled) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        when (encoding) {
            C.ENCODING_PCM_16BIT -> processPcm16(inputBuffer, output)
            C.ENCODING_PCM_FLOAT -> processFloat(inputBuffer, output)
            else -> output.put(inputBuffer)
        }
        output.flip()
    }

    private fun processPcm16(input: ByteBuffer, output: ByteBuffer) {
        val frameBytes = channels * 2
        val sampleCount = input.remaining() / 2
        if (sampleCount == 0 || frameBytes == 0) return

        var sumSquares = 0.0
        var peak = 0f
        val samples = ShortArray(sampleCount)
        var index = 0
        while (input.remaining() >= 2 && index < sampleCount) {
            val sample = input.short
            samples[index++] = sample
            val normalized = sample / 32768f
            sumSquares += normalized * normalized
            peak = maxOf(peak, kotlin.math.abs(normalized))
        }
        applyGainAndWrite(samples, sumSquares, peak, output)
    }

    private fun applyGainAndWrite(samples: ShortArray, sumSquares: Double, peak: Float, output: ByteBuffer) {
        val rms = sqrt(sumSquares / samples.size).toFloat()
        val target = if (rms > 0.0005f) (0.18f / rms).coerceIn(0.5f, 4f) else 1f
        val peakLimited = if (peak > 0f) minOf(target, 0.96f / peak) else target
        gain += (peakLimited - gain) * if (peakLimited < gain) 0.45f else 0.08f
        samples.forEach { sample ->
            output.putShort((sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
    }

    private fun processFloat(input: ByteBuffer, output: ByteBuffer) {
        val sampleCount = input.remaining() / 4
        if (sampleCount == 0) return
        val samples = FloatArray(sampleCount)
        var sumSquares = 0.0
        var peak = 0f
        var index = 0
        while (input.remaining() >= 4 && index < sampleCount) {
            val sample = input.float
            samples[index++] = sample
            sumSquares += sample * sample
            peak = maxOf(peak, kotlin.math.abs(sample))
        }
        val rms = sqrt(sumSquares / samples.size).toFloat()
        val target = if (rms > 0.0005f) (0.18f / rms).coerceIn(0.5f, 4f) else 1f
        val peakLimited = if (peak > 0f) minOf(target, 0.96f / peak) else target
        gain += (peakLimited - gain) * if (peakLimited < gain) 0.45f else 0.08f
        samples.forEach { output.putFloat((it * gain).coerceIn(-0.96f, 0.96f)) }
    }
}
