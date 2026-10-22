package com.meetnote.recorder.audio

import android.media.MediaRecorder
import java.io.File

/** 录音抽象：生产用 MediaRecorder，测试用 FakeRecorder */
interface Recorder {
    /** @throws java.io.IOException 无法写入文件 */
    /** @throws RuntimeException 麦克风被占用等 */
    fun start(file: File)

    /** 停止并返回实际时长（毫秒）；未在录音时返回已累计时长 */
    fun stop(): Long

    fun elapsedMs(): Long

    fun isRunning(): Boolean
}

/**
 * AAC / 单声道 / 44.1kHz / 96kbps → .m4a，约 85MB/2 小时。
 * 阈值与编码参数按 spec 第 8 节固定，不要随意改动。
 */
class MediaRecorderEngine : Recorder {

    private var recorder: MediaRecorder? = null
    private var startedAtMs: Long = 0L
    private var accumulatedMs: Long = 0L
    private var running: Boolean = false

    override fun start(file: File) {
        check(!running) { "已在录音中" }
        if (file.exists()) file.delete()

        val mr = MediaRecorder()
        try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioChannels(1)
            mr.setAudioSamplingRate(44_100)
            mr.setAudioEncodingBitRate(96_000)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
        } catch (e: RuntimeException) {
            runCatching { mr.reset() }
            runCatching { mr.release() }
            throw e
        }
        recorder = mr
        startedAtMs = System.currentTimeMillis()
        accumulatedMs = 0L
        running = true
    }

    override fun stop(): Long {
        if (!running) return accumulatedMs
        val mr = recorder ?: return accumulatedMs
        val elapsed = System.currentTimeMillis() - startedAtMs
        try {
            mr.stop()
        } catch (e: RuntimeException) {
            // 没录到有效数据时 stop() 会抛 IllegalStateException，保留已写入部分
        } finally {
            mr.reset()
            mr.release()
            recorder = null
            running = false
        }
        accumulatedMs = elapsed
        return elapsed
    }

    override fun elapsedMs(): Long =
        if (running) System.currentTimeMillis() - startedAtMs else accumulatedMs

    override fun isRunning(): Boolean = running
}
