package com.meetnote.recorder.ui.detail

import android.media.MediaPlayer
import java.io.File

interface AudioPlayer {
    /** @return 文件是否可播放 */
    fun prepare(file: File): Boolean
    fun play(): Boolean
    fun pause()
    fun isPlaying(): Boolean
    fun positionMs(): Long
    fun durationMs(): Long
    fun release()
}

class MediaPlayerAudioPlayer : AudioPlayer {

    private var player: MediaPlayer? = null
    private var prepared = false

    override fun prepare(file: File): Boolean {
        release()
        if (!file.exists()) return false
        return try {
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            player = mp
            prepared = true
            true
        } catch (t: Throwable) {
            prepared = false
            false
        }
    }

    override fun play(): Boolean {
        val mp = player ?: return false
        return try {
            mp.start()
            true
        } catch (t: Throwable) {
            false
        }
    }

    override fun pause() {
        runCatching { player?.pause() }
    }

    override fun isPlaying(): Boolean = runCatching { player?.isPlaying == true }.getOrDefault(false)

    override fun positionMs(): Long =
        runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)

    override fun durationMs(): Long =
        runCatching { player?.duration?.toLong() ?: 0L }.getOrDefault(0L)

    override fun release() {
        runCatching { player?.release() }
        player = null
        prepared = false
    }
}
