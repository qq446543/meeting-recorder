# 会议学习记录 App — 设计文档

- 日期：2026-10-22
- 目标设备：一加 13（ColorOS，arm64-v8a，Android 15，无 GMS）
- 状态：待用户评审

## 1. 背景与目标

用于会议/课堂学习记录的安卓应用，核心诉求两条：

1. **录音**：App 内自行录音，跑在前台服务中，切后台/息屏不中断。
2. **文档模式自动拍 PPT**（最主要）：持续观察大屏幕，画面一变动就自动抓拍；
   同一页 PPT 多次增加内容时，只保留最后一张完整的（原地覆盖）。

### 明确的取舍（YAGNI）

- 不做录音转文字。
- 不做 OCR、不做 PPT 内容结构化。
- 不建全页相似度索引库：去重只跟"当前会议最后一页"比，翻回旧页会被记成新页。
- 不做账号、云同步、多设备。
- 不做后台拍摄（系统禁止 App 后台使用相机，物理限制）。

## 2. 交付方式

在本机安装构建工具链（JDK 17 + Android SDK 命令行工具 + Gradle 8.10），
构建出可安装的 APK；同时交付完整 Android Studio 工程源码。

## 3. 技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| 语言 | Kotlin 2.0 | 现代默认 |
| UI | Jetpack Compose | 代码量少一半 |
| 架构 | 单模块单 Activity，按职责分包 | 规模不需要多模块 |
| 相机 | CameraX 1.3.4 `ImageAnalysis` | 官方，帧分析接口稳定 |
| 图像算法 | OpenCV 4.10 (org.opencv:opencv) | 边缘检测/透视变换成熟稳定 |
| 存储 | Room + App 私有文件目录 | 无需存储权限 |
| 录音 | MediaRecorder + 前台服务 | 系统限制下唯一可靠路径 |
| 构建 | AGP 8.7 / Gradle 8.10 / JDK 17 | 与上述依赖兼容 |
| ABI | `arm64-v8a` only | 一加 13 即该架构，砍掉 OpenCV 无用 so |

**被否决的方案**：Google ML Kit 文档扫描 —— 依赖 Google Play services，
一加 13 国行无 GMS，不可用。

## 4. 分包结构

```
com.meetnote.recorder
├── ui/          会议列表 · 拍摄页 · 会议详情 · 导出对话框
├── capture/     抓拍流水线（CameraX 消费者、稳定检测、文档矫正、质量门）
├── dedupe/      同页去重判定
├── audio/       录音器 + 前台服务
├── data/        Room 实体/DAO/数据库 · 文件存储 · Repository
├── export/      PDF 导出与系统分享
└── service/     MeetingService（协调录音与抓拍的前台服务）
```

## 5. 数据流

```
CameraX ImageAnalysis (1280x720, KEEP_ONLY_LATEST)
   │ Y 平面降采样 64×36
   ▼
ROI 粗稳检测 ── 稳定 且 相对上次抓拍有变化 且 冷却 ≥400ms
   │ 取全分辨率帧
   ▼
文档矫正（OpenCV）
   灰度 → GaussianBlur(5) → Canny(50,150) → findContours
   → approxPolyDP 找四边形 → 点排序 TL/TR/BR/BL
   → getPerspectiveTransform → warpPerspective(宽1600)
   ▼
质量门（不达标即丢弃候选）
   ▼
同页判定：与当前会议「最后一页」比对
   ├─ recall ≥0.90 且 fgOldSim ≥0.85 且 coverage ≥0.90
   │        → 同页 → 原地覆盖，页码不变，captureCount++
   └─ 否则 → 新页 → 追加，页码 = 末页 + 1
   ▼
Room 写入 → Flow 推送 UI（页码计数实时刷新）
```

## 6. 抓拍流水线参数

### 6.1 取帧

- `ImageAnalysis` 目标分辨率 1280×720，`STRATEGY_KEEP_ONLY_LATEST`。
- 输出格式 `YUV_420_888`，仅使用 Y 平面做分析。

### 6.2 两级变化检测

1. **ROI 建立**：首次进拍摄页取一帧做文档检测，锁定大屏幕四边形；
   此后所有帧间比较只在该 ROI 内进行。这样前排同学晃动、门口人员走动
   不会干扰"画面是否变化"的判断。
2. **稳定与变化判定**（ROI 内 64×36 降采样，平均绝对差 MAD，取值 0–255）：
   - `MAD < 2.0` 连续 **5 帧** → 判定画面稳定（翻页动画 300–500ms 被自然滤掉）；
   - 且与**上次抓拍帧**的 MAD > 变化阈值 4.0 → 内容确实变了 → 触发抓拍；
   - 每次抓拍后 **400ms 冷却**，防止连拍风暴。

### 6.3 文档矫正

- 灰度 → `GaussianBlur(5,5)` → `Canny(50,150)` → `findContours` 按面积降序
- 取第一个满足以下全部条件的轮廓：`approxPolyDP(0.02×peri)` 得 4 点、
  凸四边形、面积 ≥ 画面 **12%**、宽高比在 **0.5–3.0**
- 四点按 (TL, TR, BR, BL) 排序
- `getPerspectiveTransform` + `warpPerspective`，输出宽度 1600px，等比高度
- **降级策略**：连续 3 帧找不到合格四边形 → 切换为整帧存储，
  并在顶部显示黄条「未检测到屏幕边缘，已存原图」。功能不因此失效。

### 6.4 质量门

候选帧须同时满足，否则丢弃：

- 清晰度：拉普拉斯算子方差 ≥ 60
- 亮度：平均亮度在 25–235 之间
- 矫正后宽高比在 0.6–2.5

## 7. 同页去重算法（2026-10-22 修订）

只与当前会议的最后一页比较。

**修订原因**：初稿的 `0.55×直方图 + 0.45×NCC` 经手算验证不成立——白底幻灯片上
"标题不变、正文新增两条"这种最典型的累积场景，NCC 只能到约 0.79，会被误判成新页，
直接违背核心需求。根因是白底的像素方差极小，任何新增笔画都会显著拉低互相关。

改为**方向性判据**：不再问"两张图整体多像"，只问"旧页内容还在不在原处"。

```
recall   = 旧页墨迹格子在新帧中仍是墨迹的比例      （累积内容时恒为 1.0）
fgOldSim = 旧页墨迹区域细网格（400×225）灰度相似度  （文字被替换或擦除会明显下降）
coverage = 新帧墨迹量 / 旧页墨迹量                 （排除内容变少的动画中间态）
```

| 判定 | 条件 | 动作 |
|---|---|---|
| 同页 | `recall ≥ 0.90` **且** `fgOldSim ≥ 0.85` **且** `coverage ≥ 0.90` | 原地覆盖同名文件，页码不变，`captureCount++`，更新 `updatedAt` |
| 新页 | 任一条件不满足 | 追加新文件，页码 = 末页 + 1 |

覆盖即实现"同一页面多次增加内容，只保留最后一张完整的"。
三条件同时不满足时一律按新页处理——**宁可多留一页，不可丢内容**（误拍可在详情页手动删除）。

所有阈值集中在 `DedupeConfig`，真机调参只改该文件；调参时用
`adb logcat -s Dedupe` 读取每张的 recall/fg/cov/score。

**已知局限**：版面完全雷同且文字整体替换的两页存在误合并可能，
细网格分辨率（400×225）是主要缓解手段；若真机复现，把 `FINE_W/FINE_H` 提到 800/450。

## 8. 录音

- `MediaRecorder`，`AudioSource.MIC`，AAC / 单声道 / 44.1kHz / 96kbps
- 输出 `filesDir/meetings/<meetingId>/audio.m4a`（约 85MB/2小时）
- 跑在前台服务 `MeetingService`，类型 `FOREGROUND_SERVICE_TYPE_MICROPHONE`
  （Android 14+ 强制要求声明），通知栏常驻，附「停止」操作
- 与拍摄页解耦：退出拍摄页录音继续；相机因系统限制必须停止
- 结束时把实际时长写回 `Meeting.audioDurationMs`
- 不设时长上限

## 9. 数据模型（Room）

```kotlin
@Entity(tableName = "meeting")
data class Meeting(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val title: String,
  val createdAt: Long,
  val endedAt: Long? = null,
  val audioPath: String? = null,
  val audioDurationMs: Long = 0,
)

@Entity(
  tableName = "slide_page",
  foreignKeys = [ForeignKey(
    entity = Meeting::class,
    parentColumns = ["id"], childColumns = ["meetingId"],
    onDelete = ForeignKey.CASCADE,
  )],
  indices = [Index("meetingId")],
)
data class SlidePage(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val meetingId: Long,
  val index: Int,          // 1-based 页码
  val filePath: String,
  val width: Int,
  val height: Int,
  val captureCount: Int = 1,   // 该页被抓拍次数，去重后只留一张
  val createdAt: Long,
  val updatedAt: Long,
)
```

文件布局：

```
filesDir/meetings/<meetingId>/page_0001.jpg
filesDir/meetings/<meetingId>/page_0002.jpg
filesDir/meetings/<meetingId>/audio.m4a
```

App 私有目录，无需任何存储权限；导出走 SAF。

## 10. 权限

| 权限 | 用途 | 时机 |
|---|---|---|
| `RECORD_AUDIO` | 录音 | 新建会议时 |
| `CAMERA` | 抓拍 | 进入拍摄页时 |
| `POST_NOTIFICATIONS` (33+) | 前台服务通知 | 新建会议时 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE` | 录音服务 | 普通安装时声明 |
| `WAKE_LOCK` | 保持唤醒 | 普通安装时声明 |

不需要 `READ/WRITE_EXTERNAL_STORAGE`。

## 11. 界面

1. **会议列表**：卡片（标题 / 时间 / 页数 / 录音时长），右下角「＋ 新建会议」；
   空状态引导。
2. **拍摄页**（核心）：
   - 全屏相机预览，叠加绿色四边形框标出检测到的屏幕
   - `FLAG_KEEP_SCREEN_ON` 常亮
   - 顶部：会议名、红点录音计时、当前页码
   - 底部：「结束会议」主按钮 +「暂停抓拍」开关 + 手动快门（兜底）
   - 实时状态条：「正在稳定…」/「已拍第 7 页」/「已更新第 7 页」
3. **会议详情**：页码缩略图网格（长按删除、可调整顺序）+ 录音播放条
   +「导出 PDF」。
4. **导出**：`PdfDocument` 每页图片居中排 A4 → SAF 选择保存位置 → 系统分享。

## 12. 错误处理

| 情况 | 处理 |
|---|---|
| 相机权限拒绝 / 相机被占用 | 引导页，提供重试与跳设置 |
| 录音权限拒绝 | 阻止新建会议并说明 |
| 来电打断录音 | 通知提示；抓拍不受影响 |
| OpenCV native 加载失败 | 捕获 `UnsatisfiedLinkError` → 降级为整帧存储模式 |
| 连续找不到屏幕边缘 | 3 帧后降级为整帧存储 + 黄条提示 |
| 可用空间 < 500MB | 新建会议前弹警告，仍可继续 |
| 用户退出拍摄页 | 释放相机，录音继续，黄条提示「抓拍已暂停」；会议仍为进行中，可再次进入拍摄页恢复抓拍（页码与文件延续，不重置） |
| 拍摄中来电 / App 被切到后台 | 相机按系统策略自动停止，回到前台后重新绑定并恢复检测 |
| 进程被杀 | Room 已写入的页不丢；录音文件按已有时长入账 |
| 抓拍候选质量不达标 | 静默丢弃，继续监测 |

## 13. 一加 13 / ColorOS 专项

- **锁定后台 + 允许自启动**：首次启动给出图文引导，否则进程可能被杀。
- **禁止后台相机**：Android 硬限制，拍摄页必须在前台；UI 明确告知用户
  「拍摄期间请勿退出本应用」。
- 电池优化豁免：引导跳转 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。
- 仅保留 `arm64-v8a` ABI。

## 14. 测试策略

**单元测试**
- 去重判定：合成位图构造"同页新增""翻页""模糊带"三类样本
- 页码推进与覆盖计数逻辑
- ROI 坐标换算与四点排序
- 质量门阈值判定

**仪器测试**
- Room DAO 增删改查、级联删除
- PDF 导出生成文件可解析

**手工验收清单（真机）**
- 真实投影幕布上连续翻页 20 页，无漏拍、无重复页
- 同一页分 3 次显示要点，最终只留 1 张完整图
- 录音 30 分钟，切后台、锁屏后不中断
- 导出 PDF 后页序与放映顺序一致
- 飞行模式、来电、低电量等干扰场景

## 15. 风险

1. **投影幕布反光/低对比度**导致四边形检测失败 —— 已有整帧降级路径。
2. **OpenCV AAR 体积**（约 30MB）—— ABI 过滤后可接受。
3. **长时间录像发热**——常亮 + 相机持续运行必然发热，属物理限制；
   已用低分辨率分析帧 + 按需取全分辨率帧缓解。
4. **去重阈值**首次真机调参 —— `recall 0.90 / fgOldSim 0.85 / coverage 0.90` 为经验值，
   集中在 `DedupeConfig` 中便于调整；Task 15 的 `adb logcat -s Dedupe` 提供调参数据，
   改阈值后必须重跑 `dedupe` 包的单元测试（其中"同页累积内容"用例是硬约束）。
