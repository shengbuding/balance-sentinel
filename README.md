# DeepSeek Balance - Android Widget App

DeepSeek 余额查询 Android App，支持桌面小组件实时查看账户余额。

## 功能

- 安全存储 DeepSeek API Key（AES-256 加密）
- 一键查询余额（CNY/USD）
- 桌面小组件显示余额概览
- 自动/手动刷新

## 构建要求

- **Android Studio** (推荐 Hedgehog 2024.1+)
- JDK 17
- Gradle 8.11 (自动下载)

## 使用方式

### 方式一：Android Studio（推荐）

1. 用 Android Studio 打开 `DeepSeekBalance/` 目录
2. 等待 Gradle Sync 完成
3. 连接 Android 15+ 设备或启动模拟器
4. 点击 Run

### 方式二：命令行

```bash
# 设置 JDK 17
export JAVA_HOME=/path/to/jdk-17
# Windows:
set JAVA_HOME=C:\Program Files\Amazon Corretto\jdk17.0.19_10

# 生成 Gradle Wrapper（首次需要）
gradle wrapper

# 构建 Debug APK
./gradlew assembleDebug         # macOS/Linux
gradlew.bat assembleDebug       # Windows

# APK 输出路径
# app/build/outputs/apk/debug/app-debug.apk
```

## 使用 App

1. 首次打开 → 输入 DeepSeek API Key（sk-...），点击保存
2. 自动查询余额并展示
3. 长按桌面 → 添加小组件 → 搜索 "DeepSeek 余额" → 拖到桌面

## 技术栈

- Kotlin + Jetpack Compose (Material 3)
- Glance AppWidgets (Compose-based Widget)
- OkHttp + kotlinx.serialization
- EncryptedSharedPreferences
- MVVM 架构

## 权限

- `INTERNET` — 调用 DeepSeek API

## 项目结构

```
DeepSeekBalance/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/deepseekbalance/
│       │   ├── MainActivity.kt
│       │   ├── data/
│       │   │   ├── model/BalanceResponse.kt
│       │   │   ├── api/DeepSeekApiService.kt
│       │   │   └── repository/
│       │   │       ├── ApiKeyManager.kt
│       │   │       └── BalanceRepository.kt
│       │   ├── ui/
│       │   │   ├── theme/Theme.kt
│       │   │   ├── screen/HomeScreen.kt
│       │   │   └── viewmodel/HomeViewModel.kt
│       │   └── widget/
│       │       ├── BalanceWidget.kt
│       │       ├── BalanceWidgetReceiver.kt
│       │       └── BalanceWidgetDataStore.kt
│       └── res/
│           ├── layout/widget_placeholder.xml
│           ├── xml/balance_widget_info.xml
│           └── values/{strings,themes}.xml
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/gradle-wrapper.properties
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## DeepSeek API

- Endpoint: `GET https://api.deepseek.com/user/balance`
- Auth: `Authorization: Bearer <api-key>`
- Docs: https://api-docs.deepseek.com/api/get-user-balance
