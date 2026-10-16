package com.negativestudios.negativemusic

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@OptIn(UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var monoProcessor: MonoAudioProcessor? = null

    override fun onCreate() {
        super.onCreate()

        val prefs = getSharedPreferences("negative_music", Context.MODE_PRIVATE)
        val processor = MonoAudioProcessor(prefs.getBoolean("mono", false))
        monoProcessor = processor
        PlaybackAudioEffects.registerMonoProcessor(processor)

        val renderersFactory = object : DefaultRenderersFactory(this) {
            protected override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(processor))
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
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        monoProcessor?.setMonoEnabled(false)
        monoProcessor = null
        PlaybackAudioEffects.registerMonoProcessor(null)
        PlaybackAudioEffects.release()
        super.onDestroy()
    }
}
