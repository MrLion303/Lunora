package com.negativestudios.negativemusic

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var monoProcessor: MonoAudioProcessor? = null
    private var normalizationProcessor: LoudnessNormalizationProcessor? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var volumeJob: Job? = null
    private var fadeInStartedAt = 0L

    override fun onCreate() {
        super.onCreate()

        val prefs = getSharedPreferences("negative_music", Context.MODE_PRIVATE)
        val processor = MonoAudioProcessor(prefs.getBoolean("mono", false))
        val normalizer = LoudnessNormalizationProcessor(prefs.getBoolean("normalize", false))
        monoProcessor = processor
        normalizationProcessor = normalizer
        PlaybackAudioEffects.registerMonoProcessor(processor)
        PlaybackAudioEffects.registerNormalizationProcessor(normalizer)

        val renderersFactory = object : DefaultRenderersFactory(this) {
            protected override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(processor, normalizer))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }

        val player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true
                )
                setHandleAudioBecomingNoisy(true)
            }

        PlaybackAudioEffects.attach(this, player.audioSessionId)
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                PlaybackAudioEffects.attach(this@MusicPlaybackService, audioSessionId)
            }
        })
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                fadeInStartedAt = SystemClock.elapsedRealtime()
            }
        })
        session = MediaSession.Builder(this, player).build()
        volumeJob = serviceScope.launch {
            while (isActive) {
                val crossfadeMs = prefs.getInt("crossfade", 0).coerceIn(0, 12) * 1000L
                val selectedVolume = when (prefs.getString("volume", "Normal")) {
                    "Bajo" -> 0.55f
                    "Alto" -> 1f
                    else -> 0.8f
                }
                var factor = 1f
                if (crossfadeMs > 0 && player.isPlaying) {
                    val duration = player.duration
                    val position = player.currentPosition
                    if (duration > 0L && duration != C.TIME_UNSET) {
                        val remaining = (duration - position).coerceAtLeast(0L)
                        if (remaining <= crossfadeMs) {
                            factor = (remaining.toFloat() / crossfadeMs).coerceIn(0f, 1f)
                        }
                    }
                    if (fadeInStartedAt > 0L) {
                        val elapsed = (SystemClock.elapsedRealtime() - fadeInStartedAt).coerceAtLeast(0L)
                        val fadeInFactor = (elapsed.toFloat() / crossfadeMs).coerceIn(0f, 1f)
                        factor = minOf(factor, fadeInFactor)
                        if (fadeInFactor >= 1f) fadeInStartedAt = 0L
                    }
                } else if (fadeInStartedAt > 0L) {
                    fadeInStartedAt = 0L
                }
                player.volume = selectedVolume * factor
                delay(40L)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        volumeJob?.cancel()
        volumeJob = null
        serviceScope.cancel()
        monoProcessor?.setMonoEnabled(false)
        monoProcessor = null
        normalizationProcessor?.setEnabled(false)
        normalizationProcessor = null
        PlaybackAudioEffects.registerMonoProcessor(null)
        PlaybackAudioEffects.registerNormalizationProcessor(null)
        PlaybackAudioEffects.release()
        super.onDestroy()
    }
}
