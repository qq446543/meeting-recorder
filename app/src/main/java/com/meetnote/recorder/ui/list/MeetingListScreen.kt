package com.meetnote.recorder.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.meetnote.recorder.audio.RecordingState
import com.meetnote.recorder.data.MeetingStats
import com.meetnote.recorder.data.SpaceCheck
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingListScreen(
    meetings: StateFlow<List<MeetingStats>>,
    navigateTo: StateFlow<Long?>,
    onConsumeNavigation: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onOpenCapture: (Long) -> Unit,
    onContinueCapture: (Long) -> Unit,
    onCreateMeeting: (String) -> Unit,
    onOpenGuide: () -> Unit,
) {
    val context = LocalContext.current
    val list by meetings.collectAsState()
    // 全局录音状态（同 CaptureViewModel 的数据源）：录音中禁止并发新建会议（WHOLE-BRANCH I2）
    val recording by RecordingState.status.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var recordingBlocked by remember { mutableStateOf(false) }
    var spaceWarning by remember { mutableStateOf<String?>(null) }

    // 新建会议成功后跳到拍摄页，随即清掉一次性事件，避免配置变更后重复跳转
    val target by navigateTo.collectAsState()
    LaunchedEffect(target) {
        target?.let { id ->
            onOpenCapture(id)
            onConsumeNavigation()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("会议学习记录") },
                actions = {
                    IconButton(onClick = onOpenGuide) {
                        Icon(Icons.Default.Settings, contentDescription = "后台设置引导")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (recording.running) {
                    // 录音进行中不弹标题对话框，防止幽灵时长的第二场会议（WHOLE-BRANCH I2）
                    recordingBlocked = true
                } else {
                    title = ""
                    showDialog = true
                }
            }) {
                Icon(Icons.Default.Add, contentDescription = "新建会议")
            }
        },
    ) { padding ->
        if (list.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("还没有会议记录", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Text(
                    "点击右下角 ＋ 开始一场新的会议：\n录音会持续进行，翻到下一页 PPT 会自动拍照。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.id }) { stats ->
                    MeetingCard(
                        stats = stats,
                        onClick = {
                            // 进行中会议可回到拍摄页续拍（spec §12 重入）；已结束进详情
                            if (stats.endedAt == null) onContinueCapture(stats.id)
                            else onOpenDetail(stats.id)
                        },
                    )
                }
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("新建会议") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("会议主题") },
                    singleLine = true,
                    placeholder = { Text("例如：产品需求评审") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val t = title.trim().ifBlank { "未命名会议" }
                        showDialog = false
                        val warning = SpaceCheck.warning(
                            // 用应用可用空间近似：FileStore 的 freeBytes 在 Repository 上，
                            // 列表页没有仓库引用时可用 context.filesDir.usableSpace
                            context.filesDir.usableSpace,
                        )
                        if (warning == null) {
                            onCreateMeeting(t)
                        } else {
                            spaceWarning = warning
                        }
                    },
                ) { Text("开始") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("取消") }
            },
        )
    }

    if (recordingBlocked) {
        AlertDialog(
            onDismissRequest = { recordingBlocked = false },
            title = { Text("已有会议正在录音") },
            text = { Text("请先结束当前会议后再新建。") },
            confirmButton = {
                TextButton(onClick = { recordingBlocked = false }) { Text("知道了") }
            },
        )
    }

    spaceWarning?.let { msg ->
        AlertDialog(
            onDismissRequest = { spaceWarning = null },
            title = { Text("空间可能不够") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = {
                    spaceWarning = null
                    onCreateMeeting(title.trim().ifBlank { "未命名会议" })
                }) { Text("仍然开始") }
            },
            dismissButton = {
                TextButton(onClick = { spaceWarning = null }) { Text("先去清理") }
            },
        )
    }
}

@Composable
private fun MeetingCard(stats: MeetingStats, onClick: () -> Unit) {
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(stats.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(4.dp))
            Text(
                buildString {
                    append(fmt.format(Date(stats.createdAt)))
                    append("　·　")
                    append(stats.pageCount)
                    append(" 页 PPT")
                    if (stats.audioDurationMs > 0) {
                        append("　·　录音 ")
                        append(formatDuration(stats.audioDurationMs))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** 时长格式化：超过 1 小时显示 1:02:03，否则 02:03 */
fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%02d:%02d", m, s)
}
