package com.meetnote.recorder.ui.guide

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private data class GuideStep(val title: String, val detail: String)

private val colorOsSteps = listOf(
    GuideStep(
        "1. 锁定后台",
        "从屏幕底部上滑停顿进入多任务，找到「会议学习记录」下拉卡片加锁，" +
            "防止 ColorOS 把它清掉。否则录音可能在锁屏后被中断。",
    ),
    GuideStep(
        "2. 允许自启动",
        "设置 → 应用管理 → 会议学习记录 → 权限管理 → 打开「允许自启动」" +
            "与「关联启动」。",
    ),
    GuideStep(
        "3. 电池不限制",
        "设置 → 应用管理 → 会议学习记录 → 耗电管理 → 选「允许后台高耗电」。",
    ),
    GuideStep(
        "4. 拍摄期间不要退出 App",
        "安卓系统禁止应用在后台使用相机。翻页拍照时请让本应用保持在前台、屏幕常亮；" +
            "录音不受此限制，锁屏或切到别的应用都会继续。",
    ),
    GuideStep(
        "5. 拍摄角度",
        "尽量正对大屏幕、避免反光与前排遮挡。若顶部出现" +
            "「未检测到屏幕边缘」，应用会自动改为原图保存，不影响录音。",
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("一加 13 后台设置指引") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "为了让录音不被系统中断、拍摄不丢页，请按下面几步设置一次。",
                style = MaterialTheme.typography.bodyMedium,
            )

            colorOsSteps.forEach { step ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(step.title, style = MaterialTheme.typography.titleSmall)
                        Text(step.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Button(
                onClick = {
                    val pm = context.getSystemService(android.content.Context.POWER_SERVICE)
                        as PowerManager
                    if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                        val intent = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"),
                        )
                        runCatching { context.startActivity(intent) }
                            .onFailure {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                                    )
                                }
                            }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("一键申请忽略电池优化") }

            Text(
                "若系统拒绝跳转，请手动到：设置 → 电池 → 更多设置 → " +
                    "关闭「智能省电」中对本应用的限制。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
