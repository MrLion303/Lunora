package com.negativestudios.negativemusic

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-device BPM and key estimation used to rank the next few songs in Magic Shuffle.
 * No audio is uploaded. Signatures are cached in the app's preferences.
 */
object SmartShuffle {
    data class Signature(
        val bpm: Double,
        val root: Int,
        val minor: Boolean,
        val keyConfidence: Double
    )

    private const val MAX_ANALYSIS_SECONDS = 20
    private const val TARGET_SAMPLE_RATE = 11025
    private const val CACHE_PREFIX = "smart_shuffle_signature_"
    private val noteNames = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")

    private val majorProfile = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val minorProfile = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    suspend fun signatureFor(context: Context, prefs: SharedPreferences, song: Song): Signature? {
        val cacheKey = CACHE_PREFIX + sha256(song.uri)
        val cached = prefs.getString(cacheKey, null)
        if (cached == "none") return null
        if (cached != null) parseSignature(cached)?.let { return it }

        return withContext(Dispatchers.IO) {
            val jobContext = currentCoroutineContext()
            val decoded = decodePcm(context, song.uri) { jobContext.ensureActive() }
            val signature = decoded?.let { (samples, sampleRate) -> analyse(samples, sampleRate) }
            prefs.edit().putString(
                cacheKey,
                signature?.let { "${it.bpm}|${it.root}|${it.minor}|${it.keyConfidence}" } ?: "none"
            ).apply()
            signature
        }
    }

    fun compatibilityCost(reference: Signature, candidate: Signature): Double {
        var score = 0.0
        var weights = 0.0
        if (reference.bpm > 0.0 && candidate.bpm > 0.0) {
            val bpmDifference = minOf(
                abs(reference.bpm - candidate.bpm),
                abs(reference.bpm - candidate.bpm * 2.0),
                abs(reference.bpm * 2.0 - candidate.bpm)
            )
            score += (bpmDifference / 15.0).coerceIn(0.0, 2.0) * 0.65
            weights += 0.65
        }
        if (reference.keyConfidence >= 0.025 && candidate.keyConfidence >= 0.025) {
            score += keyCost(reference, candidate) * 0.35
            weights += 0.35
        }
        return if (weights == 0.0) Double.POSITIVE_INFINITY else score / weights
    }

    fun keyName(signature: Signature): String =
        noteNames[signature.root.coerceIn(0, 11)] + if (signature.minor) " minor" else " major"

    private fun parseSignature(value: String): Signature? = runCatching {
        val parts = value.split('|')
        Signature(parts[0].toDouble(), parts[1].toInt(), parts[2].toBoolean(), parts[3].toDouble())
    }.getOrNull()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private data class DecodedPcm(val samples: FloatArray, val sampleRate: Int)

    private fun decodePcm(context: Context, uriValue: String, checkActive: () -> Unit): DecodedPcm? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(uriValue), null)
            var trackIndex = -1
            var sourceFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = index
                    sourceFormat = format
                    break
                }
            }
            if (trackIndex < 0 || sourceFormat == null) return null

            extractor.selectTrack(trackIndex)
            val mime = sourceFormat.getString(MediaFormat.KEY_MIME) ?: return null
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(sourceFormat, null, null, 0)
            decoder.start()

            val maxSamples = TARGET_SAMPLE_RATE * MAX_ANALYSIS_SECONDS + 4096
            val samples = FloatArray(maxSamples)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var outputRate = sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var outputChannels = sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var decimation = ((outputRate + TARGET_SAMPLE_RATE - 1) / TARGET_SAMPLE_RATE).coerceAtLeast(1)
            var frameCount = 0
            var sampleCount = 0
            var decimationSum = 0.0
            var decimationCount = 0
            var iterations = 0

            while (!outputDone && sampleCount < maxSamples && iterations++ < 10000) {
                checkActive()

                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                        if (inputBuffer == null) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, 0)
                        } else {
                            inputBuffer.clear()
                            val sampleTime = extractor.sampleTime
                            val sampleSize = if (sampleTime >= 0L && sampleTime < MAX_ANALYSIS_SECONDS * 1_000_000L) {
                                extractor.readSampleData(inputBuffer, 0)
                            } else -1

                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, extractor.sampleFlags)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(info, 10_000L)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = decoder.outputFormat
                        outputRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outputChannels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        pcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else AudioFormat.ENCODING_PCM_16BIT
                        decimation = ((outputRate + TARGET_SAMPLE_RATE - 1) / TARGET_SAMPLE_RATE).coerceAtLeast(1)
                    }
                    outputIndex >= 0 -> {
                        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val output = decoder.getOutputBuffer(outputIndex)
                            if (output != null && (pcmEncoding == AudioFormat.ENCODING_PCM_16BIT ||
                                        pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT ||
                                        pcmEncoding == AudioFormat.ENCODING_PCM_8BIT)) {
                                val pcm = output.duplicate().order(ByteOrder.nativeOrder())
                                pcm.position(info.offset)
                                pcm.limit(info.offset + info.size)
                                val bytesPerSample = when (pcmEncoding) {
                                    AudioFormat.ENCODING_PCM_FLOAT -> 4
                                    AudioFormat.ENCODING_PCM_8BIT -> 1
                                    else -> 2
                                }
                                val bytesPerFrame = bytesPerSample * outputChannels
                                while (pcm.remaining() >= bytesPerFrame && sampleCount < maxSamples) {
                                    var total = 0.0
                                    repeat(outputChannels) {
                                        val value = when (pcmEncoding) {
                                            AudioFormat.ENCODING_PCM_FLOAT -> pcm.float.toDouble()
                                            AudioFormat.ENCODING_PCM_8BIT -> ((pcm.get().toInt() and 0xFF) - 128) / 128.0
                                            else -> pcm.short.toDouble() / 32768.0
                                        }
                                        total += value
                                    }
                                    decimationSum += total / outputChannels
                                    decimationCount++
                                    frameCount++
                                    if (decimationCount >= decimation) {
                                        samples[sampleCount++] = (decimationSum / decimationCount).toFloat()
                                        decimationSum = 0.0
                                        decimationCount = 0
                                    }
                                }
                            }
                        }
                        outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            if (sampleCount < outputRate / 2) return null
            return DecodedPcm(samples.copyOf(sampleCount), (outputRate / decimation).coerceAtLeast(1))
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun analyse(samples: FloatArray, sampleRate: Int): Signature? {
        if (samples.size < sampleRate * 4 || sampleRate <= 0) return null
        val bpm = estimateBpm(samples, sampleRate)
        val key = estimateKey(samples, sampleRate)
        if (bpm <= 0.0 && key.second < 0.025) return null
        return Signature(bpm, key.first, key.third, key.second)
    }

    private fun estimateBpm(samples: FloatArray, sampleRate: Int): Double {
        val frameRate = 100
        val frameSize = (sampleRate / frameRate).coerceAtLeast(1)
        val frameCount = samples.size / frameSize
        if (frameCount < 600) return 0.0

        val rms = DoubleArray(frameCount)
        for (frame in 0 until frameCount) {
            val start = frame * frameSize
            var sum = 0.0
            for (i in 0 until frameSize) {
                val value = samples[start + i].toDouble()
                sum += value * value
            }
            rms[frame] = sqrt(sum / frameSize)
        }
        val onset = DoubleArray(frameCount)
        for (i in 2 until frameCount) {
            val localFloor = (rms[i - 1] + rms[i - 2]) * 0.5
            onset[i] = max(0.0, rms[i] - localFloor)
        }
        val mean = onset.average()
        for (i in onset.indices) onset[i] = max(0.0, onset[i] - mean)
        if (onset.sumOf { it * it } < 1e-8) return 0.0

        val minLag = (frameRate * 60 / 180).coerceAtLeast(1)
        val maxLag = min(frameRate * 60 / 60, frameCount / 3)
        var bestLag = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            var cross = 0.0
            var leftEnergy = 0.0
            var rightEnergy = 0.0
            for (i in lag until frameCount) {
                val a = onset[i]
                val b = onset[i - lag]
                cross += a * b
                leftEnergy += a * a
                rightEnergy += b * b
            }
            val score = if (leftEnergy > 0.0 && rightEnergy > 0.0) cross / sqrt(leftEnergy * rightEnergy) else 0.0
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag == 0 || bestScore < 0.025) return 0.0
        return (frameRate * 60.0 / bestLag).coerceIn(60.0, 180.0)
    }

    private fun estimateKey(samples: FloatArray, sampleRate: Int): Triple<Int, Double, Boolean> {
        val size = 4096
        val hop = 2048
        if (samples.size < size) return Triple(0, 0.0, false)
        val chroma = DoubleArray(12)
        val real = DoubleArray(size)
        val imaginary = DoubleArray(size)
        var frames = 0
        val limit = min(samples.size - size, sampleRate * 18)
        var start = 0
        while (start <= limit) {
            for (i in 0 until size) {
                val hann = 0.5 - 0.5 * cos(2.0 * PI * i / (size - 1))
                real[i] = samples[start + i] * hann
                imaginary[i] = 0.0
            }
            fft(real, imaginary)
            for (bin in 2 until size / 2) {
                val frequency = bin.toDouble() * sampleRate / size
                if (frequency < 50.0 || frequency > 3500.0) continue
                val midi = (69.0 + 12.0 * ln(frequency / 440.0) / ln(2.0)).roundToInt()
                if (midi in 28..100) {
                    chroma[((midi % 12) + 12) % 12] += hypot(real[bin], imaginary[bin])
                }
            }
            frames++
            start += hop
        }
        if (frames == 0 || chroma.sum() <= 1e-8) return Triple(0, 0.0, false)

        val majorMean = majorProfile.average()
        val minorMean = minorProfile.average()
        val chromaMean = chroma.average()
        val chromaCentered = DoubleArray(12) { chroma[it] - chromaMean }
        val chromaNorm = sqrt(chromaCentered.sumOf { it * it })
        if (chromaNorm <= 1e-10) return Triple(0, 0.0, false)

        var bestRoot = 0
        var bestMinor = false
        var bestScore = Double.NEGATIVE_INFINITY
        var secondScore = Double.NEGATIVE_INFINITY
        for (root in 0..11) {
            for (minor in listOf(false, true)) {
                val profile = if (minor) minorProfile else majorProfile
                val centered = DoubleArray(12) { profile[it] - if (minor) minorMean else majorMean }
                val profileNorm = sqrt(centered.sumOf { it * it })
                var dot = 0.0
                for (i in 0..11) dot += chromaCentered[(root + i) % 12] * centered[i]
                val score = dot / (chromaNorm * profileNorm)
                if (score > bestScore) {
                    secondScore = bestScore
                    bestScore = score
                    bestRoot = root
                    bestMinor = minor
                } else if (score > secondScore) {
                    secondScore = score
                }
            }
        }
        return Triple(bestRoot, (bestScore - secondScore).coerceIn(0.0, 1.0), bestMinor)
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tempR = real[i]; real[i] = real[j]; real[j] = tempR
                val tempI = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = tempI
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wLengthR = cos(angle)
            val wLengthI = sin(angle)
            var base = 0
            while (base < n) {
                var wR = 1.0
                var wI = 0.0
                for (k in 0 until length / 2) {
                    val even = base + k
                    val odd = even + length / 2
                    val oddR = real[odd] * wR - imaginary[odd] * wI
                    val oddI = real[odd] * wI + imaginary[odd] * wR
                    real[odd] = real[even] - oddR
                    imaginary[odd] = imaginary[even] - oddI
                    real[even] += oddR
                    imaginary[even] += oddI
                    val nextWR = wR * wLengthR - wI * wLengthI
                    wI = wR * wLengthI + wI * wLengthR
                    wR = nextWR
                }
                base += length
            }
            length = length shl 1
        }
    }

    private fun keyCost(a: Signature, b: Signature): Double {
        if (a.root == b.root && a.minor == b.minor) return 0.0
        val distance = min((a.root - b.root + 12) % 12, (b.root - a.root + 12) % 12)
        if (a.root == b.root) return 0.22
        val relativeMajorMinor = (a.minor && !b.minor && (a.root + 3) % 12 == b.root) ||
            (!a.minor && b.minor && (b.root + 3) % 12 == a.root)
        if (relativeMajorMinor) return 0.08
        if (distance == 5 || distance == 7) return 0.18
        if (distance == 1) return 0.45
        return 0.75
    }
}
