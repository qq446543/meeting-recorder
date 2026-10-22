package com.meetnote.recorder.ui.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meetnote.recorder.audio.MeetingService
import com.meetnote.recorder.capture.CameraBinder
import com.meetnote.recorder.data.MeetingRepository
import com.meetnote.recorder.ui.list.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    meetingId: Long,
    repository: MeetingRepository,
    onFinished: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val vm: CaptureViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CaptureViewModel(meetingId, repository) as T
    })
    val state by vm.ui.collectAsStateWithLifecycle()

    // 屏幕常亮：拍摄期间不允许息屏（spec §11.2）
    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // 权限：相机 + 麦克风 + 通知（33+）
    var permissionTick by remember { mutableStateOf(0) }
    val needed = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    val missing = needed.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
    val cameraGranted = Manifest.permission.CAMERA !in missing
    // spec §12：录音权限未授予 → 不启动 MeetingService（阻止继续），并在界面上说明
    val audioDenied = Manifest.permission.RECORD_AUDIO in missing

    // 授权结果回调 bump tick：无论 granted 还是 denied，都会重新推导 missing
    // （checkSelfPermission 不是快照状态，对话框返回后必须有状态变化驱动重算）
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionTick++ }

    LaunchedEffect(missing.size, permissionTick) {
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else if (!state.recording.running) {
            // 权限齐了就开录音（前台服务，锁屏不断）
            MeetingService.start(context, meetingId)
        }
    }

    // 相机绑定：以授权状态为 key，授权后（含首次进入已授权）立即 bind，无需重进页面
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val binder = remember { CameraBinder(lifecycleOwner, context) }
    DisposableEffect(cameraGranted) {
        if (cameraGranted) {
            binder.bind(previewView.surfaceProvider)
        }
        onDispose { binder.unbind() }
    }

    // 斜拍梯形 → 屏幕矩形 的绿框（Task 10 会把 quad 灌进来）
    val quad = state.quad
    val previewRotation = 90   // 后置摄像头竖拍；真机若错位在 Task 15 校正

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onOpenGuide) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.recording.running) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 12.dp),
                        ) {
                            Box(
                                Modifier
                                    .padding(end = 6.dp)
                                    .size(10.dp)
                                    .background(Color.Red, androidx.compose.foundation.shape.CircleShape),
                            )
                            Text(
                                formatDuration(state.recording.elapsedMs),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

            // 检测框叠加层
            if (quad != null) {
                Canvas(Modifier.fillMaxSize()) {
                    val pts = listOf(quad.tl, quad.tr, quad.br, quad.bl).map {
                        PreviewOverlay.mapImagePointToView(
                            p = it,
                            imageW = 1280, imageH = 720,
                            rotationDeg = previewRotation,
                            viewW = size.width, viewH = size.height,
                        )
                    }
                    val xs = pts.map { it.x }
                    val ys = pts.map { it.y }
                    drawRect(
                        color = Color(0xFF3DDC84),
                        topLeft = Offset(xs.min(), ys.min()),
                        size = Size(xs.max() - xs.min(), ys.max() - ys.min()),
                        style = Stroke(width = 4.dp.toPx()),
                    )
                }
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (audioDenied) {
                    Text(
                        "录音权限被拒绝，无法开始录音记录；请授予麦克风权限后重试",
                        color = Color(0xFFF2A93B),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { permissionTick++ }) { Text("重新请求权限") }
                        OutlinedButton(onClick = {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            )
                            context.startActivity(intent)
                        }) { Text("去系统设置") }
                    }
                }
                Text(
                    buildString {
                        append(state.statusText)
                        append("　·　已拍 ")
                        append(state.pageCount)
                        append(" 页")
                        if (state.paused) append("　（抓拍已暂停）")
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.degraded) {
                    Text(
                        "未检测到屏幕边缘，已按原图保存；试试正对屏幕并保证光线充足",
                        color = Color(0xFFF2A93B),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { vm.togglePause() }) {
                        Text(if (state.paused) "继续抓拍" else "暂停抓拍")
                    }
                    Button(onClick = {
                        MeetingService.stop(context)   // 结束会议必须停止录音（spec §12）
                        onFinished()
                    }) { Text("结束会议") }
                }
            }
        }
    }
}
