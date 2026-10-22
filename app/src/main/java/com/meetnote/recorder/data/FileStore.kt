package com.meetnote.recorder.data

import java.io.File
import java.util.Locale

/**
 * 会议相关文件的路径管理。全部落在 app 私有目录下，因此不需要任何存储权限。
 * 目录布局： <root>/meetings/<meetingId>/page_0001.jpg … audio.m4a
 */
class FileStore(private val root: File) {

    fun meetingDir(meetingId: Long): File =
        File(root, "meetings/$meetingId").apply { mkdirs() }

    /** 页码零填充，保证按文件名字典序 == 按页序 */
    fun pageFile(meetingId: Long, index: Int): File =
        File(meetingDir(meetingId), String.format(Locale.ROOT, "page_%04d.jpg", index))

    fun audioFile(meetingId: Long): File =
        File(meetingDir(meetingId), "audio.m4a")

    /** 删除整场会议的文件，返回是否真的删掉了东西（幂等） */
    fun deleteMeeting(meetingId: Long): Boolean {
        val dir = File(root, "meetings/$meetingId")
        if (!dir.exists()) return false
        return dir.deleteRecursively()
    }

    fun freeBytes(): Long = root.usableSpace
}
