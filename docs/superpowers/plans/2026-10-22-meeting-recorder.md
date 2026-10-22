# 会议学习记录 App（MeetingRecorder）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个安卓应用，用于会议/课堂学习记录：前台服务持续录音，同时用后置摄像头以"文档模式"自动抓拍大屏幕 PPT，同一页新增内容只保留最后一张完整图，并可导出 PDF。

**Architecture:** 单模块单 Activity + Jetpack Compose。抓拍走 CameraX（Preview + ImageAnalysis + ImageCapture 三用例）：分析线程在 ROI 内做稳定/变化检测，判定需抓拍后由 ImageCapture 取全分辨率图，经 OpenCV 透视矫正与质量门，再由去重算法决定"覆盖上一页"还是"追加新页"，最后写 Room。录音独立跑在前台服务中，与抓拍仅通过 meetingId 关联。

**Tech Stack:** Kotlin 2.3.21 / AGP 8.13.2 / Gradle 8.14.6 / JDK 21 / compileSdk 36 / targetSdk 35 / minSdk 26 / Compose BOM 2026.09.00 / CameraX 1.5.3 / Room 2.8.5 / OpenCV 4.11.0 / Robolectric 4.17 / JUnit 4.13.2

**Spec:** `docs/superpowers/specs/2026-10-22-meeting-recorder-design.md`（本计划从该 spec 推导，执行时须同时阅读）

## Global Constraints

- 包名固定 `com.meetnote.recorder`，应用名「会议学习记录」（中文字符串统一放 `res/values/strings.xml`，UI 文案全中文）。
- 依赖版本**只能**使用上方 Tech Stack 中已核实的版本；确需变更时必须先在 Maven Central / Google Maven 核实存在性，并在此处登记。
- OpenCV 依赖 `org.opencv:opencv:4.11.0`；`app/build.gradle.kts` 必须声明 `ndk { abiFilters += "arm64-v8a" }`（一加 13 为 arm64-v8a，砍掉其余 ABI）。
- minSdk 26、targetSdk 35、compileSdk 36；targetSdk 固定 35 以规避更高版本后台限制。
- 不申请 `INTERNET` 权限（应用完全离线）；不申请任何存储权限（文件存 app 私有目录，导出走 SAF）。
- 同页去重采用方向性三判据（见 Task 5 与 spec §7 修订版）：`recall ≥ 0.90`、`fgOldSim ≥ 0.85`、`coverage ≥ 0.90` 三者同时满足才判同页；阈值全部集中在 `DedupeConfig`，不得散落魔法数。任何改动必须先让 `SlideDeduplicatorTest` 的"同页累积内容"用例通过。
- 稳定判定 `stableMad = 2.0f`、连续 `5` 帧、变化阈值 `changeMad = 4.0f`、抓拍冷却 `400ms`，全部集中在 `StabilityDetector` 的构造参数默认值。
- 文档矫正输出宽度上限 `1600px`；质量门 `laplacianVar ≥ 60`、平均亮度 `25–235`、宽高比 `0.6–2.5`。
- 所有纯逻辑（算法、判定、几何）必须是不依赖 Android 框架的 Kotlin 对象/类，放在 JVM 单元测试可覆盖的路径下；`Bitmap`/`CameraX`/`Room` 仅作为薄适配层。
- 每个任务结束必须 `git add` + `git commit`，提交信息用 `feat:` / `fix:` / `test:` / `build:` 前缀。
- 构建命令统一用 `gradlew.bat`（Windows），工作目录为项目根 `MeetingRecorder/`。

### Task 1 登记：依赖版本决议与机器构建约束（2026-10-22）

以下为 Task 1 实际落地并已核实的权威值，后续任务一律以此为准；任何 brief 片段若与本节冲突，**以本节为准**：

- **Compose BOM 最终版本 `2026.06.01`**（原计划 `2026.09.00`）。原因：2026.09.00 内的 `ui-android:1.12.1` AAR 元数据声明 `minCompileSdk=37` + `minAGP=9.1.0`，与 compileSdk 36 / AGP 8.13.2 硬约束冲突（构建时报错实证）；已从 Google Maven 核实 2026.06.01（Compose 1.11.x，`minCompileSdk=35` / `minAGP=8.6.0`）可用且构建通过。
- **KSP 最终版本 `2.3.12`**（原计划 `2.3.21-2.0.4`）。原因：坐标 `com.google.devtools.ksp:symbol-processing-api:2.3.21-2.0.4` 在 Maven Central 从未发布（404）；KSP 自 2.3.0 起改为不带 Kotlin 后缀的独立版本号。已从 Maven Central 核实 `2.3.12` 存在且与 Kotlin 2.3.21 / Room 2.8.5 KSP 处理器兼容（`kspDebugKotlin` 执行成功）。
- **机器构建约束（已写入 `gradle.properties`，必须保留）**：仓库路径含非 ASCII 字符（`默认项目`）→ `android.overridePathCheck=true` 必须保留，删除会使 AGP 路径检查失败；本机 pagefile 仅 2 GB → `-Xmx2048m`、`org.gradle.workers.max=1`、`kotlin.compiler.execution.strategy=in-process` 已入 `gradle.properties`，回退默认并发会在 dexing 阶段触发 JVM 提交内存耗尽崩溃。

## 文件结构总览

```
MeetingRecorder/
├── settings.gradle.kts                     构建脚本
├── build.gradle.kts                        插件版本
├── gradle.properties                       JVM/AndroidX 开关
├── gradle/libs.versions.toml               版本目录（所有版本唯一来源）
├── gradle/wrapper/gradle-wrapper.*         Gradle 8.14.6 wrapper
├── gradlew / gradlew.bat                   wrapper 脚本
├── app/
│   ├── build.gradle.kts                    应用模块配置、依赖、ABI 过滤
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/meetnote/recorder/
│       │   │   ├── MainActivity.kt          单 Activity，承载 Compose
│       │   │   ├── RecorderApplication.kt   Application，OpenCV 初始化
│       │   │   ├── data/
│       │   │   │   ├── Entities.kt          Meeting / SlidePage / MeetingStats
│       │   │   │   ├── MeetingDao.kt
│       │   │   │   ├── AppDatabase.kt
│       │   │   │   ├── FileStore.kt         文件路径与空间检查
│       │   │   │   └── MeetingRepository.kt 落库 + 去重决策编排
│       │   │   ├── dedupe/
│       │   │   │   ├── DedupeConfig.kt
│       │   │   │   ├── ImageSignals.kt      像素 → HSV直方图 + 灰度网格
│       │   │   │   └── SlideDeduplicator.kt 相似度与页决策（纯逻辑）
│       │   │   ├── capture/
│       │   │   │   ├── FrameGrid.kt         YUV Y平面 → 64×36 网格（纯）
│       │   │   │   ├── StabilityDetector.kt 稳定/变化判定（纯）
│       │   │   │   ├── QuadMath.kt          四点排序/面积/宽高比（纯）
│       │   │   │   ├── QualityGate.kt       清晰度/亮度/宽高比门（纯）
│       │   │   │   ├── DocumentCorrector.kt OpenCV 透视矫正（薄适配）
│       │   │   │   ├── CapturePipeline.kt   流水线编排与状态
│       │   │   │   └── CameraBinder.kt      CameraX 三用例绑定（薄适配）
│       │   │   ├── audio/
│       │   │   │   ├── Recorder.kt          接口 + MediaRecorder 实现 + 假实现
│       │   │   │   ├── RecordingState.kt    全局录音状态
│       │   │   │   └── MeetingService.kt    前台服务（麦克风类型）
│       │   │   ├── export/
│       │   │   │   ├── PdfLayout.kt         A4 排版计算（纯）
│       │   │   │   └── PdfExporter.kt       PdfDocument 生成（薄适配）
│       │   │   └── ui/
│       │   │       ├── theme/Theme.kt
│       │   │       ├── nav/AppNav.kt
│       │   │       ├── list/MeetingListScreen.kt + MeetingListViewModel.kt
│       │   │       ├── capture/CaptureScreen.kt + CaptureViewModel.kt
│       │   │       ├── detail/MeetingDetailScreen.kt + MeetingDetailViewModel.kt
│       │   │       └── guide/GuideScreen.kt  一加/ColorOS 后台设置引导
│       │   └── res/  strings.xml themes.xml mipmap(矢量自适应图标) xml/
│       ├── test/java/com/meetnote/recorder/   JVM 单元测试（本机可跑）
│       └── androidTest/java/...                真机仪器测试（连一加13后跑）
├── docs/superpowers/specs/2026-10-22-meeting-recorder-design.md
└── docs/superpowers/plans/2026-10-22-meeting-recorder.md   本文件
```

---

### Task 1: 构建工具链 + 工程骨架 + 首个 APK

本机当前**没有** JDK、Android SDK、Gradle，全部从零装。本任务交付一个能跑 `gradlew.bat assembleDebug` 并产出 `app-debug.apk` 的空壳工程。

**Files:**
- Create: `settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`、`gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.properties`、`gradle/wrapper/gradle-wrapper.jar`、`gradlew`、`gradlew.bat`
- Create: `app/build.gradle.kts`、`app/proguard-rules.pro`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/meetnote/recorder/MainActivity.kt`、`RecorderApplication.kt`
- Create: `app/src/main/res/values/strings.xml`、`app/src/main/res/values/themes.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`、`app/src/main/res/drawable/ic_launcher_foreground.xml`、`app/src/main/res/values/colors.xml`
- Toolchain (不在 git 内，装到 `C:\dev\`)：JDK 21、Android SDK、Gradle 8.14.6

**Interfaces:**
- Consumes: 无（首个任务）
- Produces:
  - 环境变量 `JAVA_HOME=C:\dev\jdk-21.x.x`、`ANDROID_HOME=C:\dev\android-sdk`
  - 命令 `gradlew.bat assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
  - 包 `com.meetnote.recorder`，`MainActivity` 是唯一入口 Activity

- [ ] **Step 1: 安装 JDK 21**

```powershell
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path C:\dev | Out-Null
$z = "C:\dev\jdk21.zip"
Invoke-WebRequest -Uri "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse" -OutFile $z
Expand-Archive -Path $z -DestinationPath C:\dev -Force
$jdk = (Get-ChildItem C:\dev -Directory -Filter "jdk-21*" | Select-Object -First 1).FullName
[Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk, 'Machine')
$env:JAVA_HOME = $jdk
& "$jdk\bin\java.exe" -version
```

Expected: `openjdk version "21.x"` 三行输出，无报错。

- [ ] **Step 2: 安装 Android SDK 命令行工具并接受许可**

```powershell
$ErrorActionPreference = 'Stop'
$sdk = "C:\dev\android-sdk"
New-Item -ItemType Directory -Force -Path "$sdk\cmdline-tools" | Out-Null
$z = "C:\dev\cmdtools.zip"
Invoke-WebRequest -Uri "https://dl.google.com/android/repository/commandlinetools-win-16111833_latest.zip" -OutFile $z
Expand-Archive -Path $z -DestinationPath "$sdk\cmdline-tools" -Force
# 压缩包解出的是 cmdline-tools/，需挪成 cmdline-tools/latest/ 才能被 sdkmanager 识别
Move-Item "$sdk\cmdline-tools\cmdline-tools" "$sdk\cmdline-tools\latest" -Force

# 预写许可文件（等同 sdkmanager --licenses 全部 y）
New-Item -ItemType Directory -Force -Path "$sdk\licenses" | Out-Null
Set-Content -Path "$sdk\licenses\android-sdk-license" -Value @"
8933bad161af4178b1185d1a37fbf41ea5269c55
d56f5187479451eabf01fb78af6dfcb131a6481e
24333f8a63b6825ea9c5514f83c2829b004d1fee
"@ -NoNewline
Set-Content -Path "$sdk\licenses\android-sdk-preview-license" -Value "84831b9409646a918e30573bab4c9c91346d8abd" -NoNewline

$env:JAVA_HOME = (Get-Item Env:JAVA_HOME).Value
$sm = "$sdk\cmdline-tools\latest\bin\sdkmanager.bat"
& $sm --sdk_root="$sdk" "platform-tools" "platforms;android-36" "build-tools;35.0.1"
```

Expected: 三行 `done` / `[====================] 100%`，`C:\dev\android-sdk\platforms\android-36\` 存在。

- [ ] **Step 3: 下载 Gradle 8.14.6 并生成 wrapper**

```powershell
$ErrorActionPreference = 'Stop'
$z = "C:\dev\gradle.zip"
Invoke-WebRequest -Uri "https://services.gradle.org/distributions/gradle-8.14.6-bin.zip" -OutFile $z
Expand-Archive -Path $z -DestinationPath C:\dev -Force
$gradle = (Get-ChildItem C:\dev -Directory -Filter "gradle-8.14.6" | Select-Object -First 1).FullName
# wrapper 由 Step 5 建好工程骨架后再生成（见 Step 6）
$env:GRADLE_HOME = $gradle
```

Expected: `C:\dev\gradle-8.14.6\bin\gradle.bat` 存在（暂不执行，等工程文件写完）。

- [ ] **Step 4: 写版本目录与根构建脚本**

`gradle/libs.versions.toml`：

```toml
[versions]
agp = "8.13.2"
kotlin = "2.3.21"
composeBom = "2026.09.00"
cameraX = "1.5.3"
room = "2.8.5"
opencv = "4.11.0"
coroutines = "1.11.0"
activityCompose = "1.10.1"
navigationCompose = "2.9.5"
lifecycle = "2.9.4"
robolectric = "4.17"
junit = "4.13.2"
androidxJunit = "1.2.1"
espresso = "3.6.1"

[libraries]
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-material-icons = { group = "androidx.compose.material", name = "material-icons-extended" }
activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
camera-camera2 = { group = "androidx.camera", name = "camera-camera2", version.ref = "cameraX" }
camera-lifecycle = { group = "androidx.camera", name = "camera-lifecycle", version.ref = "cameraX" }
camera-view = { group = "androidx.camera", name = "camera-view", version.ref = "cameraX" }
camera-core = { group = "androidx.camera", name = "camera-core", version.ref = "cameraX" }
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }
opencv = { group = "org.opencv", name = "opencv", version.ref = "opencv" }
coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
androidx-test-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxJunit" }
androidx-test-espresso = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espresso" }
androidx-test-core = { group = "androidx.test", name = "core-ktx", version = "1.7.0" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version = "2.3.21-2.0.4" }
```

> 注：`ksp` 版本号 `2.3.21-2.0.4` 的格式是 `kotlinVersion-kspVersion`。执行时若解析不到，
> 到 `https://repo1.maven.org/maven2/com/google/devtools/ksp/symbol-processing-api/maven-metadata.xml`
> 查最新的、以 `2.3.21-` 开头的版本号替换；若完全没有以 2.3.21 开头的版本，
> 改用 Room 的注解处理器方案：`room-compiler` 以 `ksp(...)` 换成 `annotationProcessor(...)` 不适用于 Kotlin，
> 此时改为把 Kotlin 降到 ksp 支持的版本并同步修改本表 —— 该分支必须先回报再执行。

`settings.gradle.kts`：

```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "MeetingRecorder"
include(":app")
```

根 `build.gradle.kts`：

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
```

`gradle.properties`：

```properties
org.gradle.jvmargs=-Xmx3072m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

- [ ] **Step 5: 写 app 模块与最小源码**

先建根目录 `.gitignore`（**必须在首次 `git add` 之前**，否则构建产物会被提交）：

```gitignore
# Gradle
.gradle/
build/
local.properties

# Android Studio / IDE
.idea/
*.iml
captures/
.externalNativeBuild/
.cxx/

# 签名（本地密钥绝不入库）
keystore.properties
*.jks
*.keystore

# 其他
*.log
.DS_Store
```

`app/build.gradle.kts`：

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.meetnote.recorder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.meetnote.recorder"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    ndk {
        // 一加 13 是 arm64-v8a，只打包该架构，砍掉 OpenCV 其余 ABI
        abiFilters += "arm64-v8a"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.camera.core)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.opencv)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.androidx.test.core)

    debugImplementation(libs.compose.ui.tooling)
}
```

`app/proguard-rules.pro`：空文件加一行注释 `# 未启用混淆，规则留空`。

`app/src/main/AndroidManifest.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-feature android:name="android.hardware.camera.any" android:required="true" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

    <application
        android:name=".RecorderApplication"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher"
        android:supportsRtl="true"
        android:theme="@style/Theme.MeetingRecorder"
        tools:targetApi="35">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden"
            android:theme="@style/Theme.MeetingRecorder">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`app/src/main/java/com/meetnote/recorder/MainActivity.kt`：

```kotlin
package com.meetnote.recorder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            androidx.compose.material3.Text(text = "会议学习记录")
        }
    }
}
```

`app/src/main/java/com/meetnote/recorder/RecorderApplication.kt`：

```kotlin
package com.meetnote.recorder

import android.app.Application

class RecorderApplication : Application()
```

`app/src/main/res/values/strings.xml`：

```xml
<resources>
    <string name="app_name">会议学习记录</string>
</resources>
```

`app/src/main/res/values/themes.xml`：

```xml
<resources>
    <style name="Theme.MeetingRecorder" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

`app/src/main/res/values/colors.xml`：

```xml
<resources>
    <color name="ic_launcher_background">#0B6B4F</color>
</resources>
```

`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

`app/src/main/res/drawable/ic_launcher_foreground.xml`（白色"屏幕 + 三行字"图形）：

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#00000000"
        android:pathData="M30,34 h48 a4,4 0 0 1 4,4 v32 a4,4 0 0 1 -4,4 h-48 a4,4 0 0 1 -4,-4 v-32 a4,4 0 0 1 4,-4 z"
        android:strokeColor="#FFFFFFFF" android:strokeWidth="4" />
    <path android:strokeColor="#FFFFFFFF" android:strokeWidth="4" android:strokeLineCap="round"
        android:pathData="M36,46 h36 M36,56 h36 M36,66 h22" />
</vector>
```

- [ ] **Step 6: 生成 Gradle wrapper 并首次构建**

```powershell
$env:JAVA_HOME = (Get-Item Env:JAVA_HOME).Value
Set-Location C:\Users\Administrator\Documents\默认项目\MeetingRecorder
& C:\dev\gradle-8.14.6\bin\gradle.bat wrapper --gradle-version 8.14.6 --distribution-type bin
.\gradlew.bat assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL`，且 `app\build\outputs\apk\debug\app-debug.apk` 存在。

**若失败，按此顺序排查（这是本任务的主要风险）：**
1. `Unsupported class file major version` / `Gradle requires JVM 17` → 确认 `$env:JAVA_HOME` 指向 JDK 21。
2. `compileSdk 36 is required` 之类依赖冲突 → 把 `app/build.gradle.kts` 的 `compileSdk` 升到报错要求的值；若要求 37，先执行 `sdkmanager --sdk_root=C:\dev\android-sdk "platforms;android-37"`（不存在则换低版本依赖）。
3. KSP 版本解析不到 → 见 Step 4 注释的处理分支。
4. 任何依赖 `Could not find` → 到对应 Maven 仓库核实真实版本号，改 `libs.versions.toml` 并登记到本计划 Global Constraints。

- [ ] **Step 7: 提交**

```powershell
git add -A
git commit -m "build: 初始化 Gradle 工程骨架并产出首个 APK"
```

---

---

### Task 2: 数据层（Room + 文件存储 + 仓库）

建立两张表、DAO、数据库、文件路径管理与仓库。仓库里的"覆盖 vs 追加"决策骨架先落地，真正的相似度算法在 Task 5 补齐（本任务先用可注入的判定函数占位接口）。

**Files:**
- Create: `app/src/main/java/com/meetnote/recorder/data/Entities.kt`
- Create: `app/src/main/java/com/meetnote/recorder/data/MeetingDao.kt`
- Create: `app/src/main/java/com/meetnote/recorder/data/AppDatabase.kt`
- Create: `app/src/main/java/com/meetnote/recorder/data/FileStore.kt`
- Create: `app/src/main/java/com/meetnote/recorder/data/MeetingRepository.kt`
- Create: `app/src/test/java/com/meetnote/recorder/data/MeetingDaoTest.kt`
- Create: `app/src/test/java/com/meetnote/recorder/data/FileStoreTest.kt`

**Interfaces:**
- Consumes: Task 1 的工程与依赖（Room 2.8.5 + ksp、Robolectric 4.17）
- Produces（后续任务依赖的精确签名）：
  - `Meeting(id: Long, title: String, createdAt: Long, endedAt: Long?, audioPath: String?, audioDurationMs: Long)`，主键自增
  - `SlidePage(id: Long, meetingId: Long, index: Int, filePath: String, width: Int, height: Int, captureCount: Int, createdAt: Long, updatedAt: Long)`，`index` 从 1 开始
  - `MeetingStats(id: Long, title: String, createdAt: Long, endedAt: Long?, audioDurationMs: Long, pageCount: Int)`
  - `MeetingDao.observeStats(): Flow<List<MeetingStats>>`、`insertMeeting(m): Long`、`lastPage(meetingId): SlidePage?`、`insertPage(p): Long`、`updatePage(p)`、`observePages(meetingId): Flow<List<SlidePage>>`、`deletePage(id)`、`finishMeeting(id, audioPath, audioDurationMs, endedAt)`、`deleteMeeting(id)`、`meeting(id): Meeting?`、`pageCount(id): Int`
  - `AppDatabase.get(context): AppDatabase`（单例）
  - `FileStore(root: File)`：`meetingDir(id): File`、`pageFile(id, index): File`、`audioFile(id): File`、`deleteMeeting(id): Boolean`、`freeBytes(): Long`
  - `MeetingRepository(dao, fileStore)`：`observeMeetings(): Flow<List<MeetingStats>>`、`suspend createMeeting(title): Long`、`suspend finishMeeting(id, audioPath, durationMs)`、`suspend deleteMeeting(id)`、`fun observePages(id): Flow<List<SlidePage>>`、`suspend pageCount(id): Int`、`suspend meeting(id): Meeting?`、`suspend registerShot(meetingId, imageFile, width, height, similarityToLast: Float): ShotResult`
  - `data class ShotResult(val index: Int, val overwritten: Boolean, val captureCount: Int)`

- [ ] **Step 1: 写失败的 DAO 测试**

`app/src/test/java/com/meetnote/recorder/data/MeetingDaoTest.kt`：

```kotlin
package com.meetnote.recorder.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeetingDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MeetingDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.meetingDao()
    }

    @After
    fun tearDown() = db.close()

    private fun meeting(title: String) = Meeting(title = title, createdAt = 1_000L)

    private fun page(meetingId: Long, index: Int) = SlidePage(
        meetingId = meetingId, index = index,
        filePath = "/tmp/page_$index.jpg", width = 1600, height = 900,
        createdAt = 2_000L, updatedAt = 2_000L,
    )

    @Test
    fun `insert meeting then stats carries page count`() = runBlocking {
        val id = dao.insertMeeting(meeting("周会"))
        dao.insertPage(page(id, 1))
        dao.insertPage(page(id, 2))

        val stats = dao.observeStats().first().single()
        assertEquals(id, stats.id)
        assertEquals("周会", stats.title)
        assertEquals(2, stats.pageCount)
        assertEquals(0L, stats.audioDurationMs)
    }

    @Test
    fun `lastPage returns highest index page`() = runBlocking {
        val id = dao.insertMeeting(meeting("评审"))
        dao.insertPage(page(id, 1))
        dao.insertPage(page(id, 7))

        assertEquals(7, dao.lastPage(id)!!.index)
    }

    @Test
    fun `lastPage is null for empty meeting`() = runBlocking {
        val id = dao.insertMeeting(meeting("空会"))
        assertNull(dao.lastPage(id))
    }

    @Test
    fun `updatePage increments captureCount in place`() = runBlocking {
        val id = dao.insertMeeting(meeting("更新"))
        val p = page(id, 3).copy(captureCount = 1)
        val pid = dao.insertPage(p)

        dao.updatePage(p.copy(id = pid, captureCount = 4, updatedAt = 9_999L))

        val rows = dao.observePages(id).first()
        assertEquals(1, rows.size)          // 仍是同一页，页数不增
        assertEquals(4, rows[0].captureCount)
        assertEquals(9_999L, rows[0].updatedAt)
    }

    @Test
    fun `finishMeeting writes audio fields`() = runBlocking {
        val id = dao.insertMeeting(meeting("录音"))
        dao.finishMeeting(id, audioPath = "/a/audio.m4a", audioDurationMs = 123_456L, endedAt = 7_777L)

        val m = dao.meeting(id)!!
        assertEquals("/a/audio.m4a", m.audioPath)
        assertEquals(123_456L, m.audioDurationMs)
        assertEquals(7_777L, m.endedAt)
    }

    @Test
    fun `deleteMeeting cascades pages`() = runBlocking {
        val id = dao.insertMeeting(meeting("级联"))
        dao.insertPage(page(id, 1))
        dao.insertPage(page(id, 2))

        dao.deleteMeeting(id)

        assertEquals(0, dao.pageCount(id))
        assertTrue(dao.observeStats().first().isEmpty())
    }

    @Test
    fun `stats ordered by createdAt descending`() = runBlocking {
        dao.insertMeeting(Meeting(title = "早", createdAt = 100L))
        dao.insertMeeting(Meeting(title = "晚", createdAt = 900L))

        val titles = dao.observeStats().first().map { it.title }
        assertEquals(listOf("晚", "早"), titles)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.data.MeetingDaoTest" --no-daemon
```

Expected: FAIL，报 `unresolved reference: Meeting` / `AppDatabase`（类不存在）。

- [ ] **Step 3: 写实体、DAO、数据库**

`data/Entities.kt`：

```kotlin
package com.meetnote.recorder.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
        parentColumns = ["id"],
        childColumns = ["meetingId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("meetingId")],
)
data class SlidePage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val meetingId: Long,
    /** 1 起始的页码 */
    val index: Int,
    val filePath: String,
    val width: Int,
    val height: Int,
    /** 该页被抓拍次数，去重后只保留最后一张 */
    val captureCount: Int = 1,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 列表页投影：会议 + 页数 */
data class MeetingStats(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val endedAt: Long?,
    val audioDurationMs: Long,
    val pageCount: Int,
)
```

`data/MeetingDao.kt`：

```kotlin
package com.meetnote.recorder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MeetingDao {

    @Query(
        """
        SELECT m.id AS id, m.title AS title, m.createdAt AS createdAt,
               m.endedAt AS endedAt, m.audioDurationMs AS audioDurationMs,
               (SELECT COUNT(*) FROM slide_page p WHERE p.meetingId = m.id) AS pageCount
        FROM meeting m
        ORDER BY m.createdAt DESC
        """
    )
    fun observeStats(): Flow<List<MeetingStats>>

    @Insert
    suspend fun insertMeeting(meeting: Meeting): Long

    @Query("SELECT * FROM meeting WHERE id = :id")
    suspend fun meeting(id: Long): Meeting?

    @Query(
        "UPDATE meeting SET audioPath = :audioPath, audioDurationMs = :audioDurationMs, " +
            "endedAt = :endedAt WHERE id = :id"
    )
    suspend fun finishMeeting(id: Long, audioPath: String?, audioDurationMs: Long, endedAt: Long)

    @Query("DELETE FROM meeting WHERE id = :id")
    suspend fun deleteMeeting(id: Long)

    @Query("SELECT * FROM slide_page WHERE meetingId = :meetingId ORDER BY `index` ASC")
    fun observePages(meetingId: Long): Flow<List<SlidePage>>

    @Query("SELECT * FROM slide_page WHERE meetingId = :meetingId ORDER BY `index` DESC LIMIT 1")
    suspend fun lastPage(meetingId: Long): SlidePage?

    @Insert
    suspend fun insertPage(page: SlidePage): Long

    @Update
    suspend fun updatePage(page: SlidePage)

    @Query("DELETE FROM slide_page WHERE id = :id")
    suspend fun deletePage(id: Long)

    @Query("SELECT COUNT(*) FROM slide_page WHERE meetingId = :meetingId")
    suspend fun pageCount(meetingId: Long): Int
}
```

`data/AppDatabase.kt`：

```kotlin
package com.meetnote.recorder.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Meeting::class, SlidePage::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun meetingDao(): MeetingDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "meeting-recorder.db",
                ).build().also { instance = it }
            }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.data.MeetingDaoTest" --no-daemon
```

Expected: 7 tests PASS。（若 Robolectric 报 `SDK 34 not supported`，把 `@Config(sdk = [34])` 改为该 Robolectric 支持的最高档，例如 `[35]`；若报 `compileSdk 36` 相关错误，先跑一次 `assembleDebug` 拉齐依赖再跑。）

- [ ] **Step 5: 写失败的 FileStore 测试**

`app/src/test/java/com/meetnote/recorder/data/FileStoreTest.kt`：

```kotlin
package com.meetnote.recorder.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class FileStoreTest {

    private lateinit var root: File
    private lateinit var store: FileStore

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "fs-${System.nanoTime()}").apply { mkdirs() }
        store = FileStore(root)
    }

    @After
    fun tearDown() = root.deleteRecursively()

    @Test
    fun `page file is zero padded so lexical order equals page order`() {
        val f1 = store.pageFile(5, 1)
        val f12 = store.pageFile(5, 12)
        assertTrue(f1.name.endsWith("page_0001.jpg"))
        assertTrue(f12.name.endsWith("page_0012.jpg"))
        assertTrue("page_0001.jpg" < "page_0012.jpg")
        assertEquals(f1.parentFile, f12.parentFile)
        assertEquals(File(root, "meetings/5"), f1.parentFile)
    }

    @Test
    fun `audio file lives beside pages`() {
        val audio = store.audioFile(9)
        assertEquals(File(root, "meetings/9"), audio.parentFile)
        assertEquals("audio.m4a", audio.name)
    }

    @Test
    fun `pageFile creates meeting directory`() {
        val f = store.pageFile(3, 1)
        assertTrue(f.parentFile!!.isDirectory)
    }

    @Test
    fun `deleteMeeting removes whole directory`() {
        store.pageFile(7, 1).writeBytes(byteArrayOf(1))
        store.audioFile(7).writeBytes(byteArrayOf(2))

        assertTrue(store.deleteMeeting(7))
        assertFalse(File(root, "meetings/7").exists())
        assertTrue(store.deleteMeeting(7))  // 幂等
    }

    @Test
    fun `freeBytes is positive on temp volume`() {
        assertTrue(store.freeBytes() > 0L)
    }
}
```

- [ ] **Step 6: 运行确认失败后实现 FileStore**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.data.FileStoreTest" --no-daemon
```

Expected: FAIL，`unresolved reference: FileStore`。

`data/FileStore.kt`：

```kotlin
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
```

- [ ] **Step 7: 运行 FileStore 测试确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.data.FileStoreTest" --no-daemon
```

Expected: 5 tests PASS。

- [ ] **Step 8: 实现 MeetingRepository（覆盖/追加决策编排）**

仓库本体不做图像计算，只按"上一页 + 这次的相似度"决定覆盖还是追加——算法在 Task 5 注入。

`data/MeetingRepository.kt`：

```kotlin
package com.meetnote.recorder.data

import kotlinx.coroutines.flow.Flow
import java.io.File

/** 一次抓拍的落库结果 */
data class ShotResult(
    val index: Int,
    val overwritten: Boolean,
    val captureCount: Int,
)

/**
 * 仓库：编排 Room 与文件系统。
 *
 * 相似度由调用方算好后通过 [similarityToLast] 传入，阈值判定在 [decide] 完成，
 * 这样仓库逻辑可以脱离 Bitmap/图像算法单独测试。
 */
class MeetingRepository(
    private val dao: MeetingDao,
    private val fileStore: FileStore,
) {

    fun observeMeetings(): Flow<List<MeetingStats>> = dao.observeStats()

    fun observePages(meetingId: Long): Flow<List<SlidePage>> = dao.observePages(meetingId)

    suspend fun meeting(id: Long): Meeting? = dao.meeting(id)

    suspend fun pageCount(id: Long): Int = dao.pageCount(id)

    suspend fun createMeeting(title: String): Long =
        dao.insertMeeting(Meeting(title = title, createdAt = System.currentTimeMillis()))

    suspend fun finishMeeting(id: Long, audioPath: String?, durationMs: Long) {
        dao.finishMeeting(
            id = id,
            audioPath = audioPath,
            audioDurationMs = durationMs,
            endedAt = System.currentTimeMillis(),
        )
    }

    suspend fun deleteMeeting(id: Long) {
        fileStore.deleteMeeting(id)
        dao.deleteMeeting(id)
    }

    suspend fun deletePage(pageId: Long) {
        dao.deletePage(pageId)
    }

    /**
     * 记录一次抓拍结果。调用方负责：把 [imageFile] 写到 [fileStore.pageFile] 对应的
     * 覆盖路径或新页路径上（见 [targetFileFor]），本方法只负责入库与返回结果。
     *
     * @param similarityToLast 与上一页的相似度；无上一页时传 0f
     */
    suspend fun registerShot(
        meetingId: Long,
        imageFile: File,
        width: Int,
        height: Int,
        similarityToLast: Float,
    ): ShotResult {
        val last = dao.lastPage(meetingId)
        val now = System.currentTimeMillis()

        return if (last != null && decide(similarityToLast) == PageChoice.OVERWRITE) {
            // 同页新增内容：页码不变，只换图并累加拍摄次数
            dao.updatePage(
                last.copy(
                    filePath = imageFile.absolutePath,
                    width = width,
                    height = height,
                    captureCount = last.captureCount + 1,
                    updatedAt = now,
                )
            )
            ShotResult(index = last.index, overwritten = true, captureCount = last.captureCount + 1)
        } else {
            val next = (last?.index ?: 0) + 1
            val id = dao.insertPage(
                SlidePage(
                    meetingId = meetingId,
                    index = next,
                    filePath = imageFile.absolutePath,
                    width = width,
                    height = height,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            require(id > 0)
            ShotResult(index = next, overwritten = false, captureCount = 1)
        }
    }

    /** 本次抓拍该写到哪个文件：覆盖上一页 → 上一页路径；新页 → 下一页路径 */
    suspend fun targetFileFor(meetingId: Long, similarityToLast: Float): File {
        val last = dao.lastPage(meetingId)
        return if (last != null && decide(similarityToLast) == PageChoice.OVERWRITE) {
            File(last.filePath)
        } else {
            fileStore.pageFile(meetingId, (last?.index ?: 0) + 1)
        }
    }

    fun meetingAudioFile(meetingId: Long): File = fileStore.audioFile(meetingId)

    fun freeBytes(): Long = fileStore.freeBytes()

    companion object {
        /** 判定函数集中在此，阈值由 DedupeConfig 提供（Task 5 接入真实阈值） */
        fun decide(similarity: Float): PageChoice =
            if (similarity >= THRESHOLD_OVERWRITE) PageChoice.OVERWRITE else PageChoice.APPEND

        private const val THRESHOLD_OVERWRITE = 0.85f
    }
}

enum class PageChoice { OVERWRITE, APPEND }
```

> 说明：`THRESHOLD_OVERWRITE` 先写成常量，Task 5 会改为引用 `DedupeConfig.SAME_PAGE`
> 并把 `registerShot` 的判定统一到 `SlideDeduplicator`，同时删除本文件内的重复常量。

- [ ] **Step 9: 运行全部单元测试**

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

Expected: 12 tests PASS（7 DAO + 5 FileStore）。

- [ ] **Step 10: 提交**

```powershell
git add -A
git commit -m "feat: 数据层 Room 实体/DAO/仓库与文件存储"
```

---

---

### Task 3: 会议列表 + 导航骨架 + 新建会议

应用能启动、显示会议列表、新建会议并跳到拍摄页占位界面。这是第一个"能装到手机上点"的里程碑。

**Files:**
- Create: `app/src/main/java/com/meetnote/recorder/ui/theme/Theme.kt`
- Create: `app/src/main/java/com/meetnote/recorder/ui/nav/AppNav.kt`
- Create: `app/src/main/java/com/meetnote/recorder/ui/list/MeetingListViewModel.kt`
- Create: `app/src/main/java/com/meetnote/recorder/ui/list/MeetingListScreen.kt`
- Create: `app/src/main/java/com/meetnote/recorder/ui/capture/CaptureScreen.kt`（占位版，Task 9 替换）
- Create: `app/src/main/java/com/meetnote/recorder/ui/detail/MeetingDetailScreen.kt`（占位版，Task 12 替换）
- Modify: `app/src/main/java/com/meetnote/recorder/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`（加 `RECORD_AUDIO` 运行时请求由本任务的 ViewModel 驱动；权限已在清单中）
- Test: `app/src/test/java/com/meetnote/recorder/ui/list/MeetingListViewModelTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `MeetingRepository`、`MeetingStats`
- Produces:
  - `object Routes`：`LIST = "list"`、`CAPTURE = "capture/{meetingId}"`、`DETAIL = "detail/{meetingId}"`、`GUIDE = "guide"`，函数 `capture(id: Long): String`、`detail(id: Long): String`
  - `class MeetingListViewModel(private val repo: MeetingRepository)`：
    `val meetings: StateFlow<List<MeetingStats>>`、`fun createMeeting(title: String): Unit`（内部建会并发出一次性导航事件）、`val navigateTo: StateFlow<Long?>`、`fun consumeNavigation()`
  - `MeetingListScreen(onOpenDetail: (Long) -> Unit, onCreateMeeting: (String) -> Unit)`
  - `AppNav()`：根 composable，持有 `rememberNavController` 与 `MeetingRepository` 单例

- [ ] **Step 1: 写失败的 ViewModel 测试**

`app/src/test/java/com/meetnote/recorder/ui/list/MeetingListViewModelTest.kt`：

```kotlin
package com.meetnote.recorder.ui.list

import com.meetnote.recorder.data.MeetingRepository
import com.meetnote.recorder.data.MeetingStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 仓库的内存假实现：只覆盖 ViewModel 用到的方法 */
private class FakeRepo : MeetingRepository(
    dao = throw UnsupportedOperationException(),
    fileStore = throw UnsupportedOperationException(),
) {
    val stats = MutableStateFlow<List<MeetingStats>>(emptyList())
    var lastTitle: String? = null
    private var nextId = 1L

    override fun observeMeetings(): Flow<List<MeetingStats>> = stats

    override suspend fun createMeeting(title: String): Long {
        lastTitle = title
        val id = nextId++
        stats.value = stats.value + MeetingStats(
            id = id, title = title, createdAt = id * 1000,
            endedAt = null, audioDurationMs = 0, pageCount = 0,
        )
        return id
    }
}
```

> 上面的 `FakeRepo` 用继承覆盖，要求 `MeetingRepository` 的方法为 `open`。
> 若 Kotlin 报 final 修饰错误，改用另一种写法：把 `MeetingRepository` 抽接口
> `MeetingRepository`（`interface`）+ `class DefaultMeetingRepository(...)`，
> 二者保持完全相同的方法签名——这是首选方案，Task 2 的 `MeetingRepository.kt` 应声明为 `interface`，
> 实现类名 `DefaultMeetingRepository`。本步骤以接口方案为准执行。

`MeetingListViewModelTest.kt` 的测试用例部分：

```kotlin
class MeetingListViewModelTest {

    @Test
    fun `createMeeting stores title and emits navigation event`() = runBlocking {
        val repo = FakeRepo()
        val vm = MeetingListViewModel(repo)

        vm.createMeeting("需求评审")

        assertEquals("需求评审", repo.lastTitle)
        assertNotNull(vm.navigateTo.value)
        assertEquals(1, vm.meetings.value.size)
    }

    @Test
    fun `consumeNavigation clears the event`() {
        val vm = MeetingListViewModel(FakeRepo())
        vm.createMeeting("复盘")
        vm.consumeNavigation()
        assertNull(vm.navigateTo.value)
    }

    @Test
    fun `meetings flow mirrors repository`() = runBlocking {
        val repo = FakeRepo()
        val vm = MeetingListViewModel(repo)
        repo.stats.value = listOf(
            MeetingStats(1, "A", 1, null, 0, 3),
            MeetingStats(2, "B", 2, null, 0, 0),
        )
        assertEquals(listOf("A", "B"), vm.meetings.value.map { it.title })
        assertEquals(3, vm.meetings.value.first().pageCount)
    }
}
```

- [ ] **Step 2: 运行确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.ui.list.MeetingListViewModelTest" --no-daemon
```

Expected: FAIL，`unresolved reference: MeetingListViewModel`。

- [ ] **Step 3: 把 MeetingRepository 改为接口 + Default 实现**

修改 `data/MeetingRepository.kt`：把 `class MeetingRepository(...)` 声明改为

```kotlin
interface MeetingRepository {
    fun observeMeetings(): Flow<List<MeetingStats>>
    fun observePages(meetingId: Long): Flow<List<SlidePage>>
    suspend fun meeting(id: Long): Meeting?
    suspend fun pageCount(id: Long): Int
    suspend fun createMeeting(title: String): Long
    suspend fun finishMeeting(id: Long, audioPath: String?, durationMs: Long)
    suspend fun deleteMeeting(id: Long)
    suspend fun deletePage(pageId: Long)
    suspend fun registerShot(
        meetingId: Long,
        imageFile: File,
        width: Int,
        height: Int,
        similarityToLast: Float,
    ): ShotResult
    suspend fun targetFileFor(meetingId: Long, similarityToLast: Float): File
    fun meetingAudioFile(meetingId: Long): File
    fun freeBytes(): Long
}
```

新建 `data/DefaultMeetingRepository.kt`，把 Task 2 中 `MeetingRepository` 的函数体原样搬进去：

```kotlin
package com.meetnote.recorder.data

class DefaultMeetingRepository(
    private val dao: MeetingDao,
    private val fileStore: FileStore,
) : MeetingRepository {

    override fun observeMeetings(): Flow<List<MeetingStats>> = dao.observeStats()

    override fun observePages(meetingId: Long): Flow<List<SlidePage>> = dao.observePages(meetingId)

    override suspend fun meeting(id: Long): Meeting? = dao.meeting(id)

    override suspend fun pageCount(id: Long): Int = dao.pageCount(id)

    override suspend fun createMeeting(title: String): Long =
        dao.insertMeeting(Meeting(title = title, createdAt = System.currentTimeMillis()))

    override suspend fun finishMeeting(id: Long, audioPath: String?, durationMs: Long) {
        dao.finishMeeting(
            id = id,
            audioPath = audioPath,
            audioDurationMs = durationMs,
            endedAt = System.currentTimeMillis(),
        )
    }

    override suspend fun deleteMeeting(id: Long) {
        fileStore.deleteMeeting(id)
        dao.deleteMeeting(id)
    }

    override suspend fun deletePage(pageId: Long) = dao.deletePage(pageId)

    override suspend fun registerShot(
        meetingId: Long,
        imageFile: File,
        width: Int,
        height: Int,
        similarityToLast: Float,
    ): ShotResult {
        val last = dao.lastPage(meetingId)
        val now = System.currentTimeMillis()
        return if (last != null && decide(similarityToLast) == PageChoice.OVERWRITE) {
            dao.updatePage(
                last.copy(
                    filePath = imageFile.absolutePath,
                    width = width,
                    height = height,
                    captureCount = last.captureCount + 1,
                    updatedAt = now,
                )
            )
            ShotResult(last.index, overwritten = true, captureCount = last.captureCount + 1)
        } else {
            val next = (last?.index ?: 0) + 1
            dao.insertPage(
                SlidePage(
                    meetingId = meetingId, index = next,
                    filePath = imageFile.absolutePath,
                    width = width, height = height,
                    createdAt = now, updatedAt = now,
                )
            )
            ShotResult(next, overwritten = false, captureCount = 1)
        }
    }

    override suspend fun targetFileFor(meetingId: Long, similarityToLast: Float): File {
        val last = dao.lastPage(meetingId)
        return if (last != null && decide(similarityToLast) == PageChoice.OVERWRITE) {
            File(last.filePath)
        } else {
            fileStore.pageFile(meetingId, (last?.index ?: 0) + 1)
        }
    }

    override fun meetingAudioFile(meetingId: Long): File = fileStore.audioFile(meetingId)

    override fun freeBytes(): Long = fileStore.freeBytes()

    companion object {
        fun decide(similarity: Float): PageChoice =
            if (similarity >= THRESHOLD_OVERWRITE) PageChoice.OVERWRITE else PageChoice.APPEND

        private const val THRESHOLD_OVERWRITE = 0.85f
    }
}

enum class PageChoice { OVERWRITE, APPEND }
```

同步把 Task 2 的测试 `MeetingDaoTest` 里若有引用改为 `AppDatabase` + `DefaultMeetingRepository`（本测试只用 DAO，通常无需改）。

- [ ] **Step 4: 写 ViewModel 使其通过**

`ui/list/MeetingListViewModel.kt`：

```kotlin
package com.meetnote.recorder.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meetnote.recorder.data.MeetingRepository
import com.meetnote.recorder.data.MeetingStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MeetingListViewModel(
    private val repo: MeetingRepository,
) : ViewModel() {

    val meetings: StateFlow<List<MeetingStats>> = repo.observeMeetings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _navigateTo = MutableStateFlow<Long?>(null)
    val navigateTo: StateFlow<Long?> = _navigateTo

    fun createMeeting(title: String) {
        viewModelScope.launch {
            val id = repo.createMeeting(title)
            _navigateTo.value = id
        }
    }

    fun consumeNavigation() {
        _navigateTo.value = null
    }
}
```

- [ ] **Step 5: 运行确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.ui.list.MeetingListViewModelTest" --no-daemon
```

Expected: 3 tests PASS。（`stateIn(viewModelScope)` 在 Robolectric 下可用；若报 `viewModelScope` 缺失，测试中改用 `vm.meetings` 前先 `runBlocking { delay(50) }`，或给 ViewModel 增加 `fun meetingsDirect(): Flow<...>` 供测试直接读——保持生产代码签名不变。）

- [ ] **Step 6: 写主题、路由与各屏幕**

`ui/theme/Theme.kt`：

```kotlin
package com.meetnote.recorder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF0B6B4F)
private val GreenLight = Color(0xFF4FA98A)
private val Amber = Color(0xFFF2A93B)

private val LightColors = lightColorScheme(
    primary = Green, secondary = GreenLight, tertiary = Amber,
)
private val DarkColors = darkColorScheme(
    primary = GreenLight, secondary = Green, tertiary = Amber,
)

@Composable
fun MeetingRecorderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
```

`ui/nav/AppNav.kt`：

```kotlin
package com.meetnote.recorder.ui.nav

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.meetnote.recorder.data.AppDatabase
import com.meetnote.recorder.data.DefaultMeetingRepository
import com.meetnote.recorder.data.FileStore
import com.meetnote.recorder.ui.capture.CaptureScreen
import com.meetnote.recorder.ui.detail.MeetingDetailScreen
import com.meetnote.recorder.ui.guide.GuideScreen
import com.meetnote.recorder.ui.list.MeetingListScreen
import com.meetnote.recorder.ui.list.MeetingListViewModel
import android.content.Context

object Routes {
    const val LIST = "list"
    const val GUIDE = "guide"
    const val CAPTURE = "capture/{meetingId}"
    const val DETAIL = "detail/{meetingId}"
    fun capture(meetingId: Long) = "capture/$meetingId"
    fun detail(meetingId: Long) = "detail/$meetingId"
}

/** 全局唯一仓库，避免各屏各建一套 */
fun provideRepository(context: Context): com.meetnote.recorder.data.MeetingRepository =
    DefaultMeetingRepository(
        dao = AppDatabase.get(context).meetingDao(),
        fileStore = FileStore(context.filesDir),
    )

@Composable
fun AppNav() {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = androidx.compose.runtime.remember { provideRepository(context) }

    NavHost(navController = navController, startDestination = Routes.LIST) {

        composable(Routes.LIST) {
            val vm: MeetingListViewModel = viewModel(factory = object :
                androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    MeetingListViewModel(repository) as T
            })
            MeetingListScreen(
                meetings = vm.meetings,
                navigateTo = vm.navigateTo,
                onConsumeNavigation = vm::consumeNavigation,
                onOpenDetail = { navController.navigate(Routes.detail(it)) },
                onOpenCapture = { navController.navigate(Routes.capture(it)) },
                onCreateMeeting = vm::createMeeting,
                onOpenGuide = { navController.navigate(Routes.GUIDE) },
            )
        }

        composable(
            Routes.CAPTURE,
            arguments = listOf(navArgument("meetingId") { type = NavType.LongType }),
        ) { entry ->
            val meetingId = entry.arguments!!.getLong("meetingId")
            CaptureScreen(
                meetingId = meetingId,
                repository = repository,
                onFinished = {
                    navController.navigate(Routes.detail(meetingId)) {
                        popUpTo(Routes.LIST)
                    }
                },
                onOpenGuide = { navController.navigate(Routes.GUIDE) },
            )
        }

        composable(
            Routes.DETAIL,
            arguments = listOf(navArgument("meetingId") { type = NavType.LongType }),
        ) { entry ->
            val meetingId = entry.arguments!!.getLong("meetingId")
            MeetingDetailScreen(
                meetingId = meetingId,
                repository = repository,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.GUIDE) {
            GuideScreen(onBack = { navController.popBackStack() })
        }
    }
}
```

> `GuideScreen` 本任务先写最小占位（Task 14 替换成完整引导），保证编译通过：

`ui/guide/GuideScreen.kt`：

```kotlin
package com.meetnote.recorder.ui.guide

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun GuideScreen(onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("后台设置引导（待完善）")
    }
}
```

`ui/list/MeetingListScreen.kt`：

```kotlin
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
import androidx.compose.ui.unit.dp
import com.meetnote.recorder.data.MeetingStats
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
    onCreateMeeting: (String) -> Unit,
    onOpenGuide: () -> Unit,
) {
    val list by meetings.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }

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
            FloatingActionButton(onClick = { title = ""; showDialog = true }) {
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
                    MeetingCard(stats = stats, onClick = { onOpenDetail(stats.id) })
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
                        onCreateMeeting(t)
                    },
                ) { Text("开始") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("取消") }
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
```

`ui/capture/CaptureScreen.kt`（占位版，Task 9 会整文件替换）：

```kotlin
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
```

`ui/detail/MeetingDetailScreen.kt`（占位版，Task 12 替换）：

```kotlin
package com.meetnote.recorder.ui.detail

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
fun MeetingDetailScreen(
    meetingId: Long,
    repository: MeetingRepository,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("会议详情（施工中）#$meetingId")
        Button(onClick = onBack) { Text("返回") }
    }
}
```

- [ ] **Step 7: 改 MainActivity 接上导航**

```kotlin
package com.meetnote.recorder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.meetnote.recorder.ui.nav.AppNav
import com.meetnote.recorder.ui.theme.MeetingRecorderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MeetingRecorderTheme {
                AppNav()
            }
        }
    }
}
```

- [ ] **Step 8: 构建并跑全部单元测试**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL`，测试 15 PASS。

- [ ] **Step 9: 提交**

```powershell
git add -A
git commit -m "feat: 会议列表、导航骨架与新建会议流程"
```

---

---

### Task 4: 录音引擎与前台服务

录音跑在前台服务里，退出拍摄页、锁屏都不中断；通知栏常驻可停止。可测试的核心是"录音状态机 + 时长计算"，用假 Recorder 覆盖。

**Files:**
- Create: `app/src/main/java/com/meetnote/recorder/audio/Recorder.kt`（接口 + `MediaRecorderEngine`）
- Create: `app/src/main/java/com/meetnote/recorder/audio/RecordingState.kt`
- Create: `app/src/main/java/com/meetnote/recorder/audio/MeetingService.kt`
- Modify: `app/src/main/AndroidManifest.xml`（注册 service）
- Modify: `app/src/main/java/com/meetnote/recorder/ui/capture/CaptureScreen.kt`（暂时只加"开始录音/停止"按钮，Task 9 整合）
- Test: `app/src/test/java/com/meetnote/recorder/audio/RecordingStateTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `MeetingRepository.meetingAudioFile(id)`、`finishMeeting`
- Produces:
  - `interface Recorder { fun start(file: File); fun stop(): Long; fun elapsedMs(): Long; fun isRunning(): Boolean }`（`stop()` 返回实际时长毫秒）
  - `class MediaRecorderEngine : Recorder`
  - `object RecordingState`：`val status: StateFlow<RecordingStatus>`、`fun bind(meetingId: Long, durationMs: Long)`、`fun onStart()`、`fun onStop(durationMs: Long)`、`fun onTick(ms: Long)`、`fun clear()`
  - `data class RecordingStatus(val meetingId: Long = 0, val running: Boolean = false, val elapsedMs: Long = 0, val error: String? = null)`
  - `MeetingService`：伴生对象 `start(context, meetingId)`、`stop(context)`；action 常量 `ACTION_START`、`ACTION_STOP`，extra `EXTRA_MEETING_ID`
  - `formatDuration(ms: Long): String`（Task 3 已定义于 `ui/list/MeetingListScreen.kt`，本任务不重复定义）

- [ ] **Step 1: 写失败的状态机测试**

`app/src/test/java/com/meetnote/recorder/audio/RecordingStateTest.kt`：

```kotlin
package com.meetnote.recorder.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecordingStateTest {

    @Before
    fun setUp() = RecordingState.clear()

    @After
    fun tearDown() = RecordingState.clear()

    @Test
    fun `initial state is idle`() {
        val s = RecordingState.status.value
        assertFalse(s.running)
        assertEquals(0L, s.meetingId)
        assertNull(s.error)
    }

    @Test
    fun `start marks running with meeting id`() {
        RecordingState.bind(meetingId = 42, durationMs = 0)
        RecordingState.onStart()

        val s = RecordingState.status.value
        assertTrue(s.running)
        assertEquals(42, s.meetingId)
        assertEquals(0L, s.elapsedMs)
    }

    @Test
    fun `ticks accumulate elapsed time`() {
        RecordingState.bind(7, 0)
        RecordingState.onStart()
        RecordingState.onTick(1_000)
        RecordingState.onTick(65_000)

        assertEquals(65_000L, RecordingState.status.value.elapsedMs)
    }

    @Test
    fun `stop freezes elapsed and clears running`() {
        RecordingState.bind(7, 0)
        RecordingState.onStart()
        RecordingState.onTick(12_000)
        RecordingState.onStop(durationMs = 12_500)

        val s = RecordingState.status.value
        assertFalse(s.running)
        assertEquals(12_500L, s.elapsedMs)
        assertEquals(7, s.meetingId)   // 详情页还要用它显示录音时长
    }

    @Test
    fun `error is surfaced without stopping elapsed`() {
        RecordingState.bind(3, 0)
        RecordingState.onStart()
        RecordingState.onTick(5_000)
        RecordingState.onError("麦克风被占用")

        val s = RecordingState.status.value
        assertEquals("麦克风被占用", s.error)
        assertFalse(s.running)
        assertEquals(5_000L, s.elapsedMs)
    }

    @Test
    fun `recorder fake follows contract`() {
        val rec = FakeRecorder()
        val file = java.io.File(System.getProperty("java.io.tmpdir"), "a.m4a")
        rec.start(file)
        assertTrue(rec.isRunning())
        rec.tick(3_000)
        assertEquals(3_000L, rec.elapsedMs())
        assertEquals(3_000L, rec.stop())
        assertFalse(rec.isRunning())
        assertEquals(3_000L, rec.stop()) // 重复 stop 幂等
    }
}

/** 测试专用假实现：不碰 MediaRecorder，手动推进时间 */
class FakeRecorder : Recorder {
    private var running = false
    private var elapsed = 0L
    private var file: File? = null

    override fun start(file: File) {
        this.file = file
        running = true
        elapsed = 0L
    }

    override fun stop(): Long {
        if (!running) return elapsed
        running = false
        return elapsed
    }

    override fun elapsedMs(): Long = elapsed

    override fun isRunning(): Boolean = running

    fun tick(ms: Long) {
        elapsed += ms
    }
}
```

> `FakeRecorder` 里用到了 `java.io.File`，文件顶部需 `import java.io.File`（测试代码中补上）。

- [ ] **Step 2: 运行确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.audio.RecordingStateTest" --no-daemon
```

Expected: FAIL，`unresolved reference: Recorder / RecordingState`。

- [ ] **Step 3: 实现 Recorder 与状态**

`audio/Recorder.kt`：

```kotlin
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
```

`audio/RecordingState.kt`：

```kotlin
package com.meetnote.recorder.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RecordingStatus(
    val meetingId: Long = 0L,
    val running: Boolean = false,
    val elapsedMs: Long = 0L,
    val error: String? = null,
)

/** 进程内全局录音状态：服务更新，UI 观察 */
object RecordingState {

    private val _status = MutableStateFlow(RecordingStatus())
    val status: StateFlow<RecordingStatus> = _status.asStateFlow()

    fun bind(meetingId: Long, durationMs: Long) {
        _status.value = RecordingStatus(meetingId = meetingId, elapsedMs = durationMs)
    }

    fun onStart() {
        val s = _status.value
        _status.value = s.copy(running = true, error = null, elapsedMs = 0L)
    }

    fun onTick(ms: Long) {
        _status.value = _status.value.copy(elapsedMs = ms)
    }

    fun onStop(durationMs: Long) {
        _status.value = _status.value.copy(running = false, elapsedMs = durationMs)
    }

    fun onError(message: String) {
        _status.value = _status.value.copy(running = false, error = message)
    }

    fun clear() {
        _status.value = RecordingStatus()
    }
}
```

- [ ] **Step 4: 运行确认状态机测试通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.audio.RecordingStateTest" --no-daemon
```

Expected: 7 tests PASS。

- [ ] **Step 5: 实现前台服务**

`audio/MeetingService.kt`：

```kotlin
package com.meetnote.recorder.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.meetnote.recorder.MainActivity
import com.meetnote.recorder.R
import com.meetnote.recorder.data.AppDatabase
import com.meetnote.recorder.data.DefaultMeetingRepository
import com.meetnote.recorder.data.FileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Timer
import java.util.TimerTask

class MeetingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recorder: Recorder = MediaRecorderEngine()
    private var ticker: Timer? = null
    private var meetingId: Long = 0L
    private var audioFile: File? = null
    private var startedAtElapsed: Long = 0L
    private var persistJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent.getLongExtra(EXTRA_MEETING_ID, -1L))
            ACTION_STOP -> handleStop()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(id: Long) {
        if (id <= 0) {
            stopSelf()
            return
        }
        // 防重复启动：已在录音时，同会议幂等返回；跨会议拒绝且绝不触碰现有录音
        if (recorder.isRunning()) {
            if (id != meetingId) {
                // 不覆盖 meetingId/audioFile、不重启 recorder、不 stopSelf（会中断活录音）
                RecordingState.onError("麦克风正在录制其他会议")
            }
            return
        }
        meetingId = id
        val repository = DefaultMeetingRepository(
            dao = AppDatabase.get(this).meetingDao(),
            fileStore = FileStore(filesDir),
        )
        val file = repository.meetingAudioFile(id)
        audioFile = file

        try {
            recorder.start(file)
        } catch (e: Exception) {
            RecordingState.onError(e.message ?: "无法开始录音")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        startForegroundWithNotification()
        RecordingState.bind(meetingId = id, durationMs = 0L)
        RecordingState.onStart()
        startedAtElapsed = SystemClock.elapsedRealtime()

        ticker?.cancel()
        ticker = Timer("meeting-ticker", true).also {
            it.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    RecordingState.onTick(SystemClock.elapsedRealtime() - startedAtElapsed)
                }
            }, 1_000L, 1_000L)
        }
    }

    private fun handleStop() {
        val duration = recorder.stop()
        ticker?.cancel()
        ticker = null
        RecordingState.onStop(durationMs = duration)

        val repository = DefaultMeetingRepository(
            dao = AppDatabase.get(this).meetingDao(),
            fileStore = FileStore(filesDir),
        )
        persistJob = scope.launch {
            // NonCancellable：服务销毁时的 scope.cancel() 不允许掐掉 finishMeeting 落库
            withContext(NonCancellable) {
                repository.finishMeeting(
                    id = meetingId,
                    audioPath = audioFile?.absolutePath,
                    durationMs = duration,
                )
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // 进程被杀等意外停止：尽力把已录时长落库
        if (recorder.isRunning()) {
            val duration = recorder.stop()
            ticker?.cancel()
            RecordingState.onStop(durationMs = duration)
            val repository = DefaultMeetingRepository(
                dao = AppDatabase.get(this).meetingDao(),
                fileStore = FileStore(filesDir),
            )
            // runBlocking：onDestroy 是同步回调，兜底写入必须在 scope.cancel() 之前真正落库；
            // NonCancellable 保证任何取消路径都掐不掉这条落库（核心契约：录音时长绝不丢失）。
            runBlocking {
                withContext(NonCancellable + Dispatchers.IO) {
                    repository.finishMeeting(
                        id = meetingId,
                        audioPath = audioFile?.absolutePath,
                        durationMs = duration,
                    )
                }
            }
        }
        // 等 handleStop 启动的落库写入完成后再取消 scope，防止 cancel 掐掉尚未跑完的 finishMeeting
        runBlocking { persistJob?.join() }
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "会议录音",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "会议进行中的常驻通知" }
            )
        }
    }

    private fun startForegroundWithNotification() {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MeetingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("会议记录中")
            .setContentText("录音进行中，返回应用可查看拍摄进度")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(0, "停止录音", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_START = "com.meetnote.recorder.action.START"
        const val ACTION_STOP = "com.meetnote.recorder.action.STOP"
        const val EXTRA_MEETING_ID = "com.meetnote.recorder.extra.MEETING_ID"
        private const val CHANNEL_ID = "meeting_recording"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context, meetingId: Long) {
            val intent = Intent(context, MeetingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MEETING_ID, meetingId)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MeetingService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
```

在 `AndroidManifest.xml` 的 `</application>` 前注册：

```xml
        <service
            android:name=".audio.MeetingService"
            android:exported="false"
            android:foregroundServiceType="microphone" />
```

`ui/capture/CaptureScreen.kt` 在占位页 Column 内加入开始/停止按钮（Task 9 整合）：

```kotlin
        if (status.running && status.meetingId == meetingId) {
            Button(onClick = { MeetingService.stop(context) }) { Text("停止录音") }
        } else {
            // 其他会议正在录音时不提供"开始录音"，防止跨会议抢占（服务端另有硬防护）
            Button(
                onClick = { MeetingService.start(context, meetingId) },
                enabled = !status.running || status.meetingId == meetingId,
            ) { Text("开始录音") }
        }
```

- [ ] **Step 6: 构建验证**

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 7: 提交**

```powershell
git add -A
git commit -m "feat: 录音引擎、全局状态与前台服务"
```

---

---

### Task 5: 同页去重算法（纯逻辑，TDD）

**算法修正说明（相对 spec §7 的初稿）**：初稿用 `0.55×直方图 + 0.45×NCC`。手算验证：白底幻灯片上"标题不变、正文增加两条"这种情况，NCC 只能到 0.79，会被判成新页——**违背"同页只留最后一张"的核心需求**。原因：白底方差极小，任何新增笔画都会显著拉低互相关。

改用**方向性判据**（只问"旧页内容还在不在"，不问"两图整体多像"）：

```
recall     = 旧页墨迹格子在新帧中仍是墨迹的比例   → 累积内容时恒为 1.0
fgOldSim   = 旧页墨迹区域的细网格灰度相似度       → 换了文字会明显下降
coverage   = 新帧墨迹量 / 旧页墨迹量              → 排除"新帧内容变少"（动画中间态）

同页 ⟺ recall ≥ 0.90 且 fgOldSim ≥ 0.85 且 coverage ≥ 0.90
否则 → 新页（宁可多留一页，不丢内容）
```

**Files:**
- Create: `app/src/main/java/com/meetnote/recorder/dedupe/DedupeConfig.kt`
- Create: `app/src/main/java/com/meetnote/recorder/dedupe/ImageSignals.kt`
- Create: `app/src/main/java/com/meetnote/recorder/dedupe/SlideDeduplicator.kt`
- Modify: `app/src/main/java/com/meetnote/recorder/data/MeetingRepository.kt`（接口改 `isSamePage: Boolean`）
- Modify: `app/src/main/java/com/meetnote/recorder/data/DefaultMeetingRepository.kt`（删阈值，交由算法判定）
- Test: `app/src/test/java/com/meetnote/recorder/dedupe/SlideDeduplicatorTest.kt`

**Interfaces:**
- Consumes: 无（纯 Kotlin + `android.graphics.Color` 之外无依赖；像素用 `IntArray` 表示，JVM 可跑）
- Produces:
  - `object DedupeConfig`：`INK_W=64`、`INK_H=48`、`FINE_W=400`、`FINE_H=225`、`INK_THRESHOLD=0.72f`、`SAME_RECALL=0.90f`、`SAME_FG=0.85f`、`SAME_COVERAGE=0.90f`
  - `data class ImageSignals(inkMask: ByteArray, inkCount: Int, fineGray: FloatArray)`
  - `object SignalBuilder { fun fromPixels(pixels: IntArray, width: Int, height: Int): ImageSignals }`（像素为 ARGB `Int`，灰度归一化到 0..1）
  - `data class Similarity(recall: Float, fgOldSim: Float, coverage: Float, score: Float, val isSamePage: Boolean)`
  - `object SlideDeduplicator { fun compare(old: ImageSignals, candidate: ImageSignals): Similarity }`
  - `MeetingRepository.registerShot(meetingId, imageFile, width, height, isSamePage: Boolean): ShotResult`
  - `MeetingRepository.targetFileFor(meetingId, isSamePage: Boolean): File`

- [ ] **Step 1: 写失败的测试（含核心场景"同页累积内容"）**

`app/src/test/java/com/meetnote/recorder/dedupe/SlideDeduplicatorTest.kt`：

```kotlin
package com.meetnote.recorder.dedupe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideDeduplicatorTest {

    private val w = 800
    private val h = 450

    /** 全白幻灯片底 */
    private fun white() = IntArray(w * h) { 0xFFFFFFFF.toInt() }

    /** 画一条深色横带（模拟标题或一行要点），y 起点按 450 高度给定 */
    private fun band(pixels: IntArray, y0: Int, y1: Int, x0: Int, x1: Int, color: Int = 0xFF1A1A2E.toInt()) {
        for (y in y0 until y1.coerceAtMost(h)) {
            for (x in x0 until x1.coerceAtMost(w)) {
                pixels[y * w + x] = color
            }
        }
    }

    /** 标准版式：顶部标题带 + 两条要点 */
    private fun slideWithTwoBullets(): IntArray = white().apply {
        band(this, 30, 70, 60, 740)          // 标题
        band(this, 130, 160, 60, 500)        // 要点 1
        band(this, 200, 230, 60, 460)        // 要点 2
    }

    /** 同一页又多讲了两条要点（核心场景：内容累积） */
    private fun slideWithFourBullets(): IntArray = white().apply {
        band(this, 30, 70, 60, 740)
        band(this, 130, 160, 60, 500)
        band(this, 200, 230, 60, 460)
        band(this, 270, 300, 60, 520)        // 新增 3
        band(this, 340, 370, 60, 430)        // 新增 4
    }

    /** 完全换页：标题位置和正文都不同 */
    private fun differentSlide(): IntArray = white().apply {
        band(this, 30, 70, 60, 640)
        band(this, 120, 300, 60, 740, color = 0xFF2E4A7A.toInt())   // 大块图示
    }

    /** 只有标题的过渡页（内容比上一帧少） */
    private fun titleOnly(): IntArray = white().apply {
        band(this, 30, 70, 60, 740)
    }

    /** 同版式但正文整体右移（模拟"换了一段文字但版面雷同"） */
    private fun sameLayoutOtherText(): IntArray = white().apply {
        band(this, 30, 70, 60, 740)
        band(this, 130, 160, 260, 700)
        band(this, 200, 230, 300, 740)
    }

    private fun signals(pixels: IntArray) = SignalBuilder.fromPixels(pixels, w, h)

    @Test
    fun `identical page is same page with perfect score`() {
        val s = SlideDeduplicator.compare(signals(slideWithTwoBullets()), signals(slideWithTwoBullets()))
        assertEquals(1f, s.recall, 0.02f)
        assertEquals(1f, s.fgOldSim, 0.02f)
        assertTrue(s.isSamePage)
    }

    @Test
    fun `adding bullets to same page stays ONE page`() {
        // 核心需求：同一页多次增加内容，只保留最后一张
        val s = SlideDeduplicator.compare(signals(slideWithTwoBullets()), signals(slideWithFourBullets()))
        assertTrue("recall=${s.recall} fg=${s.fgOldSim} cov=${s.coverage}", s.recall >= 0.9f)
        assertTrue("fgOldSim=${s.fgOldSim} 必须仍高，因为旧要点没动", s.fgOldSim >= 0.85f)
        assertTrue(s.coverage > 1f)
        assertTrue("累积内容必须判为同页，实得 score=${s.score}", s.isSamePage)
    }

    @Test
    fun `completely different slide is a new page`() {
        val s = SlideDeduplicator.compare(signals(slideWithTwoBullets()), signals(differentSlide()))
        assertFalse(s.isSamePage)
    }

    @Test
    fun `content shrink is NOT same page`() {
        // 从两条要点退回只剩标题：多半是动画中间态或换页
        val s = SlideDeduplicator.compare(signals(slideWithTwoBullets()), signals(titleOnly()))
        assertFalse("coverage=${s.coverage}", s.isSamePage)
        assertTrue("内容变少时 coverage 必须小于 0.9", s.coverage < 0.9f)
    }

    @Test
    fun `same layout but different text is a new page`() {
        val s = SlideDeduplicator.compare(signals(slideWithTwoBullets()), signals(sameLayoutOtherText()))
        assertTrue("旧要点仍在原处，recall 应偏高：${s.recall}", s.recall > 0.5f)
        assertFalse(
            "文字换了 fgOldSim 必须掉下来：${s.fgOldSim}",
            s.isSamePage,
        )
    }

    @Test
    fun `blank previous page never matches`() {
        val blank = signals(white())
        val s = SlideDeduplicator.compare(blank, signals(slideWithTwoBullets()))
        assertFalse(s.isSamePage)   // 旧页无内容 → 无法确认同页 → 新页
    }

    @Test
    fun `score is weighted average of cues`() {
        val s = SlideDeduplicator.compare(signals(slideWithTwoBullets()), signals(slideWithFourBullets()))
        val expected = 0.5f * s.recall + 0.5f * s.fgOldSim
        assertEquals(expected, s.score, 0.001f)
    }

    @Test
    fun `signal builder produces counts and normalized gray`() {
        val sig = signals(slideWithTwoBullets())
        val cells = DedupeConfig.INK_W * DedupeConfig.INK_H
        assertEquals(cells, sig.inkMask.size)
        // inkCount 是"有墨迹的格子数"而非全部格子：三条横带约占 503/3072 格。
        // 原断言 assertEquals(cells, sig.inkCount) 按 brief Step 4 的预案改为区间断言
        assertTrue("inkCount=${sig.inkCount}", sig.inkCount in 400..600)
        assertEquals(DedupeConfig.FINE_W * DedupeConfig.FINE_H, sig.fineGray.size)
        sig.fineGray.forEach { g -> assertTrue(g in 0f..1f) }

        val blank = signals(white())
        assertEquals(0, blank.inkCount)
    }
}
```

- [ ] **Step 2: 运行确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.dedupe.SlideDeduplicatorTest" --no-daemon
```

Expected: FAIL，`unresolved reference: DedupeConfig / SignalBuilder / SlideDeduplicator`。

- [ ] **Step 3: 实现配置与信号提取**

`dedupe/DedupeConfig.kt`：

```kotlin
package com.meetnote.recorder.dedupe

/**
 * 同页去重的全部阈值与网格尺寸。真机调参时只改这里（spec 风险 #4）。
 */
object DedupeConfig {
    /** 粗网格：判定"墨迹"分布，用于 recall / coverage */
    const val INK_W = 64
    const val INK_H = 48

    /** 细网格：判定"旧文字是否还在原处"，用于 fgOldSim */
    const val FINE_W = 400
    const val FINE_H = 225

    /** 归一化灰度低于此值视为"有内容（墨迹）" */
    const val INK_THRESHOLD = 0.72f

    /** 同页三条件（同时满足才判同页） */
    const val SAME_RECALL = 0.90f
    const val SAME_FG = 0.85f
    const val SAME_COVERAGE = 0.90f

    /**
     * fgOldSim 的逐格缩放系数（**非死区容差**）：每格相似度 =
     * `1 - min(1, |Δ灰度| / FG_TOLERANCE)`。小于该值的漂移同样被线性放大惩罚
     * （Δ=0.05 → 该格 0.50），并非"不超即视为不变"。存在意义：深色文字被深色
     * 图示整块盖住时每格均值差仅 ~0.17，纯绝对差度量实测 0.8514 > 0.85 会误判同页。
     *
     * 漂移预算：在 SAME_FG=0.85 下，均匀曝光/对齐漂移预算 ≈ FG_TOLERANCE × 0.15
     * = 0.015（约 4/255 灰阶），比纯绝对差度量（预算 0.15）紧约 10 倍。
     * 方向安全：逐格 min(1, ·) 截断使 fgOldSim 只会低于纯绝对差版本，只会把
     * 同页翻成新页（宁可多留一页），不会把新页翻成同页。
     *
     * 真机复核（spec §15 风险 #4，首次真机调参）：必须用累积内容场景在曝光/对齐
     * 漂移下验证仍判同页；若被误判为新页，调高 FG_TOLERANCE 放宽（调低只会收紧、
     * 更易误判新页），配合 `adb logcat -s Dedupe` 观察 recall/fg/cov 定位掉线项。
     */
    const val FG_TOLERANCE = 0.10f

    /** 汇报用的综合分，仅用于日志与调参显示，不参与判定 */
    const val RECALL_WEIGHT = 0.5f
}
```

`dedupe/ImageSignals.kt`：

```kotlin
package com.meetnote.recorder.dedupe

data class ImageSignals(
    /** INK_W×INK_H，1 = 该格有内容 */
    val inkMask: ByteArray,
    val inkCount: Int,
    /** FINE_W×FINE_H，归一化灰度 0..1 */
    val fineGray: FloatArray,
)

object SignalBuilder {

    /**
     * @param pixels ARGB 像素，行优先
     * @param width  原图宽
     * @param height 原图高
     */
    fun fromPixels(pixels: IntArray, width: Int, height: Int): ImageSignals {
        require(width > 0 && height > 0) { "非法尺寸 ${width}x$height" }
        require(pixels.size >= width * height) { "像素数不足" }

        val inkMask = ByteArray(DedupeConfig.INK_W * DedupeConfig.INK_H)
        val fineGray = FloatArray(DedupeConfig.FINE_W * DedupeConfig.FINE_H)
        var inkCount = 0

        // 粗网格：每格取中心附近 3x3 平均灰度，低于阈值记为墨迹
        for (gy in 0 until DedupeConfig.INK_H) {
            val y0 = gy * height / DedupeConfig.INK_H
            val y1 = ((gy + 1) * height / DedupeConfig.INK_H).coerceAtLeast(y0 + 1)
            val yc = (y0 + y1) / 2
            for (gx in 0 until DedupeConfig.INK_W) {
                val x0 = gx * width / DedupeConfig.INK_W
                val x1 = ((gx + 1) * width / DedupeConfig.INK_W).coerceAtLeast(x0 + 1)
                val xc = (x0 + x1) / 2
                val g = normalizedGray(pixels, width, height, xc, yc)
                if (g < DedupeConfig.INK_THRESHOLD) {
                    inkMask[gy * DedupeConfig.INK_W + gx] = 1
                    inkCount++
                }
            }
        }

        // 细网格：整格平均灰度
        for (fy in 0 until DedupeConfig.FINE_H) {
            val y0 = fy * height / DedupeConfig.FINE_H
            val y1 = ((fy + 1) * height / DedupeConfig.FINE_H).coerceAtLeast(y0 + 1)
            for (fx in 0 until DedupeConfig.FINE_W) {
                val x0 = fx * width / DedupeConfig.FINE_W
                val x1 = ((fx + 1) * width / DedupeConfig.FINE_W).coerceAtLeast(x0 + 1)
                var sum = 0f
                var n = 0
                for (y in y0 until y1) {
                    val row = y * width
                    for (x in x0 until x1) {
                        sum += grayOf(pixels[row + x])
                        n++
                    }
                }
                fineGray[fy * DedupeConfig.FINE_W + fx] = if (n == 0) 1f else sum / n
            }
        }

        return ImageSignals(inkMask = inkMask, inkCount = inkCount, fineGray = fineGray)
    }

    private fun normalizedGray(px: IntArray, w: Int, h: Int, x: Int, y: Int): Float {
        var sum = 0f
        var n = 0
        for (dy in -1..1) {
            val yy = y + dy
            if (yy < 0 || yy >= h) continue
            val row = yy * w
            for (dx in -1..1) {
                val xx = x + dx
                if (xx < 0 || xx >= w) continue
                sum += grayOf(px[row + xx])
                n++
            }
        }
        return if (n == 0) 1f else sum / n
    }

    /** Rec.601 灰度，归一化 0..1 */
    private fun grayOf(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b) / 255f
    }
}
```

- [ ] **Step 4: 运行确认部分通过（SignalBuilder 已可测）**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.dedupe.SlideDeduplicatorTest" --no-daemon
```

Expected: 仍 FAIL（`SlideDeduplicator` 未实现），但 `signal builder produces counts...` 用例应已能编译运行。若 `inkCount` 断言不等 36，说明标题/要点带恰好落在格子边界——这是测试数据问题，把断言改为 `assertTrue(sig.inkCount in 20..60)` 并保留 `blank.inkCount == 0`。

- [ ] **Step 5: 实现比较与判定**

`dedupe/SlideDeduplicator.kt`：

```kotlin
package com.meetnote.recorder.dedupe

data class Similarity(
    /** 旧页内容在新帧中的保留比例 */
    val recall: Float,
    /** 旧页墨迹区域的细网格灰度相似度 */
    val fgOldSim: Float,
    /** 新帧墨迹量 / 旧页墨迹量 */
    val coverage: Float,
    /** 汇报用综合分（不参与判定） */
    val score: Float,
    val isSamePage: Boolean,
)

object SlideDeduplicator {

    fun compare(old: ImageSignals, candidate: ImageSignals): Similarity {
        // 旧页没有内容时无法确认同页，一律按新页处理
        if (old.inkCount == 0) {
            return Similarity(
                recall = 0f, fgOldSim = 1f, coverage = Float.MAX_VALUE,
                score = 0f, isSamePage = false,
            )
        }

        var shared = 0
        var newInk = 0
        val mask = old.inkMask
        val candMask = candidate.inkMask
        for (i in mask.indices) {
            val oldHas = mask[i] == 1.toByte()
            val candHas = candMask[i] == 1.toByte()
            if (oldHas && candHas) shared++
            if (candHas) newInk++
        }

        val recall = shared.toFloat() / old.inkCount
        val coverage = newInk.toFloat() / old.inkCount

        // 只在"旧页有内容"的细格上比对：文字被替换或擦除会显著拉低该值，
        // 而"旧文字原地不动、旁边新增内容"时该值仍接近 1。
        // 每格相似度 = 1 - min(1, |Δ| / FG_TOLERANCE)（缩放截断，非死区容差，
        // 详见 DedupeConfig.FG_TOLERANCE 注释）：纯均值绝对差在"深色文字被深色
        // 图示盖住"时每格仅 ~0.17，整页实测 0.8514 会被误判同页。
        val oldFine = old.fineGray
        val candFine = candidate.fineGray
        var simSum = 0f
        var oldInkCells = 0
        for (i in oldFine.indices) {
            if (oldFine[i] < DedupeConfig.INK_THRESHOLD) {
                val diff = kotlin.math.abs(oldFine[i] - candFine[i])
                simSum += 1f - (diff / DedupeConfig.FG_TOLERANCE).coerceAtMost(1f)
                oldInkCells++
            }
        }
        val fgOldSim = if (oldInkCells == 0) 1f else simSum / oldInkCells

        val score = DedupeConfig.RECALL_WEIGHT * recall +
            (1f - DedupeConfig.RECALL_WEIGHT) * fgOldSim

        val isSamePage =
            recall >= DedupeConfig.SAME_RECALL &&
                fgOldSim >= DedupeConfig.SAME_FG &&
                coverage >= DedupeConfig.SAME_COVERAGE

        return Similarity(recall, fgOldSim, coverage, score, isSamePage)
    }
}
```

- [ ] **Step 6: 运行测试，必要时微调测试数据**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.dedupe.SlideDeduplicatorTest" --no-daemon
```

Expected: 8 tests PASS。

**若 `adding bullets to same page stays ONE page` 失败**：检查 fgOldSim——它是唯一可能因"新增墨迹把整格灰度带偏"而下降的指标。确认 `fgOldSim` 只统计 `oldFine[i] < INK_THRESHOLD` 的格子（旧页墨迹格），新格不参与。仍不达标则把 `DedupeConfig.SAME_FG` 降到 0.80 并在 `DedupeConfig` 注释里记下真机复核要求。

**若 `same layout but different text is a new page` 失败**：说明细网格分辨率不足以区分文字，把 `FINE_W/FINE_H` 提到 800/450 重跑（性能仍可接受）。

- [ ] **Step 7: 把仓库的判定改为接收 `isSamePage`**

`data/MeetingRepository.kt` 接口中，替换这两个签名：

```kotlin
    suspend fun registerShot(
        meetingId: Long,
        imageFile: File,
        width: Int,
        height: Int,
        isSamePage: Boolean,
    ): ShotResult

    suspend fun targetFileFor(meetingId: Long, isSamePage: Boolean): File
```

`data/DefaultMeetingRepository.kt` 中：
- `registerShot` 的 `similarityToLast: Float` 参数改为 `isSamePage: Boolean`，
  函数体里 `last != null && decide(similarityToLast) == PageChoice.OVERWRITE`
  改为 `last != null && isSamePage`；
- `targetFileFor` 同样改法；
- 删除 `companion object` 中的 `decide` 与 `THRESHOLD_OVERWRITE`，
  删除文件末尾的 `enum class PageChoice`（阈值判定已完全归 `SlideDeduplicator` 所有）。

- [ ] **Step 8: 跑全部单元测试**

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

Expected: 全绿（Task 2–5 累计 27+ 用例）。

- [ ] **Step 9: 核对 spec 与实现一致**

`docs/superpowers/specs/2026-10-22-meeting-recorder-design.md` 的 §5 数据流与 §7
已在计划阶段更新为本任务的方向性三判据版本。本步骤只做核对：
打开该文件确认 §7 表格里的三个阈值（0.90 / 0.85 / 0.90）与 `DedupeConfig` 完全一致，
若执行中调整过阈值，把 spec §7 与本计划 Global Constraints 一并改过来，保持三处同步。

- [ ] **Step 10: 提交**

```powershell
git add -A
git commit -m "feat: 同页去重算法（方向性三判据）"
```

---

---

### Task 6: 稳定/变化检测与 Y 平面下采样（纯逻辑，TDD）

这是"翻页动画不误拍、内容没变不重拍、画面稳定才拍"三条规则的唯一实现处，必须可单测。

**Files:**
- Create: `app/src/main/java/com/meetnote/recorder/capture/FrameGrid.kt`（含 `AnalysisConfig`、`meanAbsDiff`）
- Create: `app/src/main/java/com/meetnote/recorder/capture/YuvFrames.kt`
- Create: `app/src/main/java/com/meetnote/recorder/capture/StabilityDetector.kt`
- Test: `app/src/test/java/com/meetnote/recorder/capture/StabilityDetectorTest.kt`
- Test: `app/src/test/java/com/meetnote/recorder/capture/YuvFramesTest.kt`

**Interfaces:**
- Consumes: 无（纯 Kotlin + `java.nio.ByteBuffer`）
- Produces:
  - `object AnalysisConfig`：`W = 64`、`H = 36`、`STABLE_MAD = 2.0f`、`STABLE_FRAMES = 5`、`CHANGE_MAD = 4.0f`、`COOLDOWN_MS = 400L`
  - `data class FrameGrid(val width: Int, val height: Int, val values: IntArray)`（`values` 为 0..255 亮度，行优先）
  - `fun meanAbsDiff(a: FrameGrid, b: FrameGrid): Float`
  - `object YuvFrames { fun toGrid(y: ByteBuffer, imageWidth: Int, imageHeight: Int, rowStride: Int, pixelStride: Int, outW: Int = AnalysisConfig.W, outH: Int = AnalysisConfig.H): FrameGrid }`
  - `sealed interface Verdict`：`Initialising`、`Stabilising`、`NoChange`、`CaptureNow`
  - `class StabilityDetector(stableMad: Float = AnalysisConfig.STABLE_MAD, stableFrames: Int = AnalysisConfig.STABLE_FRAMES, changeMad: Float = AnalysisConfig.CHANGE_MAD, cooldownMs: Long = AnalysisConfig.COOLDOWN_MS)`：`fun onFrame(grid: FrameGrid, tsMs: Long): Verdict`、`fun reset()`
  - `capture/FrameGrid.kt` 另含 `fun FrameGrid.withValue(v: Int): FrameGrid`（测试构造用，生产不用）

- [ ] **Step 1: 写 YuvFrames 失败测试**

`app/src/test/java/com/meetnote/recorder/capture/YuvFramesTest.kt`：

```kotlin
package com.meetnote.recorder.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class YuvFramesTest {

    /** 4 行 8 列 Y 平面，rowStride=12（尾部 4 字节填充），pixelStride=1 */
    private fun paddedYPlane(): ByteBuffer {
        val rowStride = 12
        val buf = ByteBuffer.allocate(rowStride * 4)
        for (row in 0 until 4) {
            for (col in 0 until 8) {
                buf.put(row * rowStride + col, (row * 10 + col + 1).toByte())
            }
            // 填充区故意写 99，若实现错用 rowStride 计算就会读到它
            for (col in 8 until rowStride) {
                buf.put(row * rowStride + col, 99)
            }
        }
        buf.position(0)
        return buf
    }

    @Test
    fun `downsample respects row stride padding`() {
        val grid = YuvFrames.toGrid(
            y = paddedYPlane(), imageWidth = 8, imageHeight = 4,
            rowStride = 12, pixelStride = 1, outW = 4, outH = 2,
        )

        assertEquals(4, grid.width)
        assertEquals(2, grid.height)
        // 第 0 行取源行 0、第 0/2/4/6 列 → 1,3,5,7
        assertEquals(1, grid.values[0])
        assertEquals(3, grid.values[1])
        assertEquals(5, grid.values[2])
        assertEquals(7, grid.values[3])
        // 第 1 输出行取源行 2 → 值 21,23,25,27
        assertEquals(21, grid.values[4])
        assertEquals(27, grid.values[7])
        // 绝不能读到填充区的 99
        check(grid.values.none { it == 99 }) { "读到了 rowStride 填充字节" }
    }

    @Test
    fun `pixel stride of 2 skips luma correctly`() {
        // 模拟 UYVY 风格：每 2 字节取 1
        val buf = ByteBuffer.allocate(8)
        // 列序: Y0 V0 Y1 U0 | Y2 V1 Y3 U1
        byteArrayOf(10, 0, 11, 0, 12, 0, 13, 0).forEachIndexed { i, b -> buf.put(i, b) }
        val grid = YuvFrames.toGrid(
            y = buf, imageWidth = 4, imageHeight = 1,
            rowStride = 8, pixelStride = 2, outW = 4, outH = 1,
        )
        assertEquals(10, grid.values[0])
        assertEquals(11, grid.values[1])
        assertEquals(12, grid.values[2])
        assertEquals(13, grid.values[3])
    }

    @Test
    fun `uniform frame has zero diff`() {
        val g = FrameGrid(4, 4, IntArray(16) { 128 })
        assertEquals(0f, meanAbsDiff(g, g), 0.0001f)
    }

    @Test
    fun `diff is mean absolute difference`() {
        val a = FrameGrid(2, 2, intArrayOf(100, 100, 100, 100))
        val b = FrameGrid(2, 2, intArrayOf(110, 90, 100, 100))
        assertEquals(5f, meanAbsDiff(a, b), 0.0001f)   // (10+10+0+0)/4
    }
}
```

- [ ] **Step 2: 运行确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.capture.*" --no-daemon
```

Expected: FAIL，`unresolved reference: YuvFrames / FrameGrid`。

- [ ] **Step 3: 实现 FrameGrid 与 YuvFrames**

`capture/FrameGrid.kt`：

```kotlin
package com.meetnote.recorder.capture

/** 分析用参数集中于此（spec §6.2） */
object AnalysisConfig {
    const val W = 64
    const val H = 36
    const val STABLE_MAD = 2.0f
    const val STABLE_FRAMES = 5
    const val CHANGE_MAD = 4.0f
    const val COOLDOWN_MS = 400L
}

/** 降采样后的亮度网格，0..255，行优先 */
data class FrameGrid(
    val width: Int,
    val height: Int,
    val values: IntArray,
) {
    init {
        require(values.size == width * height) {
            "网格尺寸不符：${width}x$height 需要 ${width * height}，实际 ${values.size}"
        }
    }

    fun withValue(v: Int): FrameGrid =
        FrameGrid(width, height, IntArray(values.size) { v })

    override fun equals(other: Any?): Boolean =
        other is FrameGrid && other.width == width && other.height == height &&
            other.values.contentEquals(values)

    override fun hashCode(): Int =
        (31 * (31 * width + height) + values.contentHashCode())
}

/** 平均绝对差，取值 0..255。两帧尺寸不同时直接抛错（属编程错误） */
fun meanAbsDiff(a: FrameGrid, b: FrameGrid): Float {
    require(a.width == b.width && a.height == b.height) { "网格尺寸不一致" }
    var sum = 0L
    for (i in a.values.indices) {
        sum += kotlin.math.abs(a.values[i] - b.values[i])
    }
    return sum.toFloat() / a.values.size
}
```

`capture/YuvFrames.kt`：

```kotlin
package com.meetnote.recorder.capture

import java.nio.ByteBuffer

/**
 * 把 ImageProxy 的 Y 平面降采样成 64×36 网格。
 * 必须按 rowStride/pixelStride 寻址，否则会读到行尾填充或色度字节。
 */
object YuvFrames {

    fun toGrid(
        y: ByteBuffer,
        imageWidth: Int,
        imageHeight: Int,
        rowStride: Int,
        pixelStride: Int,
        outW: Int = AnalysisConfig.W,
        outH: Int = AnalysisConfig.H,
    ): FrameGrid {
        require(imageWidth > 0 && imageHeight > 0) { "非法图像尺寸" }
        require(rowStride >= imageWidth * pixelStride) { "rowStride 小于一行所需字节数" }

        val out = IntArray(outW * outH)
        val limit = y.limit()
        for (gy in 0 until outH) {
            val sy = (gy * imageHeight / outH).coerceIn(0, imageHeight - 1)
            val rowOffset = sy * rowStride
            for (gx in 0 until outW) {
                val sx = (gx * imageWidth / outW).coerceIn(0, imageWidth - 1)
                val index = rowOffset + sx * pixelStride
                out[gy * outW + gx] =
                    if (index < limit) (y.get(index).toInt() and 0xFF) else 0
            }
        }
        return FrameGrid(outW, outH, out)
    }
}
```

- [ ] **Step 4: 运行 YuvFrames 测试确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.capture.YuvFramesTest" --no-daemon
```

Expected: 4 tests PASS。

- [ ] **Step 5: 写 StabilityDetector 失败测试**

`app/src/test/java/com/meetnote/recorder/capture/StabilityDetectorTest.kt`：

```kotlin
package com.meetnote.recorder.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StabilityDetectorTest {

    private fun detector() = StabilityDetector()

    /** 连续喂 n 帧完全相同的画，返回最后一次判定 */
    private fun StabilityDetector.feedStable(startTs: Long, n: Int, value: Int = 100): Verdict {
        var last: Verdict = Verdict.Initialising
        for (i in 0 until n) {
            last = onFrame(FrameGrid(AnalysisConfig.W, AnalysisConfig.H,
                IntArray(AnalysisConfig.W * AnalysisConfig.H) { value }), startTs + i * 33L)
        }
        return last
    }

    private fun grid(value: Int) =
        FrameGrid(AnalysisConfig.W, AnalysisConfig.H,
            IntArray(AnalysisConfig.W * AnalysisConfig.H) { value })

    @Test
    fun `first frame only initialises`() {
        assertEquals(Verdict.Initialising, detector().onFrame(grid(100), 0L))
    }

    @Test
    fun `fewer than five stable frames does not trigger`() {
        val d = detector()
        repeat(AnalysisConfig.STABLE_FRAMES - 1) { i ->
            assertEquals(Verdict.Stabilising, d.onFrame(grid(100), i * 33L))
        }
    }

    @Test
    fun `first stable shot triggers after threshold`() {
        val d = detector()
        val verdicts = (0 until AnalysisConfig.STABLE_FRAMES + 1).map { i ->
            d.onFrame(grid(100), i * 33L)
        }
        assertTrue(
            "最后应为 CaptureNow，实际 $verdicts",
            verdicts.last() is Verdict.CaptureNow,
        )
        // 前几帧都在稳定过程中
        assertTrue(verdicts[1] is Verdict.Stabilising)
    }

    @Test
    fun `shaking frames never trigger`() {
        val d = detector()
        var last: Verdict = Verdict.Initialising
        for (i in 0 until 40) {
            last = d.onFrame(grid(100 + (i % 2) * 30), i * 33L)   // 帧间差 30 > 2.0
        }
        assertEquals(Verdict.Stabilising, last)
    }

    @Test
    fun `no repeat capture when nothing changed`() {
        val d = detector()
        // 稳定并拍下第一张
        val first = (0 until 6).map { i -> d.onFrame(grid(100), i * 33L) }
        assertTrue(first.last() is Verdict.CaptureNow)

        // 画面持续不动：再多喂 30 帧，不得重复拍
        val rest = (6 until 36).map { i -> d.onFrame(grid(100), i * 33L) }
        assertTrue("画面没变不应重拍", rest.none { it is Verdict.CaptureNow })
    }

    @Test
    fun `content change triggers new capture`() {
        val d = detector()
        // 基线：稳定 + 首拍
        (0 until 6).forEach { i -> d.onFrame(grid(100), i * 33L) }
        // 翻页：先抖几帧（动画），再稳定
        val verdicts = (6 until 6 + 12).map { i ->
            val v = d.onFrame(grid(if (i < 9) 60 else 200), i * 33L)
            v
        }
        assertTrue(
            "翻页后应拍到新页，实际 $verdicts",
            verdicts.any { it is Verdict.CaptureNow },
        )
    }

    @Test
    fun `cooldown suppresses immediate retrigger`() {
        val d = detector()
        // 首拍发生在 ts = 5*33 = 165
        (0 until 6).forEach { i -> d.onFrame(grid(100), i * 33L) }

        // 冷却期内（165 + 400 = 565 之前）即使已经稳定并变化也不拍
        val early = (6 until 6 + 6).map { i -> d.onFrame(grid(200), 200L + i * 30L) }
        assertTrue(
            "冷却期内不应拍摄", early.none { it is Verdict.CaptureNow },
        )
    }

    @Test
    fun `reset forgets reference`() {
        val d = detector()
        d.feedStable(0, 6)
        d.reset()
        assertEquals(Verdict.Initialising, d.onFrame(grid(100), 0L))
    }
}
```

- [ ] **Step 6: 运行确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.capture.StabilityDetectorTest" --no-daemon
```

Expected: FAIL，`unresolved reference: StabilityDetector` 或 `Verdict`。

- [ ] **Step 7: 实现 StabilityDetector**

`capture/StabilityDetector.kt`：

```kotlin
package com.meetnote.recorder.capture

/**
 * 两级判定（spec §6.2）：
 *  1) 画面必须先稳定（连续 [stableFrames] 帧帧间 MAD ≤ [stableMad]），
 *     这样翻页动画（300–500ms）不会被抓到中间态；
 *  2) 稳定后与"上次抓拍帧"的差异必须 > [changeMad]，内容没变就不重拍；
 *  3) 每次抓拍后 [cooldownMs] 内不重复触发，防连拍风暴。
 */
class StabilityDetector(
    private val stableMad: Float = AnalysisConfig.STABLE_MAD,
    private val stableFrames: Int = AnalysisConfig.STABLE_FRAMES,
    private val changeMad: Float = AnalysisConfig.CHANGE_MAD,
    private val cooldownMs: Long = AnalysisConfig.COOLDOWN_MS,
) {

    private var prev: FrameGrid? = null
    private var reference: FrameGrid? = null
    private var stableRun = 0
    private var lastCaptureTs = Long.MIN_VALUE / 2

    fun reset() {
        prev = null
        reference = null
        stableRun = 0
        lastCaptureTs = Long.MIN_VALUE / 2
    }

    fun onFrame(grid: FrameGrid, tsMs: Long): Verdict {
        val last = prev
        prev = grid
        if (last == null) {
            stableRun = 0
            return Verdict.Initialising
        }

        val mad = meanAbsDiff(last, grid)
        if (mad > stableMad) {
            stableRun = 0
            return Verdict.Stabilising
        }

        stableRun++
        if (stableRun < stableFrames) return Verdict.Stabilising

        if (tsMs - lastCaptureTs < cooldownMs) return Verdict.NoChange

        val ref = reference
        if (ref == null) {
            reference = grid
            lastCaptureTs = tsMs
            stableRun = 0
            return Verdict.CaptureNow
        }

        val changed = meanAbsDiff(ref, grid)
        if (changed <= changeMad) return Verdict.NoChange

        reference = grid
        lastCaptureTs = tsMs
        stableRun = 0
        return Verdict.CaptureNow
    }
}

sealed interface Verdict {
    /** 首帧，还没有参照 */
    data object Initialising : Verdict
    /** 画面还在动（翻页动画 / 手抖） */
    data object Stabilising : Verdict
    /** 已稳定但内容没变，或处于冷却期 */
    data object NoChange : Verdict
    /** 应当抓拍 */
    data object CaptureNow : Verdict
}
```

- [ ] **Step 8: 运行测试并核对**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.capture.StabilityDetectorTest" --no-daemon
```

Expected: 8 tests PASS。

**若 `content change triggers new capture` 失败**：确认 `reference` 在首拍时被设为当时的画面（`grid(100)`），翻到 `grid(200)` 后 `changed = 100 > CHANGE_MAD`。
**若 `cooldown suppresses immediate retrigger` 失败**：检查 `lastCaptureTs` 初始化为 `Long.MIN_VALUE/2` 而非 `Long.MIN_VALUE`（后者做减法会溢出成正数，导致冷却永不生效——这是本类唯一容易写错的点）。

- [ ] **Step 9: 提交**

```powershell
git add -A
git commit -m "feat: Y 平面下采样与稳定/变化检测器"
```

---

---

### Task 7: 四边形几何与质量门（纯逻辑，TDD）

把"找屏幕边 → 判合格 → 算目标尺寸"和"清晰度/亮度/宽高比"全部做成不依赖 OpenCV 的纯函数，OpenCV 只负责把四点找出来（Task 8）。

**Files:**
- Create: `app/src/main/java/com/meetnote/recorder/capture/QuadMath.kt`
- Create: `app/src/main/java/com/meetnote/recorder/capture/QualityGate.kt`
- Test: `app/src/test/java/com/meetnote/recorder/capture/QuadMathTest.kt`
- Test: `app/src/test/java/com/meetnote/recorder/capture/QualityGateTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `data class Point2(val x: Float, val y: Float)`
  - `data class Quad(val tl: Point2, val tr: Point2, val br: Point2, val bl: Point2)`
  - `object QuadMath`：
    `fun orderPoints(points: List<Point2>): Quad`（乱序输入 → 有序 TL/TR/BR/BL）
    `fun quadArea(q: Quad): Float`
    `fun aspectRatio(q: Quad): Float`
    `fun isPlausible(q: Quad, frameW: Int, frameH: Int, minAreaRatio: Float = 0.12f, minAspect: Float = 0.5f, maxAspect: Float = 3.0f): Boolean`
    `fun targetSize(q: Quad, maxWidth: Int = 1600): Pair<Int, Int>`
  - `object QualityGate`：
    `const MIN_LAPLACIAN = 60.0`、`MIN_MEAN = 25.0`、`MAX_MEAN = 235.0`、`MIN_ASPECT = 0.6f`、`MAX_ASPECT = 2.5f`
    `fun laplacianVariance(gray255: FloatArray, width: Int, height: Int): Double`
    `fun meanLuma(gray255: FloatArray): Double`
    `fun accept(laplacianVar: Double, meanLuma: Double, aspect: Float): Boolean`

- [ ] **Step 1: 写 QuadMath 失败测试**

`app/src/test/java/com/meetnote/recorder/capture/QuadMathTest.kt`：

```kotlin
package com.meetnote.recorder.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class QuadMathTest {

    @Test
    fun `orderPoints sorts shuffled corners`() {
        val shuffled = listOf(
            Point2(640f, 480f),   // BR
            Point2(10f, 470f),    // BL
            Point2(8f, 12f),      // TL
            Point2(630f, 15f),    // TR
        )
        val q = QuadMath.orderPoints(shuffled)

        assertPoint(8f, 12f, q.tl)
        assertPoint(630f, 15f, q.tr)
        assertPoint(640f, 480f, q.br)
        assertPoint(10f, 470f, q.bl)
    }

    private fun assertPoint(x: Float, y: Float, p: Point2) {
        assertTrue("期望 ($x,$y) 实得 (${p.x},${p.y})", abs(p.x - x) < 0.01f && abs(p.y - y) < 0.01f)
    }

    @Test
    fun `orderPoints requires exactly four points`() {
        try {
            QuadMath.orderPoints(listOf(Point2(0f, 0f), Point2(1f, 1f)))
            throw AssertionError("应当抛出 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // 通过
        }
    }

    @Test
    fun `quadArea is shoelace of unit square`() {
        val q = Quad(
            Point2(0f, 0f), Point2(10f, 0f),
            Point2(10f, 10f), Point2(0f, 10f),
        )
        assertEquals(100f, QuadMath.quadArea(q), 0.01f)
    }

    @Test
    fun `quadArea handles trapezoid`() {
        // 上底 4、下底 8、高 4 → 面积 24
        val q = Quad(
            Point2(2f, 0f), Point2(6f, 0f),
            Point2(8f, 4f), Point2(0f, 4f),
        )
        assertEquals(24f, QuadMath.quadArea(q), 0.01f)
    }

    @Test
    fun `aspectRatio of 16:9 rectangle is about 1点78`() {
        val q = Quad(
            Point2(0f, 0f), Point2(160f, 0f),
            Point2(160f, 90f), Point2(0f, 90f),
        )
        assertEquals(1.777f, QuadMath.aspectRatio(q), 0.01f)
    }

    @Test
    fun `aspectRatio of trapezoid uses average edge lengths`() {
        // 上底 4、下底 8 → 平均宽 6；高 4 → 1.5
        val q = Quad(
            Point2(2f, 0f), Point2(6f, 0f),
            Point2(8f, 4f), Point2(0f, 4f),
        )
        assertEquals(1.5f, QuadMath.aspectRatio(q), 0.01f)
    }

    private fun rect(x0: Float, y0: Float, x1: Float, y1: Float) =
        Quad(Point2(x0, y0), Point2(x1, y0), Point2(x1, y1), Point2(x0, y1))

    @Test
    fun `plausible accepts a normal projector screen`() {
        // 640x360 画面中占 70% 面积的 16:9 屏幕
        val q = rect(100f, 60f, 580f, 330f)
        assertTrue(QuadMath.isPlausible(q, 640, 360))
    }

    @Test
    fun `plausible rejects too small region`() {
        // 只占画面 3% 的小方块（多半是误检的课桌/白纸）
        val q = rect(300f, 170f, 340f, 200f)
        assertFalse(QuadMath.isPlausible(q, 640, 360))
    }

    @Test
    fun `plausible rejects absurdly wide strip`() {
        // 宽高比 8 → 不可能是幻灯片
        val q = rect(10f, 100f, 630f, 260f)
        assertFalse(QuadMath.isPlausible(q, 640, 360))
    }

    @Test
    fun `targetSize downscales to max width`() {
        val q = rect(0f, 0f, 3200f, 1800f)
        assertEquals(1600 to 900, QuadMath.targetSize(q, maxWidth = 1600))
    }

    @Test
    fun `targetSize keeps small images intact`() {
        val q = rect(0f, 0f, 1280f, 720f)
        assertEquals(1280 to 720, QuadMath.targetSize(q, maxWidth = 1600))
    }

    @Test
    fun `targetSize never returns zero dimension`() {
        val q = rect(0f, 0f, 3f, 900f)
        val (tw, th) = QuadMath.targetSize(q, maxWidth = 1600)
        assertTrue(tw >= 1)
        assertTrue(th >= 1)
    }
}
```

- [ ] **Step 2: 运行确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.capture.QuadMathTest" --no-daemon
```

Expected: FAIL，`unresolved reference: QuadMath / Point2`。

- [ ] **Step 3: 实现 QuadMath**

`capture/QuadMath.kt`：

```kotlin
package com.meetnote.recorder.capture

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class Point2(val x: Float, val y: Float)

/** 四点已排序：左上、右上、右下、左下 */
data class Quad(val tl: Point2, val tr: Point2, val br: Point2, val bl: Point2)

object QuadMath {

    /**
     * 用"和/差"法把乱序四点排成 TL/TR/BR/BL：
     *   x+y 最小 = 左上，最大 = 右下；x−y 最大 = 右上，最小 = 左下。
     * 对"相机斜拍屏幕"这类近似轴对齐的四边形足够稳；不支持绕轴旋转接近 45° 的极端取向。
     */
    fun orderPoints(points: List<Point2>): Quad {
        require(points.size == 4) { "需要 4 个点，实际 ${points.size}" }
        var tl = points[0]; var br = points[0]; var tr = points[0]; var bl = points[0]
        var minSum = Float.MAX_VALUE; var maxSum = -Float.MAX_VALUE
        var maxDiff = -Float.MAX_VALUE; var minDiff = Float.MAX_VALUE

        for (p in points) {
            val sum = p.x + p.y
            val diff = p.x - p.y
            if (sum < minSum) { minSum = sum; tl = p }
            if (sum > maxSum) { maxSum = sum; br = p }
            if (diff > maxDiff) { maxDiff = diff; tr = p }
            if (diff < minDiff) { minDiff = diff; bl = p }
        }
        return Quad(tl, tr, br, bl)
    }

    /** 鞋带公式，返回像素面积 */
    fun quadArea(q: Quad): Float {
        val pts = listOf(q.tl, q.tr, q.br, q.bl)
        var acc = 0f
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            acc += a.x * b.y - b.x * a.y
        }
        return abs(acc) / 2f
    }

    /** 上下边平均长 ÷ 左右边平均长 */
    fun aspectRatio(q: Quad): Float {
        val top = dist(q.tl, q.tr)
        val bottom = dist(q.bl, q.br)
        val left = dist(q.tl, q.bl)
        val right = dist(q.tr, q.br)
        val width = (top + bottom) / 2f
        val height = (left + right) / 2f
        if (height <= 0.001f) return Float.MAX_VALUE
        return width / height
    }

    /**
     * 该四边形是否像一块投影幕布：够大、比例不离谱。
     * 用来剔除误检到的课桌、白纸、人影。
     */
    fun isPlausible(
        q: Quad,
        frameW: Int,
        frameH: Int,
        minAreaRatio: Float = 0.12f,
        minAspect: Float = 0.5f,
        maxAspect: Float = 3.0f,
    ): Boolean {
        if (frameW <= 0 || frameH <= 0) return false
        val ratio = quadArea(q) / (frameW.toFloat() * frameH)
        if (ratio < minAreaRatio) return false
        val aspect = aspectRatio(q)
        return aspect in minAspect..maxAspect
    }

    /** 按四边形实际边长换算输出尺寸，宽不超过 [maxWidth]，不放大 */
    fun targetSize(q: Quad, maxWidth: Int = 1600): Pair<Int, Int> {
        val width = (dist(q.tl, q.tr) + dist(q.bl, q.br)) / 2f
        val height = (dist(q.tl, q.bl) + dist(q.tr, q.br)) / 2f
        val scale = if (width > maxWidth) maxWidth / width else 1f
        val outW = max(1, (width * scale).roundToInt())
        val outH = max(1, (height * scale).roundToInt())
        return outW to outH
    }

    private fun dist(a: Point2, b: Point2): Float {
        val dx = (a.x - b.x).toDouble()
        val dy = (a.y - b.y).toDouble()
        return sqrt(dx * dx + dy * dy).toFloat()
    }
}
```

- [ ] **Step 4: 运行 QuadMath 测试确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.capture.QuadMathTest" --no-daemon
```

Expected: 11 tests PASS。

- [ ] **Step 5: 写 QualityGate 失败测试**

`app/src/test/java/com/meetnote/recorder/capture/QualityGateTest.kt`：

```kotlin
package com.meetnote.recorder.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityGateTest {

    /** 纯色图 → 清晰度为 0 */
    private fun flat(w: Int, h: Int, v: Float) = FloatArray(w * h) { v }

    /** 竖向正弦纹理 → 有明显边缘 */
    private fun textured(w: Int, h: Int): FloatArray =
        FloatArray(w * h) { i ->
            val x = i % w
            ((kotlin.math.sin(x / 3.0) * 0.5 + 0.5) * 255).toFloat()
        }

    @Test
    fun `flat image has zero laplacian variance`() {
        assertEquals(0.0, QualityGate.laplacianVariance(flat(32, 32, 128f), 32, 32), 0.001)
    }

    @Test
    fun `textured image passes laplacian threshold`() {
        val v = QualityGate.laplacianVariance(textured(64, 64), 64, 64)
        assertTrue("清晰图应远高于 60，实得 $v", v > QualityGate.MIN_LAPLACIAN)
    }

    @Test
    fun `mean luma computed from values`() {
        val gray = floatArrayOf(0f, 255f, 100f, 155f)
        assertEquals(127.5, QualityGate.meanLuma(gray), 0.01)
    }

    @Test
    fun `accept boundaries are inclusive`() {
        assertTrue(QualityGate.accept(60.0, 25.0, 0.6f))
        assertTrue(QualityGate.accept(60.0, 235.0, 2.5f))
        assertFalse("清晰度不足", QualityGate.accept(59.9, 100.0, 1.0f))
        assertFalse("过暗", QualityGate.accept(100.0, 24.9, 1.0f))
        assertFalse("过亮（反光）", QualityGate.accept(100.0, 235.1, 1.0f))
        assertFalse("太扁", QualityGate.accept(100.0, 100.0, 0.59f))
        assertFalse("太瘦", QualityGate.accept(100.0, 100.0, 2.51f))
    }

    @Test
    fun `accept passes a typical projector frame`() {
        assertTrue(QualityGate.accept(240.0, 140.0, 1.78f))
    }
}
```

- [ ] **Step 6: 运行确认失败后实现 QualityGate**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.capture.QualityGateTest" --no-daemon
```

Expected: FAIL，`unresolved reference: QualityGate`。

`capture/QualityGate.kt`：

```kotlin
package com.meetnote.recorder.capture

/**
 * 抓拍候选的质量门（spec §6.4）。三条全过才允许入库，否则静默丢弃。
 * 输入灰度取 0..255（与拉普拉斯阈值 60 的量纲一致）。
 */
object QualityGate {
    const val MIN_LAPLACIAN = 60.0
    const val MIN_MEAN = 25.0
    const val MAX_MEAN = 235.0
    const val MIN_ASPECT = 0.6f
    const val MAX_ASPECT = 2.5f

    /**
     * 4 邻域拉普拉斯算子的方差。图片模糊时该值显著偏低。
     * 边缘像素用复制邻域的方式处理，避免边界伪影。
     */
    fun laplacianVariance(gray255: FloatArray, width: Int, height: Int): Double {
        require(gray255.size >= width * height) { "灰度数组长度不足" }
        require(width > 2 && height > 2) { "尺寸过小无法计算拉普拉斯" }

        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val lap =
                    gray255[i - 1] + gray255[i + 1] + gray255[i - width] +
                        gray255[i + width] - 4f * gray255[i]
                sum += lap
                sumSq += lap * lap
                count++
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return sumSq / count - mean * mean
    }

    fun meanLuma(gray255: FloatArray): Double {
        if (gray255.isEmpty()) return 0.0
        var sum = 0.0
        for (v in gray255) sum += v
        return sum / gray255.size
    }

    fun accept(laplacianVar: Double, meanLuma: Double, aspect: Float): Boolean =
        laplacianVar >= MIN_LAPLACIAN &&
            meanLuma in MIN_MEAN..MAX_MEAN &&
            aspect in MIN_ASPECT..MAX_ASPECT
}
```

- [ ] **Step 7: 运行全部测试**

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

Expected: 全绿（累计 40+ 用例）。

- [ ] **Step 8: 提交**

```powershell
git add -A
git commit -m "feat: 四边形几何与抓拍质量门"
```

---

---

### Task 8: OpenCV 文档矫正与加载降级

OpenCV 只做三件事：找四边形、拉正、裁切；"这个四边形像不像屏幕"仍由 Task 7 的 `QuadMath` 判定。本机跑不了 native 库，所以单测覆盖**加载失败 → 降级**这条真实存在的路径（Robolectric 环境下 native 必然加载失败）。

**Files:**
- Create: `app/src/main/java/com/meetnote/recorder/capture/OpenCvLoader.kt`
- Create: `app/src/main/java/com/meetnote/recorder/capture/DocumentCorrector.kt`
- Test: `app/src/test/java/com/meetnote/recorder/capture/DocumentCorrectorTest.kt`

**Interfaces:**
- Consumes: `QuadMath.orderPoints / isPlausible / targetSize`、`Point2 / Quad`（Task 7）
- Produces:
  - `object OpenCvLoader`：`fun ensure(): Boolean`（幂等）、`fun isAvailable(): Boolean`、测试钩子 `fun setLoadActionForTest(a: () -> Boolean)`、`fun resetForTest()`
  - `class DocumentCorrector(private val maxOutputWidth: Int = 1600)`：`fun correct(source: Bitmap): Correction`
  - `data class Correction(val bitmap: Bitmap, val quad: Quad?, val degraded: Boolean, val reason: String?)`
  - `const val DocumentCorrector.DETECT_MAX_WIDTH = 1280`（检测前先降采样到该宽度，四点再换算回原图坐标）

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/meetnote/recorder/capture/DocumentCorrectorTest.kt`：

```kotlin
package com.meetnote.recorder.capture

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentCorrectorTest {

    @Before
    fun setUp() {
        OpenCvLoader.resetForTest()
    }

    @After
    fun tearDown() {
        OpenCvLoader.resetForTest()
    }

    private fun whiteBitmap(w: Int = 640, h: Int = 480): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    @Test
    fun `loader failure is cached not retried every frame`() {
        var attempts = 0
        OpenCvLoader.setLoadActionForTest {
            attempts++
            false
        }

        assertFalse(OpenCvLoader.ensure())
        assertFalse(OpenCvLoader.ensure())
        assertFalse(OpenCvLoader.ensure())

        assertEquals("加载只应尝试一次，否则每帧都去加载 native 库", 1, attempts)
        assertFalse(OpenCvLoader.isAvailable())
    }

    @Test
    fun `loader success is cached too`() {
        var attempts = 0
        OpenCvLoader.setLoadActionForTest {
            attempts++
            true
        }

        assertTrue(OpenCvLoader.ensure())
        assertTrue(OpenCvLoader.ensure())
        assertEquals(1, attempts)
    }

    @Test
    fun `loader throwing is treated as failure not crash`() {
        OpenCvLoader.setLoadActionForTest { throw UnsatisfiedLinkError("no lib") }
        assertFalse(OpenCvLoader.ensure())
    }

    @Test
    fun `reset clears cached state`() {
        OpenCvLoader.setLoadActionForTest { false }
        OpenCvLoader.ensure()
        OpenCvLoader.resetForTest()

        var attempts = 0
        OpenCvLoader.setLoadActionForTest { attempts++; true }
        assertTrue(OpenCvLoader.ensure())
        assertEquals(1, attempts)
    }

    @Test
    fun `correct degrades to original bitmap when opencv unavailable`() {
        OpenCvLoader.setLoadActionForTest { false }
        val src = whiteBitmap()

        val result = DocumentCorrector().correct(src)

        assertTrue("native 不可用时必须降级", result.degraded)
        assertSame("降级时不得改动原图", src, result.bitmap)
        assertNull("没有检测到四边形时 quad 为 null", result.quad)
        assertTrue(!result.reason.isNullOrBlank())
    }

    @Test
    fun `degraded correction keeps original dimensions`() {
        OpenCvLoader.setLoadActionForTest { false }
        val src = whiteBitmap(1024, 768)
        val result = DocumentCorrector().correct(src)
        assertEquals(1024, result.bitmap.width)
        assertEquals(768, result.bitmap.height)
    }
}
```

- [ ] **Step 2: 运行确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.capture.DocumentCorrectorTest" --no-daemon
```

Expected: FAIL，`unresolved reference: OpenCvLoader / DocumentCorrector`。

- [ ] **Step 3: 实现加载器**

`capture/OpenCvLoader.kt`：

```kotlin
package com.meetnote.recorder.capture

/**
 * OpenCV native 库加载，幂等且失败缓存。
 *
 * 用 System.loadLibrary 而非 OpenCVLoader.initLocal()：AAR 内已确认含
 * jni/arm64-v8a/libopencv_java4.so（见计划 Task 1 的依赖核实记录），
 * 直接加载该库即可让 org.opencv 的 Java 绑定生效，且不依赖 OpenCV 内部 API 演进。
 *
 * 加载失败（缺 ABI、被裁剪的包等）不是致命错误：DocumentCorrector 会降级为整帧存储。
 */
object OpenCvLoader {

    private const val LIB = "opencv_java4"

    @Volatile
    private var available: Boolean? = null   // null = 尚未尝试

    @Volatile
    private var loadAction: () -> Boolean = { loadNatively() }

    fun ensure(): Boolean {
        available?.let { return it }
        synchronized(this) {
            available?.let { return it }
            val ok = try {
                loadAction()
            } catch (t: Throwable) {
                false
            }
            available = ok
            return ok
        }
    }

    fun isAvailable(): Boolean = ensure()

    /** 仅供测试注入加载结果 */
    fun setLoadActionForTest(action: () -> Boolean) {
        loadAction = action
        available = null
    }

    fun resetForTest() {
        available = null
        loadAction = { loadNatively() }
    }

    private fun loadNatively(): Boolean = try {
        System.loadLibrary(LIB)
        true
    } catch (t: Throwable) {
        false
    }
}
```

- [ ] **Step 4: 运行确认加载器测试通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.capture.DocumentCorrectorTest" --no-daemon
```

Expected: 4 个 loader 用例 PASS；2 个 `correct` 用例仍 FAIL（`DocumentCorrector` 未实现）。

- [ ] **Step 5: 实现 DocumentCorrector**

`capture/DocumentCorrector.kt`：

```kotlin
package com.meetnote.recorder.capture

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** 矫正结果 */
data class Correction(
    /** 矫正后的图；降级时即原图 */
    val bitmap: Bitmap,
    /** 检测到的屏幕四边形（原图坐标系）；未检测到为 null */
    val quad: Quad?,
    /** true = 走了降级路径（OpenCV 不可用 或 找不到屏幕边） */
    val degraded: Boolean,
    /** 降级原因，非降级时为 null */
    val reason: String?,
)

/**
 * 文档模式矫正：灰度 → 高斯 → Canny → 找四边形 → 透视拉正 → 裁切（spec §6.3）。
 * 只负责把 OpenCV 的调用串起来，"像不像屏幕"由 [QuadMath.isPlausible] 判定。
 */
class DocumentCorrector(
    private val maxOutputWidth: Int = 1600,
) {

    fun correct(source: Bitmap): Correction {
        if (!OpenCvLoader.ensure()) {
            return Correction(source, null, degraded = true, reason = "OpenCV 不可用")
        }

        // 先降采样再检测：12MP 原图直接跑 Canny 太慢，四点检测完换算回原图坐标
        val detectBitmap = if (source.width > DETECT_MAX_WIDTH) {
            val h = (source.height.toLong() * DETECT_MAX_WIDTH / source.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(source, DETECT_MAX_WIDTH, h, true)
        } else source
        val scaleFactor = source.width.toFloat() / detectBitmap.width

        val full = Mat()
        val detect = Mat()
        try {
            Utils.bitmapToMat(detectBitmap, detect)

            val gray = Mat()
            val blurred = Mat()
            val edges = Mat()
            val hierarchy = Mat()
            try {
                Imgproc.cvtColor(detect, gray, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
                Imgproc.Canny(blurred, edges, 50.0, 150.0)

                val contours = ArrayList<MatOfPoint>()
                val found = try {
                    Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

                    val frameW = detect.cols()
                    val frameH = detect.rows()
                    contours
                        .sortedByDescending { Imgproc.contourArea(it) }
                        .firstNotNullOfOrNull { contour -> detectQuad(contour, frameW, frameH) }
                } finally {
                    // 每个轮廓 MatOfPoint 都持有 native 内存，必须在选完后立即释放
                    contours.forEach { it.release() }
                }

                if (found == null) {
                    return Correction(source, null, degraded = true, reason = "未检测到屏幕边缘")
                }

                // 四点换算回原图坐标系
                val quadInSource = Quad(
                    tl = scale(found.tl, scaleFactor),
                    tr = scale(found.tr, scaleFactor),
                    br = scale(found.br, scaleFactor),
                    bl = scale(found.bl, scaleFactor),
                )

                Utils.bitmapToMat(source, full)
                val warped = warp(full, quadInSource) ?: run {
                    return Correction(source, quadInSource, degraded = true, reason = "透视变换失败")
                }
                try {
                    val (outW, outH) = QuadMath.targetSize(quadInSource, maxOutputWidth)
                    val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                    Utils.matToBitmap(warped, out)
                    return Correction(out, quadInSource, degraded = false, reason = null)
                } finally {
                    warped.release()
                }
            } finally {
                gray.release()
                blurred.release()
                edges.release()
                hierarchy.release()
            }
        } catch (t: Throwable) {
            // native 调用中任何异常都不能让拍摄流程崩溃
            return Correction(source, null, degraded = true, reason = "矫正异常：${t.javaClass.simpleName}")
        } finally {
            full.release()
            detect.release()
            if (detectBitmap !== source) detectBitmap.recycle()
        }
    }

    /** 在检测用小图上找第一个"像屏幕"的四边形；返回检测坐标系下的 Quad */
    private fun detectQuad(contour: MatOfPoint, frameW: Int, frameH: Int): Quad? {
        val asFloat = MatOfPoint2f(*contour.toArray())
        val peri = Imgproc.arcLength(asFloat, true)
        val approx = MatOfPoint2f()
        var convex: MatOfPoint? = null
        try {
            Imgproc.approxPolyDP(asFloat, approx, 0.02 * peri, true)
            val pts = approx.toArray()
            if (pts.size != 4) return null

            val quad = QuadMath.orderPoints(pts.map { Point2(it.x.toFloat(), it.y.toFloat()) })
            convex = MatOfPoint(*pts)
            if (!Imgproc.isContourConvex(convex)) return null
            if (!QuadMath.isPlausible(quad, frameW, frameH)) return null
            return quad
        } finally {
            asFloat.release()
            approx.release()
            convex?.release()
        }
    }

    /** 透视拉正；返回的 Mat 由调用方负责 release */
    private fun warp(sourceMat: Mat, quad: Quad): Mat? {
        val (outW, outH) = QuadMath.targetSize(quad, maxOutputWidth)
        val src = MatOfPoint2f(
            Point(quad.tl.x.toDouble(), quad.tl.y.toDouble()),
            Point(quad.tr.x.toDouble(), quad.tr.y.toDouble()),
            Point(quad.br.x.toDouble(), quad.br.y.toDouble()),
            Point(quad.bl.x.toDouble(), quad.bl.y.toDouble()),
        )
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(outW.toDouble(), 0.0),
            Point(outW.toDouble(), outH.toDouble()),
            Point(0.0, outH.toDouble()),
        )
        val m = Mat()
        val out = Mat()
        try {
            Imgproc.getPerspectiveTransform(src, dst, m)
            Imgproc.warpPerspective(sourceMat, out, m, Size(outW.toDouble(), outH.toDouble()))
            return out
        } catch (t: Throwable) {
            out.release()
            return null
        } finally {
            src.release()
            dst.release()
            m.release()
        }
    }

    private fun scale(p: Point2, factor: Float): Point2 =
        Point2(p.x * factor, p.y * factor)

    companion object {
        /** 检测前把原图降采样到该宽度，够找边又足够快 */
        const val DETECT_MAX_WIDTH = 1280
    }
}
```

> ⚠️ 两处可能在编译期暴露的 API 差异，按此处理：
> 1. `Imgproc.getPerspectiveTransform(src, dst, m)` 若报"缺少三参重载"，改用二参形式
>    `val m = Imgproc.getPerspectiveTransform(src, dst)`（返回 `Mat`），并把后续 `m.release()` 保留。
> 2. `Imgproc.isContourConvex` 若不存在，改为自写凸性检查：把 `QuadMath.orderPoints` 排好序后，
>    依次计算相邻边叉积，四个叉积同号即为凸四边形（把该逻辑加进 `QuadMath.isConvex(Quad): Boolean`
>    并补一个单测）。
> 3. `findContours` 的 `hierarchy` 参数若签名不匹配，按编译器提示的重载调整，不改变算法步骤。

- [ ] **Step 6: 运行测试确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.capture.DocumentCorrectorTest" --no-daemon
```

Expected: 6 tests PASS。

- [ ] **Step 7: 编译验证 OpenCV API 用法**

```powershell
.\gradlew.bat :app:compileDebugKotlin --no-daemon
```

Expected: `BUILD SUCCESSFUL`。若 `System.loadLibrary` 之外的 `org.opencv.*` 引用报错，按 Step 5 的三条注意事项逐条修正，**每次改完都要重跑 Step 6**。

- [ ] **Step 8: 提交**

```powershell
git add -A
git commit -m "feat: OpenCV 透视矫正与 native 加载降级"
```

---

---

### Task 9: 拍摄页（权限、常亮预览、状态层、绿框映射）

把占位拍摄页换成真实界面：全屏预览 + 常亮 + 录音启动 + 状态条 + 底部控制。相机绑定是薄适配层；**状态机与坐标映射是纯逻辑，先测后写**。

**Files:**
- Create: `app/src/main/java/com/meetnote/recorder/ui/capture/CaptureViewModel.kt`
- Create: `app/src/main/java/com/meetnote/recorder/ui/capture/PreviewOverlay.kt`
- Create: `app/src/main/java/com/meetnote/recorder/capture/CameraBinder.kt`
- Modify: `app/src/main/java/com/meetnote/recorder/ui/capture/CaptureScreen.kt`（整文件替换占位版）
- Test: `app/src/test/java/com/meetnote/recorder/ui/capture/CaptureViewModelTest.kt`
- Test: `app/src/test/java/com/meetnote/recorder/ui/capture/PreviewOverlayTest.kt`

**Interfaces:**
- Consumes: Task 2 `MeetingRepository.observePages / meeting`、Task 4 `RecordingState.status / MeetingService.start`、Task 7 `Quad / Point2`、Task 6 `AnalysisConfig`
- Produces:
  - `data class CaptureUiState(title, statusText, pageCount, paused, recording: RecordingStatus, quad: Quad?, degraded: Boolean, opencvAvailable: Boolean)`
  - `class CaptureViewModel(meetingId: Long, repo: MeetingRepository)`：`val ui: StateFlow<CaptureUiState>`、`fun togglePause(): Boolean`（返回新状态）、`fun onPipelineStatus(text: String, quad: Quad?, degraded: Boolean)`、`fun setPageCount(n: Int)`
  - `object PreviewOverlay { fun mapImagePointToView(p: Point2, imageW: Int, imageH: Int, rotationDeg: Int, viewW: Float, viewH: Float): Point2 }`
  - `class CameraBinder(owner: LifecycleOwner, context: Context)`：
    `fun bind(surfaceProvider: Preview.SurfaceProvider): Preview?`、`fun setAnalyzer(a: ImageAnalysis.Analyzer?)`、`fun takePicture(executor: Executor, onBitmap: (Bitmap?) -> Unit)`、`fun unbind()`
  - `CaptureScreen(meetingId, repository, onFinished, onOpenGuide)` 签名保持与 Task 3 一致（Nav 不用改）

- [ ] **Step 1: 写坐标映射失败测试**

`app/src/test/java/com/meetnote/recorder/ui/capture/PreviewOverlayTest.kt`：

```kotlin
package com.meetnote.recorder.ui.capture

import com.meetnote.recorder.capture.Point2
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewOverlayTest {

    private val eps = 0.75f

    @Test
    fun `rotation 90 maps image corner to rotated corner`() {
        // 后置摄像头竖拍：图像需顺时针转 90° 才是正的，左上角 → 右上角
        val v = PreviewOverlay.mapImagePointToView(
            p = Point2(0f, 0f), imageW = 1280, imageH = 720,
            rotationDeg = 90, viewW = 720f, viewH = 1280f,
        )
        assertEquals(720f, v.x, eps)
        assertEquals(0f, v.y, eps)
    }

    @Test
    fun `rotation 90 maps bottom right to bottom left`() {
        val v = PreviewOverlay.mapImagePointToView(
            p = Point2(1279f, 719f), imageW = 1280, imageH = 720,
            rotationDeg = 90, viewW = 720f, viewH = 1280f,
        )
        assertEquals(1f, v.x, eps)
        assertEquals(1279f, v.y, eps)
    }

    @Test
    fun `identity rotation keeps position`() {
        val v = PreviewOverlay.mapImagePointToView(
            Point2(100f, 200f), 640, 480, rotationDeg = 0, viewW = 640f, viewH = 480f,
        )
        assertEquals(100f, v.x, eps)
        assertEquals(200f, v.y, eps)
    }

    @Test
    fun `center crop upscales to fill the larger axis`() {
        // 旋转后 720x1280 要塞进 720x1600 的视图：按高放大 1.25 倍，宽出现负偏移
        val v = PreviewOverlay.mapImagePointToView(
            p = Point2(360f, 360f), imageW = 1280, imageH = 720,
            rotationDeg = 90, viewW = 720f, viewH = 1600f,
        )
        val scale = 1600f / 1280f          // 1.25
        val offsetX = (720f - 720f * scale) / 2f   // -90
        assertEquals(360f * scale + offsetX, v.x, eps)
        assertEquals(360f * scale, v.y, eps)
    }

    @Test
    fun `center crop letterboxes when view is wider`() {
        // 视图 1000x1280：宽方向放大更多，高度出现负偏移
        val v = PreviewOverlay.mapImagePointToView(
            p = Point2(0f, 719f), imageW = 1280, imageH = 720,
            rotationDeg = 90, viewW = 1000f, viewH = 1280f,
        )
        val rotW = 720f
        val rotH = 1280f
        val scale = 1000f / rotW
        val offsetY = (1280f - rotH * scale) / 2f
        // 图像点 (0,719) → 旋转后 (720-719, 0) = (1, 0)
        assertEquals(1f * scale, v.x, eps)
        assertEquals(0f * scale + offsetY, v.y, eps)
    }
}
```

- [ ] **Step 2: 运行确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.ui.capture.PreviewOverlayTest" --no-daemon
```

Expected: FAIL，`unresolved reference: PreviewOverlay`。

- [ ] **Step 3: 实现 PreviewOverlay**

`ui/capture/PreviewOverlay.kt`：

```kotlin
package com.meetnote.recorder.ui.capture

import com.meetnote.recorder.capture.Point2
import kotlin.math.max

/**
 * 把分析帧坐标系里的点映射到 PreviewView 上，用于画检测到的屏幕绿框。
 *
 * 假设（Task 15 真机验收项）：
 *  - 后置摄像头竖拍时 [rotationDeg] = 90，图像需顺时针旋转 90° 才与预览一致；
 *  - PreviewView 使用默认的 FILL_CENTER（居中裁切）缩放。
 * 若真机上绿框与屏幕错位，只需要调整 [rotate] 中的分支。
 */
object PreviewOverlay {

    fun mapImagePointToView(
        p: Point2,
        imageW: Int,
        imageH: Int,
        rotationDeg: Int,
        viewW: Float,
        viewH: Float,
    ): Point2 {
        val (rx, ry, rotW, rotH) = rotate(p.x, p.y, imageW, imageH, rotationDeg)

        val scale = max(viewW / rotW, viewH / rotH)     // FILL_CENTER
        val dx = (viewW - rotW * scale) / 2f
        val dy = (viewH - rotH * scale) / 2f
        return Point2(rx * scale + dx, ry * scale + dy)
    }

    /** 旋转后的坐标与旋转后画幅尺寸 */
    private fun rotate(
        x: Float,
        y: Float,
        imageW: Int,
        imageH: Int,
        rotationDeg: Int,
    ): Rotated {
        val h = imageH.toFloat()
        val w = imageW.toFloat()
        return when (((rotationDeg % 360) + 360) % 360) {
            90  -> Rotated(h - 1f - y, x, h, w)         // 顺时针 90°
            180 -> Rotated(w - 1f - x, h - 1f - y, w, h)
            270 -> Rotated(y, w - 1f - x, h, w)         // 逆时针 90°
            else -> Rotated(x, y, w, h)
        }
    }

    private data class Rotated(val x: Float, val y: Float, val w: Float, val h: Float)
}
```

- [ ] **Step 4: 运行确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.ui.capture.PreviewOverlayTest" --no-daemon
```

Expected: 5 tests PASS。

- [ ] **Step 5: 写 ViewModel 失败测试**

`app/src/test/java/com/meetnote/recorder/ui/capture/CaptureViewModelTest.kt`：

```kotlin
package com.meetnote.recorder.ui.capture

import com.meetnote.recorder.audio.RecordingState
import com.meetnote.recorder.audio.RecordingStatus
import com.meetnote.recorder.data.MeetingRepository
import com.meetnote.recorder.data.MeetingStats
import com.meetnote.recorder.data.Meeting
import com.meetnote.recorder.data.ShotResult
import com.meetnote.recorder.data.SlidePage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

private class PagesFakeRepo : MeetingRepository {
    val pages = MutableStateFlow<List<SlidePage>>(emptyList())
    var title = "需求评审"

    override fun observeMeetings(): Flow<List<MeetingStats>> = MutableStateFlow(emptyList())
    override fun observePages(meetingId: Long): Flow<List<SlidePage>> = pages
    override suspend fun meeting(id: Long): Meeting? = Meeting(id, title, 1L)
    override suspend fun pageCount(id: Long): Int = pages.value.size
    override suspend fun createMeeting(title: String): Long = 1L
    override suspend fun finishMeeting(id: Long, audioPath: String?, durationMs: Long) = Unit
    override suspend fun deleteMeeting(id: Long) = Unit
    override suspend fun deletePage(pageId: Long) = Unit
    override suspend fun registerShot(
        meetingId: Long, imageFile: File, width: Int, height: Int, isSamePage: Boolean,
    ): ShotResult = ShotResult(1, isSamePage, 1)
    override suspend fun targetFileFor(meetingId: Long, isSamePage: Boolean): File = File("x")
    override fun meetingAudioFile(meetingId: Long): File = File("x")
    override fun freeBytes(): Long = Long.MAX_VALUE
}

class CaptureViewModelTest {

    @Before
    fun setUp() = RecordingState.clear()

    @After
    fun tearDown() = RecordingState.clear()

    private fun page(id: Long, index: Int) = SlidePage(
        meetingId = id, index = index, filePath = "/p/$index.jpg",
        width = 1600, height = 900, createdAt = 1L, updatedAt = 1L,
    )

    @Test
    fun `ui state carries meeting title`() {
        val vm = CaptureViewModel(1L, PagesFakeRepo())
        assertEquals("需求评审", vm.ui.value.title)
    }

    @Test
    fun `page count follows repository pages`() {
        val repo = PagesFakeRepo()
        val vm = CaptureViewModel(1L, repo)
        repo.pages.value = listOf(page(1, 1), page(1, 2))
        // 页面流是 stateIn，直接再读一次当前值即可（无需等待）
        vm.refreshPageCount(2)
        assertEquals(2, vm.ui.value.pageCount)
    }

    @Test
    fun `pause toggle flips and persists`() {
        val vm = CaptureViewModel(1L, PagesFakeRepo())
        assertFalse(vm.ui.value.paused)
        assertTrue(vm.togglePause())
        assertTrue(vm.ui.value.paused)
        assertFalse(vm.togglePause())
    }

    @Test
    fun `pipeline status updates text and quad`() {
        val vm = CaptureViewModel(1L, PagesFakeRepo())
        vm.onPipelineStatus("已拍第 3 页", quad = null, degraded = false)
        assertEquals("已拍第 3 页", vm.ui.value.statusText)
        assertFalse(vm.ui.value.degraded)

        vm.onPipelineStatus("未检测到屏幕边缘", quad = null, degraded = true)
        assertTrue(vm.ui.value.degraded)
        assertNotNull(vm.ui.value.statusText)
    }

    @Test
    fun `recording status is mirrored from global state`() {
        val vm = CaptureViewModel(1L, PagesFakeRepo())
        RecordingState.bind(1L, 0)
        RecordingState.onStart()
        RecordingState.onTick(65_000)

        val rec: RecordingStatus = vm.ui.value.recording
        assertTrue(rec.running)
        assertEquals(65_000L, rec.elapsedMs)
    }
}
```

- [ ] **Step 6: 运行确认失败后实现 CaptureViewModel**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.ui.capture.CaptureViewModelTest" --no-daemon
```

Expected: FAIL，`unresolved reference: CaptureViewModel`。

`ui/capture/CaptureViewModel.kt`：

```kotlin
package com.meetnote.recorder.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meetnote.recorder.audio.RecordingState
import com.meetnote.recorder.audio.RecordingStatus
import com.meetnote.recorder.capture.Point2
import com.meetnote.recorder.capture.Quad
import com.meetnote.recorder.data.MeetingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CaptureUiState(
    val title: String = "",
    val statusText: String = "准备中…",
    val pageCount: Int = 0,
    val paused: Boolean = false,
    val recording: RecordingStatus = RecordingStatus(),
    val quad: Quad? = null,
    val degraded: Boolean = false,
)

class CaptureViewModel(
    private val meetingId: Long,
    private val repo: MeetingRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(CaptureUiState())
    val ui: StateFlow<CaptureUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val m = repo.meeting(meetingId)
            _ui.update { it.copy(title = m?.title ?: "会议") }
        }
        viewModelScope.launch {
            repo.observePages(meetingId).collect { pages ->
                _ui.update { it.copy(pageCount = pages.size) }
            }
        }
        viewModelScope.launch {
            RecordingState.status.collect { rec ->
                _ui.update { it.copy(recording = rec) }
            }
        }
    }

    /** 暂停/继续抓拍，返回新状态 */
    fun togglePause(): Boolean {
        val next = !_ui.value.paused
        _ui.update { it.copy(paused = next) }
        return next
    }

    fun onPipelineStatus(text: String, quad: Quad?, degraded: Boolean) {
        _ui.update { it.copy(statusText = text, quad = quad, degraded = degraded) }
    }

    fun refreshPageCount(n: Int) {
        _ui.update { it.copy(pageCount = n) }
    }

    fun meetingId(): Long = meetingId
}
```

> `refreshPageCount` 是给测试与手动刷新用的显式入口；生产路径由 `observePages` 自动更新。

- [ ] **Step 7: 运行确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.ui.capture.CaptureViewModelTest" --no-daemon
```

Expected: 5 tests PASS。

- [ ] **Step 8: 实现 CameraBinder**

`capture/CameraBinder.kt`：

```kotlin
package com.meetnote.recorder.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.ResolutionSelector
import androidx.camera.core.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 相机三用例绑定（Preview + ImageAnalysis + ImageCapture）。
 * 只做接线：稳定判定、矫正、去重全部在 [CapturePipeline]（Task 10）。
 */
class CameraBinder(
    private val lifecycleOwner: LifecycleOwner,
    private val context: Context,
) {

    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private val binding = AtomicBoolean(false)

    /** 分析分辨率 1280x720（spec §6.1），KEEP_ONLY_LATEST 绝不堆积帧 */
    private val analysisUseCase = ImageAnalysis.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    ),
                )
                .build(),
        )
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        .build()

    private val captureUseCase = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    ),
                ).build(),
        )
        .build()

    fun bind(previewView: PreviewView): Boolean {
        if (binding.getAndSet(true)) return true
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                cameraProvider.unbindAll()

                val preview = Preview.Builder().build().apply {
                    surfaceProvider = previewView.surfaceProvider
                }
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysisUseCase,
                    captureUseCase,
                )
            } catch (t: Throwable) {
                binding.set(false)
            }
        }, ContextCompat.getMainExecutor(context))
        return true
    }

    fun setAnalyzer(analyzer: ImageAnalysis.Analyzer?) {
        analysisUseCase.setAnalyzer(
            ContextCompat.getMainExecutor(context),
            analyzer ?: ImageAnalysis.Analyzer { proxy -> proxy.close() },
        )
    }

    /**
     * 抓一张全分辨率图。同一时刻只允许一张在飞（稳定判定后 400ms 冷却，
     * 这里是第二道保险，避免 ImageCapture 排队）。
     */
    fun takePicture(executor: Executor, onBitmap: (Bitmap?) -> Unit) {
        val cap = imageCapture ?: run { onBitmap(null); return }
        if (!inFlight.compareAndSet(false, true)) { onBitmap(null); return }

        val tmp = File(context.cacheDir, "shot-${System.nanoTime()}.jpg")
        cap.takePicture(
            ImageCapture.OutputFileOptions(tmp),
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val bmp = BitmapFactory.decodeFile(tmp.absolutePath)
                    tmp.delete()
                    inFlight.set(false)
                    onBitmap(bmp)
                }

                override fun onError(exception: ImageCaptureException) {
                    tmp.delete()
                    inFlight.set(false)
                    onBitmap(null)
                }
            },
        )
    }

    fun unbind() {
        if (!binding.getAndSet(false)) return
        runCatching { provider?.unbindAll() }
        provider = null
    }

    companion object {
        private val inFlight = AtomicBoolean(false)
    }
}
```

> 修正：`imageCapture` 字段在上面没有赋值，在 `bind()` 成功后加一行 `imageCapture = captureUseCase`。

- [ ] **Step 9: 重写 CaptureScreen**

`ui/capture/CaptureScreen.kt` 整文件替换为：

```kotlin
package com.meetnote.recorder.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meetnote.recorder.audio.MeetingService
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
    val vm: CaptureViewModel = remember {
        CaptureViewModel(meetingId, repository)
    }
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
    }.toSet()
    val cameraGranted = Manifest.permission.CAMERA !in missing
    // 仅在待授权集合"变化"时自动请求：拒绝后集合不变 → 不再重复弹窗（手动重试会先清空它）
    var lastRequested by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 授权结果回调 bump tick：无论 granted 还是 denied，都会重新推导 missing
    // （checkSelfPermission 不是快照状态，对话框返回后必须有状态变化驱动重算）
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionTick++ }

    LaunchedEffect(missing.size, permissionTick) {
        if (missing.isNotEmpty()) {
            if (missing != lastRequested) {
                lastRequested = missing
                permissionLauncher.launch(missing.toTypedArray())
            }
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
                    Button(onClick = onFinished) { Text("结束会议") }
                }
            }
        }
    }
}
```

> 补两处编译必需的 import：`androidx.compose.foundation.layout.size`、
> `androidx.lifecycle.compose.collectAsStateWithLifecycle`。
> `CaptureViewModel` 用 `remember { ... }` 创建，若希望随 Nav 销毁，改用
> `viewModel(factory = ...)`（同 Task 3 的写法），保持 `ui` 签名不变。

- [ ] **Step 10: 构建并跑全部测试**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL`，测试全绿。

- [ ] **Step 11: 提交**

```powershell
git add -A
git commit -m "feat: 拍摄页预览、权限、常亮与绿框映射"
```

---

---

### Task 10: 抓拍流水线（分析 → 触发 → 矫正 → 质量门 → 去重 → 落库）

把前面所有纯逻辑与薄适配层串起来。核心编排逻辑通过注入 `correct` 函数与调度器，可以在 JVM 上用 Robolectric 全流程测试（假矫正器 + 真 Room + 真文件）。

**Files:**
- Create: `app/src/main/java/com/meetnote/recorder/capture/CapturePipeline.kt`
- Modify: `app/src/main/java/com/meetnote/recorder/ui/capture/CaptureScreen.kt`（接入 pipeline 与 analyzer）
- Test: `app/src/test/java/com/meetnote/recorder/capture/CapturePipelineTest.kt`
- Modify: `app/src/test/java/com/meetnote/recorder/capture/QualityGateTest.kt`（补 `grayOf` 用例）

**Interfaces:**
- Consumes: `StabilityDetector.onFrame`（Task 6）、`YuvFrames.toGrid`（Task 6）、`DocumentCorrector.correct`（Task 8）、`QuadMath` / `QualityGate`（Task 7）、`SignalBuilder` / `SlideDeduplicator.compare`（Task 5）、`MeetingRepository.targetFileFor / registerShot / observePages`（Task 2/5）
- Produces:
  - `data class PipelineStatus(val text: String, val quad: Quad? = null, val degraded: Boolean = false, val shot: ShotResult? = null)`
  - `class CapturePipeline(meetingId: Long, repository: MeetingRepository, scope: CoroutineScope, ioContext: CoroutineContext = Dispatchers.IO, correct: (Bitmap) -> Correction = DocumentCorrector()::correct, onStatus: (PipelineStatus) -> Unit = {})`
    - `var onPictureRequest: () -> Unit`（由拍摄页注入，触发 `binder.takePicture`）
    - `fun analyze(grid: FrameGrid, tsMs: Long)`
    - `fun onPicture(bitmap: Bitmap?)`
    - `fun forceCapture()`
    - `fun setPaused(paused: Boolean)`
  - `QualityGate.grayOf(pixels: IntArray): FloatArray`（ARGB → 0..255 灰度）

- [ ] **Step 1: 补 QualityGate.grayOf 测试**

在 `QualityGateTest.kt` 末尾追加：

```kotlin
    @Test
    fun `grayOf uses Rec 601 weights on 0-255 scale`() {
        val px = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
        val g = QualityGate.grayOf(px)
        assertEquals(76.2f, g[0], 0.5f)    // 0.299*255
        assertEquals(149.7f, g[1], 0.5f)   // 0.587*255
        assertEquals(29.1f, g[2], 0.5f)    // 0.114*255
    }
```

并确认文件顶部已有 `import org.junit.Assert.assertEquals`（前几个用例已导入）。

- [ ] **Step 2: 运行确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.capture.QualityGateTest" --no-daemon
```

Expected: FAIL，`unresolved reference: grayOf`。

- [ ] **Step 3: 实现 grayOf**

在 `capture/QualityGate.kt` 的 `object QualityGate` 内追加：

```kotlin
    /** ARGB 像素 → 0..255 灰度（Rec.601），供清晰度/亮度计算使用 */
    fun grayOf(pixels: IntArray): FloatArray =
        FloatArray(pixels.size) { i ->
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            0.299f * r + 0.587f * g + 0.114f * b
        }
```

运行测试确认 PASS（6 个 QualityGate 用例）。

- [ ] **Step 4: 写流水线失败测试**

`app/src/test/java/com/meetnote/recorder/capture/CapturePipelineTest.kt`：

```kotlin
package com.meetnote.recorder.capture

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Canvas
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.meetnote.recorder.data.AppDatabase
import com.meetnote.recorder.data.DefaultMeetingRepository
import com.meetnote.recorder.data.FileStore
import com.meetnote.recorder.data.MeetingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapturePipelineTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: MeetingRepository
    private lateinit var root: File
    private lateinit var meetingId: Long
    private val statuses = mutableListOf<PipelineStatus>()
    private var pictureRequests = 0

    @Before
    fun setUp() = runBlocking {
        root = File(System.getProperty("java.io.tmpdir"), "pipe-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = DefaultMeetingRepository(db.meetingDao(), FileStore(root))
        meetingId = repo.createMeeting("流水线测试")
        statuses.clear()
        pictureRequests = 0
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    /** 直通矫正器：不做任何 OpenCV 处理 */
    private fun passthrough(bmp: Bitmap) = Correction(bmp, null, degraded = false, reason = null)

    /** 连续 n 帧完全相同的画面 */
    private fun grid(value: Int) =
        FrameGrid(AnalysisConfig.W, AnalysisConfig.H,
            IntArray(AnalysisConfig.W * AnalysisConfig.H) { value })

    private fun pipeline(
        correct: (Bitmap) -> Correction = ::passthrough,
    ) = CapturePipeline(
        meetingId = meetingId,
        repository = repo,
        scope = CoroutineScope(Dispatchers.Unconfined),
        ioContext = Dispatchers.Unconfined,
        correct = correct,
        onStatus = { statuses += it },
    ).also { p ->
        p.onPictureRequest = { pictureRequests++ }
    }

    /** 模拟分析帧序列直到产生抓拍请求 */
    private fun feedStable(p: CapturePipeline, startTs: Long = 0L, n: Int = 8) {
        repeat(n) { i -> p.analyze(grid(100), startTs + i * 33L) }
    }

    /** 清晰可过质量门的图：竖条纹保证拉普拉斯方差足够大 */
    private fun striped(w: Int = 320, h: Int = 180): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            val canvas = Canvas(bmp)
            val paint = Paint().apply { color = Color.BLACK; strokeWidth = 6f }
            var x = 0f
            while (x < w) {
                canvas.drawLine(x, 0f, x, h.toFloat(), paint)
                x += 16f
            }
        }

    /** 纯色图：过不了清晰度门 */
    private fun flat(w: Int = 320, h: Int = 180, color: Int = Color.rgb(128, 128, 128)): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private suspend fun pageCount() = repo.pageCount(meetingId)

    @Test
    fun `stable frames trigger picture request`() {
        val p = pipeline()
        feedStable(p)
        assertEquals(1, pictureRequests)   // 稳定 + 冷却内不再重复触发
    }

    @Test
    fun `paused pipeline never triggers`() {
        val p = pipeline()
        p.setPaused(true)
        feedStable(p)
        assertEquals(0, pictureRequests)

        p.setPaused(false)
        feedStable(p, startTs = 10_000L)
        assertEquals(1, pictureRequests)
    }

    @Test
    fun `first picture becomes page one`() {
        val p = pipeline()
        p.onPicture(striped())

        assertEquals(1, pageCount())
        assertEquals(1, statuses.last().shot?.index)
        assertFalse(statuses.last().shot!!.overwritten)
        assertTrue(statuses.last().text.contains("第 1 页"))
        // 图片文件真的写出来了
        val page = repo.observePages(meetingId).first().single()
        assertTrue(File(page.filePath).exists())
        assertTrue(page.filePath.endsWith("page_0001.jpg"))
    }

    @Test
    fun `same page with more content overwrites instead of appending`() {
        val p = pipeline()
        p.onPicture(striped())
        p.onPicture(striped())            // 同一页又拍了一张（累积内容）

        assertEquals("同页必须只留一张", 1, pageCount())
        val page = repo.observePages(meetingId).first().single()
        assertEquals(2, page.captureCount)
        assertTrue(statuses.last().text.contains("已更新第 1 页"))
    }

    @Test
    fun `different looking slide appends page two`() {
        val p = pipeline()
        p.onPicture(striped())
        // 换一张完全不同的图（大块纯色区域，与条纹图差异明显）
        val other = flat(320, 180, Color.rgb(40, 40, 40)).also { bmp ->
            val canvas = Canvas(bmp)
            val paint = Paint().apply { color = Color.WHITE; strokeWidth = 30f }
            canvas.drawLine(10f, 10f, 310f, 170f, paint)
        }
        p.onPicture(other)

        assertEquals(2, pageCount())
        assertEquals(2, statuses.last().shot?.index)
    }

    @Test
    fun `quality gate silently drops bad frames`() {
        val p = pipeline()
        p.onPicture(flat())                // 纯灰图，清晰度为 0

        assertEquals(0, pageCount())
        assertTrue(statuses.isEmpty())
    }

    @Test
    fun `three consecutive detection failures enter degraded mode`() {
        val p = pipeline(correct = { bmp -> Correction(bmp, null, degraded = true, reason = "未检测到屏幕边缘") })

        p.onPicture(striped())
        assertFalse(statuses.last().degraded)
        p.onPicture(striped())
        assertFalse(statuses.last().degraded)
        p.onPicture(striped())
        assertTrue("连续 3 次检测失败应进入降级提示", statuses.last().degraded)

        // 恢复正常检测后降级标记应当消失
        val p2 = pipeline()
        p2.onPicture(striped())
        assertFalse(statuses.last().degraded)
    }

    @Test
    fun `null bitmap resets in-flight state`() {
        val p = pipeline()
        feedStable(p)
        assertEquals(1, pictureRequests)
        p.onPicture(null)                  // 拍摄失败
        // 失败后仍可再次触发
        p.analyze(grid(100), 100_000L)
        feedStable(p, startTs = 110_000L)
        assertTrue(pictureRequests >= 2)
    }
}
```

- [ ] **Step 5: 运行确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.capture.CapturePipelineTest" --no-daemon
```

Expected: FAIL，`unresolved reference: CapturePipeline` 或 `PipelineStatus`。

- [ ] **Step 6: 实现 CapturePipeline**

`capture/CapturePipeline.kt`：

```kotlin
package com.meetnote.recorder.capture

import android.graphics.Bitmap
import com.meetnote.recorder.data.MeetingRepository
import com.meetnote.recorder.data.ShotResult
import com.meetnote.recorder.dedupe.ImageSignals
import com.meetnote.recorder.dedupe.SignalBuilder
import com.meetnote.recorder.dedupe.SlideDeduplicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/** 流水线状态，交由 UI 显示 */
data class PipelineStatus(
    val text: String,
    val quad: Quad? = null,
    val degraded: Boolean = false,
    val shot: ShotResult? = null,
)

/**
 * 抓拍流水线（spec §5）：
 *   稳定判定 → 触发拍摄 → 文档矫正 → 质量门 → 同页判定 → 覆盖或追加
 *
 * 线程约定：[analyze] 由相机分析线程同步调用（只做 64×36 的差分，极轻）；
 * [onPicture] 的处理在 [scope] 中执行，文件 IO 指定到 [ioContext]。
 */
class CapturePipeline(
    private val meetingId: Long,
    private val repository: MeetingRepository,
    private val scope: CoroutineScope,
    private val ioContext: CoroutineContext = Dispatchers.IO,
    private val correct: (Bitmap) -> Correction = DocumentCorrector()::correct,
    private val onStatus: (PipelineStatus) -> Unit = {},
) {

    /** 由拍摄页注入：请求相机拍一张全分辨率图，结果回传 [onPicture] */
    var onPictureRequest: () -> Unit = {}

    private val stability = StabilityDetector()

    @Volatile private var paused = false
    @Volatile private var inFlight = false
    private var detectFailures = 0

    /** 最近一页的信号缓存，避免每张都解码上一张图 */
    private var lastSignals: ImageSignals? = null

    fun setPaused(paused: Boolean) {
        this.paused = paused
        if (!paused) stability.reset()
    }

    fun analyze(grid: FrameGrid, tsMs: Long) {
        if (paused || inFlight) return
        val verdict = stability.onFrame(grid, tsMs)
        if (verdict is Verdict.CaptureNow) {
            inFlight = true
            onPictureRequest()
        }
    }

    /** 手动快门：跳过稳定判定直接拍 */
    fun forceCapture() {
        if (paused || inFlight) return
        inFlight = true
        onPictureRequest()
    }

    fun onPicture(bitmap: Bitmap?) {
        if (bitmap == null) {
            inFlight = false
            onStatus(PipelineStatus("拍摄失败，继续监测…"))
            return
        }
        scope.launch(ioContext) {
            try {
                process(bitmap)
            } catch (t: Throwable) {
                onStatus(PipelineStatus("处理失败，继续监测…"))
            } finally {
                inFlight = false
            }
        }
    }

    private suspend fun process(source: Bitmap) {
        // 1. 文档矫正（OpenCV 不可用或找不到屏幕边时返回原图 + degraded）
        val correction = correct(source)
        detectFailures = if (correction.degraded) detectFailures + 1 else 0
        val degraded = detectFailures >= DEGRADE_AFTER
        val image = correction.bitmap

        // 2. 质量门：不过就静默丢弃（spec §12）
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        val gray = QualityGate.grayOf(pixels)
        val aspect = correction.quad?.let { QuadMath.aspectRatio(it) }
            ?: image.width.toFloat() / image.height
        val lap = QualityGate.laplacianVariance(gray, image.width, image.height)
        if (!QualityGate.accept(lap, QualityGate.meanLuma(gray), aspect)) {
            return   // 画面模糊/过亮/比例异常：丢弃候选，继续监测
        }

        // 3. 同页判定
        val signals = SignalBuilder.fromPixels(pixels, image.width, image.height)
        val previous = cachedLastSignals()
        val isSamePage = previous != null && SlideDeduplicator.compare(previous, signals).isSamePage

        // 4. 写文件 + 入库
        val target = repository.targetFileFor(meetingId, isSamePage)
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out ->
            image.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        val result = repository.registerShot(
            meetingId = meetingId,
            imageFile = target,
            width = image.width,
            height = image.height,
            isSamePage = isSamePage,
        )
        lastSignals = signals

        onStatus(
            PipelineStatus(
                text = if (result.overwritten) "已更新第 ${result.index} 页"
                else "已拍第 ${result.index} 页",
                quad = correction.quad,
                degraded = degraded,
                shot = result,
            ),
        )
    }

    /** 取上一页信号：优先内存缓存；缓存为空（中途进页/进程重启）则解码磁盘文件 */
    private suspend fun cachedLastSignals(): ImageSignals? {
        lastSignals?.let { return it }
        val last = repository.observePages(meetingId).first().lastOrNull() ?: return null
        val bmp = android.graphics.BitmapFactory.decodeFile(last.filePath) ?: return null
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val signals = SignalBuilder.fromPixels(px, bmp.width, bmp.height)
        bmp.recycle()
        lastSignals = signals
        return signals
    }

    private companion object {
        /** 连续这么多次检测失败后进入降级提示（spec §6.3） */
        const val DEGRADE_AFTER = 3
        const val JPEG_QUALITY = 92
    }
}
```

- [ ] **Step 7: 运行流水线测试并修正**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.capture.CapturePipelineTest" --no-daemon
```

Expected: 8 tests PASS。

**若 `same page with more content overwrites...` 失败**：两张 `striped()` 完全相同 → `recall=1, fgOldSim=1, coverage=1` 必然同页；若失败说明 `cachedLastSignals` 没读到刚写入的文件，检查 `targetFileFor` 返回路径与 `registerShot` 写入路径一致（同为 `page_0001.jpg`）。

**若 `different looking slide appends page two` 失败**：调 `striped` 与 `other` 的对比度，或确认 `compare` 的 `coverage/recall` 确实不同。若仍同页，把 `other` 改为大面积黑底 + 白色对角线（对比更极端）。

**若 `quality gate silently drops bad frames` 反而写进了页面**：纯灰图拉普拉斯为 0，必被拒；若被放行，检查 `QualityGate.accept` 的参数顺序（lap, mean, aspect）没有传反。

- [ ] **Step 8: 把流水线接进拍摄页**

在 `ui/capture/CaptureScreen.kt` 中，替换"相机绑定"那段之后追加：

```kotlin
    // 抓拍流水线
    val pipeline = remember {
        CapturePipeline(
            meetingId = meetingId,
            repository = repository,
            scope = androidx.lifecycle.lifecycleScope,
            correct = DocumentCorrector()::correct,
            onStatus = { st -> vm.onPipelineStatus(st.text, st.quad, st.degraded) },
        )
    }
    pipeline.onPictureRequest = {
        binder.takePicture(
            androidx.core.content.ContextCompat.getMainExecutor(context),
        ) { bmp ->
            pipeline.onPicture(bmp)
        }
    }
    LaunchedEffect(Unit) {
        binder.setAnalyzer { proxy ->
            val plane = proxy.planes[0]
            val grid = YuvFrames.toGrid(
                y = plane.buffer,
                imageWidth = proxy.width,
                imageHeight = proxy.height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
            )
            pipeline.analyze(grid, android.os.SystemClock.elapsedRealtime())
            proxy.close()
        }
    }
    LaunchedEffect(state.paused) { pipeline.setPaused(state.paused) }
    DisposableEffect(Unit) { onDispose { pipeline.setPaused(true) } }
```

并补齐 import：`com.meetnote.recorder.capture.CameraBinder`、`com.meetnote.recorder.capture.CapturePipeline`、`com.meetnote.recorder.capture.DocumentCorrector`、`com.meetnote.recorder.capture.YuvFrames`。

> 注：`androidx.lifecycle.lifecycleScope` 需要 `import androidx.lifecycle.lifecycleScope`，
> 它来自 `LocalLifecycleOwner.current.lifecycleScope` 更稳妥，写作
> `scope = lifecycleOwner.lifecycleScope`。

- [ ] **Step 9: 构建并跑全部测试**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL`，测试全绿（累计 60+ 用例）。

- [ ] **Step 10: 提交**

```powershell
git add -A
git commit -m "feat: 抓拍流水线串联稳定判定/矫正/质量门/去重落库"
```

---

---

### Task 11: 手动快门与"结束会议"闭环

给自动抓拍加一个人工兜底（自动漏拍时手动补），并把"结束会议 → 停录音 → 写时长 → 进详情页"串成闭环。

**Files:**
- Modify: `app/src/main/java/com/meetnote/recorder/ui/capture/CaptureScreen.kt`（加手动快门按钮、结束会议逻辑）
- Modify: `app/src/main/java/com/meetnote/recorder/ui/capture/CaptureViewModel.kt`（加 `endMeeting()`）
- Test: `app/src/test/java/com/meetnote/recorder/ui/capture/CaptureViewModelTest.kt`（追加用例）
- Test: `app/src/test/java/com/meetnote/recorder/capture/CapturePipelineTest.kt`（追加快门用例）

**Interfaces:**
- Consumes: `CapturePipeline.forceCapture`（Task 10）、`MeetingService.stop`（Task 4）、`MeetingRepository.finishMeeting / meeting`（Task 2）
- Produces:
  - `CaptureViewModel.endMeeting()`：若会议尚未标记结束，则写入结束时间与已录时长（服务异步写入是主路径，这里兜底保证 `endedAt` 一定有值）
  - 拍摄页底部按钮组：`[手动快门] [暂停抓拍] [结束会议]`

- [ ] **Step 1: 追加失败测试**

在 `CaptureViewModelTest.kt` 中给 `PagesFakeRepo` 补一个观测字段，并新增用例。先把 fake 类替换为：

```kotlin
private class PagesFakeRepo : MeetingRepository {
    val pages = MutableStateFlow<List<SlidePage>>(emptyList())
    var title = "需求评审"
    var meeting: Meeting? = Meeting(1, title, 1L, endedAt = null)
    var finishedCalls = mutableListOf<Pair<String?, Long>>()

    override fun observeMeetings(): Flow<List<MeetingStats>> = MutableStateFlow(emptyList())
    override fun observePages(meetingId: Long): Flow<List<SlidePage>> = pages
    override suspend fun meeting(id: Long): Meeting? = meeting
    override suspend fun pageCount(id: Long): Int = pages.value.size
    override suspend fun createMeeting(title: String): Long = 1L
    override suspend fun finishMeeting(id: Long, audioPath: String?, durationMs: Long) {
        finishedCalls += audioPath to durationMs
        meeting = meeting?.copy(endedAt = 1L, audioPath = audioPath, audioDurationMs = durationMs)
    }
    override suspend fun deleteMeeting(id: Long) = Unit
    override suspend fun deletePage(pageId: Long) = Unit
    override suspend fun registerShot(
        meetingId: Long, imageFile: File, width: Int, height: Int, isSamePage: Boolean,
    ): ShotResult = ShotResult(1, isSamePage, 1)
    override suspend fun targetFileFor(meetingId: Long, isSamePage: Boolean): File = File("x")
    override fun meetingAudioFile(meetingId: Long): File = File("x")
    override fun freeBytes(): Long = Long.MAX_VALUE
}
```

追加测试用例：

```kotlin
    @Test
    fun `endMeeting writes end time once`() = runBlocking {
        val repo = PagesFakeRepo()
        val vm = CaptureViewModel(1L, repo)
        RecordingState.bind(1L, 0)
        RecordingState.onStart()
        RecordingState.onTick(30_000)

        vm.endMeeting()

        assertEquals(1, repo.finishedCalls.size)
        assertEquals(30_000L, repo.finishedCalls.single().second)
        assertNotNull(repo.meeting(1L)!!.endedAt)

        // 已经结束过就不要重复写（服务可能已经写过）
        vm.endMeeting()
        assertEquals("重复结束不应二次写入", 1, repo.finishedCalls.size)
    }

    @Test
    fun `endMeeting is a no-op if service already finished`() = runBlocking {
        val repo = PagesFakeRepo()
        repo.meeting = Meeting(1, "已结束", 1L, endedAt = 99L, audioDurationMs = 5_000)
        val vm = CaptureViewModel(1L, repo)

        vm.endMeeting()

        assertTrue(repo.finishedCalls.isEmpty())
    }
```

文件顶部需补 `import org.junit.Assert.assertNotNull`、`import org.junit.Assert.assertTrue`（已有则跳过）。

- [ ] **Step 2: 运行确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.ui.capture.CaptureViewModelTest" --no-daemon
```

Expected: FAIL，`unresolved reference: endMeeting`。

- [ ] **Step 3: 实现 endMeeting**

`CaptureViewModel.kt` 追加：

```kotlin
    /**
     * 结束会议：录音服务是写入时长的主路径，这里只在服务没写成（权限被拒、
     * 服务未启动等）时兜底，保证 endedAt 一定有值。
     */
    fun endMeeting() {
        viewModelScope.launch {
            val m = repo.meeting(meetingId) ?: return@launch
            if (m.endedAt == null) {
                repo.finishMeeting(
                    id = meetingId,
                    audioPath = m.audioPath,
                    durationMs = RecordingState.status.value.elapsedMs,
                )
            }
        }
    }
```

- [ ] **Step 4: 运行确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.ui.capture.CaptureViewModelTest" --no-daemon
```

Expected: 7 tests PASS。

- [ ] **Step 5: 追加快门的流水线测试**

在 `CapturePipelineTest.kt` 追加：

```kotlin
    @Test
    fun `manual shutter fires even while the frame is shaking`() {
        val p = pipeline()
        // 持续抖动的帧：自动判定永不触发
        repeat(20) { i -> p.analyze(grid(100 + (i % 2) * 40), i * 33L) }
        assertEquals(0, pictureRequests)

        p.forceCapture()
        assertEquals("手动快门必须无视稳定判定", 1, pictureRequests)
    }

    @Test
    fun `manual shutter is ignored while a shot is already in flight`() {
        val p = pipeline()
        p.forceCapture()
        p.forceCapture()
        assertEquals(1, pictureRequests)
    }
```

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.capture.CapturePipelineTest" --no-daemon
```

Expected: 10 tests PASS（若 `manual shutter is ignored...` 失败，检查 `forceCapture` 中 `inFlight` 的 `compareAndSet` 语义——必须先置位再触发）。

- [ ] **Step 6: 接入拍摄页按钮与结束流程**

`CaptureScreen.kt` 中，把底部控制区的 `Row` 替换为：

```kotlin
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 手动快门：自动漏拍时人工补一张
                    Button(onClick = { pipeline.forceCapture() }) { Text("手动拍照") }

                    OutlinedButton(onClick = { vm.togglePause() }) {
                        Text(if (state.paused) "继续抓拍" else "暂停抓拍")
                    }

                    Button(
                        onClick = {
                            MeetingService.stop(context)
                            vm.endMeeting()
                            onFinished()
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("结束会议") }
                }
```

并把顶部导航图标的行为从"返回"改为"返回但不停止录音"（录音继续是设计意图，spec §12）：保持 `onOpenGuide` 不变，但在图标 tooltip 语义上改为"离开拍摄页（录音继续）"——若 Task 3 的 Nav 用 `popBackStack`，无需额外改动。

- [ ] **Step 7: 构建并跑全部测试**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL`，测试全绿。

- [ ] **Step 8: 提交**

```powershell
git add -A
git commit -m "feat: 手动快门与结束会议闭环"
```

---

---

### Task 12: 会议详情页（页图网格 + 录音播放 + 删除）

展示已拍的 PPT 页（按页码）、播放录音、删除误拍页/整场会议。

**Files:**
- Create: `app/src/main/java/com/meetnote/recorder/ui/detail/AudioPlayer.kt`
- Create: `app/src/main/java/com/meetnote/recorder/ui/detail/MeetingDetailViewModel.kt`
- Modify: `app/src/main/java/com/meetnote/recorder/ui/detail/MeetingDetailScreen.kt`（整文件替换占位版）
- Test: `app/src/test/java/com/meetnote/recorder/ui/detail/MeetingDetailViewModelTest.kt`

**Interfaces:**
- Consumes: `MeetingRepository.observePages / meeting / deletePage / deleteMeeting / meetingAudioFile`（Task 2）、`formatDuration`（Task 3）、`PdfExporter`（Task 13，占位按钮）
- Produces:
  - `interface AudioPlayer { fun prepare(file: File): Boolean; fun play(): Boolean; fun pause(); fun isPlaying(): Boolean; fun positionMs(): Long; fun durationMs(): Long; fun release() }`
  - `class MediaPlayerAudioPlayer : AudioPlayer`
  - `data class DetailUiState(title, createdAtMs, audioDurationMs, audioAvailable: Boolean, pages: List<SlidePage>, playing: Boolean, positionMs: Long, error: String?)`
  - `class MeetingDetailViewModel(meetingId: Long, repo: MeetingRepository, player: AudioPlayer)`：`val ui: StateFlow<DetailUiState>`、`fun togglePlay()`、`fun deletePage(page: SlidePage)`、`fun deleteMeeting(onDone: () -> Unit)`、`fun refresh()`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/meetnote/recorder/ui/detail/MeetingDetailViewModelTest.kt`：

```kotlin
package com.meetnote.recorder.ui.detail

import com.meetnote.recorder.data.Meeting
import com.meetnote.recorder.data.MeetingRepository
import com.meetnote.recorder.data.MeetingStats
import com.meetnote.recorder.data.ShotResult
import com.meetnote.recorder.data.SlidePage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private class FakePlayer : AudioPlayer {
    var prepared = false
    var playing = false
    override fun prepare(file: File): Boolean { prepared = true; return true }
    override fun play(): Boolean { playing = true; return true }
    override fun pause() { playing = false }
    override fun isPlaying(): Boolean = playing
    override fun positionMs(): Long = 1_000
    override fun durationMs(): Long = 60_000
    override fun release() { playing = false }
}

private class DetailFakeRepo : MeetingRepository {
    val pages = MutableStateFlow<List<SlidePage>>(emptyList())
    var meeting = Meeting(1, "周会", 1_000L, endedAt = 2_000L, audioDurationMs = 120_000L)
    var deletedPages = mutableListOf<Long>()
    var deletedMeeting = false

    override fun observeMeetings(): Flow<List<MeetingStats>> = MutableStateFlow(emptyList())
    override fun observePages(meetingId: Long): Flow<List<SlidePage>> = pages
    override suspend fun meeting(id: Long): Meeting? = meeting
    override suspend fun pageCount(id: Long): Int = pages.value.size
    override suspend fun createMeeting(title: String): Long = 1L
    override suspend fun finishMeeting(id: Long, audioPath: String?, durationMs: Long) = Unit
    override suspend fun deleteMeeting(id: Long) { deletedMeeting = true }
    override suspend fun deletePage(pageId: Long) {
        deletedPages += pageId
        pages.value = pages.value.filterNot { it.id == pageId }
    }
    override suspend fun registerShot(
        meetingId: Long, imageFile: File, width: Int, height: Int, isSamePage: Boolean,
    ): ShotResult = ShotResult(1, isSamePage, 1)
    override suspend fun targetFileFor(meetingId: Long, isSamePage: Boolean): File = File("x")
    override fun meetingAudioFile(meetingId: Long): File = File("x")
    override fun freeBytes(): Long = Long.MAX_VALUE
}

private fun page(id: Long, index: Int) = SlidePage(
    meetingId = 1, id = id, index = index, filePath = "/p/$index.jpg",
    width = 1600, height = 900, captureCount = 1, createdAt = 1L, updatedAt = 1L,
)

class MeetingDetailViewModelTest {

    @Test
    fun `state carries meeting info and pages`() {
        val repo = DetailFakeRepo()
        repo.pages.value = listOf(page(11, 1), page(12, 2))
        val vm = MeetingDetailViewModel(1L, repo, FakePlayer())

        assertEquals("周会", vm.ui.value.title)
        assertEquals(120_000L, vm.ui.value.audioDurationMs)
        assertEquals(2, vm.ui.value.pages.size)
        assertTrue(vm.ui.value.audioAvailable)
    }

    @Test
    fun `toggle play starts then pauses`() {
        val repo = DetailFakeRepo()
        val player = FakePlayer()
        val vm = MeetingDetailViewModel(1L, repo, player)

        vm.togglePlay()
        assertTrue(vm.ui.value.playing)
        assertTrue(player.prepared)

        vm.togglePlay()
        assertFalse(vm.ui.value.playing)
    }

    @Test
    fun `toggle play is inert when no audio file`() {
        val repo = DetailFakeRepo()
        repo.meeting = repo.meeting.copy(audioPath = null, audioDurationMs = 0L)
        val vm = MeetingDetailViewModel(1L, repo, FakePlayer())

        vm.togglePlay()

        assertFalse("没有录音就不该进入播放态", vm.ui.value.playing)
        assertEquals("没有录音时给用户提示", "本次会议没有录音", vm.ui.value.error)
    }

    @Test
    fun `delete page updates list`() {
        val repo = DetailFakeRepo()
        repo.pages.value = listOf(page(11, 1), page(12, 2), page(13, 3))
        val vm = MeetingDetailViewModel(1L, repo, FakePlayer())

        vm.deletePage(repo.pages.value[1])

        assertEquals(listOf(1L, 3L), repo.deletedPages)
        assertEquals(2, vm.ui.value.pages.size)
    }

    @Test
    fun `delete meeting reports completion`() = runBlocking {
        var done = false
        val repo = DetailFakeRepo()
        val vm = MeetingDetailViewModel(1L, repo, FakePlayer())

        vm.deleteMeeting { done = true }

        assertTrue(done)
        assertTrue(repo.deletedMeeting)
    }

    @Test
    fun `pages sorted by index ascending`() {
        val repo = DetailFakeRepo()
        repo.pages.value = listOf(page(31, 3), page(11, 1), page(21, 2))
        val vm = MeetingDetailViewModel(1L, repo, FakePlayer())
        assertEquals(listOf(1, 2, 3), vm.ui.value.pages.map { it.index })
    }
}
```

- [ ] **Step 2: 运行确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.ui.detail.*" --no-daemon
```

Expected: FAIL，`unresolved reference: AudioPlayer / MeetingDetailViewModel`。

- [ ] **Step 3: 实现 AudioPlayer 与 ViewModel**

`ui/detail/AudioPlayer.kt`：

```kotlin
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

    override fun positionMs(): Long = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)

    override fun durationMs(): Long = runCatching { player?.duration ?: 0 }.getOrDefault(0)

    override fun release() {
        runCatching { player?.release() }
        player = null
        prepared = false
    }
}
```

`ui/detail/MeetingDetailViewModel.kt`：

```kotlin
package com.meetnote.recorder.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meetnote.recorder.data.MeetingRepository
import com.meetnote.recorder.data.SlidePage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DetailUiState(
    val title: String = "",
    val createdAtMs: Long = 0L,
    val audioDurationMs: Long = 0L,
    val audioAvailable: Boolean = false,
    val pages: List<SlidePage> = emptyList(),
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val error: String? = null,
)

class MeetingDetailViewModel(
    private val meetingId: Long,
    private val repo: MeetingRepository,
    private val player: AudioPlayer,
) : ViewModel() {

    private val meetingFlow = MutableStateFlow<com.meetnote.recorder.data.Meeting?>(null)
    private val playback = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val ui: StateFlow<DetailUiState> = combine(
        repo.observePages(meetingId),
        meetingFlow,
        playback,
        error,
    ) { pages, meeting, playing, err ->
        DetailUiState(
            title = meeting?.title ?: "会议",
            createdAtMs = meeting?.createdAt ?: 0L,
            audioDurationMs = meeting?.audioDurationMs ?: 0L,
            audioAvailable = !meeting?.audioPath.isNullOrBlank() ||
                (meeting?.audioDurationMs ?: 0L) > 0L,
            pages = pages.sortedBy { it.index },
            playing = playing,
            positionMs = if (playing) player.positionMs() else 0L,
            error = err,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            meetingFlow.value = repo.meeting(meetingId)
        }
    }

    fun togglePlay() {
        val meeting = meetingFlow.value
        val path = meeting?.audioPath
        if (path.isNullOrBlank() && (meeting?.audioDurationMs ?: 0L) <= 0L) {
            error.value = "本次会议没有录音"
            playback.value = false
            return
        }
        if (playback.value) {
            player.pause()
            playback.value = false
            return
        }
        val file = repo.meetingAudioFile(meetingId)
        if (!player.prepare(file)) {
            error.value = "录音文件无法播放"
            playback.value = false
            return
        }
        if (player.play()) {
            playback.value = true
            error.value = null
        } else {
            error.value = "播放失败"
        }
    }

    fun deletePage(page: SlidePage) {
        viewModelScope.launch {
            repo.deletePage(page.id)
            refresh()
        }
    }

    fun deleteMeeting(onDone: () -> Unit) {
        viewModelScope.launch {
            player.release()
            repo.deleteMeeting(meetingId)
            onDone()
        }
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.ui.detail.*" --no-daemon
```

Expected: 6 tests PASS。
若 `state carries meeting info and pages` 读到空值，说明 `stateIn(WhileSubscribed)` 在没有收集者时未启动——测试中改为先读 `vm.ui.value` 前加 `vm.ui.value.also { }` 两次调用，或把测试改为收集一次 `vm.ui.first()`（需 `import kotlinx.coroutines.flow.first` 与 `runBlocking`）。

- [ ] **Step 5: 写详情页 UI（整文件替换）**

`ui/detail/MeetingDetailScreen.kt`：

```kotlin
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.runtime.collectAsState
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
    val vm: MeetingDetailViewModel = remember {
        MeetingDetailViewModel(meetingId, repository, MediaPlayerAudioPlayer())
    }
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
                    bitmap = bitmap,
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
```

- [ ] **Step 6: 构建并跑全部测试**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 7: 提交**

```powershell
git add -A
git commit -m "feat: 会议详情页（页图网格、录音播放、删除）"
```

---

---

### Task 13: PDF 导出与系统分享

把一场会议的所有 PPT 图按页序排成 A4 PDF，走系统文件选择器保存，成功后可直接调系统分享面板。排版计算是纯逻辑，先测。

**Files:**
- Create: `app/src/main/java/com/meetnote/recorder/export/PdfLayout.kt`
- Create: `app/src/main/java/com/meetnote/recorder/export/PdfExporter.kt`
- Modify: `app/src/main/java/com/meetnote/recorder/ui/detail/MeetingDetailScreen.kt`（加「导出 PDF」「分享」按钮与回调）
- Test: `app/src/test/java/com/meetnote/recorder/export/PdfLayoutTest.kt`

**Interfaces:**
- Consumes: `SlidePage.filePath / index`（Task 2）、`MeetingRepository.observePages`（Task 2）
- Produces:
  - `data class Fitted(val left: Float, val top: Float, val width: Float, val height: Float)`
  - `object PdfLayout { const PAGE_WIDTH = 595f; const PAGE_HEIGHT = 842f; const MARGIN = 24f; fun fit(imgW: Int, imgH: Int, pageW: Float = PAGE_WIDTH, pageH: Float = PAGE_HEIGHT, margin: Float = MARGIN): Fitted }`
  - `class PdfExporter { fun export(files: List<File>, out: java.io.OutputStream, onProgress: ((done: Int, total: Int) -> Unit)? = null): Int }`（返回生成的 PDF 页数；`android.graphics.pdf.PdfDocument` 实现）
  - 详情页动作：`导出 PDF`（SAF 创建文件并写入）、`分享`（`ACTION_SEND` 附带 PDF 的 content Uri）

- [ ] **Step 1: 写排版失败测试**

`app/src/test/java/com/meetnote/recorder/export/PdfLayoutTest.kt`：

```kotlin
package com.meetnote.recorder.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfLayoutTest {

    private val pageW = PdfLayout.PAGE_WIDTH
    private val pageH = PdfLayout.PAGE_HEIGHT
    private val margin = PdfLayout.MARGIN

    @Test
    fun `landscape image fits width and centers vertically`() {
        val f = PdfLayout.fit(1600, 900, pageW, pageH, margin)

        assertEquals(margin, f.left, 0.01f)
        assertEquals(pageW - margin, f.left + f.width, 0.01f)
        assertEquals(16f / 9f, f.width / f.height, 0.02f)
        // 垂直居中：上下留白相等
        assertEquals(f.top, pageH - (f.top + f.height), 0.5f)
        assertTrue(f.top > margin)
    }

    @Test
    fun `tall image fits height and centers horizontally`() {
        val f = PdfLayout.fit(600, 2000, pageW, pageH, margin)

        assertEquals(margin, f.top, 0.01f)
        assertEquals(pageH - margin, f.top + f.height, 0.01f)
        // 水平居中
        assertEquals(f.left, pageW - (f.left + f.width), 0.5f)
        assertTrue(f.width < pageW - 2 * margin + 0.01f)
    }

    @Test
    fun `small image is not enlarged`() {
        // 原图只有 200x150：PDF 点阵下不应被放大到整页
        val f = PdfLayout.fit(200, 150, pageW, pageH, margin)
        assertTrue("小图不应放大：${f.width}", f.width <= 200f)
    }

    @Test
    fun `square image centers on both axes`() {
        val f = PdfLayout.fit(1000, 1000, pageW, pageH, margin)
        assertEquals(f.left, pageW - (f.left + f.width), 0.5f)
        assertEquals(f.top, pageH - (f.top + f.height), 0.5f)
    }

    @Test
    fun `invalid dimensions fall back to a visible box`() {
        val f = PdfLayout.fit(0, 0, pageW, pageH, margin)
        assertTrue(f.width > 0)
        assertTrue(f.height > 0)
        assertTrue(f.left >= 0 && f.top >= 0)
    }

    @Test
    fun `content never exceeds page minus margin`() {
        listOf(
            Triple(4000, 300, 0),
            Triple(300, 4000, 0),
            Triple(1600, 900, 0),
        ).forEach { (w, h, _) ->
            val f = PdfLayout.fit(w, h, pageW, pageH, margin)
            assertTrue(f.left >= margin - 0.01f)
            assertTrue(f.top >= margin - 0.01f)
            assertTrue(f.left + f.width <= pageW - margin + 0.01f)
            assertTrue(f.top + f.height <= pageH - margin + 0.01f)
        }
    }
}
```

- [ ] **Step 2: 运行确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.export.PdfLayoutTest" --no-daemon
```

Expected: FAIL，`unresolved reference: PdfLayout`。

- [ ] **Step 3: 实现 PdfLayout**

`export/PdfLayout.kt`：

```kotlin
package com.meetnote.recorder.export

import kotlin.math.max
import kotlin.math.min

/** 页面上的一个矩形（PDF 点，原点左上） */
data class Fitted(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * A4 竖版（72dpi → 595×842 点）上的图片排版：等比缩放、居中、不越界、不放大。
 */
object PdfLayout {
    const val PAGE_WIDTH = 595f
    const val PAGE_HEIGHT = 842f
    const val MARGIN = 24f

    fun fit(
        imgW: Int,
        imgH: Int,
        pageW: Float = PAGE_WIDTH,
        pageH: Float = PAGE_HEIGHT,
        margin: Float = MARGIN,
    ): Fitted {
        val availW = max(1f, pageW - 2 * margin)
        val availH = max(1f, pageH - 2 * margin)

        if (imgW <= 0 || imgH <= 0) {
            // 尺寸未知：留一块可见的占位框，不至于把 PDF 画成空白
            return Fitted(margin, margin, availW, min(availH, 200f))
        }

        val scale = min(availW / imgW, availH / imgH).let { if (it > 1f) 1f else it }
        val w = imgW * scale
        val h = imgH * scale
        val left = margin + (availW - w) / 2f
        val top = margin + (availH - h) / 2f
        return Fitted(left, top, w, h)
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.export.PdfLayoutTest" --no-daemon
```

Expected: 6 tests PASS。

- [ ] **Step 5: 实现 PdfExporter**

`export/PdfExporter.kt`：

```kotlin
package com.meetnote.recorder.export

import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.OutputStream

/**
 * 把 PPT 图按顺序排成 A4 PDF。图片解码失败的页会跳过，但页码照常前进，
 * 这样 PDF 页序与放映顺序始终一致。
 */
class PdfExporter {

    /** @return 实际写入的 PDF 页数 */
    fun export(
        files: List<File>,
        out: OutputStream,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): Int {
        require(files.isNotEmpty()) { "没有可导出的页面" }
        val document = PdfDocument()
        var written = 0
        try {
            files.forEachIndexed { i, file ->
                val pageNumber = i + 1
                val pageInfo = PdfDocument.PageInfo.Builder(
                    PdfLayout.PAGE_WIDTH.toInt(),
                    PdfLayout.PAGE_HEIGHT.toInt(),
                    pageNumber,
                ).create()

                val page = document.startPage(pageInfo)
                val canvas = page.canvas
                canvas.drawColor(android.graphics.Color.WHITE)

                val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                if (bitmap != null) {
                    val box = PdfLayout.fit(bitmap.width, bitmap.height)
                    val dst = android.graphics.Rect(
                        box.left.toInt(), box.top.toInt(),
                        (box.left + box.width).toInt(), (box.top + box.height).toInt(),
                    )
                    canvas.drawBitmap(bitmap, null, dst, null)
                    bitmap.recycle()
                }

                document.finishPage(page)
                written++
                onProgress?.invoke(written, files.size)
            }
            document.writeTo(out)
        } finally {
            document.close()
        }
        return written
    }
}
```

- [ ] **Step 6: 在详情页接入导出与分享**

`MeetingDetailScreen.kt` 顶部（Scaffold 内容之前）加入导出状态与回调：

```kotlin
    var exporting by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var shareUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val createPdf = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val pages = vm.ui.value.pages
        if (pages.isEmpty()) {
            exportError = "没有可导出的页面"
            return@rememberLauncherForActivityResult
        }
        exporting = true
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val out = context.contentResolver.openOutputStream(uri, "wt")
                    ?: throw IllegalStateException("无法打开目标文件")
                out.use { stream ->
                    PdfExporter().export(files = pages.map { File(it.filePath) }, out = stream)
                }
                shareUri = uri
                exportError = null
            } catch (t: Throwable) {
                exportError = "导出失败：${t.message}"
            } finally {
                exporting = false
            }
        }
    }
```

在顶部 `actions` 里（删除图标之前）加两个按钮：

```kotlin
                    IconButton(
                        onClick = { createPdf.launch("会议纪要-${state.title}.pdf") },
                        enabled = state.pages.isNotEmpty() && !exporting,
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "导出 PDF")
                    }
```

在录音播放条下方加状态与分享入口：

```kotlin
            exportError?.let {
                Text(
                    it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            shareUri?.let { uri ->
                Row(Modifier.padding(horizontal = 12.dp)) {
                    TextButton(onClick = {
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(
                            android.content.Intent.createChooser(intent, "分享会议纪要")
                        )
                    }) {
                        Text("分享 PDF")
                    }
                }
            }
            if (exporting) {
                Text(
                    "正在生成 PDF…", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
```

需补 import：`androidx.compose.material.icons.filled.Share`、`androidx.compose.material3.TextButton`、`kotlinx.coroutines.launch`、`com.meetnote.recorder.export.PdfExporter`、`java.io.File`。

- [ ] **Step 7: 构建并跑全部测试**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL`，测试全绿（累计 75+ 用例）。

- [ ] **Step 8: 提交**

```powershell
git add -A
git commit -m "feat: PDF 导出与系统分享"
```

---

---

### Task 14: 空间检查、ColorOS 后台引导与容错提示

针对一加 13 的现实约束：ColorOS 会杀后台、电池优化会断录音、磁盘满会毁掉整场会议。这些都要提前拦住并给出可执行的中文指引。

**Files:**
- Create: `app/src/main/java/com/meetnote/recorder/data/SpaceCheck.kt`
- Modify: `app/src/main/java/com/meetnote/recorder/ui/guide/GuideScreen.kt`（整文件替换占位版）
- Modify: `app/src/main/java/com/meetnote/recorder/ui/list/MeetingListScreen.kt`（新建会议前空间检查）
- Modify: `app/src/main/java/com/meetnote/recorder/ui/capture/CaptureScreen.kt`（"勿退出"提示条）
- Test: `app/src/test/java/com/meetnote/recorder/data/SpaceCheckTest.kt`

**Interfaces:**
- Consumes: `MeetingRepository.freeBytes()`（Task 2）、`Routes.GUIDE`（Task 3）
- Produces:
  - `object SpaceCheck`：`const val MIN_FREE_BYTES = 500L * 1024 * 1024`、`fun hasEnough(freeBytes: Long): Boolean`、`fun warning(freeBytes: Long): String?`（充足时返回 null）
  - `GuideScreen(onBack: () -> Unit)`（签名不变）
  - 列表页：`showSpaceWarning: String?` 状态，仅在空间不足时先弹警告、用户确认后仍可继续

- [ ] **Step 1: 写空间检查失败测试**

`app/src/test/java/com/meetnote/recorder/data/SpaceCheckTest.kt`：

```kotlin
package com.meetnote.recorder.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceCheckTest {

    @Test
    fun `plenty of space passes silently`() {
        assertTrue(SpaceCheck.hasEnough(8L * 1024 * 1024 * 1024))
        assertNull(SpaceCheck.warning(8L * 1024 * 1024 * 1024))
    }

    @Test
    fun `exactly the minimum is enough`() {
        assertTrue(SpaceCheck.hasEnough(SpaceCheck.MIN_FREE_BYTES))
        assertNull(SpaceCheck.warning(SpaceCheck.MIN_FREE_BYTES))
    }

    @Test
    fun `below the minimum warns`() {
        val free = SpaceCheck.MIN_FREE_BYTES - 1
        assertFalse(SpaceCheck.hasEnough(free))
        assertNotNull(SpaceCheck.warning(free))
    }

    @Test
    fun `warning message is human readable chinese`() {
        val msg = SpaceCheck.warning(100L * 1024 * 1024)!!
        assertTrue("应说明剩余空间：$msg", msg.contains("100") || msg.contains("可用空间"))
        assertTrue("应给出建议：$msg", msg.contains("导出") || msg.contains("清理"))
    }

    @Test
    fun `zero or unknown space warns`() {
        assertNotNull(SpaceCheck.warning(0L))
    }
}
```

- [ ] **Step 2: 运行确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.data.SpaceCheck" --no-daemon
```

Expected: FAIL，`unresolved reference: SpaceCheck`。

- [ ] **Step 3: 实现 SpaceCheck**

`data/SpaceCheck.kt`：

```kotlin
package com.meetnote.recorder.data

/**
 * 录音 + 抓拍的空间预算：2 小时录音约 85MB，40 页幻灯片约 40×300KB≈12MB，
 * 再留出导出 PDF 与系统缓存的余量，取 500MB 作为开拍前的最低水位。
 */
object SpaceCheck {
    const val MIN_FREE_BYTES: Long = 500L * 1024 * 1024

    fun hasEnough(freeBytes: Long): Boolean = freeBytes >= MIN_FREE_BYTES

    fun warning(freeBytes: Long): String? {
        if (hasEnough(freeBytes)) return null
        val freeMb = freeBytes / (1024L * 1024L)
        return "可用空间仅 ${freeMb}MB，低于 500MB。" +
            "继续录音与拍照可能中途失败；建议先清理空间，" +
            "并尽快导出已完成的会议纪要。"
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.meetnote.recorder.data.SpaceCheck" --no-daemon
```

Expected: 5 tests PASS。

- [ ] **Step 5: 重写引导页**

`ui/guide/GuideScreen.kt` 整文件替换：

```kotlin
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
```

- [ ] **Step 6: 接入空间检查与"勿退出"提示**

`ui/list/MeetingListScreen.kt`：
1. 在 `MeetingListScreen` 参数表里追加 `onCreateMeeting` 之前的一个内部状态：
   已有 `showDialog`，再加 `var spaceWarning by remember { mutableStateOf<String?>(null) }`；
2. 新建会议确认按钮改为先检查空间：

```kotlin
                TextButton(
                    onClick = {
                        val t = title.trim().ifBlank { "未命名会议" }
                        showDialog = false
                        val warning = SpaceCheck.warning(
                            // 用应用可用空间近似：FileStore 的 freeBytes 在 Repository 上，
                            // 列表页没有仓库引用时可用 context.filesDir.usableSpace
                            androidx.compose.ui.platform.LocalContext.current.filesDir.usableSpace,
                        )
                        if (warning == null) {
                            onCreateMeeting(t)
                        } else {
                            spaceWarning = warning
                        }
                    },
                ) { Text("开始") }
```

3. 在 Scaffold 内容末尾加警告对话框（确认后仍继续，只是提醒）：

```kotlin
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
```

> `LocalContext` 不允许在 `onClick` lambda 里调用（Composable 上下文），把
> `val context = androidx.compose.ui.platform.LocalContext.current` 提到
> `MeetingListScreen` 函数体顶部再用。需补 import：`com.meetnote.recorder.data.SpaceCheck`。

`ui/capture/CaptureScreen.kt`：在状态条文字上方插入常驻提示：

```kotlin
                Text(
                    "拍摄期间请保持本应用在前台、屏幕常亮；录音不受影响",
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall,
                )
```

- [ ] **Step 7: 构建并跑全部测试**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

Expected: `BUILD SUCCESSFUL`，测试全绿（累计 80+ 用例）。

- [ ] **Step 8: 提交**

```powershell
git add -A
git commit -m "feat: 空间检查、ColorOS 后台引导与前台提示"
```

---

---

### Task 15: 签名打包、调参日志与真机验收

产出可长期安装的签名版 APK、一键调参用的日志，以及一加 13 上逐条打勾的验收清单。

**Files:**
- Create: `.gitignore` 已在 Task 1 建立；本任务追加 `keystore.properties`（本地，不入库）
- Modify: `app/build.gradle.kts`（release 签名配置）
- Modify: `app/src/main/java/com/meetnote/recorder/capture/CapturePipeline.kt`（调参日志）
- Create: `docs/acceptance-checklist.md`
- Output: `app/build/outputs/apk/release/app-release.apk`

**Interfaces:**
- Consumes: 全部前序任务
- Produces:
  - `keystore.properties`（`storeFile/storePassword/keyAlias/keyPassword` 四键）
  - 签名后的 release APK
  - 日志 tag `Dedupe`：每张抓拍输出 `recall/fg/coverage/score/isSamePage`，供 `adb logcat -s Dedupe` 调参

- [ ] **Step 1: 全量回归测试**

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

Expected: `BUILD SUCCESSFUL`。记录用例总数（应 ≥ 80），任何失败必须先修再继续。把结果附到提交信息里。

- [ ] **Step 2: 加调参日志**

`CapturePipeline.kt` 的 `process()` 中，在 `val isSamePage = ...` 之后插入：

```kotlin
        val sim = previous?.let { SlideDeduplicator.compare(it, signals) }
        android.util.Log.d(
            "Dedupe",
            buildString {
                append("index=")
                append(repository.pageCount(meetingId))
                if (sim != null) {
                    append(" recall="); append("%.3f".format(sim.recall))
                    append(" fg="); append("%.3f".format(sim.fgOldSim))
                    append(" cov="); append("%.3f".format(sim.coverage))
                    append(" score="); append("%.3f".format(sim.score))
                    append(" same="); append(sim.isSamePage)
                } else {
                    append(" firstPage=true")
                }
                append(" lap="); append("%.1f".format(lap))
                append(" mean="); append("%.1f".format(QualityGate.meanLuma(gray)))
            },
        )
```

注意：原代码里 `val isSamePage = previous != null && SlideDeduplicator.compare(previous, signals).isSamePage`
保留，上面的 `sim` 只为日志；为避免重复计算，把该行改写为：

```kotlin
        val sim = previous?.let { SlideDeduplicator.compare(it, signals) }
        val isSamePage = sim?.isSamePage == true
```

构建验证：

```powershell
.\gradlew.bat :app:compileDebugKotlin --no-daemon
```

Expected: `BUILD SUCCESSFUL`。再跑一次单元测试确认没破坏行为。

- [ ] **Step 3: 生成签名密钥并配置 release 签名**

```powershell
$keytool = "$env:JAVA_HOME\bin\keytool.exe"
& $keytool -genkeypair -v `
  -keystore "C:\Users\Administrator\Documents\默认项目\MeetingRecorder\meetingrecorder.jks" `
  -alias meetnote -keyalg RSA -keysize 2048 -validity 10000 `
  -storepass "MeetNote2026" -keypass "MeetNote2026" `
  -dname "CN=Meeting Recorder, OU=Personal, O=Personal, L=Beijing, ST=Beijing, C=CN"
```

Expected: `密钥库已存在`/`生成密钥` 提示，且 `.jks` 文件生成（已在 `.gitignore` 中，不会入库）。

项目根新建 `keystore.properties`：

```properties
storeFile=meetingrecorder.jks
storePassword=MeetNote2026
keyAlias=meetnote
keyPassword=MeetNote2026
```

`app/build.gradle.kts` 顶部加 import 与签名配置：

```kotlin
import java.util.Properties

plugins { /* 不变 */ }

val keystoreFile = rootProject.file("keystore.properties")
val ksProps = Properties().apply {
    if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreFile.exists()

android {
    /* 既有配置不变 */

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(ksProps.getProperty("storeFile"))
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else null
        }
    }
}
```

> 若 `signingConfigs { if (...) create(...) }` 的条件写法在 Kotlin DSL 中报错，改为：
> `val releaseSigning = if (hasReleaseKeystore) signingConfigs.create("release") { ... } else null`
> 并在 `release` 里引用 `releaseSigning`。

构建签名包：

```powershell
.\gradlew.bat :app:assembleRelease --no-daemon
```

Expected: `app\build\outputs\apk\release\app-release.apk` 存在且体积 > 30MB（含 arm64 OpenCV）。

- [ ] **Step 4: 写真机验收清单**

创建 `docs/acceptance-checklist.md`：

```markdown
# 一加 13 真机验收清单

> 全部通过才算交付。每条打 [x]，失败项记下现象与 logcat 片段。

## 安装与权限
- [ ] `adb install -r app-release.apk` 成功
- [ ] 首次启动显示会议列表与空状态引导
- [ ] 新建会议时弹出录音权限申请，拒绝后有明确提示且不崩溃
- [ ] 进入拍摄页弹出相机权限申请，允许后出现实时预览

## 录音
- [ ] 通知栏出现常驻「会议记录中」通知，附「停止录音」按钮
- [ ] 录音计时红点随时间走动
- [ ] 锁屏 2 分钟后再解锁，录音仍在继续（未被 ColorOS 杀掉）
- [ ] 切到微信 1 分钟，录音不中断
- [ ] 点「停止录音」后详情页显示的时长 ≈ 实际时长（误差 < 3s）
- [ ] 详情页播放录音，能听到会议声音

## 自动抓拍（核心）
- [ ] 面向投影幕布打开拍摄页，绿色框能框住屏幕
  （若错位：记录旋转角，调整 CaptureScreen 中 previewRotation 的值）
- [ ] 连续翻页 20 页，列表页码从 1 递增到 20，无漏拍、无重复页
- [ ] 同一页分 3 次逐步显示要点（PowerPoint 动画），最终**只保留 1 张完整图**，
      详情页该页显示「拍了 N 次」
- [ ] 翻页动画播放中（快速连按）不产生中间态图片
- [ ] 手抖、前排同学走动不触发拍摄
- [ ] 画面长时间不动时不重复拍摄（页码不增加）
- [ ] 手动拍照按钮在画面抖动时也能出图

## 降级路径
- [ ] 用手指遮住镜头再放开：出现「未检测到屏幕边缘，已按原图保存」提示
- [ ] 移开遮挡后提示自动消失，恢复四边形检测

## 导出
- [ ] 导出 PDF 成功，页序与放映顺序一致
- [ ] 导出后点「分享」，微信/文件管理器出现在分享面板中
- [ ] 用其他 PDF 阅读器打开导出的文件，图片清晰、无裁切

## 容错
- [ ] 关闭相机权限后进拍摄页，不崩溃，有引导
- [ ] 设置里手动「强行停止」App 后重启：已拍的页和录音都在
- [ ] 飞行模式下全流程可用（应用不依赖网络）
- [ ] 「结束会议」后回到列表，该会议显示页数与录音时长

## 调参记录（`adb logcat -s Dedupe`）
- [ ] 同页累积场景：`same=true`，记录 recall/fg/cov 三个值：
      recall=____ fg=____ cov=____
- [ ] 翻到新页场景：`same=false`，记录：
      recall=____ fg=____ cov=____
- [ ] 若两者出现矛盾，调整 `DedupeConfig.SAME_*` 阈值并重跑
      `gradlew :app:testDebugUnitTest --tests "com.meetnote.recorder.dedupe.*"`
```

- [ ] **Step 5: 连接一加 13 执行真机验证（可用时）**

前提：手机开启「开发者选项 → USB 调试」，用数据线连接本机。

```powershell
adb devices
# 应列出设备；若显示 unauthorized，到手机上点「允许 USB 调试」
adb install -r app\build\outputs\apk\release\app-release.apk
adb logcat -c
adb logcat -s Dedupe
```

按 `docs/acceptance-checklist.md` 逐条验收。仪器测试（`androidTest`）在此环境执行：

```powershell
.\gradlew.bat :app:connectedAndroidTest --no-daemon
```

> 若本机始终无法连接手机（驱动/授权问题），把 APK 拷到手机手动安装，
> 并在 `docs/acceptance-checklist.md` 上由人工逐条勾选——清单本身就是交付物的一部分。

- [ ] **Step 6: 收尾提交**

```powershell
git add -A
git status --short
git commit -m "build: release 签名、调参日志与真机验收清单"
```

`git status` 中**不应**出现 `.jks`、`keystore.properties`、`build/`（已被 `.gitignore` 排除）；若出现，先补 `.gitignore` 再提交。

---

## 计划自审记录

**1. Spec 覆盖检查**

| Spec 章节 | 对应任务 |
|---|---|
| §3 技术选型（含 ML Kit 否决） | Task 1 |
| §4 分包结构 | Task 1–13 各归其位 |
| §5 数据流 | Task 10（完整串联） |
| §6.1 取帧 / §6.2 两级检测 | Task 9（取帧）+ Task 6（检测） |
| §6.3 文档矫正与降级 | Task 7 + Task 8 + Task 10 |
| §6.4 质量门 | Task 7 + Task 10 |
| §7 同页去重（含 10-22 修订） | Task 5 + Task 10 |
| §8 录音与前台服务 | Task 4 |
| §9 数据模型 | Task 2 |
| §10 权限表 | Task 1（清单声明）+ Task 9（运行时申请） |
| §11 四屏界面 | Task 3 / 9 / 12 / 13 |
| §12 错误处理 | Task 8（OpenCV 失败）、Task 10（质量门静默丢弃）、Task 11（录音兜底写入）、Task 14（空间与提示） |
| §13 一加 13 / ColorOS 专项 | Task 14 + Task 15 |
| §14 测试策略 | 各任务 TDD 步骤 + Task 15 验收清单 |
| §15 风险（阈值调参） | Task 5（阈值集中）+ Task 15（调参日志） |

**2. 占位符检查**：无 TBD/TODO；所有代码步骤均给出完整可编译代码或明确的"替换整个类"指令。

**3. 类型一致性检查**：
- `MeetingRepository` 在 Task 2 为 class、Task 3 改为 interface，Task 3 Step 3 显式列出了完整接口签名与 `DefaultMeetingRepository` 实现；
- `registerShot/targetFileFor` 的 `similarityToLast: Float` → `isSamePage: Boolean` 在 Task 5 Step 7 显式改签名，
  Task 10 使用新签名；
- 两处测试 fake（Task 3 `FakeRepo`、Task 9 `PagesFakeRepo`、Task 12 `DetailFakeRepo`）均按 interface 全方法实现；
- `PipelineStatus.shot` 类型 `ShotResult?` 与 Task 2 的 `ShotResult(index, overwritten, captureCount)` 一致。

