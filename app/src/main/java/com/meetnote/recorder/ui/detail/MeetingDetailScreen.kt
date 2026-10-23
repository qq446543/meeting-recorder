package com.meetnote.recorder.ui.detail

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meetnote.recorder.data.MeetingRepository
import com.meetnote.recorder.data.SlidePage
import com.meetnote.recorder.ui.list.formatDuration
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MeetingDetailScreen(
    meetingId: Long,
    repository: MeetingRepository,
    onBack: () -> Unit,
) {
    val vm: MeetingDetailViewModel = viewModel(
        key = "detail-$meetingId",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MeetingDetailViewModel(meetingId, repository, MediaPlayerAudioPlayer()) as T
        },
    )
    val state by vm.ui.collectAsState()
    var pendingPageDelete by remember { mutableStateOf<SlidePage?>(null) }
    var confirmMeetingDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { confirmMeetingDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除会议")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            // 录音播放条
            Card(
                Modifier.fillMaxWidth().padding(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = vm::togglePlay) {
                        Icon(
                            if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (state.playing) "暂停" else "播放",
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text("会议录音", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (state.audioDurationMs > 0) formatDuration(state.audioDurationMs)
                            else "暂无录音",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (state.pages.isNotEmpty()) {
                        Text(
                            "${state.pages.size} 页",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                state.error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }

            if (state.pages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("这次会议没有拍到 PPT 页面", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.pages, key = { it.id }) { page ->
                        PageThumb(
                            page = page,
                            onClick = { pendingPageDelete = page },
                        )
                    }
                }
            }
        }
    }

    pendingPageDelete?.let { page ->
        AlertDialog(
            onDismissRequest = { pendingPageDelete = null },
            title = { Text("删除第 ${page.index} 页？") },
            text = {
                Text(
                    "该页共拍了 ${page.captureCount} 次（只保留了最后一张）。" +
                        "删除后无法恢复。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deletePage(page)
                    pendingPageDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingPageDelete = null }) { Text("取消") }
            },
        )
    }

    if (confirmMeetingDelete) {
        AlertDialog(
            onDismissRequest = { confirmMeetingDelete = false },
            title = { Text("删除整场会议？") },
            text = { Text("将删除 ${state.pages.size} 页图片与录音文件，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmMeetingDelete = false
                    vm.deleteMeeting(onDone = onBack)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmMeetingDelete = false }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageThumb(page: SlidePage, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column {
            val bitmap = remember(page.filePath, page.updatedAt) {
                runCatching { BitmapFactory.decodeFile(page.filePath) }.getOrNull()
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "第 ${page.index} 页",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
            } else {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("图片已丢失", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("P${page.index}", style = MaterialTheme.typography.labelMedium)
                if (page.captureCount > 1) {
                    Text(
                        "拍了 ${page.captureCount} 次",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
