package com.meetnote.recorder.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.meetnote.recorder.audio.MeetingService
import com.meetnote.recorder.audio.RecordingState
import com.meetnote.recorder.data.MeetingRepository

@Composable
fun CaptureScreen(
    meetingId: Long,
    repository: MeetingRepository,
    onFinished: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    val context = LocalContext.current
    val status by RecordingState.status.collectAsState()

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("拍摄页（施工中）会议 #$meetingId")
        if (status.running && status.meetingId == meetingId) {
            Button(onClick = { MeetingService.stop(context) }) { Text("停止录音") }
        } else {
            // 其他会议正在录音时不提供"开始录音"，防止跨会议抢占（服务端另有硬防护）
            Button(
                onClick = { MeetingService.start(context, meetingId) },
                enabled = !status.running || status.meetingId == meetingId,
            ) { Text("开始录音") }
        }
        TextButton(onClick = onFinished) { Text("结束会议") }
    }
}
