package com.meetnote.recorder.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meetnote.recorder.data.MeetingRepository

@Composable
fun CaptureScreen(
    meetingId: Long,
    repository: MeetingRepository,
    onFinished: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("拍摄页（施工中）会议 #$meetingId")
        Button(onClick = onFinished) { Text("结束会议") }
    }
}
