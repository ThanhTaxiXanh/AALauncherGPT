package com.cartaxi.launcher

import androidx.media3.common.AudioAttributes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Media3-backed local audio only. Not a browsing library or an arbitrary
 * custom dashboard player: system media controls/automotive host govern UI.
 */
class LocalAudioService : MediaSessionService() {
    private var localSession: MediaSession? = null
    private var player: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
        }
        player = exo
        localSession = MediaSession.Builder(this, exo).build()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = localSession

    override fun onDestroy() {
        localSession?.release()
        localSession = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
